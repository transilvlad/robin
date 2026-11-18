import base64
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import unittest

from docker_fixture import DockerFixture, SUITE, run


def bash():
    if os.name == "nt":
        shell = shutil.which("sh")
        if not shell:
            raise RuntimeError("Git Bash is required for suite initialization tests")
        return str(Path(shell).with_name("bash.exe"))
    return "bash"


def production_model(fixture, backend="dovecot", production=True, overrides=None):
    suite = fixture.root / ".suite"
    suite.mkdir()
    for path in SUITE.glob("docker-compose*.yaml"):
        shutil.copyfile(path, suite / path.name)
    (suite / "mailbox").mkdir()
    for path in (SUITE / "mailbox").glob("*.yaml"):
        shutil.copyfile(path, suite / "mailbox" / path.name)
    shutil.copyfile(SUITE / "init-secrets.sh", suite / "init-secrets.sh")
    run(bash(), str(suite / "init-secrets.sh"))
    # Model merging must not fetch Admin or resolve a developer's real .env.
    admin = fixture.root / "admin.json"
    admin.write_text(json.dumps({"services": {
        name: {"image": "admin:fixture"} for name in
        ("suite-admin", "suite-admin-agent", "suite-admin-gateway")
    }}), encoding="utf-8")
    admin_overlay = fixture.root / "admin-suite.json"
    admin_overlay.write_text('{"services": {}}', encoding="utf-8")
    values = {
        "ROBIN_ADMIN_COMPOSE_FILE": str(admin),
        "ROBIN_ADMIN_SUITE_COMPOSE_FILE": str(admin_overlay),
        "ROBIN_ADMIN_VERSION": "1.2.0", "ROBIN_ADMIN_PUBLIC_URL": "https://localhost:3443",
        "MODULE_PROXY_SECRETS_JSON": json.dumps({"robin-tools": fixture.password}),
        "SESSION_SECRET": fixture.password, "ROBIN_ADMIN_ENCRYPTION_KEY": fixture.password,
        "DB_PASSWORD": fixture.password, "MONITOR_DB_PASSWORD": fixture.password,
        "SUITE_AGENT_CREDENTIAL": fixture.password, "ROBIN_API_AUTH": "fixture:" + fixture.password,
        "SUITE_LOG_MOUNT": str(fixture.root / "logs"),
        "REDIS_ADMIN_DSN": "redis://:" + (suite / "secrets" / "redis_password").read_text().strip()
                           + "@redis-backend:6379",
        "MAIL_HOSTNAME": "mail.example.test", "MAIL_TLS_DIR": str(fixture.root / "tls"),
        "MAILBOX_BACKEND": backend,
        "ROBIN_IMAGE": "robin-suite:fixture", "CYRUS_IMAGE": "cyrus:fixture",
        "NGINX_IMAGE": "nginx:fixture",
        **{name + "_IMAGE": image for name, image in (
            ("POSTGRES", "postgres:18-alpine"), ("DOVECOT", "dovecot/dovecot:latest"),
            ("REDIS", os.environ.get("ROBIN_TEST_REDIS_IMAGE", "redis:alpine")),
            ("CLAMAV", os.environ.get("ROBIN_TEST_CLAMAV_IMAGE", "clamav/clamav:latest")),
            ("RSPAMD", os.environ.get("ROBIN_TEST_RSPAMD_IMAGE", "rspamd/rspamd:latest")),
            ("ROUNDCUBE", "roundcube/roundcubemail:latest"))},
    }
    values.update(overrides or {})
    return compose_model(suite, values, production)


def compose_model(suite, values, production=True):
    (suite / ".env").write_text(
        "\n".join(f"{key}={value}" for key, value in values.items()) + "\n", encoding="utf-8")
    command = ["docker", "compose", "--project-directory", str(suite),
               "--env-file", str(suite / ".env"),
               "-f", str(suite / "docker-compose.yaml"),
               "-f", str(suite / "docker-compose.override.yaml")]
    if production:
        command.extend(["-f", str(suite / "docker-compose.production.yaml")])
    environment = {**os.environ, **values}
    for name in ("MAIL_BIND", "COMPOSE_FILE", "COMPOSE_PROFILES", "COMPOSE_ENV_FILES"):
        environment.pop(name, None)
    return json.loads(run(*command, "config", "--no-env-resolution", "--format", "json",
                          env=environment))


@unittest.skipUnless(os.environ.get("ROBIN_SUITE_DOCKER_TEST") == "1",
                     "Opt in to isolated Compose model acceptance")
