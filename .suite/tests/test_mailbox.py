import base64
import imaplib
import os
import shutil
import smtplib
import socket
import ssl
import subprocess
import unittest

from docker_fixture import DockerFixture, SUITE, run


def port(fixture, target):
    endpoint = run(*fixture.command, "port", "mailbox", str(target)).strip()
    number = int(endpoint.rsplit(":", 1)[1])
    if not 1024 <= number <= 65535:
        raise ValueError(f"Docker did not allocate a loopback port: {endpoint}")
    return number


def certificate(root, serial):
    run("docker", "run", "--rm", "--network", "none",
        "--mount", f"type=bind,src={root},dst=/out",
        "--entrypoint", "openssl", "dovecot/dovecot:latest",
        "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "2",
        "-set_serial", str(serial), "-subj", "/CN=mail.example.test",
        "-addext", "subjectAltName=DNS:mail.example.test",
        "-keyout", "/out/privkey.pem", "-out", "/out/fullchain.pem")


@unittest.skipUnless(os.environ.get("ROBIN_SUITE_DOCKER_TEST") == "1", "Opt in to isolated Docker acceptance")
class MailboxDockerTest(unittest.TestCase):
    def test_delivery_folders_acl_quota_sieve_revocation_and_certificate_rotation(self):
        with DockerFixture() as fixture:
            fixture.postgres()
            fixture.config["networks"]["edge"] = {}
            config = fixture.root / "dovecot"
            shutil.copytree(SUITE / "etc" / "dovecot", config)
            shutil.copyfile(SUITE / "dovecot-entrypoint.sh", fixture.root / "entrypoint.sh")
            tls = fixture.root / "tls"
            tls.mkdir()
            certificate(tls, 101)
            (config / "conf.d" / "99-test.conf").write_text("""
ssl_server_cert_file = /run/mail-tls/fullchain.pem
ssl_server_key_file = /run/mail-tls/privkey.pem
quota_storage_size = 64k
quota_storage_grace = 0
log_path = /dev/stderr
info_log_path = /dev/stderr
debug_log_path = /dev/stderr
""")
            (fixture.root / "password").write_text(fixture.password)
            fixture.config["services"]["init"] = {
                "image": "alpine:3.23", "networks": ["backend"],
                "command": ["chown", "5000:5000", "/mail"],
                "volumes": ["mailboxes:/mail"],
            }
            fixture.config["services"]["mailbox"] = {
                "image": os.environ.get("ROBIN_TEST_DOVECOT_IMAGE", "dovecot/dovecot:latest"),
                "user": "0:0", "read_only": True,
                "cap_drop": ["ALL"],
                "cap_add": ["CHOWN", "DAC_OVERRIDE", "FOWNER", "SETGID", "SETUID", "NET_BIND_SERVICE", "SYS_CHROOT"],
                "command": ["sh", "/entrypoint.sh"],
                "volumes": [f"{config}:/etc/dovecot:ro", f"{tls}:/run/mail-tls:ro",
                            f"{fixture.root / 'entrypoint.sh'}:/entrypoint.sh:ro",
                            f"{fixture.root / 'password'}:/run/secrets/mailbox_db_password:ro",
                            "mailboxes:/var/mail/vhosts", "logs:/var/log"],
                "tmpfs": ["/tmp", "/run/dovecot"],
                "networks": ["backend", "edge"], "mem_limit": "512m", "cpus": 1, "pids_limit": 256,
                "ports": [{"target": number, "host_ip": "127.0.0.1"} for number in (993, 24, 4190)],
                "depends_on": {"postgres": {"condition": "service_healthy"},
                               "init": {"condition": "service_completed_successfully"}},
                "healthcheck": {"test": ["CMD", "doveadm", "who"],
                                "interval": "1s", "timeout": "5s", "retries": 30},
            }
            fixture.config["volumes"].update({"mailboxes": {}, "logs": {}})
            fixture.start()
            password_hash = run(*fixture.command, "exec", "-T", "mailbox", "doveadm", "pw",
                                "-s", "SHA512-CRYPT", "-p", fixture.password).strip()
            fixture.sql(f"""ALTER ROLE robin_mailbox PASSWORD '{fixture.password}';
UPDATE users SET password='{password_hash}', home='/var/mail/vhosts/example.test/' || split_part(email,'@',1),
 maildir='maildir:/var/mail/vhosts/example.test/' || split_part(email,'@',1);
""")
            context = ssl.create_default_context(cafile=str(tls / "fullchain.pem"))
            # Connect over a random loopback port, while pinning the fixture CA.
            context.check_hostname = False

            def login(user):
                client = imaplib.IMAP4_SSL("127.0.0.1", port(fixture, 993), ssl_context=context, timeout=15)
                self.assertIn(("DNS", "mail.example.test"), client.sock.getpeercert()["subjectAltName"])
                self.assertEqual("OK", client.login(user, fixture.password)[0])
                return client

            with login("alice@example.test") as alice, login("bob@example.test") as bob:
                listing = b"\n".join(alice.list()[1])
                for flag in (b"\\Sent", b"\\Drafts", b"\\Trash", b"\\Junk", b"\\Archive"):
                    self.assertIn(flag, listing)
                capabilities = b" ".join(alice.capability()[1]).split()
                self.assertIn(b"ACL", capabilities)
                self.assertIn(b"QUOTA", capabilities)
                message = b"From: sender@remote.test\r\nTo: alice@example.test\r\nSubject: unique-flatcurve-token\r\n\r\nfixture message\r\n"
                with smtplib.LMTP("127.0.0.1", port(fixture, 24), timeout=15) as lmtp:
                    self.assertEqual({}, lmtp.sendmail("sender@remote.test", ["alice@example.test"], message))
                self.assertEqual("OK", alice.select("INBOX")[0])
                self.assertEqual([b"1"], alice.search(None, "ALL")[1])
                self.assertEqual("OK", alice.setacl("INBOX", "bob@example.test", "lrs")[0])
                result = subprocess.run([*fixture.command, "exec", "-T", "mailbox", "doveadm", "-f", "tab",
                                         "acl", "get", "-u", "alice@example.test", "INBOX"],
                                        capture_output=True, text=True)
                self.assertEqual(0, result.returncode, result.stderr)
                acl = result.stdout
                self.assertIn("bob@example.test", acl)
                print("Dovecot ACL readback:", repr(acl))
                self.assertEqual("OK", bob.select("Shared/alice@example.test/INBOX")[0])
                self.assertEqual([b"1"], bob.search(None, "ALL")[1])
                self.assertEqual("NO", bob.append("Shared/alice@example.test/INBOX", None, None, message)[0])
                bob.close()
                self.assertEqual("OK", alice.deleteacl("INBOX", "bob@example.test")[0])
                run(*fixture.command, "exec", "-T", "mailbox", "doveadm", "acl", "set", "-u",
                    "alice@example.test", "INBOX", "user=bob@example.test", "lookup", "read", "write-seen")
                exact = run(*fixture.command, "exec", "-T", "mailbox", "doveadm", "-f", "tab",
                            "acl", "get", "-u", "alice@example.test", "INBOX")
                self.assertEqual("ID\tGlobal\tRights\nuser=bob@example.test\t\tlookup read write-seen\n", exact)
                run(*fixture.command, "exec", "-T", "mailbox", "doveadm", "acl", "delete", "-u",
                    "alice@example.test", "INBOX", "user=bob@example.test")
                self.assertNotIn("user=bob@example.test", run(
                    *fixture.command, "exec", "-T", "mailbox", "doveadm", "-f", "tab",
                    "acl", "get", "-u", "alice@example.test", "INBOX"))

            # ACL cache is per process: prove revocation on a fresh session.
            with login("bob@example.test") as bob:
                self.assertEqual("NO", bob.select("Shared/alice@example.test/INBOX")[0])
                self.assertEqual("NO", bob.append("INBOX", None, None, b"Subject: too large\r\n\r\n" + b"x" * 70000)[0])
                self.assertEqual("OK", bob.getquotaroot("INBOX")[0])

            # The Sieve protocol must actually accept TLS and authenticate.
            with socket.create_connection(("127.0.0.1", port(fixture, 4190)), timeout=15) as connection:
                stream = connection.makefile("rb")
                self.read_sieve(stream)
                connection.sendall(b"STARTTLS\r\n")
                self.read_sieve(stream)
                stream.close()
                with context.wrap_socket(connection, server_hostname="mail.example.test") as secure:
                    sieve = secure.makefile("rb")
                    self.read_sieve(sieve)
                    token = base64.b64encode(b"\0alice@example.test\0" + fixture.password.encode())
                    secure.sendall(b'AUTHENTICATE "PLAIN" "' + token + b'"\r\n')
                    self.read_sieve(sieve)
                    script = b'require ["fileinto"]; if header :is "Subject" "file-to-junk" { fileinto "Junk"; stop; }\n'
                    secure.sendall(b'PUTSCRIPT "acceptance" {' + str(len(script)).encode() + b"+}\r\n" + script + b"\r\n")
                    self.read_sieve(sieve)
                    secure.sendall(b'SETACTIVE "acceptance"\r\n')
                    self.read_sieve(sieve)
                    sieve.close()
            with smtplib.LMTP("127.0.0.1", port(fixture, 24), timeout=15) as lmtp:
                self.assertEqual({}, lmtp.sendmail("sender@remote.test", ["alice@example.test"],
                                                  message.replace(b"unique-flatcurve-token", b"file-to-junk")))
            with login("alice@example.test") as alice:
                self.assertEqual("OK", alice.select("Junk")[0])
                self.assertEqual([b"1"], alice.search(None, "ALL")[1])
            fixture.sql("UPDATE users SET password='revoked' WHERE email='alice@example.test';")
            with imaplib.IMAP4_SSL("127.0.0.1", port(fixture, 993), ssl_context=context, timeout=15) as client:
                with self.assertRaises(imaplib.IMAP4.error):
                    client.login("alice@example.test", fixture.password)

            certificate(tls, 102)
            run(*fixture.command, "exec", "-T", "mailbox", "doveadm", "reload")
            rotated = ssl.create_default_context(cafile=str(tls / "fullchain.pem"))
            with socket.create_connection(("127.0.0.1", port(fixture, 993)), timeout=15) as connection:
                with rotated.wrap_socket(connection, server_hostname="mail.example.test") as secure:
                    self.assertEqual("66", secure.getpeercert()["serialNumber"])

    def read_sieve(self, stream):
        lines = []
        while True:
            line = stream.readline()
            self.assertTrue(line, b"".join(lines))
            lines.append(line)
            if line.startswith((b"OK", b"NO", b"BYE")):
                self.assertTrue(line.startswith(b"OK"), b"".join(lines))
                return lines
