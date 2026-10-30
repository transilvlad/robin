"""Offline only: synthetic protocol, credentials and manifests; never connect."""

import copy
from contextlib import contextmanager, redirect_stderr, redirect_stdout
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import ssl
import stat
import tempfile
import unittest
from unittest.mock import Mock, patch


SOURCE = Path(__file__).resolve().parents[1] / "cyrus/suite-inventory.py"
SPEC = importlib.util.spec_from_file_location("cyrus_suite_inventory", SOURCE)
inventory = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(inventory)

MAILBOX = b'user/synthetic@example.test/Folder "quoted"'
BODY = b"From: fake@example.test\r\nSubject: private fixture\r\n\r\nhello\x00world\r\n"
DATE = b"01-Jan-2020 12:34:56 +0000"


def fetch_data(body=False, uid=7, size=None, flags=b"(\\Seen custom)"):
    size = len(BODY) if size is None else size
    header = (b"1 (UID " + str(uid).encode() + b" FLAGS " + flags + b' INTERNALDATE "'
              + DATE + b'" RFC822.SIZE ' + str(size).encode())
    if body:
        return [(header + b" BODY[] {" + str(len(BODY)).encode() + b"}", BODY), b")"]
    return [header + b")"]


class MockIMAP:
    capabilities = (b"IMAP4REV1", b"ACL", b"QUOTA")

    def __init__(self):
        self.calls = []
        self.count = b"1"
        self.next = b"8"
        self.search_result = [b"7"]
        self.list_result = [b'(\\HasNoChildren) "/" ' + inventory.quote(MAILBOX)]

    def capability(self):
        return "OK", [b" ".join(self.capabilities)]

    def list(self, reference, pattern):
        self.calls.append(("LIST", reference, pattern))
        return "OK", self.list_result

    def lsub(self, reference, pattern):
        self.calls.append(("LSUB", reference, pattern))
        return "OK", [b'() "/" ' + inventory.quote(MAILBOX)]

    def getacl(self, mailbox):
        self.calls.append(("GETACL", mailbox))
        return "OK", [inventory.quote(MAILBOX) + b' "fake@example.test" lrswipkxtecda']

    def getquotaroot(self, mailbox):
        self.calls.append(("GETQUOTAROOT", mailbox))
        return "OK", [[inventory.quote(MAILBOX) + b' "root"'],
                      [b'"root" (STORAGE 12 1024 MESSAGE 1 100)']]

    def select(self, mailbox, readonly=False):
        self.calls.append(("EXAMINE" if readonly else "SELECT", mailbox))
        return "OK", [self.count]

    def response(self, key):
        return key, [b"123" if key == "UIDVALIDITY" else self.next]

    def uid(self, command, *args):
        self.calls.append(("UID", command, *args))
        if command == "SEARCH":
            return "OK", self.search_result
        return "OK", fetch_data(body="BODY.PEEK[]" in args[-1])

    def login(self, username, password):
        self.calls.append(("LOGIN",))
        return "OK", [b"logged in"]

    def shutdown(self):
        self.calls.append(("SHUTDOWN",))


def manifest():
    doc = inventory.inventory(MockIMAP(), inventory.Budget())
    doc.update(format=1, complete=True, scope="new-volume-clone", stage="3.8.8",
               endpoint={"host": "rehearsal.example.test", "address": "127.0.0.1",
                         "port": 993, "transport": "verified-tls"})
    return doc


