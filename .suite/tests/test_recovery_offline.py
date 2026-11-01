"""Portable archive/state checks only; no Docker calls or Linux lock simulation."""

from contextlib import ExitStack
import importlib.util
import io
import json
from pathlib import Path
import sys
import tarfile
import tempfile
import types
import unittest
from unittest.mock import patch


def load_archive_functions():
    spec = importlib.util.spec_from_file_location(
        "recovery_offline_fixture", Path(__file__).resolve().parents[1] / "recovery.py")
    module = importlib.util.module_from_spec(spec)
    if sys.platform == "win32":
        unavailable_lock = types.ModuleType("fcntl")

        def no_linux_lock(*_):
            raise RuntimeError("Linux operation locking is not tested by this offline fixture")

        unavailable_lock.flock = no_linux_lock
        with patch.dict(sys.modules, {"fcntl": unavailable_lock}):
            spec.loader.exec_module(module)
    else:
        spec.loader.exec_module(module)
    return module


recovery = load_archive_functions()


class RecoveryOfflineAcceptanceTest(unittest.TestCase):
    def prepare(self, root, shared_volume=False):
        suite = root / ".suite"
        suite.mkdir()
        (suite / "docker-compose.yaml").write_text("synthetic Compose source", encoding="utf-8")
        source = root / "snapshot"
        source.mkdir()
        payload = root / "source-data"
        payload.mkdir()
        (payload / "message.eml").write_bytes(b"synthetic pending message body")
        with tarfile.open(source / "mount-000.tar", "w") as archive:
            archive.add(payload, arcname="data")
        with tarfile.open(source / "deployment.tar", "w") as archive:
            archive.add(suite / "docker-compose.yaml", arcname="docker-compose.yaml")
        (source / recovery.RESOLVED_COMPOSE).write_text('{"services":{}}', encoding="utf-8")
        mount = {"type": "volume" if shared_volume else "bind",
                 "source": "old-volume" if shared_volume else str(payload),
                 "read_only": False, "archive": "mount-000.tar", "file": False}
        targets = {"mail:/data": dict(mount)}
        if shared_volume:
            targets["mail-reader:/data"] = dict(mount)
        manifest = {
            "format": 1, "revision": "a" * 40, "images": {}, "mounts": targets,
            "deploymentInputs": recovery.input_inventory(source / "deployment.tar"),
            "checksums": {name: recovery.digest(source / name)
                          for name in ("mount-000.tar", "deployment.tar", recovery.RESOLVED_COMPOSE)},
        }
        self.write_manifest(source, manifest)
        current_mount = {"type": mount["type"],
                         "source": "new-volume" if shared_volume else str(root / "fresh" / "data"),
                         "read_only": False}
        return suite, source, manifest, {key: dict(current_mount) for key in targets}

    def write_manifest(self, source, manifest):
        (source / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
        (source / "COMPLETE").write_text(recovery.digest(source / "manifest.json") + "\n",
                                       encoding="utf-8")

    def mocked_host(self, stack, current, revision="a" * 40, records=()):
        mocked = {}
        for name, value in (
                ("configuration", {"services": {}}), ("mounts", current),
                ("containers", list(records)), ("output", revision),
                ("nonempty", False)):
            mocked[name] = stack.enter_context(patch.object(recovery, name, return_value=value))
        for name in ("run", "require_no_writers", "restore_mount"):
            mocked[name] = stack.enter_context(patch.object(recovery, name))
        return mocked

    def test_corrupted_payload_fails_before_inspection_or_restore(self):
        with tempfile.TemporaryDirectory(prefix="robin-recovery-offline-") as directory:
            suite, source, _, current = self.prepare(Path(directory))
            (source / "mount-000.tar").write_bytes(b"corrupt synthetic archive")
            with ExitStack() as stack:
                host = self.mocked_host(stack, current)
                with self.assertRaisesRegex(ValueError, "checksum"):
                    recovery.restore(suite, source, "not-used-offline")
                host["run"].assert_not_called()
                host["configuration"].assert_not_called()
                host["restore_mount"].assert_not_called()

    def test_even_stopped_target_containers_block_restore_before_any_data_write(self):
        with tempfile.TemporaryDirectory(prefix="robin-recovery-offline-") as directory:
            suite, source, _, current = self.prepare(Path(directory))
            with ExitStack() as stack:
                host = self.mocked_host(stack, current, records=[{"State": {"Running": False}}])
                with self.assertRaisesRegex(ValueError, "no containers"):
                    recovery.restore(suite, source, "not-used-offline")
                host["restore_mount"].assert_not_called()
                host["require_no_writers"].assert_not_called()
                self.assertFalse(Path(current["mail:/data"]["source"]).exists())

    def test_wrong_revision_blocks_restore_without_rewriting_operator_state(self):
        with tempfile.TemporaryDirectory(prefix="robin-recovery-offline-") as directory:
            suite, source, _, current = self.prepare(Path(directory))
            with ExitStack() as stack:
                host = self.mocked_host(stack, current, revision="b" * 40)
                with self.assertRaisesRegex(ValueError, "recorded Git revision"):
                    recovery.restore(suite, source, "not-used-offline")
                host["restore_mount"].assert_not_called()
                self.assertFalse(Path(current["mail:/data"]["source"]).exists())

    def test_shared_volume_is_restored_once_and_services_are_never_started(self):
        with tempfile.TemporaryDirectory(prefix="robin-recovery-offline-") as directory:
            suite, source, _, current = self.prepare(Path(directory), shared_volume=True)
            with ExitStack() as stack:
                host = self.mocked_host(stack, current)
                recovery.restore(suite, source, "not-used-offline")
                host["restore_mount"].assert_called_once()
                self.assertEqual("new-volume", host["restore_mount"].call_args.args[0]["source"])
                self.assertEqual(source / "mount-000.tar", host["restore_mount"].call_args.args[1])
                self.assertEqual(1, host["run"].call_count)
                self.assertEqual(("docker", "image", "inspect", "not-used-offline"),
                                 host["run"].call_args.args)
                self.assertFalse(any("up" in call.args or "start" in call.args
                                     for call in host["run"].call_args_list))

    def test_incomplete_snapshot_does_not_begin_restore(self):
        with tempfile.TemporaryDirectory(prefix="robin-recovery-offline-") as directory:
            suite, source, _, current = self.prepare(Path(directory))
            (source / "COMPLETE").unlink()
            with ExitStack() as stack:
                host = self.mocked_host(stack, current)
                with self.assertRaisesRegex(ValueError, "incomplete"):
                    recovery.restore(suite, source, "not-used-offline")
                host["run"].assert_not_called()
                host["restore_mount"].assert_not_called()

    def test_missing_or_changed_required_target_input_blocks_before_docker(self):
        for changed in (False, True):
            with self.subTest(changed=changed), tempfile.TemporaryDirectory(
                    prefix="robin-recovery-offline-") as directory:
                suite, source, _, current = self.prepare(Path(directory))
                if changed:
                    (suite / "docker-compose.yaml").write_text("changed source")
                else:
                    (suite / "docker-compose.yaml").unlink()
                with ExitStack() as stack:
                    host = self.mocked_host(stack, current)
                    with self.assertRaisesRegex(ValueError, "deployment input"):
                        recovery.restore(suite, source, "not-used-offline")
                    host["run"].assert_not_called()
                    host["restore_mount"].assert_not_called()

    def test_missing_inventory_in_legacy_snapshot_does_not_claim_reconstruction(self):
        with tempfile.TemporaryDirectory(prefix="robin-recovery-offline-") as directory:
            suite, source, manifest, current = self.prepare(Path(directory))
            manifest.pop("deploymentInputs")
            self.write_manifest(source, manifest)
            with ExitStack() as stack:
                host = self.mocked_host(stack, current)
                with self.assertRaisesRegex(ValueError, "legacy recovery needs an independent audit"):
                    recovery.restore(suite, source, "not-used-offline")
                host["run"].assert_not_called()
                host["restore_mount"].assert_not_called()

    def test_archive_omission_is_detected_even_if_archive_checksum_is_updated(self):
        with tempfile.TemporaryDirectory(prefix="robin-recovery-offline-") as directory:
            suite, source, manifest, current = self.prepare(Path(directory))
            with tarfile.open(source / "deployment.tar", "w"):
                pass
            manifest["checksums"]["deployment.tar"] = recovery.digest(source / "deployment.tar")
            self.write_manifest(source, manifest)
            with ExitStack() as stack:
                host = self.mocked_host(stack, current)
                with self.assertRaisesRegex(ValueError, "inventory mismatch"):
                    recovery.restore(suite, source, "not-used-offline")
                host["run"].assert_not_called()
                host["restore_mount"].assert_not_called()

    def test_operator_runtime_drift_blocks_before_volume_restore(self):
        with tempfile.TemporaryDirectory(prefix="robin-recovery-offline-") as directory:
            suite, source, _, current = self.prepare(Path(directory))
            with ExitStack() as stack:
                host = self.mocked_host(stack, current)
                host["configuration"].return_value = {"services": {"mail": {
                    "environment": {"OPERATOR_SETTING": "changed"}}}}
                with self.assertRaisesRegex(ValueError, "Resolved deployment settings differ"):
                    recovery.restore(suite, source, "not-used-offline")
                host["restore_mount"].assert_not_called()

    def test_host_bind_and_config_paths_can_relocate_without_changing_runtime_settings(self):
        original = {"services": {"mail": {"image": "fixture", "build": {"context": "/old"},
                    "volumes": [{"type": "bind", "source": "/old/tls", "target": "/tls", "read_only": True}],
                    "environment": {"FIXTURE": "same"}}},
                    "secrets": {"password": {"file": "/old/password"}},
                    "configs": {"policy": {"file": "/old/policy"}}}
        relocated = json.loads(json.dumps(original))
        relocated["services"]["mail"]["build"]["context"] = "/new"
        relocated["services"]["mail"]["volumes"][0]["source"] = "/new/tls"
        relocated["secrets"]["password"]["file"] = "/new/password"
        relocated["configs"]["policy"]["file"] = "/new/policy"
        self.assertEqual(recovery.reconstruction_model(original), recovery.reconstruction_model(relocated))
        relocated["services"]["mail"]["environment"]["FIXTURE"] = "changed"
        self.assertNotEqual(recovery.reconstruction_model(original), recovery.reconstruction_model(relocated))

    def test_resolved_model_dollars_are_escaped_once_for_compose_and_round_trip(self):
        original = {"services": {"fixture": {
            "environment": {"VALUE": "$VAR ${VAR:-default} $$ literal"},
            "command": ["sh", "-c", "printf '%s' \"$VALUE\""]}}}
        escaped = recovery.compose_dollars(original, escape=True)
        self.assertEqual("$$VAR $${VAR:-default} $$$$ literal",
                         escaped["services"]["fixture"]["environment"]["VALUE"])
        self.assertEqual(original, recovery.compose_dollars(escaped, escape=False))

    def test_volume_first_invalid_bind_last_fails_before_any_restore_or_data_write(self):
        for invalid in ("renamed", "empty", "nested-root", "multiple-roots"):
            with self.subTest(invalid=invalid), tempfile.TemporaryDirectory(
                    prefix="robin-recovery-offline-") as directory:
                root = Path(directory)
                suite, source, manifest, current = self.prepare(root, shared_volume=True)
                key = "mail-reader:/data"
                manifest["mounts"][key].update({"type": "bind", "archive": "bind.tar"})
                current[key] = {"type": "bind", "source": str(root / "fresh" / "data"), "read_only": False}
                with tarfile.open(source / "bind.tar", "w") as archive:
                    if invalid == "renamed":
                        member = tarfile.TarInfo("mailboxes")
                        member.type = tarfile.DIRTYPE
                        archive.addfile(member)
                    elif invalid == "nested-root":
                        archive.addfile(tarfile.TarInfo("data/message"))
                    elif invalid == "multiple-roots":
                        for name in ("data", "other"):
                            member = tarfile.TarInfo(name)
                            member.type = tarfile.DIRTYPE
                            archive.addfile(member)
                manifest["checksums"]["bind.tar"] = recovery.digest(source / "bind.tar")
                self.write_manifest(source, manifest)
                with ExitStack() as stack:
                    host = self.mocked_host(stack, current)
                    with self.assertRaisesRegex(ValueError, "Bind basename|Bind archive is empty|bind archive root"):
                        recovery.restore(suite, source, "not-used-offline")
                    host["restore_mount"].assert_not_called()
                    self.assertFalse(any(call.args[:3] == ("docker", "volume", "create")
                                         or call.args[:2] == ("docker", "run")
                                         for call in host["run"].call_args_list))
                    self.assertFalse((root / "fresh").exists())

    def test_parent_only_bind_relocation_passes_real_guard_before_mocked_tar_restore(self):
        with tempfile.TemporaryDirectory(prefix="robin-recovery-offline-") as directory:
            root = Path(directory)
            suite, source, _, current = self.prepare(root)
            real_restore_mount = recovery.restore_mount
            with ExitStack() as stack:
                host = self.mocked_host(stack, current)
                host["restore_mount"].side_effect = real_restore_mount
                recovery.restore(suite, source, "not-used-offline")
                host["restore_mount"].assert_called_once()
                self.assertEqual(root / "fresh" / "data",
                                 Path(host["restore_mount"].call_args.args[0]["source"]))
                self.assertTrue(any(call.args[:2] == ("docker", "run")
                                    for call in host["run"].call_args_list))

    def test_direct_empty_bind_archive_rejection_does_not_create_parent_directory(self):
        with tempfile.TemporaryDirectory(prefix="robin-recovery-offline-") as directory:
            root = Path(directory)
            archive = root / "empty.tar"
            with tarfile.open(archive, "w"):
                pass
            with patch.object(recovery, "run") as execute, \
                    self.assertRaisesRegex(ValueError, "Bind archive is empty"):
                recovery.restore_mount({"type": "bind", "source": str(root / "fresh" / "data")},
                                       archive, "not-used-offline")
            execute.assert_not_called()
            self.assertFalse((root / "fresh").exists())


class RecoveryInputArchiveTest(unittest.TestCase):
    def fixture(self, root):
        suite = root / ".suite"
        suite.mkdir()
        files = {
            ".env": "SYNTHETIC_SECRET=fixture\n",
            "docker-compose.yaml": "synthetic source",
            "docker-compose.production.yaml": "synthetic overlay",
            "cfg/server.json5": "synthetic demo",
            "cfg-production/server.json5": "synthetic production",
            "db-init-production/01-users.sql": "synthetic fresh database",
            "mailbox/dovecot.production.yaml": "synthetic mailbox include",
            "migration/001-domains.sql": "synthetic migration",
            "cyrus/entrypoint.sh": "synthetic optional entrypoint",
            "dovecot-entrypoint.sh": "synthetic master entrypoint",
            "custom/policy.conf": "synthetic required bind config",
            "secrets/password": "synthetic private password",
            "etc/policy.conf": "synthetic service settings",
        }
        for name, content in files.items():
            path = suite / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8")
        external = root / "operator-inputs"
        external.mkdir()
        (external / "compose.json").write_text("synthetic external source")
        (external / "operator.env").write_text("EXTERNAL_INPUT=synthetic\n")
        (external / "config.conf").write_text("synthetic external Docker config")
        config = {
            "name": "offline-recovery",
            "services": {"suite-postgres": {
                "image": "synthetic:immutable",
                "environment": {"OPERATOR": "not-in-dotenv", "LITERAL": "$synthetic"},
                "volumes": [
                    {"type": "volume", "source": "data", "target": "/data"},
                    {"type": "bind", "source": str(suite / "custom" / "policy.conf"),
                     "target": "/policy", "read_only": True}]}},
            "volumes": {"data": {"name": "synthetic-data"}},
            "configs": {"operator": {"file": str(external / "config.conf")}},
            "secrets": {"password": {"file": str(suite / "secrets" / "password")}},
        }
        unresolved = json.loads(json.dumps(config))
        unresolved["services"]["suite-postgres"]["env_file"] = [
            {"path": str(external / "operator.env"), "required": True}]
        record = {
            "Config": {"Labels": {
                "com.docker.compose.service": "suite-postgres",
                "com.docker.compose.project.config_files":
                    str(suite / "docker-compose.yaml") + "," + str(external / "compose.json")},
                "Image": "synthetic:immutable"},
            "Image": "sha256:synthetic", "State": {"Running": True},
            "Mounts": [{"Type": "volume", "Name": "synthetic-data", "Destination": "/data",
                        "Source": "/synthetic-volume"}],
        }
        return suite, config, unresolved, record, files

    def host(self, stack, config, unresolved, record):
        mocked = {}
        mocked["configuration"] = stack.enter_context(patch.object(
            recovery, "configuration", side_effect=[config, unresolved]))
        stopped = {**record, "State": {"Running": False}}
        mocked["containers"] = stack.enter_context(patch.object(
            recovery, "containers", side_effect=[[record], [stopped]]))

        def output(*arguments):
            return "synthetic-data\n" if arguments[:3] == ("docker", "volume", "ls") else "a" * 40

        mocked["output"] = stack.enter_context(patch.object(recovery, "output", side_effect=output))
        for name in ("run", "require_matching_configuration", "require_no_writers"):
            mocked[name] = stack.enter_context(patch.object(recovery, name))

        def archive_mount(mount, target, _):
            with tarfile.open(target, "w") as archive:
                if mount["type"] == "bind":
                    path = Path(mount["source"])
                    archive.add(path, arcname=path.name)
                else:
                    payload = b"synthetic durable payload"
                    member = tarfile.TarInfo("payload")
                    member.size = len(payload)
                    archive.addfile(member, io.BytesIO(payload))

        mocked["archive_mount"] = stack.enter_context(patch.object(
            recovery, "archive_mount", side_effect=archive_mount))
        return mocked

    def test_exact_archive_contains_omitted_bootstrap_and_selected_operator_inputs(self):
        with tempfile.TemporaryDirectory(prefix="robin-recovery-inputs-offline-") as directory:
            suite, config, unresolved, record, files = self.fixture(Path(directory))
            destination = Path(directory) / "snapshot"
            with ExitStack() as stack:
                host = self.host(stack, config, unresolved, record)
                recovery.backup(suite, destination, "not-used-offline")
                self.assertFalse(any("start" in call.args or "up" in call.args
                                     for call in host["run"].call_args_list))
            manifest = json.loads((destination / "manifest.json").read_text())
            with tarfile.open(destination / "deployment.tar") as archive:
                actual = {member.name for member in archive if member.isfile()}
            external_names = set(manifest["operatorInputs"]["externalInputs"])
            self.assertEqual(set(files) | external_names, actual)
            self.assertEqual(3, len(external_names))
            self.assertTrue((destination / "COMPLETE").is_file())
            self.assertEqual(set(files) | external_names, set(manifest["deploymentInputs"]["files"]))
            self.assertIn("config:operator", manifest["mounts"])
            self.assertIn("secret:password", manifest["mounts"])
            self.assertEqual("volume", manifest["mounts"]["suite-postgres:/data"]["type"])
            saved = recovery.compose_dollars(
                json.loads((destination / recovery.RESOLVED_COMPOSE).read_text()), escape=False)
            self.assertEqual(config, saved)
            self.assertEqual("not-in-dotenv", saved["services"]["suite-postgres"]["environment"]["OPERATOR"])

    def test_missing_required_compose_env_file_bind_and_config_fail_before_quiescing(self):
        for missing in ("compose", "env-file", "read-only-bind", "config",
                        "production-template", "mailbox-include", "migration", "entrypoint"):
            with self.subTest(missing=missing), tempfile.TemporaryDirectory(
                    prefix="robin-recovery-inputs-offline-") as directory:
                suite, config, unresolved, record, _ = self.fixture(Path(directory))
                path = {
                    "compose": Path(directory) / "operator-inputs" / "compose.json",
                    "env-file": Path(directory) / "operator-inputs" / "operator.env",
                    "read-only-bind": suite / "custom" / "policy.conf",
                    "config": Path(directory) / "operator-inputs" / "config.conf",
                    "production-template": suite / "cfg-production" / "server.json5",
                    "mailbox-include": suite / "mailbox" / "dovecot.production.yaml",
                    "migration": suite / "migration" / "001-domains.sql",
                    "entrypoint": suite / "dovecot-entrypoint.sh",
                }[missing]
                if missing in ("production-template", "migration", "entrypoint"):
                    config["services"]["suite-postgres"]["volumes"].append({
                        "type": "bind", "source": str(path), "target": "/required-input", "read_only": True})
                elif missing == "mailbox-include":
                    record["Config"]["Labels"]["com.docker.compose.project.config_files"] += "," + str(path)
                path.unlink()
                with ExitStack() as stack:
                    host = self.host(stack, config, unresolved, record)
                    with self.assertRaisesRegex(ValueError, "missing"):
                        recovery.backup(suite, Path(directory) / "snapshot", "not-used-offline")
                    host["run"].assert_not_called()
                    host["archive_mount"].assert_not_called()
                    self.assertFalse((Path(directory) / "snapshot").exists())

    def test_optional_absent_env_file_is_not_misrepresented_as_required(self):
        with tempfile.TemporaryDirectory(prefix="robin-recovery-inputs-offline-") as directory:
            suite, config, unresolved, record, _ = self.fixture(Path(directory))
            optional = Path(directory) / "operator-inputs" / "optional.env"
            unresolved["services"]["suite-postgres"]["env_file"].append(
                {"path": str(optional), "required": False})
            inputs, _ = recovery.deployment_inputs(suite, config, unresolved, [record])
            self.assertNotIn(optional, inputs.values())
            self.assertFalse(optional.exists())

    def test_omitted_required_directory_never_writes_complete_even_after_archiving(self):
        with tempfile.TemporaryDirectory(prefix="robin-recovery-inputs-offline-") as directory:
            suite, config, unresolved, record, _ = self.fixture(Path(directory))
            original_add = tarfile.TarFile.add

            def omit_production(archive, name, *arguments, **keywords):
                if archive.name.endswith("deployment.tar") and Path(name).name == "cfg-production":
                    return
                return original_add(archive, name, *arguments, **keywords)

            with ExitStack() as stack:
                host = self.host(stack, config, unresolved, record)
                stack.enter_context(patch.object(tarfile.TarFile, "add", new=omit_production))
                with self.assertRaisesRegex(ValueError, "inventory mismatch"):
                    recovery.backup(suite, Path(directory) / "snapshot", "not-used-offline")
                self.assertFalse((Path(directory) / "snapshot" / "COMPLETE").exists())
                self.assertTrue(any("stop" in call.args for call in host["run"].call_args_list))
                self.assertFalse(any("start" in call.args or "up" in call.args
                                     for call in host["run"].call_args_list))

    def test_external_runtime_configs_and_environment_secrets_cannot_be_omitted(self):
        for section, definition in (
                ("configs", {"external": True}), ("secrets", {"environment": "PRIVATE_VALUE"})):
            with self.subTest(section=section), self.assertRaisesRegex(ValueError, "explicit|separately"):
                recovery.mounts({"services": {}, section: {"required": definition}},
                                Path("synthetic-suite").resolve())

    def test_compose_dependency_directory_is_not_accepted_as_a_source_file(self):
        with tempfile.TemporaryDirectory(prefix="robin-recovery-inputs-offline-") as directory:
            suite, config, unresolved, record, _ = self.fixture(Path(directory))
            record["Config"]["Labels"]["com.docker.compose.project.config_files"] = str(Path(directory))
            with self.assertRaisesRegex(ValueError, "input file is missing/invalid"):
                recovery.deployment_inputs(suite, config, unresolved, [record])


if __name__ == "__main__":
    unittest.main()
