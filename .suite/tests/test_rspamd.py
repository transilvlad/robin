import copy
import json
import os
from pathlib import Path
import shutil
import subprocess
import unittest
import urllib.error
import urllib.request

from docker_fixture import DockerFixture, SUITE, run
from test_compose import bash, production_model
from test_dkim_offline import verify_fixture_dkim


class SuiteSecretsTest(unittest.TestCase):
    def initialize(self, root):
        script = root / "init-secrets.sh"
        shutil.copyfile(SUITE / script.name, script)
        run(bash(), str(script))
        return script

    def test_generated_server_and_client_credentials_match_and_rerun_is_idempotent(self):
        with DockerFixture() as fixture:
            script = self.initialize(fixture.root)
            secrets = fixture.root / "secrets"
            original = {file.name: file.read_bytes() for file in secrets.iterdir()}
            for name, value in original.items():
                if name.endswith("_password"):
                    self.assertRegex(value, rb"^[0-9a-f]{64}\n$")
            password = original["redis_password"].decode().strip()
            self.assertEqual(f'user default reset on >{password} ~* &* +@all\n',
                             original["redis_auth.acl"].decode())
            self.assertIn(f'password = "{password}";', original["rspamd_redis.inc"].decode())
            run(bash(), str(script))
            self.assertEqual(original, {file.name: file.read_bytes() for file in secrets.iterdir()})
            if os.name != "nt":
                self.assertTrue(all(file.stat().st_mode & 0o777 == 0o600 for file in secrets.iterdir()))

    def test_stale_derived_config_refuses_rotation_until_explicitly_regenerated(self):
        with DockerFixture() as fixture:
            script = self.initialize(fixture.root)
            secrets = fixture.root / "secrets"
            original = (secrets / "rspamd_redis.inc").read_bytes()
            replacement = "a" * 64
            (secrets / "redis_password").write_bytes((replacement + "\n").encode())
            with self.assertRaises(subprocess.CalledProcessError) as refused:
                run(bash(), str(script))
            self.assertIn("derived secret config differs", refused.exception.stderr)
            self.assertNotIn(replacement, refused.exception.stderr)
            self.assertEqual(original, (secrets / "rspamd_redis.inc").read_bytes())
            for name in ("redis_auth.acl", "rspamd_redis.inc"):
                (secrets / name).unlink()
            run(bash(), str(script))
            self.assertIn(replacement, (secrets / "redis_auth.acl").read_text())
            self.assertIn(replacement, (secrets / "rspamd_redis.inc").read_text())

    def test_empty_or_malformed_passwords_and_empty_derived_configs_are_refused(self):
        for name, value in (("redis_password", ""), ("redis_password", 'bad";\nservers="other";'),
                            ("rspamd_controller_password", "short"), ("redis_auth.conf", "")):
            with self.subTest(name=name, value=value), DockerFixture() as fixture:
                script = self.initialize(fixture.root)
                (fixture.root / "secrets" / name).write_text(value, encoding="utf-8")
                with self.assertRaises(subprocess.CalledProcessError) as refused:
                    run(bash(), str(script))
                self.assertIn("ERROR:", refused.exception.stderr)
                self.assertFalse(list((fixture.root / "secrets").glob(".config.*")))


@unittest.skipUnless(os.environ.get("ROBIN_SUITE_DOCKER_TEST") == "1",
                     "Opt in to isolated Docker acceptance")