class ProtocolTest(unittest.TestCase):
    def test_quoted_literal_names_nested_flags_and_nil(self):
        raw = b'user/synthetic@example.test/A B "Q" \\ &AOQ-'
        rows = inventory.parse_rows([(b'(\\HasChildren) NIL {' + str(len(raw)).encode() + b'}', raw), b''])
        self.assertEqual([[ [b"\\HasChildren"], None, raw ]], rows)
        self.assertEqual(raw, inventory.parse_rows([b'() "/" ' + inventory.quote(raw)])[0][2])

    def test_multiple_literals_in_one_record(self):
        self.assertEqual([[b"one", b"two", b"three"]], inventory.parse_rows(
            [(b"{3}", b"one"), (b" {3}", b"two"), b' "three"']))

    def test_literal_body_is_opaque(self):
        rows = inventory.parse_rows(fetch_data(True))
        self.assertEqual(BODY, rows[0][1][-1])
        message = inventory.fetch(MockIMAP(), 7, inventory.Budget(), body=True)
        self.assertEqual(hashlib.sha256(BODY).hexdigest(), message["sha256"])
        self.assertEqual({"header_count": 2, "defects": []}, message["email"])
        self.assertNotIn("Subject", json.dumps(message))
        self.assertNotIn("private fixture", json.dumps(message))

    def test_refuses_unknown_malformed_and_truncated_protocol(self):
        cases = [[b'("unterminated)'], [b"(one"], [b")"], [b"{100}"],
                 [(b"{4}", b"abc"), b""], [(b"{3}", b"abc")],
                 [None, b""], [b'"bad\\escape"'], [b"bad\r\nline"],
                 [b"(" * 17 + b")" * 17], [(b"{1+}", b"a"), b""],
                 [b"FLAGS(\\Seen)"], [b"()NIL"], [b'"a"(b)'],
                 [(b"{1}", b"a"), b"adjacent"]]
        for case in cases:
            with self.subTest(case=case), self.assertRaises(inventory.Refusal):
                inventory.parse_rows(case)

    def test_missing_duplicate_unknown_fetch_and_wrong_size_fail(self):
        cases = [fetch_data(uid=8), fetch_data(True, size=len(BODY) + 1),
                 [fetch_data()[0].replace(b"UID 7", b"UID 7 UID 7")],
                 [fetch_data()[0].replace(b"UID 7", b"UNKNOWN 7")],
                 [None], fetch_data() * 2,
                 [fetch_data()[0].replace(DATE, b"99-Jan-2020 12:34:56 +0000")]]
        for case in cases:
            client = MockIMAP()
            client.uid = Mock(return_value=("OK", case))
            with self.subTest(case=case), self.assertRaises((inventory.Refusal, ValueError)):
                inventory.fetch(client, 7, inventory.Budget(), body=isinstance(case[0], tuple))

    def test_size_is_checked_before_requesting_literal(self):
        with self.assertRaisesRegex(inventory.Refusal, "literal-limit"):
            inventory.inventory(MockIMAP(), inventory.Budget(literal=4))

    def test_full_inventory_uses_only_read_commands(self):
        client = MockIMAP()
        doc = inventory.inventory(client, inventory.Budget())
        self.assertEqual({"mailboxes": 1, "messages": 1}, doc["counts"])
        self.assertEqual(["\\seen", "custom"], doc["mailboxes"][MAILBOX.hex()]["messages"][0]["flags"])
        self.assertEqual({"EXAMINE", "GETACL", "GETQUOTAROOT", "LIST", "LSUB", "UID"},
                         {call[0] for call in client.calls})
        self.assertEqual(2, sum(call[0] == "EXAMINE" for call in client.calls))
        self.assertEqual(1, sum("BODY.PEEK[]" in str(call) for call in client.calls))
        self.assertTrue(all(call[1:] == ('""', '"*"') for call in client.calls
                            if call[0] in ("LIST", "LSUB")))

    def test_admin_star_list_keeps_other_users_and_shared_namespaces(self):
        names = [b"INBOX", b"Other Users/alice@example.test/Archive", b"Shared Folders/team"]
        result = inventory.listing([b'() "/" ' + inventory.quote(item) for item in names], 3)
        self.assertEqual({item.hex() for item in names}, set(result))

    def test_empty_search_is_valid_but_missing_search_is_not(self):
        client = MockIMAP()
        client.count, client.search_result = b"0", [b""]
        self.assertEqual(0, inventory.inventory(client, inventory.Budget())["counts"]["messages"])
        client.search_result = [None]
        with self.assertRaises(inventory.Refusal):
            inventory.inventory(client, inventory.Budget())

    def test_mailbox_and_total_message_limits_fail_without_partial_success(self):
        client = MockIMAP()
        client.list_result *= 2
        with self.assertRaisesRegex(inventory.Refusal, "mailbox-limit"):
            inventory.inventory(client, inventory.Budget(mailboxes=1))
        client = MockIMAP()
        client.list_result.append(b'() "/" "second"')
        # Make extensions unavailable so their mailbox echo does not mask the cap.
        client.capabilities = (b"IMAP4REV1",)
        with self.assertRaisesRegex(inventory.Refusal, "message-limit"):
            inventory.inventory(client, inventory.Budget(messages=1))

    def test_unavailable_selectable_mailbox_is_failure(self):
        client = MockIMAP()
        client.select = Mock(return_value=("NO", [b"private diagnostic"]))
        with self.assertRaisesRegex(inventory.Refusal, "imap-command-failed"):
            inventory.inventory(client, inventory.Budget())

    def test_structural_noselect_is_explicit_and_not_examined(self):
        client = MockIMAP()
        client.list_result.append(b'(\\Noselect) "/" "user"')
        doc = inventory.inventory(client, inventory.Budget())
        entry = doc["mailboxes"][b"user".hex()]
        self.assertIsNone(entry["state"])
        self.assertEqual({"status": "not-selectable"}, entry["observations"]["acl"])
        self.assertFalse(any(call[0] == "EXAMINE" and call[1] == b'"user"' for call in client.calls))

    def test_unadvertised_features_are_explicit_not_queried(self):
        client = MockIMAP()
        client.capabilities = (b"IMAP4REV1",)
        doc = inventory.inventory(client, inventory.Budget())
        self.assertEqual({"acl": {"status": "unsupported"}, "quota": {"status": "unsupported"}},
                         doc["mailboxes"][MAILBOX.hex()]["observations"])
        self.assertFalse(any(call[0] in ("GETACL", "GETQUOTAROOT") for call in client.calls))

    def test_advertised_but_denied_or_malformed_features_fail(self):
        for method, reply in (
            ("getacl", ("NO", [b"denied private"])),
            ("getacl", ("BAD", [b"unsupported despite advertisement"])),
            ("getacl", ("OK", [b'"wrong-mailbox" id lr'])),
            ("getquotaroot", ("OK", [[inventory.quote(MAILBOX) + b' "root"'], [None]])),
            ("getquotaroot", ("NO", [None])),
        ):
            client = MockIMAP()
            setattr(client, method, Mock(return_value=reply))
            with self.subTest(method=method, reply=reply), self.assertRaises(inventory.Refusal):
                inventory.inventory(client, inventory.Budget())

    def test_supported_no_quota_root_is_not_unsupported(self):
        client = MockIMAP()
        client.getquotaroot = Mock(return_value=("OK", [[inventory.quote(MAILBOX)], [None]]))
        doc = inventory.inventory(client, inventory.Budget())
        self.assertEqual({"status": "supported", "roots": {}},
                         doc["mailboxes"][MAILBOX.hex()]["observations"]["quota"])

    def test_counter_uid_flag_and_final_listing_churn_fail(self):
        for mutation in ("counter", "uids", "flags", "list", "subscriptions", "acl"):
            client = MockIMAP()
            if mutation == "counter":
                client.select = Mock(side_effect=[("OK", [b"1"]), ("OK", [b"2"])])
            elif mutation == "uids":
                client.uid = Mock(side_effect=[("OK", [b"7"]), ("OK", fetch_data()),
                                              ("OK", fetch_data(True)), ("OK", [b"8"])])
            elif mutation == "flags":
                client.uid = Mock(side_effect=[("OK", [b"7"]), ("OK", fetch_data()),
                                              ("OK", fetch_data(True)), ("OK", [b"7"]),
                                              ("OK", fetch_data(flags=b"()"))])
            elif mutation == "list":
                client.list = Mock(side_effect=[("OK", client.list_result), ("OK", [None])])
            elif mutation == "subscriptions":
                client.lsub = Mock(side_effect=[("OK", [None]), ("OK", client.list_result)])
            else:
                client.getacl = Mock(side_effect=[("OK", [inventory.quote(MAILBOX) + b" id lr"]),
                                                 ("OK", [inventory.quote(MAILBOX) + b" id l"])])
            with self.subTest(mutation=mutation), self.assertRaises(inventory.Refusal):
                inventory.inventory(client, inventory.Budget())


