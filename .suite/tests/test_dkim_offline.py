"""Offline RSA DKIM fixture verification, not DNS publication or scanner proof."""

import base64
import binascii
import hashlib
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest


def relaxed_body(body):
    lines = [re.sub(rb"[ \t]+", b" ", line).rstrip(b" \t")
             for line in body.split(b"\r\n")]
    while lines and not lines[-1]:
        lines.pop()
    return b"\r\n".join(lines) + b"\r\n" if lines else b""


def relaxed_header(name, value):
    unfolded = re.sub(rb"\r\n[ \t]+", b" ", value)
    normalized = re.sub(rb"[ \t]+", b" ", unfolded).strip(b" \t")
    return name.lower().strip() + b":" + normalized + b"\r\n"


def message_parts(message):
    header_block, separator, body = message.partition(b"\r\n\r\n")
    if not separator:
        raise ValueError("DKIM fixture requires a complete CRLF message")
    headers = []
    for line in header_block.split(b"\r\n"):
        if line.startswith((b" ", b"\t")):
            if not headers:
                raise ValueError("Invalid leading folded header")
            name, value = headers[-1]
            headers[-1] = (name, value + b"\r\n" + line)
        else:
            name, colon, value = line.partition(b":")
            if not colon or not re.fullmatch(rb"[A-Za-z0-9-]+", name):
                raise ValueError("Invalid DKIM fixture header")
            headers.append((name, value))
    return headers, body


def signature_tags(value):
    tags = {}
    for part in re.sub(rb"\r\n[ \t]+", b" ", value).split(b";"):
        if not part.strip():
            continue
        name, equals, content = part.partition(b"=")
        name = name.strip().lower()
        if not equals or not re.fullmatch(rb"[a-z]+", name) or name in tags:
            raise ValueError("Invalid or duplicate DKIM tag")
        tags[name] = content.strip()
    return tags


def selected_headers(headers, signed_names):
    remaining = list(headers)
    canonical = bytearray()
    for name in signed_names:
        for index in range(len(remaining) - 1, -1, -1):
            if remaining[index][0].lower() == name:
                key, value = remaining.pop(index)
                canonical.extend(relaxed_header(key, value))
                break
    return bytes(canonical)


def dkim_verification_bytes(message, domain, selector):
    headers, body = message_parts(message)
    signatures = []
    for index, (name, value) in enumerate(headers):
        if name.lower() == b"dkim-signature":
            tags = signature_tags(value)
            if tags.get(b"d") == domain.encode("ascii") and tags.get(b"s") == selector.encode("ascii"):
                signatures.append((index, value, tags))
    if len(signatures) != 1:
        raise ValueError("Require exactly one signature for the expected domain and selector")
    index, value, tags = signatures[0]
    if (tags.get(b"v") != b"1" or tags.get(b"a") != b"rsa-sha256"
            or tags.get(b"c") != b"relaxed/relaxed" or b"l" in tags):
        raise ValueError("Fixture verifier requires full-body RSA-SHA256 relaxed/relaxed signing")
    names = [name.strip().lower() for name in tags.get(b"h", b"").split(b":")]
    if b"from" not in names or sum(name.lower() == b"from" for name, _ in headers) != 1:
        raise ValueError("The single From header must be signed")
    if any(not re.fullmatch(rb"[a-z0-9-]+", name) for name in names):
        raise ValueError("Invalid signed-header list")
    try:
        body_hash = base64.b64decode(b"".join(tags[b"bh"].split()), validate=True)
        signature = base64.b64decode(b"".join(tags[b"b"].split()), validate=True)
    except (KeyError, binascii.Error) as error:
        raise ValueError("Missing or invalid DKIM digest/signature") from error
    if body_hash != hashlib.sha256(relaxed_body(body)).digest():
        raise ValueError("DKIM body hash mismatch")
    if not signature:
        raise ValueError("Empty DKIM signature")
    emptied = re.sub(rb"(?i)(^|;)([ \t\r\n]*b[ \t]*=)[^;]*",
                     lambda match: match[1] + match[2], value, count=1)
    canonical = selected_headers(headers[:index] + headers[index + 1:], names)
    canonical += relaxed_header(b"DKIM-Signature", emptied).removesuffix(b"\r\n")
    return canonical, signature


