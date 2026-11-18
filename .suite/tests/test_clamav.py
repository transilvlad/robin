import copy
from datetime import datetime, timedelta, timezone
import io
import os
import socket
import struct
import subprocess
import unittest
import zipfile

from docker_fixture import DockerFixture, SUITE, run
from test_compose import production_model


@unittest.skipUnless(os.environ.get("ROBIN_SUITE_DOCKER_TEST") == "1",
                     "Opt in to isolated Docker acceptance")
class ClamavPolicyDockerTest(unittest.TestCase):
    def test_actual_daemon_limits_encrypted_metadata_and_signature_age_gate(self):
        with DockerFixture() as fixture:
            source = production_model(fixture)["services"]["suite-clamav"]
            database = fixture.root / "database"
            database.mkdir()
            marker = b"ROBIN_SCANNER_FIXTURE_MARKER"
            (database / "fixture.ndb").write_text(f"Robin.Fixture:0:*:{marker.hex()}\n", encoding="ascii")
            health = fixture.root / "healthcheck.sh"
            health.write_text((SUITE / "etc" / "clamav" / "healthcheck.sh").read_text(), encoding="utf-8", newline="\n")
            fixture.config["networks"]["edge"] = {}
            fixture.config["services"]["scanner"] = {
                "image": source["image"], "pull_policy": "never",
                "mem_limit": "256m", "memswap_limit": "256m", "cpus": 1, "pids_limit": 64,
                "environment": copy.deepcopy(source["environment"]),
                "command": ["clamd", "--foreground=true"],
                "volumes": [f"{database}:/var/lib/clamav", f"{fixture.root}:/fixture:ro"],
                "networks": {"backend": {}, "edge": {}},
                "ports": [{"target": 3310, "host_ip": "127.0.0.1"}],
                # Synthetic signatures exercise policy, not production definition freshness.
                "healthcheck": {"test": ["CMD-SHELL", "printf 'PING\\n' | nc -w 2 127.0.0.1 3310 | grep -qx PONG"],
                                "interval": "1s", "timeout": "4s", "retries": 30},
            }
            fixture.start()
            port = int(run(*fixture.command, "port", "scanner", "3310").strip().rsplit(":", 1)[1])

            def response(payload=None, declared=None):
                with socket.create_connection(("127.0.0.1", port), timeout=30) as client:
                    client.sendall(b"zINSTREAM\0")
                    client.sendall(struct.pack("!I", declared if declared is not None else len(payload)))
                    if payload is not None:
                        client.sendall(payload)
                        client.sendall(struct.pack("!I", 0))
                    data = bytearray()
                    while b"\0" not in data:
                        chunk = client.recv(4096)
                        if not chunk:
                            break
                        data.extend(chunk)
                    return bytes(data).rstrip(b"\0").decode()

            self.assertEqual("stream: OK", response(b"Harmless synthetic message"))
            self.assertIn("Robin.Fixture", response(marker))
            self.assertEqual("stream: OK", response(b"x" * (20 * 1024 ** 2)))
            self.assertIn("size limit exceeded", response(declared=20 * 1024 ** 2 + 1))
            archive = io.BytesIO()
            with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as files:
                for index in range(1001):
                    files.writestr(f"{index}.txt", "synthetic")
            self.assertIn("Heuristics.Limits.Exceeded", response(archive.getvalue()))
            encrypted = bytearray()
            archive = io.BytesIO()
            with zipfile.ZipFile(archive, "w") as files:
                files.writestr("encrypted.txt", "uninspectable encrypted archive metadata")
            encrypted.extend(archive.getvalue())
            for offset in (6, encrypted.index(b"PK\x01\x02") + 8):
                struct.pack_into("<H", encrypted, offset, struct.unpack_from("<H", encrypted, offset)[0] | 1)
            self.assertIn("Heuristics.Encrypted", response(encrypted))

            with self.assertRaises(subprocess.CalledProcessError) as missing:
                run(*fixture.command, "exec", "-T", "scanner", "sh", "/fixture/healthcheck.sh")
            self.assertIn("did not report loaded signature metadata", missing.exception.stderr)
            binaries = fixture.root / "bin"
            binaries.mkdir()
            stub = binaries / "nc"
            stub.write_bytes(b"#!/bin/sh\ncat >/dev/null\ncat /fixture/version.txt\n")
            stub.chmod(0o755)
            now = datetime.now(timezone.utc)
            for age, accepted in ((timedelta(), True), (timedelta(hours=47), True),
                                  (timedelta(hours=49), False),
                                  (timedelta(days=-1), False)):
                with self.subTest(age=age):
                    built = (now - age).strftime("%a %b %d %H:%M:%S %Y")
                    (fixture.root / "version.txt").write_text(f"ClamAV 1.5.0/12345/{built}\n")
                    command = [*fixture.command, "exec", "-T", "scanner", "sh", "-c",
                               "PATH=/fixture/bin:$PATH sh /fixture/healthcheck.sh"]
                    if accepted:
                        self.assertIn("current loaded signature metadata", run(*command))
                    else:
                        with self.assertRaises(subprocess.CalledProcessError):
                            run(*command)
