import os
from pathlib import Path
import subprocess
import unittest

from docker_fixture import DockerFixture, SUITE


class FixtureIsolationTest(unittest.TestCase):
    def test_external_mounts_names_and_ports_are_rejected(self):
        with DockerFixture() as fixture:
            for definition in (
                {"container_name": "suite-postgres"},
                {"volumes": [str(SUITE / ".env") + ":/env:ro"]},
                {"ports": [{"target": 993, "host_ip": "0.0.0.0"}]},
                {"ports": [{"target": 993, "host_ip": "127.0.0.1", "published": 993}]},
            ):
                fixture.config["services"]["bad"] = definition
                with self.subTest(definition=definition), self.assertRaises(ValueError):
                    fixture.validate_isolation()


@unittest.skipUnless(os.environ.get("ROBIN_SUITE_DOCKER_TEST") == "1", "Opt in to isolated Docker acceptance")
class MailPolicyDockerTest(unittest.TestCase):
    def test_migration_grants_revocation_and_operator_policy(self):
        migration = (SUITE / "migration" / "001-mail-domains-and-sender-grants.sql").read_text()
        with DockerFixture() as fixture:
            fixture.postgres()
            fixture.start()
            fixture.sql(migration)
            self.assertEqual("3", fixture.sql("SELECT count(*) FROM mail_sender_grants").strip())
            fixture.sql("UPDATE mail_domains SET enabled=false WHERE domain='example.test';")
            fixture.sql(migration)
            self.assertEqual("f", fixture.sql("SELECT enabled FROM mail_domains").strip())
            for statement in (
                "INSERT INTO mail_sender_grants VALUES ('alice@example.test','Bad@example.test','alias');",
                "INSERT INTO mail_sender_grants VALUES ('missing@example.test','alice@example.test','delegated');",
                "INSERT INTO mail_sender_grants VALUES ('alice@example.test','bob@example.test','primary');",
                "INSERT INTO mail_domains(domain,catch_all) VALUES ('remote.test','missing@example.test');",
            ):
                with self.subTest(statement=statement), self.assertRaises(subprocess.CalledProcessError):
                    fixture.sql(statement)
            fixture.sql("""
INSERT INTO mail_sender_grants VALUES ('alice@example.test','shared@example.test','delegated');
DELETE FROM users WHERE email='shared@example.test';
""")
            self.assertEqual("0", fixture.sql(
                "SELECT count(*) FROM mail_sender_grants WHERE address='shared@example.test'").strip())
            fixture.sql("""
INSERT INTO aliases(source,destination) VALUES ('team@example.test','alice@example.test'),
 ('team@example.test','bob@example.test');
INSERT INTO mail_sender_grants VALUES ('alice@example.test','team@example.test','alias');
DELETE FROM aliases WHERE source='team@example.test' AND destination='bob@example.test';
""")
            self.assertEqual("1", fixture.sql(
                "SELECT count(*) FROM mail_sender_grants WHERE address='team@example.test'").strip())
            fixture.sql("DELETE FROM aliases WHERE source='team@example.test';")
            self.assertEqual("0", fixture.sql(
                "SELECT count(*) FROM mail_sender_grants WHERE address='team@example.test'").strip())
            fixture.sql("DELETE FROM users WHERE email='alice@example.test';")
            self.assertEqual("0", fixture.sql(
                "SELECT count(*) FROM mail_sender_grants WHERE account='alice@example.test'").strip())
            fixture.sql("UPDATE users SET email='robert@example.test' WHERE email='bob@example.test';")
            self.assertEqual("0", fixture.sql("SELECT count(*) FROM mail_sender_grants").strip())
            fixture.sql("INSERT INTO users(email,password) VALUES ('Mixed@example.test','none');")
            with self.assertRaises(subprocess.CalledProcessError):
                fixture.sql(migration)