class ComparisonTest(unittest.TestCase):
    def test_exact_and_explicit_uidnext_increase(self):
        before = manifest()
        before["scope"] = "legacy-before-maintenance"
        after = copy.deepcopy(before)
        after.update(scope="new-volume-clone", stage="3.10.4")
        after["endpoint"]["host"] = "other-rehearsal.example.test"
        self.assertEqual(0, inventory.compare(before, after))
        after["mailboxes"][MAILBOX.hex()]["state"]["uidnext"] += 1
        with self.assertRaisesRegex(inventory.Refusal, "uidnext-differs"):
            inventory.compare(before, after)
        self.assertEqual(1, inventory.compare(before, after, True))
        self.assertRaises(inventory.Refusal, inventory.compare, after, before, True)
        after["mailboxes"][MAILBOX.hex()]["state"]["uidnext"] = 7
        self.assertRaises(inventory.Refusal, inventory.compare, before, after, True)
        before["mailboxes"][MAILBOX.hex()]["state"]["uidnext"] = 9
        after["mailboxes"][MAILBOX.hex()]["state"]["uidnext"] = 8
        with self.assertRaisesRegex(inventory.Refusal, "uidnext-differs"):
            inventory.compare(before, after, True)

    def test_each_durable_field_is_checked(self):
        before = manifest()
        def mutate(after, field):
            record = after["mailboxes"][MAILBOX.hex()]
            if field == "name":
                after["mailboxes"][b"different".hex()] = after["mailboxes"].pop(MAILBOX.hex())
            elif field == "subscription":
                after["subscriptions"] = {}
            elif field == "acl":
                record["observations"]["acl"]["entries"] = {}
            elif field == "quota":
                record["observations"]["quota"]["roots"][b"root".hex()]["STORAGE"][0] += 1
            elif field == "support":
                record["observations"]["acl"] = {"status": "unsupported"}
            elif field == "uidvalidity":
                record["state"]["uidvalidity"] += 1
            elif field == "flags":
                record["messages"][0]["flags"] = []
            elif field == "internaldate":
                record["messages"][0][field] = "01-Jan-2020 12:34:57 +0000"
            elif field == "uid":
                record["messages"][0]["uid"] = 6
            elif field == "size":
                record["messages"][0][field] += 1
            elif field == "sha256":
                record["messages"][0][field] = "a" * 64
            elif field == "delimiter":
                record[field] = None
            elif field == "attributes":
                record[field] = []
        for field in ("name", "subscription", "acl", "quota", "support", "uidvalidity",
                      "flags", "internaldate", "uid", "size", "sha256", "delimiter", "attributes"):
            after = copy.deepcopy(before)
            mutate(after, field)
            with self.subTest(field=field), self.assertRaises(inventory.Refusal):
                inventory.compare(before, after, True)

    def test_incomplete_unknown_and_inconsistent_manifests_fail(self):
        for mutation in ("complete", "format", "count", "missing", "unknown", "bool"):
            doc = manifest()
            if mutation == "complete":
                doc["complete"] = False
            elif mutation == "format":
                doc["format"] = 2
            elif mutation == "count":
                doc["counts"]["messages"] = 0
            elif mutation == "missing":
                doc["mailboxes"][MAILBOX.hex()]["messages"] = []
            elif mutation == "unknown":
                doc["ignored"] = "private"
            else:
                doc["mailboxes"][MAILBOX.hex()]["messages"][0]["uid"] = True
            with self.subTest(mutation=mutation), self.assertRaises(inventory.Refusal):
                inventory.compare(doc, doc)

    def test_duplicate_json_keys_and_nonfinite_json_are_refused(self):
        for raw in (b'{"complete": true, "complete": false}', b'{"value": NaN}'):
            with self.assertRaises(inventory.Refusal):
                inventory.json_document(raw)