class ComposeModelTest(unittest.TestCase):
    def test_ansible_template_supplies_production_authentication_for_both_backends(self):
        import yaml
        from jinja2 import Environment, StrictUndefined

        examples = SUITE.parent / "examples" / "ansible"
        template_environment = Environment(undefined=StrictUndefined)
        template_environment.filters["b64decode"] = lambda value: base64.b64decode(value).decode()
        template = template_environment.from_string(
            (examples / "templates" / "env.j2").read_text(encoding="utf-8"))
        for backend in ("dovecot", "cyrus"):
            with self.subTest(backend=backend), DockerFixture() as fixture:
                production_model(fixture, backend)
                suite = fixture.root / ".suite"
                variables = yaml.safe_load(
                    (examples / "group_vars" / "robin_suite.example.yml").read_text())
                variables.update(yaml.safe_load(
                    (examples / "group_vars" / "robin_suite.vault.example.yml").read_text()))
                variables["robin_suite_secrets"] = {
                    name: secrets.token_hex(32) for name in variables["robin_suite_secrets"]}
                redis_password = (suite / "secrets" / "redis_password").read_bytes()
                variables.update({
                    "robin_suite_revision": "a" * 40,
                    "robin_suite_mailbox_backend": backend,
                    "robin_suite_install_dir": str(fixture.root),
                    "robin_suite_tls_dir": str(fixture.root / "tls"),
                    "suite_redis_password": {"content": base64.b64encode(redis_password).decode()},
                })
                variables["robin_suite_images"] = {
                    name: "fixture@sha256:" + "a" * 64
                    for name in variables["robin_suite_images"]}
                values = dict(line.split("=", 1) for line in template.render(**variables).splitlines()
                              if line and not line.startswith("#"))
                values.update({
                    "ROBIN_ADMIN_COMPOSE_FILE": str(fixture.root / "admin.json"),
                    "ROBIN_ADMIN_SUITE_COMPOSE_FILE": str(fixture.root / "admin-suite.json"),
                })
                services = compose_model(suite, values)["services"]
                expected = "suite-admin:" + variables["robin_suite_secrets"]["api"]
                self.assertEqual(expected, services["suite-robin"]["environment"]["ROBIN_API_AUTH"])
                self.assertEqual(expected, services["suite-admin"]["environment"]["ROBIN_API_AUTH"])
                self.assertEqual(
                    f"redis://:{redis_password.decode().strip()}@redis-backend:6379",
                    services["suite-admin"]["environment"]["REDIS_ADMIN_DSN"])
                if backend == "cyrus":
                    self.assertEqual(expected, services["suite-cyrus"]["environment"]["ROBIN_API_AUTH"])

    def test_production_mailbox_models_use_tls_auth_and_shared_scanner_secrets(self):
        for backend in ("dovecot", "cyrus"):
            with self.subTest(backend=backend), DockerFixture() as fixture:
                model = production_model(fixture, backend)
                services = model["services"]
                robin = services["suite-robin"]
                self.assertEqual("fixture:" + fixture.password, robin["environment"]["ROBIN_API_AUTH"])
                self.assertEqual(robin["environment"]["ROBIN_API_AUTH"],
                                 services["suite-admin"]["environment"]["ROBIN_API_AUTH"])
                password = (fixture.root / ".suite" / "secrets" / "redis_password").read_text().strip()
                self.assertEqual(f"redis://:{password}@redis-backend:6379",
                                 services["suite-admin"]["environment"]["REDIS_ADMIN_DSN"])
                self.assertFalse(any("demo-tls" in mount.get("source", "") for mount in robin["volumes"]))
                mailbox = services["suite-dovecot" if backend == "dovecot" else "suite-imap-proxy"]
                if backend == "cyrus":
                    for name in ("suite-cyrus", "suite-imap-proxy"):
                        self.assertEqual(robin["environment"]["ROBIN_API_AUTH"],
                                         services[name]["environment"]["ROBIN_API_AUTH"])
                self.assertTrue(any("mail-tls" in mount["target"] or "/nginx/certs/" in mount["target"]
                                    for mount in mailbox["volumes"]))
                self.assertTrue(any(mount["target"] == "/templates/rspamd.json5"
                                    and "cfg-production" in mount["source"]
                                    for mount in services["suite-config-init"]["volumes"]))
                self.assertTrue(any(mount["target"] == "/templates/queue.json5"
                                    and "cfg-production" in mount["source"]
                                    for mount in services["suite-config-init"]["volumes"]))
                self.assertEqual(["sh", "/etc/redis/suite-start.sh"], services["suite-redis"]["command"])
                self.assertTrue(any(mount["target"] == "/etc/redis/suite-production.conf"
                                    and mount["read_only"]
                                    for mount in services["suite-redis"]["volumes"]))
                for name in ("suite-robin", "suite-postgres", "suite-redis", "suite-clamav",
                             "suite-rspamd", "suite-roundcube",
                             *(("suite-dovecot",) if backend == "dovecot" else ("suite-cyrus", "suite-imap-proxy"))):
                    service = services[name]
                    self.assertGreater(int(service["mem_limit"]), 0, name)
                    self.assertEqual(service["mem_limit"], service["memswap_limit"], name)
                    self.assertGreater(float(service["cpus"]), 0, name)
                    self.assertGreater(service["pids_limit"], 0, name)
                    self.assertIn("stop_grace_period", service, name)
                self.assertGreaterEqual(int(services["suite-clamav"]["mem_limit"]), 4 * 1024 ** 3)
                if backend == "cyrus":
                    self.assertEqual("production", mailbox["environment"]["MAIL_TLS_PROFILE"])
                    tls = [mount for mount in mailbox["volumes"] if mount["target"].startswith("/run/mail-tls")]
                    self.assertEqual(1, len(tls))
                    self.assertEqual("/run/mail-tls", tls[0]["target"])
                    self.assertTrue(tls[0]["read_only"])
                    self.assertFalse(any("dovecot/certs" in mount["source"].replace("\\", "/")
                                         for mount in mailbox["volumes"]))
                for service in (robin, mailbox):
                    self.assertTrue(all(item.get("host_ip") == "127.0.0.1" for item in service["ports"]))
                self.assertTrue(any(mount["target"].endswith("03-mail-domains-and-sender-grants.sql")
                                    for mount in services["suite-postgres"]["volumes"]))
                self.assertEqual({"redis_auth", "redis_acl", "redis_password"},
                                 {secret["source"] for secret in services["suite-redis"]["secrets"]})
                self.assertEqual({"rspamd_controller", "rspamd_redis"},
                                 {secret["source"] for secret in services["suite-rspamd"]["secrets"]})
                self.assertFalse(services["suite-redis"].get("ports"))

    def test_demo_defaults_to_loopback_without_production_secret_mounts(self):
        with DockerFixture() as fixture:
            services = production_model(fixture, production=False)["services"]
            self.assertFalse(services["suite-redis"].get("secrets"))
            self.assertFalse(services["suite-rspamd"].get("secrets"))
            for name in ("suite-robin", "suite-dovecot"):
                self.assertTrue(all(port["host_ip"] == "127.0.0.1" for port in services[name]["ports"]))

    def test_production_refuses_missing_api_credential_or_required_deployment_inputs(self):
        for name in ("ROBIN_API_AUTH", "REDIS_ADMIN_DSN", "MAIL_TLS_DIR", "RSPAMD_IMAGE"):
            with self.subTest(name=name), DockerFixture() as fixture:
                with self.assertRaises(subprocess.CalledProcessError) as refused:
                    production_model(fixture, overrides={name: ""})
                self.assertIn(name, refused.exception.stderr)


