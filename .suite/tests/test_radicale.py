"""Optional DAV acceptance; offline tests never start Docker or use suite inputs."""

import base64
import configparser
from dataclasses import dataclass
import http.client
import os
import time
import unittest
from unittest.mock import patch
from urllib.parse import urlsplit
import xml.etree.ElementTree as ET

from docker_fixture import DockerFixture, run


DAV = "DAV:"
CALDAV = "urn:ietf:params:xml:ns:caldav"
CARDDAV = "urn:ietf:params:xml:ns:carddav"
MAX_CONTENT_LENGTH = 1048576

PROPFIND = b"""<propfind xmlns="DAV:"><prop>
<current-user-principal/><resourcetype/><getetag/>
</prop></propfind>"""


@dataclass
class DavResponse:
    status: int
    headers: dict
    body: bytes


def dav_request(origin, method, path, user=None, password=None, body=None, headers=None):
    address = urlsplit(origin)
    if (address.scheme != "http" or address.hostname != "127.0.0.1" or not address.port
            or address.username is not None or address.password is not None
            or address.path not in ("", "/") or address.query or address.fragment):
        raise ValueError("DAV fixture requests require an explicit loopback-only origin")
    if not path.startswith("/") or path.startswith("//") or "\r" in path or "\n" in path:
        raise ValueError("Invalid DAV fixture path")
    if (user is None) != (password is None):
        raise ValueError("Supply both synthetic username and password")
    request_headers = dict(headers or {})
    if user is not None:
        encoded = base64.b64encode(f"{user}:{password}".encode("utf-8")).decode("ascii")
        request_headers["Authorization"] = "Basic " + encoded
    connection = http.client.HTTPConnection(address.hostname, address.port, timeout=5)
    try:
        connection.request(method, path, body=body, headers=request_headers)
        response = connection.getresponse()
        return DavResponse(response.status, dict(response.getheaders()), response.read())
    finally:
        connection.close()


def collection_request(namespace, display_name):
    root = ET.Element(f"{{{DAV}}}mkcol")
    properties = ET.SubElement(ET.SubElement(root, f"{{{DAV}}}set"), f"{{{DAV}}}prop")
    resource_type = ET.SubElement(properties, f"{{{DAV}}}resourcetype")
    ET.SubElement(resource_type, f"{{{DAV}}}collection")
    ET.SubElement(resource_type, f"{{{namespace}}}"
                  + ("calendar" if namespace == CALDAV else "addressbook"))
    ET.SubElement(properties, f"{{{DAV}}}displayname").text = display_name
    return ET.tostring(root, encoding="utf-8", xml_declaration=True)


def item_request(namespace, path):
    name = "calendar" if namespace == CALDAV else "addressbook"
    root = ET.Element(f"{{{namespace}}}{name}-multiget")
    properties = ET.SubElement(root, f"{{{DAV}}}prop")
    ET.SubElement(properties, f"{{{DAV}}}getetag")
    ET.SubElement(properties, f"{{{namespace}}}"
                  + ("calendar-data" if namespace == CALDAV else "address-data"))
    ET.SubElement(root, f"{{{DAV}}}href").text = path
    return ET.tostring(root, encoding="utf-8", xml_declaration=True)


def configure_dav_fixture(fixture, image):
    """Image contract: python3/Radicale, UID 1000, writable /data volume seed."""
    if not image:
        raise ValueError("Supply a reviewed local Radicale fixture image")
    inputs = fixture.root / "dav-inputs"
    inputs.mkdir()
    (inputs / "users").write_text(f"alice:{fixture.password}\nbob:{fixture.password}\n",
                                 encoding="utf-8")
    (inputs / "config").write_text(f"""[server]
hosts = 0.0.0.0:5232
max_connections = 8
max_content_length = {MAX_CONTENT_LENGTH}
timeout = 15
[auth]
type = htpasswd
htpasswd_filename = /fixture/users
# Synthetic disposable credentials only, never a production auth template.
htpasswd_encryption = plain
delay = 0
[rights]
type = owner_only
[storage]
filesystem_folder = /data/collections
[logging]
level = warning
""", encoding="utf-8")
    fixture.config["networks"]["edge"] = {}
    fixture.config["volumes"]["dav-data"] = {}
    fixture.config["services"]["dav"] = {
        "image": image,
        "entrypoint": ["python3", "-m", "radicale"],
        "command": ["--config", "/fixture/config"],
        "user": "1000:1000",
        "read_only": True,
        "cap_drop": ["ALL"],
        "security_opt": ["no-new-privileges:true"],
        "tmpfs": ["/tmp:rw,nosuid,noexec,size=16m"],
        "volumes": [f"{inputs}:/fixture:ro", "dav-data:/data"],
        "networks": ["backend", "edge"],
        "ports": [{"target": 5232, "host_ip": "127.0.0.1"}],
        "mem_limit": "256m", "cpus": 0.5, "pids_limit": 64,
    }
    fixture.validate_isolation()