class ScriptedSocket:
    """Feeds actual imaplib parsing without a socket or local server."""
    def __init__(self):
        self.incoming = bytearray(b"* OK synthetic greeting\r\n")
        self.commands, self.timeouts = [], []
        self.closed = False

    def settimeout(self, value):
        self.timeouts.append(value)

    def connect(self, address):
        self.address = address

    def makefile(self, mode):
        return io.BytesIO()

    def recv(self, size):
        chunk = bytes(self.incoming[:min(size, 7)])
        del self.incoming[:len(chunk)]
        return chunk

    def sendall(self, data):
        tag, command = data.rstrip(b"\r\n").split(b" ", 1)
        self.commands.append(command)
        if command == b"CAPABILITY":
            self.incoming.extend(b"* CAPABILITY IMAP4rev1\r\n")
        elif command.startswith(b"UID FETCH"):
            header = fetch_data(True)[0][0].replace(b"1 (", b"1 FETCH (", 1)
            self.incoming.extend(b"* " + header + b"\r\n" + BODY + b")\r\n")
        elif command.startswith(b"LIST"):
            self.incoming.extend(b'* LIST () "/" {' + str(len(MAILBOX)).encode() + b"}\r\n"
                                 + MAILBOX + b"\r\n")
        if command.startswith(b"LOGIN "):
            self.incoming.extend(tag + b" OK [CAPABILITY IMAP4rev1] logged in\r\n")
        else:
            self.incoming.extend(tag + b" OK complete\r\n")

    def shutdown(self, mode):
        pass

    def close(self):
        self.closed = True


