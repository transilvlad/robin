#!/usr/bin/env python3
"""Cold Cyrus snapshots and new-volume-only staged rehearsals. Linux Docker host."""

import argparse
import fcntl
import hashlib
import importlib.util
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys
import tarfile
import tempfile


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("suite_recovery", ROOT / "recovery.py")
recovery = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(recovery)
LABEL = "org.robin.cyrus.rehearsal"
PINS = {
    "3.8.8": "13d10babf2e8bb753c5b8fe37328f6f9d43527539957561b840ebfa24a518b61",
    "3.10.4": "7ec307f26c7cf12cc59a2c9b59bef4186e51e0c31d933180558289f5ca837860",
    "3.12.4": "87a39e27208d9019f00608483d532eb05bd0d71394a6f3f8bb05a72833ad8d91",
}
WRITERS = ("suite-robin", "suite-imap-proxy", "suite-roundcube", "suite-webmail",
           "suite-webmail-worker", "suite-jmap-proxy")


def run(*args, **kwargs):
    return subprocess.run(args, check=True, **kwargs)


def output(*args):
    return run(*args, stdout=subprocess.PIPE, text=True).stdout


def inspect(kind, name):
    return json.loads(output("docker", kind, "inspect", name))[0]


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def safe_archive(path):
    """Never let restored symlinks/hardlinks escape the new empty volume."""
    with tarfile.open(path) as archive:
        for item in archive:
            name = PurePosixPath(item.name)
            if name.is_absolute() or ".." in name.parts:
                raise ValueError("Unsafe archive member path")
            if not (item.isdir() or item.isfile() or item.issym() or item.islnk()):
                raise ValueError("Unsupported archive member type")
            if item.issym() or item.islnk():
                # Cyrus's mailbox store needs hardlinks, but not absolute or
                # parent-relative links. Reject rather than guess their target.
                link = PurePosixPath(item.linkname)
                if link.is_absolute() or ".." in link.parts:
                    raise ValueError("Unsafe archive link")


def volume_name(name):
    if not re.fullmatch(r"robin-cyrus-(?:rehearsal|rollback)-[a-z0-9][a-z0-9-]{0,60}", name):
        raise ValueError("Target must be a new robin-cyrus-rehearsal-* or robin-cyrus-rollback-* volume")
    return name


def load_snapshot(path):
    path = path.resolve()
    if not (path / "COMPLETE").is_file() or not (path / "manifest.json").is_file():
        raise ValueError("Snapshot is incomplete")
    if (path / "COMPLETE").read_text().strip() != digest(path / "manifest.json"):
        raise ValueError("Snapshot manifest checksum mismatch")
    data = json.loads((path / "manifest.json").read_text())
    expected = {"data.tar", "images.tar", "imapd.conf", "cyrus.conf"}
    if data.get("format") != 1 or set(data.get("files", {})) != expected:
        raise ValueError("Unsupported or incomplete snapshot manifest")
    if not re.fullmatch(r"sha256:[a-f0-9]{64}", data.get("image_id", "")):
        raise ValueError("Invalid saved image ID")
    for filename, checksum in data["files"].items():
        target = path / filename
        if target.is_symlink() or not target.is_file() or digest(target) != checksum:
            raise ValueError("Snapshot file checksum mismatch")
    safe_archive(path / "data.tar")
    return data


def require_quiesced(container):
    record = inspect("container", container)
    state = record["State"]
    if state["Running"] or state.get("OOMKilled") or state.get("ExitCode", 1) != 0:
        raise ValueError("Cyrus must be cleanly stopped, not running/crashed/OOM-killed")
    data = [m for m in record.get("Mounts", []) if m["Destination"] == "/var/lib/cyrus"]
    running = set(output("docker", "ps", "--format", "{{.Names}}").splitlines())
    fixture = ((record.get("Config", {}).get("Labels") or {}).get(LABEL) == "synthetic-fixture"
               and len(data) == 1 and data[0].get("Name", "").startswith("robin-cyrus-legacy-"))
    if not fixture and running.intersection(WRITERS):
        raise ValueError("Quiesce Robin, proxies, Roundcube, Webmail and worker before snapshot")
    if len(data) != 1 or data[0]["Type"] != "volume":
        raise ValueError("Expected exactly one named Cyrus data volume")
    mount = {"type": "volume", "source": data[0]["Name"]}
    recovery.require_no_writers({"cyrus": mount})
    return record, mount


