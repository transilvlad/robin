import hashlib
import ast
import importlib.util
import io
import json
from pathlib import Path
import tarfile
import tempfile
import subprocess
import ssl
import unittest
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("cyrus_upgrade", ROOT / "cyrus/upgrade.py")
upgrade = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(upgrade)
PROBE_SPEC = importlib.util.spec_from_file_location("cyrus_tls_probe", ROOT / "cyrus/probe.py")
probe = importlib.util.module_from_spec(PROBE_SPEC)
PROBE_SPEC.loader.exec_module(probe)


class CyrusUpgradeTest(unittest.TestCase):
    def make_snapshot(self, path):
        with tarfile.open(path / "data.tar", "w") as archive:
            message = tarfile.TarInfo("./mail/user/1.")
            message.size = 7
            archive.addfile(message, io.BytesIO(b"message"))
        for name in ("images.tar", "imapd.conf", "cyrus.conf"):
            (path / name).write_text("fixture")
        data = {"format": 1, "image_id": "sha256:" + "a" * 64,
                "source_volume": "suite-cyrus-data", "files": {name: upgrade.digest(path / name)
                    for name in ("data.tar", "images.tar", "imapd.conf", "cyrus.conf")}}
        (path / "manifest.json").write_text(json.dumps(data))
        (path / "COMPLETE").write_text(upgrade.digest(path / "manifest.json"))
        return data

    def test_incomplete_snapshot_fails_without_docker(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(upgrade, "run") as run:
            with self.assertRaisesRegex(ValueError, "incomplete"):
                upgrade.load_snapshot(Path(directory))
            run.assert_not_called()

    def test_snapshot_hash_checks_all_required_files(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            self.make_snapshot(path)
            self.assertEqual("suite-cyrus-data", upgrade.load_snapshot(path)["source_volume"])
            (path / "data.tar").write_bytes(b"changed")
            with self.assertRaisesRegex(ValueError, "checksum"):
                upgrade.load_snapshot(path)

    def test_manifest_cannot_select_arbitrary_files(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            data = self.make_snapshot(path)
            data["files"]["../secret"] = "a" * 64
            (path / "manifest.json").write_text(json.dumps(data))
            (path / "COMPLETE").write_text(upgrade.digest(path / "manifest.json"))
            with self.assertRaisesRegex(ValueError, "manifest"):
                upgrade.load_snapshot(path)

    def test_archive_rejects_traversal_devices_and_escaping_links(self):
        for name, kind, link in (("../x", tarfile.REGTYPE, ""),
                                  ("/absolute", tarfile.REGTYPE, ""),
                                  ("dev", tarfile.CHRTYPE, ""),
                                  ("link", tarfile.SYMTYPE, "../../outside"),
                                  ("link", tarfile.LNKTYPE, "/outside")):
            with self.subTest(name=name, kind=kind), tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / "data.tar"
                with tarfile.open(path, "w") as archive:
                    item = tarfile.TarInfo(name)
                    item.type = kind
                    item.linkname = link
                    archive.addfile(item)
                with self.assertRaises(ValueError):
                    upgrade.safe_archive(path)

    def test_hardlinked_message_bytes_are_included_in_comparison(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "data.tar"
            with tarfile.open(path, "w") as archive:
                message = tarfile.TarInfo("./mail/a/1.")
                message.size = 4
                archive.addfile(message, io.BytesIO(b"mail"))
                link = tarfile.TarInfo("./mail/b/1.")
                link.type = tarfile.LNKTYPE
                link.linkname = "./mail/a/1."
                archive.addfile(link)
            upgrade.safe_archive(path)
            expected = hashlib.sha256(b"mail").hexdigest()
            self.assertEqual({"./mail/a/1.": expected, "./mail/b/1.": expected}, upgrade.manifest_messages(path))

    def test_live_or_crashed_cyrus_cannot_be_snapshotted(self):
        for state in ({"Running": True, "ExitCode": 0},
                      {"Running": False, "ExitCode": 137, "OOMKilled": True},
                      {"Running": False, "ExitCode": 1}):
            with self.subTest(state=state), patch.object(upgrade, "inspect", return_value={"State": state}):
                with self.assertRaisesRegex(ValueError, "cleanly stopped"):
                    upgrade.require_quiesced("suite-cyrus")

    def test_running_delivery_or_webmail_prevents_snapshot(self):
        record = {"State": {"Running": False, "ExitCode": 0}, "Mounts": []}
        for service in upgrade.WRITERS:
            with self.subTest(service=service), patch.object(upgrade, "inspect", return_value=record), \
                 patch.object(upgrade, "output", return_value=service):
                with self.assertRaisesRegex(ValueError, "Quiesce"):
                    upgrade.require_quiesced("suite-cyrus")

    def test_fixture_label_never_bypasses_quiescing_for_original_data(self):
        record = {"State": {"Running": False, "ExitCode": 0},
                  "Config": {"Labels": {upgrade.LABEL: "synthetic-fixture"}}, "Mounts": [
                      {"Destination": "/var/lib/cyrus", "Type": "volume", "Name": "suite-cyrus-data"}]}
        with patch.object(upgrade, "inspect", return_value=record), \
             patch.object(upgrade, "output", return_value="suite-robin"):
            with self.assertRaisesRegex(ValueError, "Quiesce"):
                upgrade.require_quiesced("fixture")

    def test_other_containers_mounting_the_data_are_checked(self):
        record = {"State": {"Running": False, "ExitCode": 0}, "Mounts": [
            {"Destination": "/var/lib/cyrus", "Type": "volume", "Name": "suite-cyrus-data"}]}
        with patch.object(upgrade, "inspect", return_value=record), \
             patch.object(upgrade, "output", return_value=""), \
             patch.object(upgrade.recovery, "require_no_writers", side_effect=ValueError("writer")):
            with self.assertRaisesRegex(ValueError, "writer"):
                upgrade.require_quiesced("suite-cyrus")

    def test_snapshot_failure_has_no_complete_marker_or_auto_restart(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "snapshot"
            record = {"Image": "sha256:" + "a" * 64, "Mounts": []}
            with patch.object(upgrade, "require_quiesced", return_value=(record, {"source": "old"})), \
                 patch.object(upgrade, "run") as run, \
                 patch.object(upgrade.recovery, "archive_mount", side_effect=OSError("disk full")):
                with self.assertRaises(OSError):
                    upgrade.snapshot("suite-cyrus", path)
                self.assertFalse((path / "COMPLETE").exists())
                self.assertFalse(any("start" in call.args or "up" in call.args for call in run.call_args_list))

    def test_restore_never_reuses_existing_volume_even_if_empty(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            self.make_snapshot(path)
            with patch.object(upgrade, "output", return_value="robin-cyrus-rehearsal-test\n"), \
                 patch.object(upgrade, "run") as run:
                with self.assertRaisesRegex(ValueError, "existing"):
                    upgrade.restore(path, "robin-cyrus-rehearsal-test")
                run.assert_not_called()

    def test_restore_rejects_main_or_arbitrary_target_names(self):
        for name in ("suite-cyrus-data", "../data", "robin-cyrus-rehearsal-", "arbitrary"):
            with self.subTest(name=name), self.assertRaises(ValueError):
                upgrade.volume_name(name)

    def test_stage_requires_matching_snapshot_label_before_docker_run(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            self.make_snapshot(path)
            with patch.object(upgrade, "inspect", return_value={"Labels": {}}), \
                 patch.object(upgrade, "run") as run:
                with self.assertRaisesRegex(ValueError, "belong"):
                    upgrade.stage(path, "robin-cyrus-rehearsal-test", "new")
                run.assert_not_called()

    def test_unreviewed_image_cannot_upgrade_clone(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            self.make_snapshot(path)
            volume = {"Labels": {upgrade.LABEL: upgrade.digest(path / "manifest.json")}}
            image = {"Config": {"Labels": {"org.opencontainers.image.version": "3.12.4"}}}
            with patch.object(upgrade, "inspect", side_effect=[volume, image]), \
                 patch.object(upgrade.recovery, "require_no_writers"), patch.object(upgrade, "run") as run:
                with self.assertRaisesRegex(ValueError, "reviewed"):
                    upgrade.stage(path, "robin-cyrus-rehearsal-test", "new")
                run.assert_not_called()

    def test_stage_is_offline_bounded_and_pinned_to_image_id(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            self.make_snapshot(path)
            volume = {"Labels": {upgrade.LABEL: upgrade.digest(path / "manifest.json")}}
            image = {"Id": "sha256:" + "b" * 64, "Config": {"Labels": {
                "org.opencontainers.image.version": "3.12.4",
                "org.robin.cyrus.source-sha256": upgrade.PINS["3.12.4"]}}}
            with patch.object(upgrade, "inspect", side_effect=[volume, image]), \
                 patch.object(upgrade.recovery, "require_no_writers"), patch.object(upgrade, "run") as run:
                upgrade.stage(path, "robin-cyrus-rehearsal-test", "mutable-tag")
                args = run.call_args.args
                self.assertEqual("none", args[args.index("--network") + 1])
                self.assertEqual("768m", args[args.index("--memory") + 1])
                self.assertEqual(image["Id"], args[-1])
                self.assertNotIn("suite-cyrus-data", " ".join(args))

    def test_message_comparison_mismatch_fails_without_provider_or_content_log(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            self.make_snapshot(path)
            volume = {"Labels": {upgrade.LABEL: upgrade.digest(path / "manifest.json")}}
            with patch.object(upgrade, "inspect", return_value=volume), \
                 patch.object(upgrade.recovery, "require_no_writers"), \
                 patch.object(upgrade.recovery, "archive_mount"), \
                 patch.object(upgrade, "manifest_messages", side_effect=[{"1.": "before"}, {"1.": "after"}]):
                with self.assertRaisesRegex(ValueError, "bytes changed"):
                    upgrade.compare(path, "robin-cyrus-rehearsal-test")

    def test_default_suite_does_not_enable_jmap(self):
        self.assertNotIn("jmap", (ROOT / "mailbox/cyrus.yaml").read_text().lower())
        self.assertNotIn("8008", (ROOT / "etc/cyrus/cyrus.conf").read_text())

    def test_proxy_never_retries_writes_or_logs_request_paths(self):
        proxy = (ROOT / "etc/jmap-proxy/default.conf.template").read_text()
        config = (ROOT / "etc/jmap-proxy/nginx.conf").read_text()
        self.assertIn("proxy_next_upstream off;", proxy)
        self.assertIn("limit_req_status 429;", proxy)
        self.assertIn('return 421;', proxy)
        self.assertIn('return 403;', proxy)
        self.assertNotIn("$request_uri", config)
        self.assertNotIn("$http_authorization", config)
        self.assertNotIn("$request_body", config)
        self.assertNotIn("listen 80;", proxy)

    def test_shell_scripts_parse_without_execution(self):
        for name in ("jmap-entrypoint.sh", "jmap-healthcheck.sh", "offline-upgrade.sh", "robin-auth.sh"):
            with self.subTest(name=name):
                subprocess.run(["sh", "-n", str(ROOT / "cyrus" / name)], check=True)
        subprocess.run(["bash", "-n", str(ROOT / "cyrus/run-upgrade-rehearsal.sh")], check=True)

    def test_new_python_sources_parse_without_side_effects(self):
        for source in (ROOT / "cyrus").glob("*.py"):
            with self.subTest(source=source.name):
                ast.parse(source.read_text(), filename=str(source))

    def test_all_reviewed_release_hashes_are_required_by_docker_build(self):
        dockerfile = (ROOT / "cyrus/Dockerfile.jmap").read_text()
        for version, checksum in upgrade.PINS.items():
            self.assertIn(version + ":" + checksum, dockerfile)
        self.assertIn("sha256sum -c -", dockerfile)
        self.assertIn("make -j2", dockerfile)
        self.assertIn("--uid 101", dockerfile)

    def test_current_stage_must_be_verified_before_next_stage(self):
        script = (ROOT / "cyrus/offline-upgrade.sh").read_text()
        self.assertIn("3.8.8:|3.10.4:3.8.8|3.12.4:3.10.4", script)
        self.assertIn(".robin-jmap-verified", script)
        self.assertLess(script.index(".robin-jmap-verified"), script.index("ctl_cyrusdb"))

    def test_verification_cannot_target_main_or_different_volume(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            self.make_snapshot(path)
            volume = {"Labels": {upgrade.LABEL: upgrade.digest(path / "manifest.json")}}
            container = {"State": {"Running": True}, "Mounts": [
                {"Destination": "/var/lib/cyrus", "Name": "suite-cyrus-data"}]}
            with patch.object(upgrade, "inspect", side_effect=[volume, container]), \
                 patch.object(upgrade, "run") as run:
                with self.assertRaisesRegex(ValueError, "selected rehearsal"):
                    upgrade.verify_stage(path, "robin-cyrus-rehearsal-test", "suite-cyrus")
                run.assert_not_called()

    def test_pam_helper_never_puts_password_in_process_arguments(self):
        script = (ROOT / "cyrus/robin-auth.sh").read_text()
        self.assertNotIn('--arg p "$password"', script)
        self.assertIn('jq -Rsc --arg u "$PAM_USER"', script)

    def test_promotion_overlay_cannot_silently_use_original_volume(self):
        overlay = (ROOT / "mailbox/cyrus.jmap.yaml").read_text()
        self.assertIn("external: true", overlay)
        self.assertIn("CYRUS_PROMOTED_VOLUME:?", overlay)
        self.assertNotIn('ports:', overlay.split("services:", 1)[1].split("volumes:\n  cyrus-data", 1)[0])

    def test_generated_config_is_the_default_child_service_config(self):
        entrypoint = (ROOT / "cyrus/jmap-entrypoint.sh").read_text()
        overlay = (ROOT / "mailbox/cyrus.jmap.yaml").read_text()
        self.assertIn("config=/etc/imapd.conf", entrypoint)
        self.assertIn("volumes: !override", overlay)
        self.assertNotIn(":/etc/imapd.conf:ro", overlay)

    def test_admin_sasl_realm_matches_configured_server_name(self):
        entrypoint = (ROOT / "cyrus/jmap-entrypoint.sh").read_text()
        self.assertIn('saslpasswd2 -p -c -u "${JMAP_SERVER_NAME:-jmap-backend}" cyrus', entrypoint)

    def test_search_backfill_uses_all_mailbox_mode_not_recursive_without_source(self):
        script = (ROOT / "cyrus/offline-upgrade.sh").read_text()
        self.assertIn("squatter -C /run/cyrus/imapd.conf\n", script)
        self.assertNotIn("squatter -C /run/cyrus/imapd.conf -r", script)

    def test_tls_probe_rejects_cross_origin_or_downgraded_advertisements(self):
        base = "https://jmap-rehearsal.test:8443/"
        for target in ("http://jmap-rehearsal.test:8443/jmap/", "https://other.test/jmap/",
                       "https://jmap-rehearsal.test/jmap/", "https://user:pass@jmap-rehearsal.test:8443/jmap/",
                       "//evil.test/jmap/", "/jmap/#fragment"):
            with self.subTest(target=target), self.assertRaises(ValueError):
                probe.same_origin(base, target)
        self.assertEqual(base + "jmap/", probe.same_origin(base, "/jmap/"))

    def test_tls_probe_uses_real_ca_and_no_redirect_or_proxy_bypass(self):
        source = (ROOT / "cyrus/probe.py").read_text()
        self.assertIn("ssl.create_default_context(cafile=", source)
        self.assertIn("ProxyHandler({})", source)
        self.assertIn("NoRedirect()", source)
        self.assertNotIn("CERT_NONE", source)
        self.assertNotIn("check_hostname = False", source)

    def test_negative_tls_probe_does_not_accept_connection_failures_as_certificate_denial(self):
        with patch.object(probe, "build_opener") as opener:
            opener.return_value.open.side_effect = probe.URLError(ConnectionRefusedError())
            with self.assertRaisesRegex(ValueError, "non-certificate"):
                probe.rejected_tls("https://localhost/", ssl.create_default_context())

    def test_negative_tls_probe_requires_a_certificate_verification_error(self):
        with patch.object(probe, "build_opener") as opener:
            opener.return_value.open.side_effect = probe.URLError(ssl.SSLCertVerificationError())
            probe.rejected_tls("https://localhost/", ssl.create_default_context())
        with patch.object(probe, "build_opener") as opener:
            with self.assertRaisesRegex(ValueError, "was accepted"):
                probe.rejected_tls("https://localhost/", ssl.create_default_context())


if __name__ == "__main__":
    unittest.main()