class TransportTest(unittest.TestCase):
    def test_cyrus_login_capability_is_drained_before_fresh_query(self):
        sock = ScriptedSocket()
        with patch.object(inventory.socket, "socket", return_value=sock):
            budget = inventory.Budget()
            client = inventory.PlainIMAP(host="rehearsal.test", port=143,
                                         address="127.0.0.1", budget=budget, timeout=3)
            try:
                client.login("synthetic", "fake-secret")
                self.assertIn("CAPABILITY", client.untagged_responses)
                client.list = Mock(return_value=("OK", [None]))
                # A complete capability parse must reach the explicit empty-LIST
                # refusal rather than fail on cached + fresh CAPABILITY rows.
                with self.assertRaisesRegex(inventory.Refusal, "empty-inventory"):
                    inventory.inventory(client, budget)
                client.list.assert_called_once_with('""', '"*"')
            finally:
                client.shutdown()

    def test_real_imaplib_literal_representation_and_per_call_timeouts(self):
        sock = ScriptedSocket()
        with patch.object(inventory.socket, "socket", return_value=sock), \
             patch.object(inventory.socket, "getaddrinfo", side_effect=AssertionError("DNS forbidden")):
            budget = inventory.Budget(timeout=3)
            client = inventory.PlainIMAP(host="rehearsal.example.test", port=143,
                                         address="127.0.0.1", budget=budget, timeout=3)
            client.login("synthetic", "fake-secret")
            listed = inventory.listing(inventory.ok(client.list('""', '"*"')), 10)
            self.assertIn(MAILBOX.hex(), listed)
            client.state = "SELECTED"
            self.assertEqual(hashlib.sha256(BODY).hexdigest(),
                             inventory.fetch(client, 7, budget, True)["sha256"])
            client.shutdown()
        self.assertEqual(("127.0.0.1", 143), sock.address)
        self.assertGreater(len(sock.timeouts), 10)
        self.assertTrue(all(0 < timeout <= 3 for timeout in sock.timeouts))
        self.assertTrue(sock.closed)

    def test_mutating_commands_and_nonpeek_fetch_are_refused(self):
        client = object.__new__(inventory.PlainIMAP)
        client.budget = inventory.Budget()
        for command, args in (("SELECT", ("INBOX",)), ("CLOSE", ()), ("STORE", ()),
                              ("EXPUNGE", ()), ("SETACL", ()), ("APPEND", ()),
                              ("UID", ("STORE", "1", "+FLAGS", "\\Seen")),
                              ("UID", ("FETCH", "1", "(BODY[])"))):
            with self.subTest(command=command), self.assertRaisesRegex(inventory.Refusal, "write-command"):
                client._command(command, *args)

    def test_oversized_wire_literal_refused_by_real_imaplib_before_literal_read(self):
        sock = ScriptedSocket()
        original_send = sock.sendall
        def send(data):
            original_send(data)
            if b"UID FETCH" in data:
                sock.incoming = sock.incoming.replace(b"{66}", b"{999999999}")
        sock.sendall = send
        with patch.object(inventory.socket, "socket", return_value=sock):
            budget = inventory.Budget(literal=1024)
            client = inventory.PlainIMAP(host="rehearsal.test", port=143,
                                         address="127.0.0.1", budget=budget, timeout=3)
            try:
                client.login("synthetic", "fake-secret")
                client.state = "SELECTED"
                with self.assertRaisesRegex(inventory.Refusal, "literal-limit"):
                    inventory.fetch(client, 7, budget, True)
            finally:
                client.shutdown()
        self.assertLess(budget.received, 1024)

    def test_literal_line_wire_eof_and_deadline_limits(self):
        def client(**kwargs):
            result = object.__new__(inventory.PlainIMAP)
            result.budget = inventory.Budget(**kwargs)
            result.pending = bytearray()
            result.sock = ScriptedSocket()
            return result
        with self.assertRaisesRegex(inventory.Refusal, "literal-limit"):
            client(literal=4).read(5)
        wire = client(wire=1)
        wire.pending.clear()
        with self.assertRaisesRegex(inventory.Refusal, "wire-limit"):
            wire.readline()
        eof = client()
        eof.sock.incoming.clear()
        with self.assertRaisesRegex(inventory.Refusal, "truncated-response"):
            eof.readline()
        line = client()
        line.pending.extend(b"x" * inventory.MAX_LINE)
        with self.assertRaisesRegex(inventory.Refusal, "line-limit"):
            line.readline()
        expired = client()
        expired.budget.end = 0
        with self.assertRaisesRegex(inventory.Refusal, "deadline"):
            expired.read(0)
        trickle = client()
        with patch.object(inventory.time, "monotonic", side_effect=[trickle.budget.end - 1,
                                                                   trickle.budget.end - 1,
                                                                   trickle.budget.end + 1]):
            with self.assertRaisesRegex(inventory.Refusal, "deadline"):
                trickle.readline()

    def test_invalid_limits_fail(self):
        for limits in ({"deadline": float("nan")}, {"timeout": 0}, {"messages": -1},
                       {"output": inventory.MAX_FILE + 1}, {"literal": True}):
            with self.subTest(limits=limits), self.assertRaises(inventory.Refusal):
                inventory.Budget(**limits)


