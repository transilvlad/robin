import base64
import imaplib
import json
import os
import shutil
import ssl
import subprocess
import time
import unittest

from docker_fixture import DockerFixture, SUITE, run


@unittest.skipUnless(os.environ.get("ROBIN_SUITE_DOCKER_TEST") == "1",
                     "Opt in to isolated Docker acceptance")
class CyrusApiAuthDockerTest(unittest.TestCase):
    def test_proxy_and_pam_authenticate_to_protected_robin_callbacks(self):
        with DockerFixture() as fixture:
            for name in ("entrypoint.sh", "robin-auth.sh"):
                shutil.copyfile(SUITE / "cyrus" / name, fixture.root / name)
            for name in ("imapd.conf", "cyrus.conf"):
                shutil.copyfile(SUITE / "etc" / "cyrus" / name, fixture.root / name)
            credential = "fixture:" + fixture.password
            password = fixture.root / "admin-password"
            password.write_bytes((fixture.password + "\n").encode())
            backend = {
                "image": os.environ.get("ROBIN_TEST_CYRUS_AUTH_IMAGE", "robin-suite-cyrus:local"),
                "pull_policy": "never", "mem_limit": "256m", "memswap_limit": "256m",
                "cpus": 0.5, "pids_limit": 128,
                "environment": {"ROBIN_API_AUTH": credential},
                "volumes": [
                    f"{fixture.root / 'entrypoint.sh'}:/usr/local/bin/suite-cyrus-entrypoint:ro",
                    f"{fixture.root / 'robin-auth.sh'}:/usr/local/bin/robin-auth:ro",
                    f"{fixture.root / 'imapd.conf'}:/etc/imapd.conf:ro",
                    f"{fixture.root / 'cyrus.conf'}:/etc/cyrus.conf:ro",
                    f"{password}:/run/secrets/cyrus_admin_password:ro",
                    "mail:/var/lib/cyrus", "logs:/var/log"],
                "networks": {"backend": {"aliases": ["mailbox-backend"]}},
                "healthcheck": {"test": ["CMD", "nc", "-z", "127.0.0.1", "143"],
                                "interval": "1s", "timeout": "5s", "retries": 30},
            }
            fixture.config["services"]["cyrus"] = backend
            fixture.config["volumes"].update({"mail": {}, "logs": {}})
            fixture.start()
            identity = run(*fixture.command, "ps", "-q", "cyrus").strip()
            state = json.loads(run("docker", "inspect", identity))[0]
            address = next(iter(state["NetworkSettings"]["Networks"].values()))["IPAddress"]
            token = base64.b64encode(credential.encode()).decode()
            nginx = fixture.root / "nginx.conf"
            nginx.write_text(
                (SUITE / "etc" / "imap-proxy" / "nginx.conf").read_text().replace(
                    "worker_processes auto;", "worker_processes 1;") + """
http {
    server {
        listen 8090;
        if ($http_authorization != "Basic TOKEN") { return 401; }
        location = /mail/proxy-auth {
            add_header Auth-Status OK;
            add_header Auth-Server ADDRESS;
            add_header Auth-Port 143;
            add_header Auth-User alice@example.test;
            return 200;
        }
        location = /users/authenticate {
            return 200 '{"authenticated":true}';
        }
    }
}
""".replace("TOKEN", token).replace("ADDRESS", address), encoding="utf-8")
            start = fixture.root / "proxy-start.sh"
            shutil.copyfile(SUITE / "etc" / "imap-proxy" / "start.sh", start)
            tls = fixture.root / "tls"
            tls.mkdir()
            certificate = tls / "fullchain.pem"
            key = tls / "privkey.pem"
            run("openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1",
                "-subj", "/CN=localhost", "-addext", "subjectAltName=IP:127.0.0.1",
                "-set_serial", "101",
                "-keyout", str(key), "-out", str(certificate))
            fixture.config["networks"]["edge"] = {}
            proxy = {
                "image": os.environ.get("ROBIN_TEST_NGINX_IMAGE", "nginx:alpine"),
                "pull_policy": "never", "mem_limit": "64m", "memswap_limit": "64m",
                "cpus": 0.5, "pids_limit": 32, "command": ["sh", "/etc/nginx/suite-start.sh"],
                "environment": {"ROBIN_API_AUTH": credential, "MAIL_TLS_PROFILE": "production"},
                "networks": {"backend": {"aliases": ["mta-backend"]}, "edge": {}},
                "ports": [{"target": 993, "host_ip": "127.0.0.1"}],
                "volumes": [f"{nginx}:/etc/nginx/nginx.conf:ro",
                            f"{start}:/etc/nginx/suite-start.sh:ro",
                            f"{tls}:/run/mail-tls:ro"],
                "healthcheck": {"test": ["CMD", "nc", "-z", "127.0.0.1", "993"],
                                "interval": "1s", "timeout": "5s", "retries": 30},
            }
            fixture.config["services"]["proxy"] = proxy
            fixture.start()
            context = ssl.create_default_context(cafile=str(certificate))

            def connect():
                port = run(*fixture.command, "port", "proxy", "993").strip().rsplit(":", 1)[1]
                return imaplib.IMAP4_SSL("127.0.0.1", int(port), ssl_context=context, timeout=15)

            with connect() as client:
                self.assertEqual("OK", client.login("alias@example.test", "synthetic-mailbox-password")[0])
                self.assertEqual("65", client.sock.getpeercert()["serialNumber"])
            previous = certificate.read_text()
            renewed_certificate = tls / "renewed.pem"
            renewed_key = tls / "renewed.key"
            run("openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1",
                "-subj", "/CN=localhost", "-addext", "subjectAltName=IP:127.0.0.1",
                "-set_serial", "102", "-keyout", str(renewed_key), "-out", str(renewed_certificate))
            renewed_certificate.replace(certificate)
            renewed_key.replace(key)
            context = ssl.create_default_context(cafile=str(certificate))
            context.load_verify_locations(cadata=previous)
            run(*fixture.command, "exec", "-T", "proxy", "nginx", "-t")
            run(*fixture.command, "exec", "-T", "proxy", "nginx", "-s", "reload")
            for attempt in range(20):
                with connect() as client:
                    serial = client.sock.getpeercert()["serialNumber"]
                if serial == "66":
                    break
                time.sleep(0.1)
            self.assertEqual("66", serial, "Renewed directory-mounted certificate was not served after reload")
            context = ssl.create_default_context(cafile=str(certificate))
            with connect() as client:
                self.assertEqual("OK", client.login("alias@example.test", "synthetic-mailbox-password")[0])
            with self.assertRaises(subprocess.CalledProcessError) as refused:
                run(*fixture.command, "exec", "-T", "proxy", "env", "MAIL_TLS_PROFILE=unknown",
                    "sh", "/etc/nginx/suite-start.sh")
            self.assertIn("Unknown MAIL_TLS_PROFILE", refused.exception.stderr)
            shutil.copyfile(certificate, tls / "imap.crt")
            shutil.copyfile(key, tls / "imap.key")
            certificate.unlink()
            key.unlink()
            proxy["environment"].pop("MAIL_TLS_PROFILE")
            fixture.start()
            with connect() as client:
                self.assertEqual("OK", client.login("alias@example.test", "synthetic-mailbox-password")[0])
                self.assertEqual("66", client.sock.getpeercert()["serialNumber"])
            run(*fixture.command, "exec", "-T", "cyrus", "env", "ROBIN_API_AUTH=wrong:wrong",
                "ROBIN_API_AUTH_HEADER_FILE=/tmp/wrong-api-header", "/usr/local/bin/robin-auth", "--init")
            with self.assertRaises(subprocess.CalledProcessError):
                run(*fixture.command, "exec", "-T", "cyrus", "env", "PAM_USER=alice@example.test",
                    "ROBIN_API_AUTH_HEADER_FILE=/tmp/wrong-api-header", "/usr/local/bin/robin-auth",
                    "http://mta-backend:8090/users/authenticate", input="synthetic-mailbox-password")
            proxy["environment"]["ROBIN_API_AUTH"] = "wrong:wrong"
            fixture.start()
            client = connect()
            try:
                with self.assertRaises(imaplib.IMAP4.error):
                    client.login("alias@example.test", "synthetic-mailbox-password")
            finally:
                client.shutdown()
            for name, memory, pids in (("cyrus", 256 * 1024 ** 2, 128),
                                       ("proxy", 64 * 1024 ** 2, 32)):
                identity = run(*fixture.command, "ps", "-q", name).strip()
                state = json.loads(run("docker", "inspect", identity))[0]
                self.assertEqual("healthy", state["State"]["Health"]["Status"])
                self.assertFalse(state["State"]["OOMKilled"])
                self.assertEqual(memory, state["HostConfig"]["Memory"])
                self.assertEqual(memory, state["HostConfig"]["MemorySwap"])
                self.assertEqual(500_000_000, state["HostConfig"]["NanoCpus"])
                self.assertEqual(pids, state["HostConfig"]["PidsLimit"])