class RspamdDockerTest(unittest.TestCase):
    def test_production_redis_auth_signing_missing_keys_and_rotation(self):
        with DockerFixture() as fixture:
            model = production_model(fixture)
            local = fixture.root / "local.d"
            local.mkdir()
            for name in ("dkim_signing.conf", "greylist.conf", "logging.inc",
                         "redis.conf", "worker-controller.inc"):
                shutil.copyfile(SUITE / "etc" / "rspamd" / "production" / "local.d" / name, local / name)
            keys = local / "dkim"
            keys.mkdir()
            private_key = keys / "example.test.default.key"
            public_key = fixture.root / "signing.pub"

            def generate_key():
                run("openssl", "genpkey", "-algorithm", "RSA", "-pkeyopt",
                    "rsa_keygen_bits:2048", "-out", str(private_key))
                run("openssl", "pkey", "-in", str(private_key), "-pubout", "-out", str(public_key))
                private_key.chmod(0o644)  # Synthetic fixture key; readable by the image's worker UID.

            generate_key()
            (local / "dkim_signing.conf").write_text(
                (local / "dkim_signing.conf").read_text()
                + '\ncheck_pubkey = false;\n', encoding="utf-8")
            (local / "worker-normal.inc").write_text('bind_socket = "*:11333"; count = 1;\n', encoding="utf-8")
            fixture.config["networks"]["edge"] = {}
            for production, name, memory, cpus in (
                    ("suite-redis", "redis", "128m", 0.5),
                    ("suite-rspamd", "rspamd", "768m", 1)):
                source = model["services"][production]
                service = {
                    "image": source["image"], "pull_policy": "never",
                    "mem_limit": memory, "memswap_limit": memory, "cpus": cpus, "pids_limit": 128,
                    "healthcheck": copy.deepcopy(source["healthcheck"]), "volumes": [],
                    "networks": {"backend": {"aliases": ["redis-backend" if name == "redis" else "spam-backend"]}},
                }
                if "command" in source:
                    service["command"] = source["command"]
                for secret in source["secrets"]:
                    path = model["secrets"][secret["source"]]["file"]
                    target = secret["target"]
                    if not target.startswith("/"):
                        target = "/run/secrets/" + target
                    service["volumes"].append(f"{path}:{target}:ro")
                fixture.config["services"][name] = service
            redis = fixture.config["services"]["redis"]
            redis["volumes"].append("database:/data")
            scanner = fixture.config["services"]["rspamd"]
            scanner["volumes"].extend([f"{local}:/etc/rspamd/local.d:ro", "scanner:/var/lib/rspamd"])
            scanner["networks"]["edge"] = {}
            scanner["ports"] = [{"target": number, "host_ip": "127.0.0.1"} for number in (11333, 11334)]
            scanner["depends_on"] = {"redis": {"condition": "service_healthy"}}
            fixture.config["volumes"].update({"database": {}, "scanner": {}})
            try:
                fixture.start()
            except RuntimeError:
                for identity in run(*fixture.command, "ps", "-aq").split():
                    print(run("docker", "inspect", "--format", "{{json .State}}", identity))
                raise
            secrets = fixture.root / ".suite" / "secrets"
            password = (secrets / "redis_password").read_text().strip()
            controller = (secrets / "rspamd_controller_password").read_text().strip()
            enable = (secrets / "rspamd_enable_password").read_text().strip()
            effective = run(*fixture.command, "exec", "-T", "rspamd", "rspamadm", "configdump")
            self.assertTrue(controller in effective, "Generated controller password is absent from effective config")
            self.assertTrue(enable in effective, "Generated enable password is absent from effective config")

            def redis_command(*arguments, credential=None):
                command = [*fixture.command, "exec", "-T", "redis"]
                if credential is not None:
                    command.extend(["env", f"REDISCLI_AUTH={credential}"])
                return run(*command, "redis-cli", "--raw", *arguments).strip()

            def endpoint(service_port):
                port = run(*fixture.command, "port", "rspamd", str(service_port)).strip().rsplit(":", 1)[1]
                return f"http://127.0.0.1:{port}"

            def control(path, credential=None, data=None):
                headers = {"Password": credential} if credential else {}
                try:
                    with urllib.request.urlopen(urllib.request.Request(
                            endpoint(11334) + path, data=data, headers=headers), timeout=15) as response:
                        return response.status, response.read()
                except urllib.error.HTTPError as error:
                    error.close()
                    raise

            self.assertIn("NOAUTH", redis_command("ping"))
            self.assertIn("NOAUTH", redis_command("ping", credential="wrong"))
            self.assertEqual("PONG", redis_command("ping", credential=password))
            self.assertEqual("OK", redis_command("set", "fixture:preserved", "yes", credential=password))
            self.assertEqual("OK", redis_command("config", "set", "timeout", "90", credential=password))
            self.assertEqual("OK", redis_command("config", "rewrite", credential=password))
            for credential in (None, "wrong"):
                with self.subTest(controller=bool(credential)), self.assertRaises(urllib.error.HTTPError) as denied:
                    control("/stat", credential)
                self.assertEqual(401, denied.exception.code)
            self.assertIn("version", json.loads(control("/stat", controller)[1]))

            body = (b"From: alice@example.test\r\nTo: receiver@remote.test\r\n"
                    b"Subject: scanner acceptance\r\nMessage-ID: <scanner@example.test>\r\n"
                    b"Date: Mon, 16 Nov 2026 12:00:00 +0000\r\n\r\n"
                    b"A synthetic signed message containing enough distinct vocabulary for classifier learning. "
                    b"Apples gardens rivers mountains libraries bicycles sunshine calendars letters "
                    b"envelopes windows forests oceans bridges notebooks tables flowers blankets.\r\n")

            def scan(authenticated=True, message=body):
                headers = {"From": "alice@example.test", "Rcpt": "receiver@remote.test", "Flags": "milter",
                           "Settings": '{"groups_disabled":["rbl","surbl","spf","dmarc"]}'}
                if authenticated:
                    headers["User"] = "delegate@example.test"
                with urllib.request.urlopen(urllib.request.Request(
                        endpoint(11333) + "/checkv2", data=message, headers=headers), timeout=30) as response:
                    return json.load(response)

            def signature(result):
                return result.get("dkim-signature") or result.get("milter", {}).get("add_headers", {}).get("DKIM-Signature")

            def verify(result):
                value = signature(result)
                self.assertIsInstance(value, str, result)
                # The JSON protocol uses LF folding; SMTP headers require CRLF.
                value = value.replace("\r\n", "\n").replace("\n", "\r\n")
                signed = b"DKIM-Signature: " + value.encode() + b"\r\n" + body
                verify_fixture_dkim(signed, public_key, "example.test", "default")
                return signed

            signed = verify(scan())
            with self.assertRaisesRegex(ValueError, "body hash"):
                verify_fixture_dkim(signed.replace(b"A synthetic signed", b"A modified signed"),
                                    public_key, "example.test", "default")
            self.assertFalse(signature(scan(authenticated=False)))
            self.assertFalse(signature(scan(message=body.replace(b"alice@example.test", b"alice@missing.test"))))
            self.assertRegex(run(*fixture.command, "exec", "-T", "rspamd",
                                 "rspamadm", "configdump", "greylist"), r"enabled\s*=\s*false")
            with self.assertRaises(urllib.error.HTTPError) as denied:
                control("/learnspam", controller, body)
            self.assertEqual(401, denied.exception.code)
            self.assertIn(control("/learnspam", enable, body)[0], (200, 204))
            self.assertGreater(json.loads(control("/stat", controller)[1])["learned"], 0)
            self.assertGreater(int(redis_command("dbsize", credential=password)), 1)

            original_public_key = fixture.root / "previous.pub"
            shutil.copyfile(public_key, original_public_key)
            generate_key()
            (secrets / "redis_password").write_bytes(("b" * 64 + "\n").encode())
            for name in ("redis_auth.acl", "rspamd_redis.inc"):
                (secrets / name).unlink()
            run(bash(), str(fixture.root / ".suite" / "init-secrets.sh"))
            try:
                run(*fixture.command, "up", "-d", "--force-recreate", "--wait", "--wait-timeout", "120")
            except subprocess.CalledProcessError as error:
                print(error.stderr)
                for identity in run(*fixture.command, "ps", "-aq").split():
                    print(run("docker", "inspect", "--format", "{{json .State}}", identity))
                raise
            self.assertIn("NOAUTH", redis_command("ping", credential=password))
            self.assertEqual("yes", redis_command("get", "fixture:preserved", credential="b" * 64))
            self.assertEqual("timeout\n90", redis_command("config", "get", "timeout", credential="b" * 64))
            self.assertIn("version", json.loads(control("/stat", controller)[1]))
            signed = verify(scan())
            with self.assertRaisesRegex(ValueError, "RSA"):
                verify_fixture_dkim(signed, original_public_key, "example.test", "default")
            for name, memory, cpus in (("redis", 128 * 1024 ** 2, 500_000_000),
                                       ("rspamd", 768 * 1024 ** 2, 1_000_000_000)):
                identity = run(*fixture.command, "ps", "-q", name).strip()
                state = json.loads(run("docker", "inspect", identity))[0]
                self.assertFalse(state["State"]["OOMKilled"])
                self.assertEqual("healthy", state["State"]["Health"]["Status"])
                self.assertEqual(memory, state["HostConfig"]["Memory"])
                self.assertEqual(memory, state["HostConfig"]["MemorySwap"])
                self.assertEqual(cpus, state["HostConfig"]["NanoCpus"])
                self.assertEqual(128, state["HostConfig"]["PidsLimit"])