def snapshot(container, destination):
    destination = destination.resolve()
    record, mount = require_quiesced(container)
    if any(m["Type"] == "bind" and str(destination).startswith(m["Source"] + "/")
           for m in record["Mounts"]):
        raise ValueError("Snapshot must be outside Cyrus bind mounts")
    destination.mkdir(mode=0o700, parents=True, exist_ok=False)
    image = record["Image"]
    for source, filename in (("/etc/imapd.conf", "imapd.conf"), ("/etc/cyrus.conf", "cyrus.conf")):
        run("docker", "cp", f"{container}:{source}", str(destination / filename), stdout=subprocess.DEVNULL)
    # Use the exact old image as the networkless tar helper and retain that image
    # with the archive. Do not rely on a mutable tag for rollback.
    recovery.archive_mount(mount, destination / "data.tar", image)
    safe_archive(destination / "data.tar")
    with (destination / "images.tar").open("wb") as stream:
        run("docker", "image", "save", image, stdout=stream)
    require_quiesced(container)
    data = {"format": 1, "image_id": image, "source_volume": mount["source"],
            "container": container, "files": {name: digest(destination / name) for name in
                ("data.tar", "images.tar", "imapd.conf", "cyrus.conf")}}
    (destination / "manifest.json").write_text(json.dumps(data, indent=2) + "\n")
    (destination / "COMPLETE").write_text(digest(destination / "manifest.json") + "\n")
    print("PASS cold snapshot; suite remains stopped; admin secret is backed up separately")


def restore(snapshot_dir, name):
    volume_name(name)
    data = load_snapshot(snapshot_dir)
    existing = output("docker", "volume", "ls", "--format", "{{.Name}}").splitlines()
    if name in existing or name == data["source_volume"]:
        raise ValueError("Refusing existing/source volume; restore always creates a new volume")
    with (snapshot_dir / "images.tar").open("rb") as stream:
        run("docker", "image", "load", stdin=stream, stdout=subprocess.DEVNULL)
    inspect("image", data["image_id"])
    run("docker", "volume", "create", "--label", f"{LABEL}={digest(snapshot_dir / 'manifest.json')}",
        "--label", f"org.robin.cyrus.source-volume={data['source_volume']}", name,
        stdout=subprocess.DEVNULL)
    # Failed/partial restores are retained for inspection, never overwritten.
    recovery.restore_mount({"type": "volume", "source": name}, snapshot_dir / "data.tar", data["image_id"])
    print(f"PASS restored new volume {name}; original volume unchanged; no server started")


def stage(snapshot_dir, name, image):
    volume_name(name)
    data = load_snapshot(snapshot_dir)
    volume = inspect("volume", name)
    if (volume.get("Labels") or {}).get(LABEL) != digest(snapshot_dir / "manifest.json"):
        raise ValueError("Volume does not belong to this rehearsal snapshot")
    if name == data["source_volume"]:
        raise ValueError("Refusing original volume")
    recovery.require_no_writers({"cyrus": {"source": name, "type": "volume"}})
    image_data = inspect("image", image)
    labels = image_data["Config"].get("Labels") or {}
    version = labels.get("org.opencontainers.image.version")
    if version not in PINS or labels.get("org.robin.cyrus.source-sha256") != PINS[version]:
        raise ValueError("Image lacks a reviewed Cyrus release/source pin")
    command = ["docker", "run", "--rm", "--network", "none", "--memory", "768m", "--cpus", "2",
               "--mount", f"type=volume,src={name},dst=/var/lib/cyrus",
               "--mount", f"type=bind,src={snapshot_dir.resolve() / 'imapd.conf'},dst=/etc/imapd-base.conf,readonly",
               "--mount", f"type=bind,src={ROOT / 'etc/cyrus/imapd-jmap.conf'},dst=/etc/imapd-jmap.conf,readonly",
               "--env", f"CYRUS_UPGRADE_STAGE={version}", "--entrypoint", "/usr/local/bin/suite-cyrus-offline-upgrade",
               image_data["Id"]]
    run(*command)
    print(f"PASS clone stage {version}; no service promoted; protocol/byte verification still required")


