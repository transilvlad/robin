import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tarfile
import tempfile
import unittest
from unittest.mock import patch
import uuid


SPEC = importlib.util.spec_from_file_location("recovery", Path(__file__).resolve().parents[1] / "recovery.py")
recovery = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(recovery)


class RecoveryTest(unittest.TestCase):
    def test_mount_inventory_includes_secrets_but_excludes_logs_and_socket(self):
        config = {"services": {"mail": {"volumes": [
            {"type": "bind", "source": "/opt/robin/store/robin", "target": "/store"},
            {"type": "bind", "source": "/opt/robin/log", "target": "/logs"},
            {"type": "bind", "source": "/var/run/docker.sock", "target": "/socket"},
            {"type": "volume", "source": "cache", "target": "/cfg"}]}},
            "volumes": {"cache": {"name": "test-cache"}},
            "secrets": {"password": {"file": "/opt/robin/.suite/secrets/password"}}}
        mounts = recovery.mounts(config, Path("/opt/robin/.suite"))
        self.assertEqual({"mail:/store", "mail:/cfg", "secret:password"}, set(mounts))
        self.assertEqual("test-cache", mounts["mail:/cfg"]["source"])

    def test_external_secrets_cannot_be_silently_omitted(self):
        with self.assertRaisesRegex(ValueError, "External secret"):
            recovery.mounts({"services": {}, "secrets": {"password": {"external": True}}},
                            Path("/opt/robin/.suite"))

    def test_existing_files_and_directories_are_not_empty_targets(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            self.assertFalse(recovery.nonempty({"type": "bind", "source": str(path)}, "unused"))
            (path / "message").write_bytes(b"accepted mail")
            self.assertTrue(recovery.nonempty({"type": "bind", "source": str(path)}, "unused"))
            self.assertTrue(recovery.nonempty({"type": "bind", "source": str(path / "message")}, "unused"))

    def test_matching_configuration_checks_missing_changed_and_extra_files(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            config = root / "config"
            config.mkdir()
            (config / "server").write_text("original")
            archive = root / "config.tar"
            with tarfile.open(archive, "w") as snapshot:
                snapshot.add(config, arcname="config")
            self.assertTrue(recovery.matching_directory(config, archive))
            (config / "extra").write_text("extra")
            self.assertFalse(recovery.matching_directory(config, archive))
            (config / "extra").unlink()
            (config / "server").write_text("changed")
            self.assertFalse(recovery.matching_directory(config, archive))
            (config / "server").unlink()
            self.assertFalse(recovery.matching_directory(config, archive))

    def test_incomplete_or_corrupt_snapshots_fail_before_docker(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with patch.object(recovery, "configuration") as configuration:
                with self.assertRaisesRegex(ValueError, "incomplete"):
                    recovery.restore(root, root, "unused")
                (root / "manifest.json").write_text("{}")
                (root / "COMPLETE").write_text("wrong")
                with self.assertRaisesRegex(ValueError, "checksum"):
                    recovery.restore(root, root, "unused")
                configuration.assert_not_called()

    def test_failure_does_not_restart_or_write_a_complete_marker(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            suite = root / ".suite"
            suite.mkdir()
            mount = root / "data"
            mount.mkdir()
            config = {"name": "test", "services": {"suite-postgres": {
                "volumes": [{"type": "bind", "source": str(mount), "target": "/data"}]}}}
            record = {"Config": {"Labels": {"com.docker.compose.service": "suite-postgres"},
                                 "Image": "test-image"}, "Image": "sha256:test",
                      "State": {"Running": True}, "Mounts": []}
            stopped = {**record, "State": {"Running": False}}
            with patch.object(recovery, "configuration", return_value=config), \
                 patch.object(recovery, "output", return_value=""), \
                 patch.object(recovery, "containers", side_effect=[[record], [stopped]]), \
                 patch.object(recovery, "require_matching_configuration"), \
                 patch.object(recovery, "require_no_writers"), \
                 patch.object(recovery, "run") as run, \
                 patch.object(recovery, "archive_mount", side_effect=OSError("disk full")):
                with self.assertRaisesRegex(OSError, "disk full"):
                    recovery.backup(suite, root / "backup", "unused")
                self.assertFalse((root / "backup" / "COMPLETE").exists())
                self.assertTrue(any("stop" in call.args for call in run.call_args_list))
                self.assertFalse(any("start" in call.args or "up" in call.args for call in run.call_args_list))

    def test_changed_compose_mounts_fail_before_quiescing(self):
        record = {"Config": {"Labels": {"com.docker.compose.service": "mail",
                                       "com.docker.compose.config-hash": "old-config"}}}
        with patch.object(recovery, "output", return_value="mail changed-config\n"), \
             self.assertRaisesRegex(ValueError, "differs"):
            recovery.require_matching_configuration([record], Path("/opt/robin/.suite"))

    def test_writers_with_overlapping_parent_mounts_are_rejected(self):
        record = {"Name": "/another-writer", "Mounts": [
            {"RW": True, "Source": "/opt/robin/store"}]}
        with patch.object(recovery, "output", side_effect=["container-id", json.dumps([record])]), \
             self.assertRaisesRegex(ValueError, "Another running container"):
            recovery.require_no_writers({"mail:/data": {"source": "/opt/robin/store/robin"}})

    def test_archive_references_must_have_checksums(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = {"format": 1, "images": {}, "mounts": {
                "mail:/data": {"archive": "unchecked.tar"}}, "checksums": {}}
            (root / "manifest.json").write_text(json.dumps(manifest))
            (root / "COMPLETE").write_text(recovery.digest(root / "manifest.json"))
            with patch.object(recovery, "configuration") as configuration, \
                 self.assertRaisesRegex(ValueError, "unchecked archives"):
                recovery.restore(root, root, "unused")
            configuration.assert_not_called()

    def test_missing_data_and_unconfigured_runtime_volumes_fail_before_stopping(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            suite = root / ".suite"
            suite.mkdir()
            missing = {"type": "bind", "source": str(root / "missing"), "target": "/data"}
            cases = [
                ([missing], {"Type": "bind", "Destination": "/data"}, "", "missing from the host"),
                ([], {"Type": "volume", "Destination": "/anonymous", "Name": "unconfigured"},
                 "unconfigured\n", "Unconfigured runtime volume"),
            ]
            for declared, runtime, volumes, message in cases:
                with self.subTest(message=message):
                    config = {"name": "test", "services": {"suite-postgres": {"volumes": declared}}}
                    record = {"Config": {"Labels": {"com.docker.compose.service": "suite-postgres"}},
                              "Mounts": [runtime]}
                    with patch.object(recovery, "configuration", return_value=config), \
                         patch.object(recovery, "output", return_value=volumes), \
                         patch.object(recovery, "containers", return_value=[record]), \
                         patch.object(recovery, "require_matching_configuration"), \
                         patch.object(recovery, "run") as run, \
                         self.assertRaisesRegex(ValueError, message):
                        recovery.backup(suite, root / str(len(declared)), "unused")
                    self.assertFalse(any("stop" in call.args for call in run.call_args_list))


@unittest.skipUnless(os.environ.get("ROBIN_RECOVERY_DOCKER_TEST") == "1",
                     "Opt in to a disposable local-Docker recovery drill")
class DockerRecoveryTest(unittest.TestCase):
    def test_cold_postgres_queue_and_mailbox_restore(self):
        with tempfile.TemporaryDirectory(prefix="robin-recovery-") as directory:
            root = Path(directory)
            suite = root / ".suite"
            suite.mkdir()
            project = "robin-recovery-" + uuid.uuid4().hex[:12]
            config = {
                "name": project,
                "services": {
                    "suite-postgres": {
                        "image": "postgres:18-alpine",
                        "environment": {"POSTGRES_PASSWORD": "disposable-test-only"},
                        "volumes": ["database:/var/lib/postgresql"],
                        "healthcheck": {"test": ["CMD-SHELL", "pg_isready -U postgres"],
                                        "interval": "1s", "timeout": "5s", "retries": 30}},
                    "mail": {"image": recovery.HELPER_IMAGE,
                             "command": ["sleep", "infinity"],
                             "volumes": ["payloads:/queue", "../mailboxes:/mailboxes",
                                         "./settings:/settings:ro"]}},
                "volumes": {"database": {}, "payloads": {}}}
            (suite / "docker-compose.yaml").write_text(json.dumps(config))
            (suite / "settings").write_text("test configuration")
            (suite / ".env").write_text("TEST_SECRET=disposable-test-only\n")
            (root / "mailboxes").mkdir()
            (root / "mailboxes" / "message").write_bytes(b"mailbox contents")
            recovery.run("git", "init", "--quiet", str(root))
            recovery.run("git", "-C", str(root), "add", ".suite")
            recovery.run("git", "-C", str(root), "-c", "user.name=Recovery test",
                         "-c", "user.email=recovery@example.invalid", "commit", "--quiet", "-m", "fixture")
            command = recovery.compose(suite)
            snapshot = root / "snapshot"
            try:
                recovery.run(*command, "up", "-d", "--wait", "--wait-timeout", "90")
                recovery.run(*command, "exec", "-T", "suite-postgres", "psql", "-U", "postgres",
                             "-v", "ON_ERROR_STOP=1", "-c",
                             "CREATE TABLE queue_test (uid TEXT, body TEXT);"
                             "INSERT INTO queue_test VALUES ('accepted-message','queue/payload.eml');")
                recovery.run(*command, "exec", "-T", "mail", "sh", "-ec",
                             "printf 'pending message body' > /queue/payload.eml; chown 5000:5000 /mailboxes/message")
                recovery.backup(suite, snapshot, recovery.HELPER_IMAGE)
                self.assertTrue((snapshot / "COMPLETE").is_file())
                self.assertFalse(any(item["State"]["Running"] for item in recovery.containers(suite)))
                recovery.run(*command, "down", "--volumes")
                (root / "mailboxes" / "message").unlink()
                # Fail-safe preflight: refuse existing mail, before creating volumes.
                (root / "mailboxes" / "existing").write_text("must not be overwritten")
                with self.assertRaisesRegex(ValueError, "non-empty"):
                    recovery.restore(suite, snapshot, recovery.HELPER_IMAGE)
                self.assertEqual("must not be overwritten", (root / "mailboxes" / "existing").read_text())
                (root / "mailboxes" / "existing").unlink()
                recovery.restore(suite, snapshot, recovery.HELPER_IMAGE)
                self.assertEqual([], recovery.containers(suite))
                self.assertEqual(b"mailbox contents", (root / "mailboxes" / "message").read_bytes())
                self.assertEqual(5000, (root / "mailboxes" / "message").stat().st_uid)
                locked = [*command, "-f", str(suite / "docker-compose.yaml"),
                          "-f", str(snapshot / "images.yaml")]
                recovery.run(*locked, "up", "-d", "--no-build", "--pull", "never", "--wait", "--wait-timeout", "90")
                rows = recovery.output(*command, "exec", "-T", "suite-postgres", "psql",
                                       "-U", "postgres", "-Atc", "SELECT uid || ':' || body FROM queue_test")
                self.assertEqual("accepted-message:queue/payload.eml", rows.strip())
                body = recovery.output(*command, "exec", "-T", "mail", "cat", "/queue/payload.eml")
                self.assertEqual("pending message body", body)
                (snapshot / "deployment.tar").write_bytes(b"corrupt")
                with self.assertRaisesRegex(ValueError, "checksum"):
                    recovery.restore(suite, snapshot, recovery.HELPER_IMAGE)
            finally:
                recovery.run(*command, "down", "--volumes", stdout=subprocess.DEVNULL)


if __name__ == "__main__":
    unittest.main()