def verify_fixture_dkim(message, public_key, domain, selector):
    canonical, signature = dkim_verification_bytes(message, domain, selector)
    openssl = shutil.which("openssl")
    if not openssl:
        raise RuntimeError("OpenSSL is required for independent RSA fixture verification")
    with tempfile.TemporaryDirectory(prefix="robin-dkim-verify-") as directory:
        signature_file = Path(directory) / "signature.bin"
        signature_file.write_bytes(signature)
        try:
            subprocess.run([openssl, "dgst", "-sha256", "-verify", str(public_key),
                            "-signature", str(signature_file)], input=canonical,
                           check=True, capture_output=True, timeout=15)
        except subprocess.CalledProcessError as error:
            raise ValueError("DKIM RSA signature verification failed") from error


class DkimCanonicalizationOfflineTest(unittest.TestCase):
    def test_relaxed_body_collapses_horizontal_whitespace_and_trailing_empty_lines(self):
        self.assertEqual(b" C\r\nD E\r\n", relaxed_body(b" C \r\nD \t E\r\n\r\n\r\n"))
        self.assertEqual(b"", relaxed_body(b"\r\n\r\n"))
        self.assertEqual(b"body\r\n", relaxed_body(b"body"))
        self.assertEqual(b"47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU=",
                         base64.b64encode(hashlib.sha256(relaxed_body(b"")).digest()))

    def test_relaxed_header_unfolds_collapses_and_trims_whitespace(self):
        self.assertEqual(b"subject:A B C\r\n",
                         relaxed_header(b"SUBJect", b"  A \t B\r\n\t C \t"))

    def test_repeated_headers_are_selected_bottom_up_and_absent_oversigned_headers_are_skipped(self):
        headers = [(b"X-Fixture", b" first"), (b"X-Fixture", b" last")]
        self.assertEqual(b"x-fixture:last\r\nx-fixture:first\r\n",
                         selected_headers(headers, [b"x-fixture", b"x-fixture", b"reply-to"]))

    def test_duplicate_signature_tags_are_rejected(self):
        with self.assertRaisesRegex(ValueError, "duplicate"):
            signature_tags(b"v=1; d=example.test; d=another.test")