class SafetyTest(unittest.TestCase):
    def test_raw_secret_format_without_real_files(self):
        password = b" " + b"synthetic-only-password-32-characters" + b" "
        for ending in (b"", b"\n", b"\r\n"):
            with patch.object(inventory, "private_read", return_value=password + ending):
                secret = inventory.credentials("cyrus", Path("synthetic-only"))
                self.assertEqual({"username": "cyrus", "password": password.decode("ascii")}, secret)
        for raw in (b"short", b"x" * 32 + b"\n\n", b"x" * 32 + b"\x00", b"x" * 8193):
            with patch.object(inventory, "private_read", return_value=raw), self.assertRaises(inventory.Refusal):
                inventory.credentials("cyrus", Path("synthetic-only"))
        with patch.object(inventory, "private_read") as reader, self.assertRaises(inventory.Refusal):
            inventory.credentials("bad\nusername", Path("synthetic-only"))
        reader.assert_not_called()

    def test_plain_is_opt_in_private_clone_only(self):
        for address in ("127.0.0.1", "10.1.2.3", "172.16.1.1", "192.168.1.1", "::1", "fd00::1"):
            self.assertEqual(address, inventory.target("rehearsal.test", address, "new-volume-clone", True))
        for address in ("8.8.8.8", "0.0.0.0", "224.0.0.1", "169.254.1.1", "192.0.2.1"):
            with self.subTest(address=address), self.assertRaises(inventory.Refusal):
                inventory.target("rehearsal.test", address, "new-volume-clone", True)
        with self.assertRaises(inventory.Refusal):
            inventory.target("legacy.test", "127.0.0.1", "legacy-before-maintenance", True)

    def test_plain_legacy_requires_explicit_loopback_mode(self):
        for address in ("127.0.0.1", "::1"):
            self.assertEqual(address, inventory.target("legacy.test", address,
                                                       "legacy-before-maintenance", loopback=True))
        for address in ("10.1.2.3", "8.8.8.8", "fd00::1"):
            with self.subTest(address=address), self.assertRaises(inventory.Refusal):
                inventory.target("legacy.test", address, "legacy-before-maintenance", loopback=True)

    def capture_args(self, *extra):
        return ["capture", "--host", "rehearsal.test", "--connect-address", "127.0.0.1",
                "--port", "993", "--scope", "new-volume-clone", "--stage", "3.8.8",
                "--username", "cyrus", "--password-file", "synthetic-password",
                "--manifest", "synthetic-manifest.json", *extra]

    @contextmanager
    def fake_files(self):
        @contextmanager
        def directory(_):
            yield 1
        with patch.object(inventory, "private_directory", directory), \
             patch.object(inventory, "absent"), \
             patch.object(inventory, "credentials", return_value={"username": "fake", "password": "secret"}), \
             patch.object(inventory, "private_write") as writer:
            yield writer

    def test_capture_verifies_tls_and_closes_without_logout(self):
        client = MockIMAP()
        with self.fake_files() as writer, patch.object(inventory, "TLSIMAP", return_value=client) as factory, \
             redirect_stdout(io.StringIO()) as output:
            self.assertEqual(0, inventory.main(self.capture_args()))
        context = factory.call_args.kwargs["ssl_context"]
        self.assertTrue(context.check_hostname)
        self.assertEqual(ssl.CERT_REQUIRED, context.verify_mode)
        self.assertGreaterEqual(context.minimum_version, ssl.TLSVersion.TLSv1_2)
        self.assertEqual("SHUTDOWN", client.calls[-1][0])
        writer.assert_called_once()
        self.assertEqual("PASS inventory: mailboxes=1 messages=1\n", output.getvalue())

    def test_failed_authentication_never_writes_and_redacts_diagnostics(self):
        client = MockIMAP()
        client.login = Mock(side_effect=inventory.imaplib.IMAP4.error("secret private@example.test"))
        with self.fake_files() as writer, patch.object(inventory, "TLSIMAP", return_value=client), \
             redirect_stderr(io.StringIO()) as error, redirect_stdout(io.StringIO()) as output:
            self.assertEqual(1, inventory.main(self.capture_args()))
        writer.assert_not_called()
        self.assertEqual("", output.getvalue())
        self.assertNotIn("secret", error.getvalue())
        self.assertNotIn("@", error.getvalue())
        self.assertEqual("SHUTDOWN", client.calls[-1][0])

    def test_wrong_certificate_is_not_retried_or_downgraded(self):
        with self.fake_files() as writer, \
             patch.object(inventory, "TLSIMAP", side_effect=ssl.SSLCertVerificationError("private-name")) as tls, \
             patch.object(inventory, "PlainIMAP") as plain, redirect_stderr(io.StringIO()) as error:
            self.assertEqual(1, inventory.main(self.capture_args()))
        tls.assert_called_once()
        plain.assert_not_called()
        writer.assert_not_called()
        self.assertNotIn("private-name", error.getvalue())

    def test_unsafe_tls_context_is_refused_before_connect(self):
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        context.check_hostname = False
        context.verify_mode = ssl.CERT_NONE
        with self.fake_files() as writer, \
             patch.object(inventory.ssl, "create_default_context", return_value=context), \
             patch.object(inventory, "TLSIMAP") as tls, redirect_stderr(io.StringIO()):
            self.assertEqual(1, inventory.main(self.capture_args()))
        tls.assert_not_called()
        writer.assert_not_called()

    def test_refusal_before_credentials_or_connection_and_quiet_arguments(self):
        for args in (self.capture_args("--max-mailboxes", "0"),
                     self.capture_args("--password", "private-secret@example.test"),
                     self.capture_args("--plain-loopback", "--ca-file", "not-read.pem"),
                     self.capture_args("--plain-private-rehearsal", "--connect-address", "8.8.8.8")):
            with patch.object(inventory, "credentials") as credentials, \
                 patch.object(inventory, "TLSIMAP") as tls, patch.object(inventory, "PlainIMAP") as plain, \
                 redirect_stderr(io.StringIO()) as error, redirect_stdout(io.StringIO()):
                self.assertEqual(1, inventory.main(args))
                credentials.assert_not_called()
                tls.assert_not_called()
                plain.assert_not_called()
                self.assertNotIn("@", error.getvalue())

    def test_existing_manifest_refuses_before_password_read_or_connect(self):
        @contextmanager
        def directory(_):
            yield 1
        with patch.object(inventory, "private_directory", directory), \
             patch.object(inventory, "absent", side_effect=inventory.Refusal("manifest-exists")), \
             patch.object(inventory, "credentials") as credentials, \
             patch.object(inventory, "TLSIMAP") as tls, redirect_stderr(io.StringIO()):
            self.assertEqual(1, inventory.main(self.capture_args()))
        credentials.assert_not_called()
        tls.assert_not_called()

    def test_unexpected_exception_is_also_redacted(self):
        with patch.object(inventory, "capture", side_effect=RuntimeError("private@example.test")), \
             redirect_stderr(io.StringIO()) as error:
            self.assertEqual(1, inventory.main(self.capture_args()))
        self.assertNotIn("@", error.getvalue())

    @unittest.skipIf(os.name == "posix", "native non-POSIX refusal")
    def test_native_windows_refuses_private_file_access(self):
        with self.assertRaisesRegex(inventory.Refusal, "posix-permissions-required"):
            inventory.private_read(Path("never-open-this-secret"))


