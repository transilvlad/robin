"""Portable archive/state checks only; no Docker calls or Linux lock simulation."""

from contextlib import ExitStack
import importlib.util
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
        source = root / "snapshot"
        source.mkdir()
        payload = root / "source-data"
        payload.mkdir()
        (payload / "message.eml").write_bytes(b"synthetic pending message body")
        with tarfile.open(source / "mount-000.tar", "w") as archive:
            archive.add(payload, arcname="data")
        with tarfile.open(source / "deployment.tar", "w") as archive:
            archive.add(suite, arcname=".suite")
        mount = {"type": "volume" if shared_volume else "bind",
                 "source": "old-volume" if shared_volume else str(payload),
                 "read_only": False, "archive": "mount-000.tar", "file": False}
        targets = {"mail:/data": dict(mount)}
        if shared_volume:
            targets["mail-reader:/data"] = dict(mount)
        manifest = {
            "format": 1, "revision": "a" * 40, "images": {}, "mounts": targets,
            "checksums": {name: recovery.digest(source / name)
                          for name in ("mount-000.tar", "deployment.tar")},
        }
        self.write_manifest(source, manifest)
        current_mount = {"type": mount["type"],
                         "source": "new-volume" if shared_volume else str(root / "restored-data"),
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
                self.assertFalse((Path(directory) / "restored-data").exists())

    def test_wrong_revision_blocks_restore_without_rewriting_operator_state(self):
        with tempfile.TemporaryDirectory(prefix="robin-recovery-offline-") as directory:
            suite, source, _, current = self.prepare(Path(directory))
            with ExitStack() as stack:
                host = self.mocked_host(stack, current, revision="b" * 40)
                with self.assertRaisesRegex(ValueError, "recorded Git revision"):
                    recovery.restore(suite, source, "not-used-offline")
                host["restore_mount"].assert_not_called()
                self.assertFalse((Path(directory) / "restored-data").exists())

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


if __name__ == "__main__":
    unittest.main()
