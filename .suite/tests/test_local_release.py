import io
import json
import os
from pathlib import Path
import tarfile
import tempfile
import unittest
from unittest.mock import patch

from local_release import (RetainedFixture, audit_snapshot, encrypt, freeze_revision, immutable_image,
                           message, prepare, recovery, sieve_response)


IMAGE = "sha256:" + "a" * 64


class LocalReleaseTest(unittest.TestCase):
    def test_frozen_metadata_copies_a_real_commit_without_primary_mutation(self):
        from docker_fixture import SUITE, run
        source = SUITE.parent / ".git"
        original = (source / "HEAD").read_bytes()
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ):
            root = Path(directory)
            revision = freeze_revision(root)
            self.assertEqual(revision, (root / "revision.git/HEAD").read_text().strip())
            self.assertEqual("commit", run("git", "cat-file", "-t", "HEAD").strip())
            self.assertFalse(any((root / "revision.git/refs/heads").iterdir()))
        self.assertEqual(original, (source / "HEAD").read_bytes())

    def test_only_immutable_images(self):
        self.assertEqual(IMAGE, immutable_image(IMAGE))
        for value in ("postgres:latest", "sha256:abc", "sha256:" + "A" * 64):
            with self.subTest(value=value), self.assertRaises(ValueError):
                immutable_image(value)

    def test_manage_sieve_response_is_bounded_and_fails_closed(self):
        self.assertEqual([b'"SIEVE" "fileinto"\r\n', b"OK\r\n"],
                         sieve_response(io.BytesIO(b'"SIEVE" "fileinto"\r\nOK\r\n')))
        for value in (b"", b"NO rejected\r\n", b"BYE\r\n", b"x" * 65537,
                      b"capability\r\n" * 101):
            with self.subTest(length=len(value)), self.assertRaises(ValueError):
                sieve_response(io.BytesIO(value))

    def test_retained_fixture_never_removes_volumes(self):
        with tempfile.TemporaryDirectory() as directory:
            fixture = RetainedFixture(Path(directory) / ".suite", "robin-acceptance-offline")
            fixture.created = True
            with patch("local_release.run", return_value="") as run, \
                 patch("local_release.recovery.containers", return_value=[]):
                fixture.close()
            self.assertFalse(any("--volumes" in call.args or "-v" in call.args
                                 for call in run.call_args_list))
            self.assertTrue(fixture.root.is_dir())

    def test_isolation_rejects_egress_builds_and_live_mounts(self):
        with tempfile.TemporaryDirectory() as directory:
            fixture = RetainedFixture(Path(directory) / ".suite", "robin-acceptance-offline")
            fixture.config["services"]["mail"] = {"image": IMAGE, "networks": ["backend"]}
            fixture.validate_isolation()
            for section, value in (
                ("networks", {"backend": {}}),
                ("services", {"mail": {"image": IMAGE, "build": "."}}),
                ("services", {"mail": {"image": IMAGE, "volumes": ["/live:/mail:ro"]}}),
                ("services", {"mail": {"image": "mail:latest"}}),
                ("services", {"mail": {"image": IMAGE, "ports": [
                    {"target": 587, "host_ip": "127.0.0.1", "published": "587"}]}}),
                ("services", {"mail": {"image": IMAGE, "ports": [
                    {"target": 587, "host_ip": "127.0.0.1"}]}}),
                ("services", {"mail": {"image": IMAGE}}),
                ("services", {"mail": {"image": IMAGE, "networks": ["undeclared"]}}),
            ):
                original = fixture.config[section]
                fixture.config[section] = value
                with self.subTest(section=section, value=value), self.assertRaises(ValueError):
                    fixture.validate_isolation()
                fixture.config[section] = original

    def test_messages_have_exact_local_headers_and_injection_cases(self):
        body = message("primary", "alias@example.test")
        self.assertIn(b"From: alias@example.test\r\n", body)
        self.assertIn(b"\r\n\r\nLocal acceptance body:", body)
        self.assertEqual(2, message("duplicate", extra="From: bob@example.test\r\n").count(b"From:"))

    def test_encryption_requires_exact_decryption_before_certifying_evidence(self):
        for corrupt in (False, True):
            with self.subTest(corrupt=corrupt), tempfile.TemporaryDirectory() as directory:
                root = Path(directory) / "evidence"
                root.mkdir()
                (root / "result.json").write_text('{"status":"failed"}')
                destination = Path(directory) / "encrypted.gpg"
                def encrypted(*args, **kwargs):
                    destination.write_bytes((root / "evidence.tar").read_bytes())
                def decrypted(*args, **kwargs):
                    kwargs["stdout"].write(b"corrupt" if corrupt else destination.read_bytes())
                with patch("local_release.private_file"), \
                     patch("local_release.run", side_effect=encrypted) as command, \
                     patch("local_release.subprocess.run", side_effect=decrypted) as decrypt:
                    if corrupt:
                        with self.assertRaisesRegex(ValueError, "did not decrypt exactly"):
                            encrypt(root, destination, Path(directory) / "key")
                    else:
                        encrypt(root, destination, Path(directory) / "key")
                self.assertEqual(600, command.call_args.kwargs["timeout"])
                self.assertEqual(600, decrypt.call_args.kwargs["timeout"])
                self.assertNotIn("--yes", command.call_args.args)
                index = command.call_args.args.index("--compress-algo")
                self.assertEqual("none", command.call_args.args[index + 1])
                self.assertEqual(not corrupt, Path(str(destination) + ".sha256").exists())

    def test_encryption_preserves_preexisting_archive_artifacts(self):
        for name in ("destination", "evidence.tar", "verified.tar"):
            with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                destination = root / "encrypted.gpg"
                previous = destination if name == "destination" else root / name
                previous.write_bytes(b"preserved failure")
                with patch("local_release.private_file"), patch("local_release.run") as command:
                    with self.assertRaises(ValueError):
                        encrypt(root, destination, root / "key")
                self.assertEqual(b"preserved failure", previous.read_bytes())
                command.assert_not_called()

    def test_preparation_uses_real_sql_auth_and_never_starts_docker(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            runtime = root / "runtime"
            runtime.mkdir()
            (runtime / "log4j2.xml").write_text("copied runtime logging")
            fixture = RetainedFixture(root / ".suite", "robin-acceptance-offline")
            with patch("local_release.run") as run:
                prepare(fixture, {name: IMAGE for name in ("postgres", "robin", "cyrus", "helper")}, runtime)
            self.assertTrue(all(call.args[0] == "openssl" for call in run.call_args_list))
            self.assertIn('"passwordQuery": "SELECT password FROM users WHERE email = ?"',
                          (fixture.root / "cfg/dovecot.json5").read_text())
            self.assertIn('"senderAuthorization": {"enabled": true}',
                          (fixture.root / "cfg/server.json5").read_text())
            self.assertEqual("copied runtime logging", (fixture.root / "cfg/log4j2.xml").read_text())
            self.assertNotIn(fixture.password, (fixture.root / "docker-compose.yaml").read_text())
            self.assertFalse(fixture.created)

    def test_snapshot_audit_requires_every_resolved_input_and_exact_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fixture = RetainedFixture(root / ".suite", "robin-acceptance-offline")
            cfg = fixture.root / "cfg"
            cfg.mkdir()
            (cfg / "server").write_text("original configuration")
            snapshot = root / "snapshot"
            snapshot.mkdir()
            with tarfile.open(snapshot / "cfg.tar", "w") as archive:
                archive.add(cfg, arcname="cfg")
            config = {"services": {"robin": {"image": IMAGE, "volumes": [
                {"type": "bind", "source": str(cfg), "target": "/cfg", "read_only": True}]}}}
            manifest = {
                "helper": IMAGE, "images": {"robin": {"id": IMAGE}},
                "mounts": {"robin:/cfg": {"type": "bind", "source": str(cfg), "archive": "cfg.tar"}},
                "checksums": {"cfg.tar": recovery.digest(snapshot / "cfg.tar")},
            }
            def save():
                (snapshot / "manifest.json").write_text(json.dumps(manifest))
                (snapshot / "COMPLETE").write_text(recovery.digest(snapshot / "manifest.json"))
            save()
            with patch("local_release.recovery.configuration", return_value=config):
                self.assertEqual(1, audit_snapshot(fixture, snapshot, {"helper": IMAGE}))
                (cfg / "extra").write_text("not captured")
                with self.assertRaisesRegex(ValueError, "not fully preserved"):
                    audit_snapshot(fixture, snapshot, {"helper": IMAGE})
                manifest["mounts"] = {}
                save()
                with self.assertRaisesRegex(ValueError, "omitted"):
                    audit_snapshot(fixture, snapshot, {"helper": IMAGE})


if __name__ == "__main__":
    unittest.main()
