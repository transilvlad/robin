import copy
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import unittest

from docker_fixture import DockerFixture, SUITE, run
from test_compose import production_model
from test_rspamd import production_redis
from test_preflight import preflight


@unittest.skipUnless(os.environ.get("ROBIN_SUITE_DOCKER_TEST") == "1"
                     and os.environ.get("ROBIN_TEST_ROBIN_IMAGE"),
                     "Requires isolated Docker opt-in and a reviewed local Robin image")
class ProductionMailFlowTest(unittest.TestCase):
    def test_actual_production_policies_with_each_mailbox_backend(self):
        for backend in ("dovecot", "cyrus"):
            with self.subTest(backend=backend), DockerFixture() as fixture:
                self.prepare(fixture, backend)
                fixture.start(wait_timeout=240)
                self.probe(fixture, "positive")
                effective = self.configuration(fixture)
                server = fixture.root / "cfg" / "server.json5"
                stale = copy.deepcopy(effective["server.json5"])
                stale["senderAuthorization"]["enabled"] = False
                server.write_text(json.dumps(stale))
                run(*fixture.command, "restart", "robin")
                run(*fixture.command, "up", "-d", "--no-deps", "--wait", "--wait-timeout", "120", "robin")
                self.probe(fixture, "activation")
                self.configuration(fixture)
                self.probe(fixture, "malware")
                run(*fixture.command, "stop", "rspamd")
                self.probe(fixture, "scanner-outage")
                run(*fixture.command, "start", "--wait", "rspamd")
                (fixture.root / "rspamd" / "dkim" / "example.test.default.key").unlink()
                run(*fixture.command, "restart", "rspamd")
                self.probe(fixture, "missing-signing-key")
                fixture.sql("DELETE FROM mail_sender_grants WHERE address='alias@example.test';")
                self.probe(fixture, "revoked")
                fixture.sql("UPDATE mail_domains SET enabled=false WHERE domain='example.test';")
                self.probe(fixture, "disabled-domain")

    def probe(self, fixture, stage):
        try:
            result = json.loads(run(*fixture.command, "exec", "-T", "probe",
                                    "python3", "/fixture/mailflow_protocol.py", stage))
        except subprocess.CalledProcessError as error:
            self.fail(f"{stage} probe failed:\n{error.stderr}")
        runtime = result.pop("runtime", None)
        self.assertEqual({"stage": stage, "status": "accepted"}, result)
        return runtime

    def configuration(self, fixture):
        runtime = self.probe(fixture, "configuration")
        self.assertEqual("configuration-snapshot", runtime["scope"])
        self.assertTrue(runtime["sync"]["enabled"])
        self.assertFalse(runtime["restartRequired"])
        rows = json.loads(fixture.sql("""
SELECT json_agg(json_build_object('server_id',server_id,'file_name',file_name,'payload_json',payload_json))
FROM robin_config_store;
"""))
        effective = preflight.effective_files(rows, "robin1")
        self.assertTrue(effective["server.json5"]["senderAuthorization"]["enabled"])
        for name in preflight.CRITICAL_FILES:
            self.assertEqual(preflight.canonical_hash(effective[name]), runtime["sourceFiles"][name]["sha256"], name)
        self.assertEqual("/var/log/postgresql.log", fixture.sql("SELECT pg_current_logfile();").strip())
        self.assertEqual("1", fixture.sql("SELECT count(*) FROM robin_queue;").strip())
        run(*fixture.command, "exec", "-T", "postgres", "test", "-s", "/var/log/postgresql.log")
        return effective

    def prepare(self, fixture, backend):
        model = production_model(fixture, backend)
        fixture.postgres()
        postgres = fixture.config["services"]["postgres"]
        postgres["pull_policy"] = "never"
        postgres["command"] = copy.deepcopy(model["services"]["suite-postgres"]["command"])
        postgres["entrypoint"] = copy.deepcopy(model["services"]["suite-postgres"]["entrypoint"])
        shutil.copyfile(SUITE / "postgres-entrypoint.sh", fixture.root / "postgres-start.sh")
        postgres["volumes"].extend([
            f"{fixture.root / 'postgres-start.sh'}:/etc/postgresql/suite-start.sh:ro",
            "postgres-logs:/var/log"])
        fixture.config["volumes"]["postgres-logs"] = {}
        schema = fixture.root / "init" / "00-schema.sql"
        schema.write_text(schema.read_text() + f"""
ALTER ROLE robin_mta PASSWORD '{fixture.password}';
ALTER ROLE robin_mailbox PASSWORD '{fixture.password}';
GRANT CREATE ON SCHEMA public TO robin_mta;
UPDATE users SET password='{{PLAIN}}{fixture.password}',
 home='/var/mail/vhosts/example.test/' || split_part(email,'@',1),
 maildir='maildir:/var/mail/vhosts/example.test/' || split_part(email,'@',1);
""" + (SUITE / "migration" / "001-mail-domains-and-sender-grants.sql").read_text() + """
INSERT INTO aliases(source,destination) VALUES
 ('alias@example.test','alice@example.test'), ('receive-only@example.test','alice@example.test');
INSERT INTO auto_aliases(source,destination) VALUES ('auto@example.test','alice@example.test');
INSERT INTO mail_sender_grants VALUES ('alice@example.test','alias@example.test','alias');
""")
        production_redis(fixture, model, volume="redis-state")
        cfg = fixture.root / "cfg"
        cfg.mkdir()
        for file in (SUITE / "cfg").iterdir():
            if file.is_file():
                shutil.copyfile(file, cfg / file.name)
        for file in (SUITE / "cfg-production").glob("*.json5"):
            shutil.copyfile(file, cfg / file.name)
        queue, count = re.subn(r"(?m)^(\s*startDelaySeconds:\s*)\d+", r"\g<1>86400",
                               (cfg / "queue.json5").read_text())
        self.assertEqual(1, count, "Outbound dispatcher must be held inside the isolated fixture")
        (cfg / "queue.json5").write_text(queue)
        tls = fixture.root / "tls"
        tls.mkdir()
        run("openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1",
            "-subj", "/CN=mail.example.test", "-addext",
            "subjectAltName=DNS:mail.example.test,DNS:mta-backend,DNS:imap-backend",
            "-keyout", str(tls / "privkey.pem"), "-out", str(tls / "fullchain.pem"))
        (fixture.root / "password").write_text(fixture.password)
        for name in ("mailflow_protocol.py", "test_dkim_offline.py"):
            shutil.copyfile(Path(__file__).parent / name, fixture.root / name)
        credential = "fixture:" + fixture.password
        fixture.config["volumes"].update({"mail": {}, "payloads": {}, "mail-logs": {}, "scanner": {}})
        fixture.config["services"]["robin"] = {
            "image": os.environ["ROBIN_TEST_ROBIN_IMAGE"], "pull_policy": "never",
            "environment": {"MTA_DB_PASSWORD": fixture.password, "MAILBOX_DB_PASSWORD": fixture.password,
                            "ROBIN_API_AUTH": credential, "MAIL_HOSTNAME": "mail.example.test"},
            "volumes": [f"{cfg}:/usr/local/robin/cfg", f"{tls}:/run/robin-tls:ro",
                        "payloads:/usr/local/robin/store"],
            "networks": {"backend": {"aliases": ["mta-backend"]}},
            "mem_limit": "768m", "cpus": 1, "pids_limit": 128,
            "depends_on": {"postgres": {"condition": "service_healthy"},
                           "rspamd": {"condition": "service_healthy"},
                           "clamav": {"condition": "service_healthy"}},
            "healthcheck": {"test": ["CMD", "nc", "-z", "localhost", "25"],
                            "interval": "2s", "timeout": "4s", "retries": 45},
        }
        fixture.config["services"]["probe"] = {
            "image": os.environ.get("ROBIN_TEST_PROBE_IMAGE", "python:3.13-slim"),
            "pull_policy": "never", "command": ["sleep", "infinity"], "networks": ["backend"],
            "volumes": [f"{fixture.root}:/fixture:ro", "payloads:/payloads:ro"],
            "mem_limit": "96m", "cpus": 0.5, "pids_limit": 32,
        }
        self.scanners(fixture, model)
        self.mailbox(fixture, model, backend, credential)

    def scanners(self, fixture, model):
        local = fixture.root / "rspamd"
        local.mkdir()
        for file in (SUITE / "etc" / "rspamd" / "production" / "local.d").iterdir():
            if file.is_file():
                shutil.copyfile(file, local / file.name)
        keys = local / "dkim"
        keys.mkdir()
        key = keys / "example.test.default.key"
        run("openssl", "genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:2048", "-out", str(key))
        run("openssl", "pkey", "-in", str(key), "-pubout", "-out", str(fixture.root / "signing.pub"))
        key.chmod(0o644)
        with (local / "dkim_signing.conf").open("a") as config:
            config.write("\ncheck_pubkey = false;\n")
        # External DNS checks cannot be meaningful on the internal-only fixture network.
        for name in ("rbl", "surbl", "spf", "dmarc", "arc"):
            (local / f"{name}.conf").write_text("enabled = false;\n")
        (local / "worker-normal.inc").write_text('bind_socket = "*:11333"; count = 1;\n')
        source = model["services"]["suite-rspamd"]
        volumes = [f"{local}:/etc/rspamd/local.d:ro", "scanner:/var/lib/rspamd"]
        for secret in source["secrets"]:
            volumes.append(f"{model['secrets'][secret['source']]['file']}:/run/secrets/{secret['target']}:ro")
        fixture.config["services"]["rspamd"] = {
            "image": source["image"], "pull_policy": "never", "volumes": volumes,
            "healthcheck": copy.deepcopy(source["healthcheck"]),
            "networks": {"backend": {"aliases": ["spam-backend"]}},
            "depends_on": {"redis": {"condition": "service_healthy"}},
            "mem_limit": "512m", "cpus": 1, "pids_limit": 96,
        }
        database = fixture.root / "clamav"
        database.mkdir()
        marker = b"ROBIN_SCANNER_FIXTURE_MARKER"
        (database / "fixture.ndb").write_text(f"Robin.Fixture:0:*:{marker.hex()}\n")
        source = model["services"]["suite-clamav"]
        fixture.config["services"]["clamav"] = {
            "image": source["image"], "pull_policy": "never",
            "command": ["clamd", "--foreground=true"],
            "environment": copy.deepcopy(source["environment"]),
            "volumes": [f"{database}:/var/lib/clamav"],
            "networks": {"backend": {"aliases": ["antivirus-backend"]}},
            "mem_limit": "256m", "cpus": 1, "pids_limit": 64,
            "healthcheck": {"test": ["CMD-SHELL", "printf 'PING\\n' | nc -w 2 127.0.0.1 3310 | grep -qx PONG"],
                            "interval": "1s", "timeout": "4s", "retries": 30},
        }

    def mailbox(self, fixture, model, backend, credential):
        tls = fixture.root / "tls"
        if backend == "dovecot":
            config = fixture.root / "dovecot"
            shutil.copytree(SUITE / "etc" / "dovecot" / "conf.d", config / "conf.d")
            shutil.copyfile(SUITE / "etc" / "dovecot" / "dovecot.conf", config / "dovecot.conf")
            shutil.copyfile(SUITE / "etc" / "dovecot" / "production-tls.conf",
                            config / "conf.d" / "10-ssl-certificates.conf")
            shutil.copyfile(SUITE / "dovecot-entrypoint.sh", fixture.root / "mailbox-start.sh")
            fixture.config["services"]["init"] = {
                "image": os.environ.get("ROBIN_TEST_HELPER_IMAGE", "alpine:3.23"), "pull_policy": "never",
                "command": ["chown", "5000:5000", "/mail"], "volumes": ["mail:/mail"],
                "networks": ["backend"], "mem_limit": "32m", "cpus": 0.25, "pids_limit": 16,
            }
            fixture.config["services"]["mailbox"] = {
                "image": os.environ.get("ROBIN_TEST_DOVECOT_IMAGE", "dovecot/dovecot:latest"),
                "pull_policy": "never", "user": "0:0", "cap_add": ["CHOWN"],
                "command": ["sh", "/entrypoint.sh"],
                "environment": {"POSTMASTER_ADDRESS": "postmaster@example.test"},
                "volumes": [f"{config}:/etc/dovecot:ro", f"{tls}:/run/mail-tls:ro",
                            f"{fixture.root / 'mailbox-start.sh'}:/entrypoint.sh:ro",
                            f"{fixture.root / 'password'}:/run/secrets/mailbox_db_password:ro",
                            "mail:/var/mail/vhosts", "mail-logs:/var/log"],
                "depends_on": {"postgres": {"condition": "service_healthy"},
                               "init": {"condition": "service_completed_successfully"}},
                "networks": {"backend": {"aliases": ["imap-backend", "lmtp-backend"]}},
                "mem_limit": "512m", "cpus": 1, "pids_limit": 256,
                "healthcheck": {"test": ["CMD", "doveadm", "who"],
                                "interval": "1s", "timeout": "4s", "retries": 30},
            }
            return
        volumes = ["mail:/var/lib/cyrus", "mail-logs:/var/log",
                   f"{fixture.root / 'password'}:/run/secrets/cyrus_admin_password:ro"]
        for source, target in (
                ("cyrus/entrypoint.sh", "/usr/local/bin/suite-cyrus-entrypoint"),
                ("cyrus/robin-auth.sh", "/usr/local/bin/robin-auth"),
                ("cyrus/healthcheck.sh", "/usr/local/bin/suite-cyrus-healthcheck"),
                ("etc/cyrus/imapd.conf", "/etc/imapd.conf"),
                ("etc/cyrus/cyrus.conf", "/etc/cyrus.conf")):
            file = fixture.root / Path(source).name
            shutil.copyfile(SUITE / source, file)
            volumes.append(f"{file}:{target}:ro")
        fixture.config["services"]["mailbox"] = {
            "image": os.environ.get("ROBIN_TEST_CYRUS_AUTH_IMAGE", "robin-suite-cyrus:local"),
            "pull_policy": "never", "init": True, "environment": {"ROBIN_API_AUTH": credential},
            "volumes": volumes, "networks": {"backend": {"aliases": ["mailbox-backend", "lmtp-backend"]}},
            "mem_limit": "256m", "cpus": 1, "pids_limit": 128,
            "healthcheck": {"test": ["CMD", "nc", "-z", "localhost", "143"],
                            "interval": "1s", "timeout": "4s", "retries": 30},
        }
        for name in ("start.sh", "nginx.conf"):
            shutil.copyfile(SUITE / "etc" / "imap-proxy" / name, fixture.root / ("proxy-" + name))
        fixture.config["services"]["proxy"] = {
            "image": os.environ.get("ROBIN_TEST_NGINX_IMAGE", "nginx:alpine"), "pull_policy": "never",
            "command": ["sh", "/etc/nginx/suite-start.sh"],
            "environment": {"ROBIN_API_AUTH": credential, "MAIL_TLS_PROFILE": "production"},
            "volumes": [f"{fixture.root / 'proxy-start.sh'}:/etc/nginx/suite-start.sh:ro",
                        f"{fixture.root / 'proxy-nginx.conf'}:/etc/nginx/nginx.conf:ro",
                        f"{tls}:/run/mail-tls:ro"],
            "networks": {"backend": {"aliases": ["imap-backend"]}},
            "mem_limit": "64m", "cpus": 0.5, "pids_limit": 32,
            "depends_on": {"robin": {"condition": "service_healthy"},
                           "mailbox": {"condition": "service_healthy"}},
            "healthcheck": {"test": copy.deepcopy(model["services"]["suite-imap-proxy"]["healthcheck"]["test"]),
                            "interval": "1s", "timeout": "4s", "retries": 30},
        }