class DavOfflineFixtureTest(unittest.TestCase):
    def test_dav_model_is_isolated_bounded_and_never_started(self):
        with patch("docker_fixture.run") as execute:
            with DockerFixture() as fixture:
                configure_dav_fixture(fixture, "radicale-fixture:reviewed")
                service = fixture.config["services"]["dav"]
                self.assertEqual("1000:1000", service["user"])
                self.assertTrue(service["read_only"])
                self.assertEqual(["ALL"], service["cap_drop"])
                self.assertEqual("256m", service["mem_limit"])
                self.assertEqual(0.5, service["cpus"])
                self.assertEqual(64, service["pids_limit"])
                self.assertEqual([{"target": 5232, "host_ip": "127.0.0.1"}], service["ports"])
                self.assertFalse(fixture.created)
                config = configparser.ConfigParser()
                config.read(fixture.root / "dav-inputs" / "config")
                self.assertEqual("owner_only", config["rights"]["type"])
                self.assertEqual(MAX_CONTENT_LENGTH, config.getint("server", "max_content_length"))
                self.assertEqual("htpasswd", config["auth"]["type"])
            execute.assert_not_called()

    def test_missing_reviewed_image_is_rejected_before_any_inputs_are_created(self):
        with DockerFixture() as fixture:
            with self.assertRaises(ValueError):
                configure_dav_fixture(fixture, "")
            self.assertEqual([], list(fixture.root.iterdir()))

    def test_requests_cannot_use_public_origins_or_url_credentials(self):
        for origin in ("https://dav.example.test:443", "http://127.0.0.1",
                       "http://localhost:12345", "http://127.0.0.1:12345/secret",
                       "http://alice:secret@127.0.0.1:12345",
                       "http://127.0.0.1:12345?override=1"):
            with self.subTest(origin=origin), patch.object(http.client, "HTTPConnection") as connect:
                with self.assertRaises(ValueError):
                    dav_request(origin, "GET", "/")
                connect.assert_not_called()

    def test_basic_auth_etag_and_body_are_preserved_and_connection_is_closed(self):
        with patch.object(http.client, "HTTPConnection") as connect:
            connection = connect.return_value
            response = connection.getresponse.return_value
            response.status = 412
            response.getheaders.return_value = [("ETag", '"existing"')]
            response.read.return_value = b"Precondition failed"
            result = dav_request("http://127.0.0.1:12345", "PUT", "/alice/calendar/event.ics",
                                 "alice", "synthetic", b"unchanged",
                                 {"If-Match": '"stale"'})
            encoded = base64.b64encode(b"alice:synthetic").decode("ascii")
            connection.request.assert_called_once_with(
                "PUT", "/alice/calendar/event.ics", body=b"unchanged",
                headers={"If-Match": '"stale"', "Authorization": "Basic " + encoded})
            self.assertEqual(DavResponse(412, {"ETag": '"existing"'}, b"Precondition failed"), result)
            connection.close.assert_called_once()

    def test_request_failure_is_not_converted_to_success(self):
        with patch.object(http.client, "HTTPConnection") as connect:
            connect.return_value.request.side_effect = OSError("Synthetic disconnected fixture")
            with self.assertRaises(OSError):
                dav_request("http://127.0.0.1:12345", "GET", "/")
            connect.return_value.close.assert_called_once()

    def test_collection_and_report_xml_preserve_the_correct_dav_namespaces(self):
        for namespace, resource, report, data in (
                (CALDAV, "calendar", "calendar-multiget", "calendar-data"),
                (CARDDAV, "addressbook", "addressbook-multiget", "address-data")):
            with self.subTest(namespace=namespace):
                created = ET.fromstring(collection_request(namespace, "Fixture & collection"))
                self.assertIsNotNone(created.find(f".//{{{namespace}}}{resource}"))
                self.assertEqual("Fixture & collection",
                                 created.find(f".//{{{DAV}}}displayname").text)
                requested = ET.fromstring(item_request(namespace, "/alice/test/item"))
                self.assertEqual(f"{{{namespace}}}{report}", requested.tag)
                self.assertIsNotNone(requested.find(f".//{{{namespace}}}{data}"))
                self.assertEqual("/alice/test/item", requested.find(f"{{{DAV}}}href").text)


@unittest.skipUnless(os.environ.get("ROBIN_SUITE_DOCKER_TEST") == "1"
                     and os.environ.get("ROBIN_TEST_RADICALE_IMAGE"),
                     "After maintenance release, opt in with a reviewed local Radicale image")