class InitSuiteTest(unittest.TestCase):
    def test_production_refuses_unknown_database_state_before_writes(self):
        for state in ("file", "lookup-error"):
            with self.subTest(state=state), DockerFixture() as fixture:
                suite = fixture.root / ".suite"
                suite.mkdir()
                script = suite / "init-suite.sh"
                shutil.copyfile(SUITE / script.name, script)
                database = fixture.root / "store" / "postgres"
                database.parent.mkdir()
                arguments = [str(script), "--production"]
                if state == "file":
                    database.write_text("not a database directory", encoding="utf-8")
                else:
                    database.mkdir()
                    arguments = ["-c", 'find() { return 73; }; source "$1" --production',
                                 "fixture", str(script)]
                with self.assertRaises(subprocess.CalledProcessError) as refused:
                    run(bash(), *arguments)
                self.assertIn("ERROR:", refused.exception.stderr)
                self.assertFalse((fixture.root / "log").exists())

    def test_missing_or_extra_mode_is_refused_before_initializing(self):
        for arguments in ([], ["--demo", "--production"]):
            with self.subTest(arguments=arguments), DockerFixture() as fixture:
                script = fixture.root / "init-suite.sh"
                shutil.copyfile(SUITE / script.name, script)
                with self.assertRaises(subprocess.CalledProcessError) as refused:
                    run(bash(), str(script), *arguments)
                self.assertEqual(2, refused.exception.returncode)
                self.assertIn("Usage:", refused.exception.stderr)
                self.assertFalse((fixture.root / "etc").exists())

    def test_production_initialization_refuses_existing_database_before_writes(self):
        with DockerFixture() as fixture:
            suite = fixture.root / ".suite"
            suite.mkdir()
            script = suite / "init-suite.sh"
            shutil.copyfile(SUITE / script.name, script)
            database = fixture.root / "store" / "postgres"
            database.mkdir(parents=True)
            (database / "PG_VERSION").write_text("18", encoding="utf-8")
            with self.assertRaises(subprocess.CalledProcessError) as refused:
                run(bash(), str(script), "--production")
            self.assertIn("not a production migration", refused.exception.stderr)
            self.assertFalse((fixture.root / "log").exists())
            self.assertEqual("18", (database / "PG_VERSION").read_text())