@unittest.skipUnless(shutil.which("openssl"), "Install the required OpenSSL fixture tool")
class DkimCryptoOfflineTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory(prefix="robin-dkim-signing-offline-")
        cls.addClassCleanup(cls.temporary.cleanup)
        cls.root = Path(cls.temporary.name)
        cls.private_key = cls.root / "synthetic.key"
        cls.public_key = cls.root / "synthetic.pub"
        cls.openssl = shutil.which("openssl")
        subprocess.run([cls.openssl, "genpkey", "-algorithm", "RSA", "-pkeyopt",
                        "rsa_keygen_bits:2048", "-out", str(cls.private_key)],
                       check=True, capture_output=True, timeout=30)
        subprocess.run([cls.openssl, "pkey", "-in", str(cls.private_key),
                        "-pubout", "-out", str(cls.public_key)],
                       check=True, capture_output=True, timeout=15)

    def signed_message(self, extra_tags=b"", names=None, body=None):
        message = (b"From: alice@example.test\r\nTo: fixture@remote.test\r\n"
                   b"Subject: synthetic acceptance\r\nDate: Sat, 31 Oct 2026 10:00:00 +0000\r\n"
                   b"X-Fixture: first\r\nX-Fixture: last\r\n\r\nA synthetic body.\r\n")
        if body is not None:
            message = message.replace(b"A synthetic body.\r\n", body)
        headers, body = message_parts(message)
        names = names or [b"from", b"to", b"subject", b"date", b"x-fixture", b"x-fixture", b"reply-to"]
        prefix = (b"v=1; a=rsa-sha256; c=relaxed/relaxed; d=example.test; s=fixture; h="
                  + b":".join(names) + b"; bh="
                  + base64.b64encode(hashlib.sha256(relaxed_body(body)).digest())
                  + b"; " + extra_tags + b"b=")
        canonical = selected_headers(headers, names)
        canonical += relaxed_header(b"DKIM-Signature", prefix).removesuffix(b"\r\n")
        signature = subprocess.run([self.openssl, "dgst", "-sha256", "-sign", str(self.private_key)],
                                   input=canonical, check=True, capture_output=True, timeout=15).stdout
        return b"DKIM-Signature: " + prefix + base64.b64encode(signature) + b"\r\n" + message

    def test_real_openssl_rsa_signature_and_body_hash_verify(self):
        verify_fixture_dkim(self.signed_message(), self.public_key, "example.test", "fixture")

    def test_empty_relaxed_body_uses_rfc6376_null_input_hash(self):
        message = self.signed_message(body=b"")
        self.assertIn(b"bh=47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU=", message)
        verify_fixture_dkim(message, self.public_key, "example.test", "fixture")

    def test_body_tampering_is_rejected_before_rsa_verification(self):
        modified = self.signed_message().replace(b"A synthetic body.", b"A modified body.")
        with self.assertRaisesRegex(ValueError, "body hash"):
            verify_fixture_dkim(modified, self.public_key, "example.test", "fixture")

    def test_signed_sender_tampering_fails_rsa_verification(self):
        modified = self.signed_message().replace(b"From: alice@", b"From: mallory@")
        with self.assertRaisesRegex(ValueError, "RSA"):
            verify_fixture_dkim(modified, self.public_key, "example.test", "fixture")

    def test_equivalent_relaxed_header_and_body_whitespace_still_verify(self):
        modified = self.signed_message().replace(
            b"Subject: synthetic acceptance", b"SUBJECT:\t synthetic \t acceptance")
        modified = modified.replace(b"A synthetic body.\r\n", b"A \t synthetic body. \t\r\n\r\n")
        verify_fixture_dkim(modified, self.public_key, "example.test", "fixture")

    def test_signature_base64_can_be_folded_without_changing_the_signed_headers(self):
        message = self.signed_message()
        header, separator, remainder = message.partition(b"\r\n")
        prefix, equals, signature = header.rpartition(b"b=")
        self.assertEqual(b"b=", equals)
        folded = b" \r\n\t" + b"\r\n\t".join(
            signature[offset:offset + 48] for offset in range(0, len(signature), 48))
        modified = prefix + b"b=" + folded + separator + remainder
        verify_fixture_dkim(modified, self.public_key, "example.test", "fixture")

    def test_adding_an_oversigned_header_is_rejected(self):
        modified = self.signed_message().replace(
            b"\r\n\r\n", b"\r\nReply-To: altered@example.test\r\n\r\n", 1)
        with self.assertRaisesRegex(ValueError, "RSA"):
            verify_fixture_dkim(modified, self.public_key, "example.test", "fixture")

    def test_unsigned_from_partial_body_and_wrong_selector_are_rejected(self):
        for message, selector, reason in (
                (self.signed_message(names=[b"to", b"subject"]), "fixture", "From"),
                (self.signed_message(extra_tags=b"l=2; "), "fixture", "full-body"),
                (self.signed_message(), "another", "exactly one")):
            with self.subTest(reason=reason), self.assertRaisesRegex(ValueError, reason):
                verify_fixture_dkim(message, self.public_key, "example.test", selector)


if __name__ == "__main__":
    unittest.main()