class RadicaleDockerTest(unittest.TestCase):
    def test_authenticated_calendar_contacts_etag_limits_and_account_isolation(self):
        with DockerFixture() as fixture:
            configure_dav_fixture(fixture, os.environ["ROBIN_TEST_RADICALE_IMAGE"])
            fixture.start()
            port = int(run(*fixture.command, "port", "dav", "5232").strip().rsplit(":", 1)[1])
            origin = f"http://127.0.0.1:{port}"

            def request(method, path, body=None, headers=None, user="alice", password=None):
                return dav_request(origin, method, path, user,
                                   fixture.password if password is None and user is not None else password,
                                   body, headers)

            deadline = time.monotonic() + 30
            while True:
                try:
                    denied = request("PROPFIND", "/", PROPFIND, {"Depth": "0"}, user=None)
                    self.assertEqual(401, denied.status)
                    break
                except (OSError, http.client.HTTPException):
                    if time.monotonic() >= deadline:
                        raise
                    time.sleep(0.25)
            self.assertEqual(401, request("PROPFIND", "/", PROPFIND, {"Depth": "0"},
                                          password="not-the-password").status)
            for user in ("alice", "bob"):
                discovery = request("PROPFIND", "/", PROPFIND, {"Depth": "0"}, user=user)
                self.assertEqual(207, discovery.status)
                principal = ET.fromstring(discovery.body).find(
                    f".//{{{DAV}}}current-user-principal/{{{DAV}}}href")
                self.assertIsNotNone(principal)
                self.assertEqual(f"/{user}/", principal.text)

            calendar = (b"BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Robin fixture//EN\r\n"
                        b"BEGIN:VEVENT\r\nUID:robin-dav-calendar\r\nDTSTAMP:20261031T100000Z\r\n"
                        b"DTSTART:20261101T120000Z\r\nDTEND:20261101T130000Z\r\n"
                        b"SUMMARY:Fixture calendar\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n")
            contact = (b"BEGIN:VCARD\r\nVERSION:3.0\r\nUID:robin-dav-contact\r\n"
                       b"FN:Fixture Contact\r\nN:Contact;Fixture;;;\r\n"
                       b"EMAIL:fixture@example.test\r\nEND:VCARD\r\n")
            for namespace, collection, filename, content_type, content, marker in (
                    (CALDAV, "calendar", "event.ics", "text/calendar", calendar, b"robin-dav-calendar"),
                    (CARDDAV, "contacts", "person.vcf", "text/vcard", contact, b"robin-dav-contact")):
                with self.subTest(collection=collection):
                    path = f"/alice/{collection}/"
                    item = path + filename
                    created = request("MKCOL", path, collection_request(namespace, collection),
                                      {"Content-Type": "application/xml"})
                    self.assertEqual(201, created.status, created.body)
                    created_item = request("PUT", item, content,
                                           {"Content-Type": content_type, "If-None-Match": "*"})
                    self.assertEqual(201, created_item.status, created_item.body)
                    fetched = request("GET", item)
                    self.assertEqual(200, fetched.status)
                    self.assertIn(marker, fetched.body)
                    etag = fetched.headers.get("ETag")
                    self.assertTrue(etag)
                    self.assertEqual(412, request("PUT", item, content,
                                                  {"Content-Type": content_type,
                                                   "If-None-Match": "*"}).status)
                    self.assertEqual(412, request("PUT", item, content,
                                                  {"Content-Type": content_type,
                                                   "If-Match": '"stale"'}).status)
                    reported = request("REPORT", path, item_request(namespace, item),
                                       {"Content-Type": "application/xml", "Depth": "1"})
                    self.assertEqual(207, reported.status, reported.body)
                    self.assertIn(marker, reported.body)
                    for method, body in (("GET", None), ("DELETE", None), ("PUT", content)):
                        forbidden = request(method, item, body,
                                            {"Content-Type": content_type}, user="bob")
                        self.assertEqual(403, forbidden.status, forbidden.body)
                        self.assertNotIn(marker, forbidden.body)
                    bob_listing = request("PROPFIND", "/bob/", PROPFIND,
                                          {"Depth": "1"}, user="bob")
                    self.assertEqual(207, bob_listing.status)
                    self.assertNotIn(item.encode(), bob_listing.body)
                    self.assertEqual(400, request("PUT", path + "invalid", b"not a DAV item",
                                                  {"Content-Type": content_type}).status)
                    self.assertEqual(413, request("PUT", item, b"x" * (MAX_CONTENT_LENGTH + 1),
                                                  {"Content-Type": content_type}).status)
                    updated = content.replace(b"Fixture", b"Updated fixture")
                    self.assertEqual(204, request("PUT", item, updated,
                                                  {"Content-Type": content_type, "If-Match": etag}).status)
                    latest = request("GET", item)
                    self.assertEqual(200, latest.status)
                    self.assertIn(b"Updated fixture", latest.body)
                    self.assertNotEqual(etag, latest.headers.get("ETag"))
                    self.assertEqual(204, request("DELETE", item, headers={
                        "If-Match": latest.headers["ETag"]}).status)
                    self.assertEqual(404, request("GET", item).status)


if __name__ == "__main__":
    unittest.main()
