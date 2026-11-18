import gzip
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

from docker_fixture import DockerFixture, SUITE, run


@unittest.skipUnless(os.environ.get("ROBIN_SUITE_DOCKER_TEST") == "1", "Opt in to isolated Docker acceptance")
class PostgresLogSafetyTest(unittest.TestCase):
    def test_startup_refuses_link_or_special_active_log(self):
        for create in ("ln -s /etc/passwd", "mkfifo"):
            with self.subTest(create=create), DockerFixture() as fixture:
                script = fixture.root / "postgres-start.sh"
                shutil.copyfile(SUITE / "postgres-entrypoint.sh", script)
                with self.assertRaises(subprocess.CalledProcessError) as refused:
                    run("docker", "run", "--rm", "--network", "none", "--read-only",
                        "--tmpfs", "/var/log", "--memory", "64m", "--cpus", "0.5", "--pids-limit", "32",
                        "--mount", f"type=bind,src={script},dst=/fixture-start.sh,readonly",
                        "--entrypoint", "sh",
                        os.environ.get("ROBIN_TEST_POSTGRES_IMAGE", "postgres:18-alpine"), "-ec",
                        f"{create} /var/log/postgresql.log; exec sh /fixture-start.sh postgres --version")
                self.assertIn("ERROR: PostgreSQL active log must be a regular file", refused.exception.stderr)


@unittest.skipUnless(os.name != "nt" and shutil.which("logrotate")
                     and os.geteuid() == 0, "Requires Linux root and the host logrotate utility")
class FileLogRetentionTest(unittest.TestCase):
    def test_retention_preserves_open_writer_and_does_not_touch_message_storage(self):
        with tempfile.TemporaryDirectory(prefix="robin-log-policy-") as directory:
            root = Path(directory)
            logs = root / "log" / "postgres"
            logs.mkdir(parents=True)
            logs.chmod(0o750)
            os.chown(logs, 65534, 65534)
            message = root / "store" / "queue" / "pending.eml"
            message.parent.mkdir(parents=True)
            message.write_bytes(b"accepted message must not be pruned")
            active = logs / "postgresql.log"
            policy = root / "logrotate.conf"
            policy.write_text(
                (SUITE.parent / "examples" / "ansible" / "templates" / "logrotate.j2").read_text()
                .replace("{{ robin_suite_install_dir }}", str(root)))
            with active.open("a") as writer:
                os.chown(active, 65534, 65534)
                for generation in range(16):
                    writer.write(f"generation-{generation}\n")
                    writer.flush()
                    subprocess.run(
                        ["logrotate", "--force", "--state", str(root / "state"), str(policy)],
                        check=True, capture_output=True, text=True, timeout=15)
                writer.write("writer still attached\n")
                writer.flush()
            self.assertEqual("writer still attached\n", active.read_text())
            archives = [path for path in logs.iterdir() if path != active]
            self.assertEqual(14, len(archives))
            self.assertEqual("generation-15\n", (logs / "postgresql.log.1").read_text())
            with gzip.open(logs / "postgresql.log.14.gz", "rt") as oldest:
                self.assertEqual("generation-2\n", oldest.read())
            self.assertEqual(b"accepted message must not be pruned", message.read_bytes())