def manifest_messages(path):
    result = {}
    with tarfile.open(path) as archive:
        for item in archive:
            if item.isfile() and re.fullmatch(r"[0-9]+\.", PurePosixPath(item.name).name):
                with archive.extractfile(item) as stream:
                    result[item.name] = hashlib.file_digest(stream, "sha256").hexdigest()
            elif item.islnk() and re.fullmatch(r"[0-9]+\.", PurePosixPath(item.name).name):
                with archive.extractfile(item) as stream:
                    result[item.name] = hashlib.file_digest(stream, "sha256").hexdigest()
    return result


def verify_stage(snapshot_dir, name, container):
    volume_name(name)
    load_snapshot(snapshot_dir)
    volume = inspect("volume", name)
    if (volume.get("Labels") or {}).get(LABEL) != digest(snapshot_dir / "manifest.json"):
        raise ValueError("Volume does not belong to this rehearsal snapshot")
    record = inspect("container", container)
    if not record["State"]["Running"]:
        raise ValueError("Rehearsal container must be running")
    matches = [m for m in record["Mounts"] if m["Destination"] == "/var/lib/cyrus"]
    if len(matches) != 1 or matches[0].get("Name") != name:
        raise ValueError("Container does not use the selected rehearsal volume")
    image = inspect("image", record["Image"])
    labels = image["Config"].get("Labels") or {}
    release = labels.get("org.opencontainers.image.version")
    if release not in PINS or labels.get("org.robin.cyrus.source-sha256") != PINS[release]:
        raise ValueError("Unreviewed running image")
    run("docker", "exec", record["Id"], "python3", "/usr/local/bin/suite-cyrus-verify-stage")


def compare(snapshot_dir, name):
    volume_name(name)
    data = load_snapshot(snapshot_dir)
    volume = inspect("volume", name)
    if (volume.get("Labels") or {}).get(LABEL) != digest(snapshot_dir / "manifest.json"):
        raise ValueError("Volume does not belong to this rehearsal snapshot")
    recovery.require_no_writers({"cyrus": {"source": name, "type": "volume"}})
    with tempfile.TemporaryDirectory(prefix="robin-cyrus-compare-") as directory:
        archive = Path(directory) / "data.tar"
        recovery.archive_mount({"type": "volume", "source": name}, archive, data["image_id"])
        expected = manifest_messages(snapshot_dir / "data.tar")
        if not expected or expected != manifest_messages(archive):
            raise ValueError("Original message paths/bytes changed or original store is empty")
        print(f"PASS {len(expected)} original message files are byte-identical; no mail content logged")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="action", required=True)
    backup = commands.add_parser("snapshot")
    backup.add_argument("--container", default="suite-cyrus")
    backup.add_argument("--destination", required=True, type=Path)
    for action in ("restore", "stage", "compare", "verify-stage"):
        command = commands.add_parser(action)
        command.add_argument("--snapshot", required=True, type=Path)
        command.add_argument("--volume", required=True)
        if action == "stage":
            command.add_argument("--image", required=True)
        if action == "verify-stage":
            command.add_argument("--container", required=True)
    args = parser.parse_args()
    os.umask(0o077)
    with open(Path(tempfile.gettempdir()) / "robin-cyrus-upgrade.lock", "a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        if args.action == "snapshot":
            snapshot(args.container, args.destination)
        elif args.action == "restore":
            restore(args.snapshot.resolve(), args.volume)
        elif args.action == "stage":
            stage(args.snapshot.resolve(), args.volume, args.image)
        elif args.action == "verify-stage":
            verify_stage(args.snapshot.resolve(), args.volume, args.container)
        else:
            compare(args.snapshot.resolve(), args.volume)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError):
        # Docker/runtime diagnostics may mention paths, but never echo a secret,
        # message body or entire inspect/config output from this wrapper.
        print("ERROR: Cyrus snapshot/rehearsal stopped; inspect local diagnostics; no automatic restart or cleanup", file=sys.stderr)
        sys.exit(1)