@unittest.skipUnless(os.name == "posix", "private file checks require POSIX filesystem")
class PrivateFileTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.directory.chmod(0o700)
        self.path = self.directory / "manifest.json"

    def test_atomic_private_publication_no_overwrite_and_bounded_read(self):
        doc = manifest()
        inventory.private_write(self.path, doc, inventory.Budget())
        self.assertEqual(0o600, stat.S_IMODE(self.path.stat().st_mode))
        self.assertEqual(doc, inventory.json_document(inventory.private_read(self.path)))
        with self.assertRaisesRegex(inventory.Refusal, "manifest-exists"):
            inventory.private_write(self.path, {"replacement": True}, inventory.Budget())
        with self.assertRaisesRegex(inventory.Refusal, "private-file-limit"):
            inventory.private_read(self.path, 1)
        self.assertEqual([self.path], list(self.directory.iterdir()))

    def test_failure_does_not_publish_partial_manifest(self):
        with self.assertRaisesRegex(inventory.Refusal, "manifest-limit"):
            inventory.private_write(self.path, manifest(), inventory.Budget(output=10))
        self.assertEqual([], list(self.directory.iterdir()))

    def test_concurrent_publisher_is_never_overwritten(self):
        original_link = os.link
        def race(source, destination, **kwargs):
            self.path.write_bytes(b"other-publisher")
            return original_link(source, destination, **kwargs)
        with patch.object(inventory.os, "link", side_effect=race), self.assertRaises(FileExistsError):
            inventory.private_write(self.path, manifest(), inventory.Budget())
        self.assertEqual(b"other-publisher", self.path.read_bytes())
        self.assertEqual([self.path], list(self.directory.iterdir()))

    def test_permissive_credentials_directory_symlink_and_hardlink_are_refused(self):
        self.path.write_text("synthetic-only-password-32-characters")
        self.path.chmod(0o644)
        with self.assertRaisesRegex(inventory.Refusal, "private-permissions"):
            inventory.credentials("cyrus", self.path)
        self.path.chmod(0o600)
        self.assertEqual("cyrus", inventory.credentials("cyrus", self.path)["username"])
        self.directory.chmod(0o750)
        with self.assertRaisesRegex(inventory.Refusal, "private-permissions"):
            inventory.credentials("cyrus", self.path)
        self.directory.chmod(0o700)
        link = self.directory / "link"
        link.symlink_to(self.path)
        with self.assertRaises((OSError, inventory.Refusal)):
            inventory.credentials("cyrus", link)
        link.unlink()
        os.link(self.path, link)
        with self.assertRaisesRegex(inventory.Refusal, "linked-private-file"):
            inventory.credentials("cyrus", self.path)

    def test_raw_secret_rejects_short_and_control_characters(self):
        for raw in (b"", b"short-password", b"x" * 32 + b"\n\n", b"x" * 32 + b"\x00",
                    b"x" * 16 + b"\n" + b"x" * 16):
            self.path.write_bytes(raw)
            self.path.chmod(0o600)
            with self.assertRaises(inventory.Refusal):
                inventory.credentials("cyrus", self.path)

    def test_raw_secret_accepts_only_optional_single_line_ending_and_preserves_spaces(self):
        password = b" " + b"synthetic-only-password-32-characters" + b" "
        for ending in (b"", b"\n", b"\r\n"):
            self.path.write_bytes(password + ending)
            self.path.chmod(0o600)
            self.assertEqual(password.decode("ascii"), inventory.credentials("cyrus", self.path)["password"])


if __name__ == "__main__":
    unittest.main()
