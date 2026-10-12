#!/usr/bin/env python3
"""Cold, local suite snapshots. Run on the Linux Docker host as root."""

import argparse
import hashlib
import fcntl
import json
import os
from pathlib import Path
import subprocess
import sys
import tarfile


HELPER_IMAGE = "alpine:3.23"


def run(*args, **kwargs):
    return subprocess.run(args, check=True, **kwargs)


def output(*args):
    return run(*args, stdout=subprocess.PIPE, text=True).stdout


def compose(suite, *args):
    return ["docker", "compose", "--project-directory", str(suite), "--profile", "*", *args]


def configuration(suite):
    return json.loads(output(*compose(suite, "config", "--format", "json")))


def containers(suite):
    ids = output(*compose(suite, "ps", "--all", "--quiet")).split()
    return json.loads(output("docker", "inspect", *ids)) if ids else []


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def mounts(config, suite):
    """Key by service/target, so recovery can relocate a fresh checkout."""
    result = {}
    logs = suite.parent / "log"
    for service, definition in config["services"].items():
        for mount in definition.get("volumes", []):
            kind = mount["type"]
            if kind not in ("bind", "volume"):
                raise ValueError(f"Unsupported persistent mount type: {kind}")
            source = mount["source"]
            if kind == "bind":
                source = Path(source).resolve()
                if str(source) == "/var/run/docker.sock" or source == Path("/run/docker.sock"):
                    continue
                if source == logs or logs in source.parents:
                    continue
                if source == Path("/") or source == Path.home():
                    raise ValueError(f"Refusing broad backup mount: {source}")
                source = str(source)
            else:
                source = config["volumes"][source]["name"]
            result[f"{service}:{mount['target']}"] = {"type": kind, "source": source,
                                                     "read_only": mount.get("read_only", False)}
    for name, definition in config.get("secrets", {}).items():
        if definition.get("external"):
            raise ValueError(f"External secret must be backed up separately: {name}")
        if "file" in definition:
            result[f"secret:{name}"] = {"type": "bind",
                                      "source": str(Path(definition["file"]).resolve()),
                                      "read_only": True}
    return result


def archive_mount(mount, destination, helper):
    if mount["type"] == "bind":
        source = Path(mount["source"])
        root, member = str(source.parent), source.name
    else:
        root, member = mount["source"], "."
    with destination.open("wb") as archive:
        run("docker", "run", "--rm", "--network", "none",
            "--mount", f"type={mount['type']},src={root},dst=/source,readonly",
            helper, "tar", "-C", "/source", "-cpf", "-", "--", member,
            stdout=archive)


def restore_mount(mount, archive, helper):
    if mount["type"] == "bind":
        source = Path(mount["source"])
        source.parent.mkdir(parents=True, exist_ok=True)
        root = str(source.parent)
    else:
        root = mount["source"]
        run("docker", "volume", "create", root, stdout=subprocess.DEVNULL)
    # Bind archives include their basename; relocation may change the parent,
    # but not the mounted directory/file name.
    if mount["type"] == "bind":
        with tarfile.open(archive) as source_archive:
            members = source_archive.getmembers()
            original = members[0].name.split("/")[0]
        source = Path(mount["source"])
        if original != source.name:
            raise ValueError(f"Bind basename changed: {original} -> {source.name}")
    with archive.open("rb") as stream:
        run("docker", "run", "--rm", "-i", "--network", "none",
            "--mount", f"type={'bind' if mount['type'] == 'bind' else 'volume'},src={root},dst=/restore",
            helper, "tar", "-C", "/restore", "-xpf", "-", stdin=stream)


def nonempty(mount, helper):
    if mount["type"] == "bind":
        path = Path(mount["source"])
        return path.exists() and (not path.is_dir() or any(path.iterdir()))
    names = output("docker", "volume", "ls", "--format", "{{.Name}}").splitlines()
    if mount["source"] not in names:
        return False
    return bool(output("docker", "run", "--rm", "--network", "none",
                       "--mount", f"type=volume,src={mount['source']},dst=/data,readonly",
                       helper, "find", "/data", "-mindepth", "1", "-maxdepth", "1").strip())


def require_no_writers(all_mounts, extra_sources=()):
    ids = output("docker", "ps", "--quiet").split()
    if not ids:
        return
    sources = {item["source"] for item in all_mounts.values()} | set(extra_sources)
    for record in json.loads(output("docker", "inspect", *ids)):
        for mount in record["Mounts"]:
            overlaps = any(Path(source).is_absolute() and
                           (Path(source).is_relative_to(mount["Source"]) or
                            Path(mount["Source"]).is_relative_to(source))
                           for source in sources)
            if mount["RW"] and (mount.get("Name") in sources or mount["Source"] in sources or overlaps):
                raise ValueError(f"Another running container mounts snapshot data: {record['Name']}")


def require_matching_configuration(records, suite):
    hashes = dict(line.split() for line in output(*compose(suite, "config", "--hash", "*")).splitlines())
    for record in records:
        labels = record["Config"]["Labels"]
        service = labels["com.docker.compose.service"]
        if hashes.get(service) != labels.get("com.docker.compose.config-hash"):
            raise ValueError(f"Running/stopped container differs from current Compose inputs: {service}")


def matching_directory(path, archive_path):
    with tarfile.open(archive_path) as archive:
        members = archive.getmembers()
        root = members[0].name.split("/")[0]
        expected = set()
        for member in members:
            relative = Path(member.name).relative_to(root)
            target = path / relative
            if str(relative) != ".":
                expected.add(str(relative))
            if member.isdir():
                if not target.is_dir():
                    return False
            elif member.issym():
                if not target.is_symlink() or os.readlink(target) != member.linkname:
                    return False
            elif member.isfile():
                if not target.is_file():
                    return False
                with archive.extractfile(member) as stream:
                    if digest(target) != hashlib.file_digest(stream, "sha256").hexdigest():
                        return False
            else:
                return False
        return expected == {str(item.relative_to(path)) for item in path.rglob("*")}


def backup(suite, destination, helper):
    config = configuration(suite)
    all_mounts = mounts(config, suite)
    for key, mount in all_mounts.items():
        if key.startswith("secret:") and not Path(mount["source"]).is_file():
            raise ValueError(f"Required secret file missing: {key}")
    existing_volumes = output("docker", "volume", "ls", "--format", "{{.Name}}").splitlines()
    declared_mounts = dict(all_mounts)
    all_mounts = {key: mount for key, mount in all_mounts.items()
                  if (mount["type"] == "volume" and mount["source"] in existing_volumes)
                  or (mount["type"] == "bind" and Path(mount["source"]).exists())}
    for mount in all_mounts.values():
        if mount["type"] == "bind":
            path = Path(mount["source"])
            if path == destination or path in destination.parents:
                raise ValueError("Backup destination must be outside all mounted directories")
    destination.mkdir(mode=0o700, parents=True, exist_ok=False)
    run("docker", "image", "inspect", helper, stdout=subprocess.DEVNULL)
    revision = output("git", "-C", str(suite.parent), "rev-parse", "HEAD").strip()
    records = containers(suite)
    if not any(record["Config"]["Labels"]["com.docker.compose.service"] == "suite-postgres"
               for record in records):
        raise ValueError("No suite PostgreSQL container found; refusing an incomplete deployment backup")
    require_matching_configuration(records, suite)
    for record in records:
        service = record["Config"]["Labels"]["com.docker.compose.service"]
        for mount in record["Mounts"]:
            key = f"{service}:{mount['Destination']}"
            if key in declared_mounts and key not in all_mounts:
                raise ValueError(f"Mounted data is missing from the host: {key}")
            if mount["Type"] == "volume" and mount["Name"] not in existing_volumes:
                raise ValueError(f"Mounted volume is missing: {mount['Name']}")
            if mount["Type"] == "volume" and mount["Name"] not in {item["source"] for item in all_mounts.values()}:
                raise ValueError(f"Unconfigured runtime volume requires an explicit backup: {mount['Name']}")
    running = [record["Config"]["Labels"]["com.docker.compose.service"]
               for record in records if record["State"]["Running"]]
    if running:
        run(*compose(suite, "stop", "--timeout", "120", *running))
    if any(record["State"]["Running"] for record in containers(suite)):
        raise ValueError("Containers are still running; no snapshot was taken")
    require_no_writers(all_mounts, (mount["Source"] for record in records for mount in record["Mounts"]))
    print("Suite is stopped. It will NOT be restarted automatically.", flush=True)
    # All PostgreSQL files are copied cold, alongside the corresponding queue
    # payloads and mailboxes. No cross-version database conversion is attempted.
    manifest = {"format": 1, "project": config["name"], "helper": helper, "revision": revision,
                "running_before_backup": running, "mounts": {}, "images": {}}
    for record in records:
        service = record["Config"]["Labels"]["com.docker.compose.service"]
        manifest["images"][service] = {
            "reference": record["Config"]["Image"], "id": record["Image"]}
    images = sorted({item["id"] for item in manifest["images"].values()})
    if images:
        with (destination / "images.tar").open("wb") as archive:
            run("docker", "image", "save", *images, stdout=archive)
        lines = ["services:"]
        for service, item in sorted(manifest["images"].items()):
            lines.extend([f"  {service}:", f"    image: {json.dumps(item['id'])}",
                          "    pull_policy: never", "    build: !reset null"])
        (destination / "images.yaml").write_text("\n".join(lines) + "\n")
    seen = {}
    for key, mount in all_mounts.items():
        identity = (mount["type"], mount["source"])
        if identity not in seen:
            filename = f"mount-{len(seen):03d}.tar"
            archive_mount(mount, destination / filename, helper)
            seen[identity] = filename
        manifest["mounts"][key] = {**mount, "archive": seen[identity],
                                  "file": mount["type"] == "bind" and Path(mount["source"]).is_file()}
    # Preserve deployment inputs separately, including independent Admin keys.
    with tarfile.open(destination / "deployment.tar", "w", dereference=True) as archive:
        for name in (".env", "cfg", "etc", "secrets", "db-init",
                     "docker-compose.yaml", "docker-compose.override.yaml",
                     "docker-compose.production.yaml", "docker-compose.host.yaml"):
            path = suite / name
            if path.exists():
                archive.add(path, arcname=name)
    manifest["checksums"] = {path.name: digest(path) for path in destination.glob("*.tar")}
    if images:
        manifest["checksums"]["images.yaml"] = digest(destination / "images.yaml")
    (destination / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    (destination / "COMPLETE").write_text(digest(destination / "manifest.json") + "\n")
    print(f"Complete snapshot: {destination}. Protect and encrypt it before off-host storage.")


def restore(suite, source, helper):
    if not (source / "COMPLETE").is_file():
        raise ValueError("Snapshot is incomplete")
    if (source / "COMPLETE").read_text().strip() != digest(source / "manifest.json"):
        raise ValueError("Manifest checksum mismatch")
    manifest = json.loads((source / "manifest.json").read_text())
    if manifest["format"] != 1:
        raise ValueError("Unsupported snapshot format")
    required = {"deployment.tar"} | {item["archive"] for item in manifest["mounts"].values()}
    if manifest["images"]:
        required.update({"images.tar", "images.yaml"})
    if not required.issubset(manifest["checksums"]):
        raise ValueError("Snapshot references unchecked archives")
    for filename, checksum in manifest["checksums"].items():
        if Path(filename).name != filename or digest(source / filename) != checksum:
            raise ValueError(f"Archive checksum mismatch: {filename}")
    run("docker", "image", "inspect", helper, stdout=subprocess.DEVNULL)
    config = configuration(suite)
    current = mounts(config, suite)
    if containers(suite):
        raise ValueError("Restore requires a fresh project with no containers, even stopped ones")
    require_no_writers(current)
    revision = output("git", "-C", str(suite.parent), "rev-parse", "HEAD").strip()
    if revision != manifest["revision"]:
        raise ValueError("Restore the recorded Git revision first; upgrade only after recovery")
    pending = {}
    for key, old in manifest["mounts"].items():
        if key not in current or current[key]["type"] != old["type"]:
            raise ValueError(f"Mount changed or missing: {key}")
        mount = current[key]
        if mount["type"] == "bind" and old["file"]:
            path = Path(mount["source"])
            # Operator restores deployment inputs before resolving Compose.
            # Those files must match the snapshot, not be silently overwritten.
            with tarfile.open(source / old["archive"]) as archive:
                member = archive.getmembers()[0]
                with archive.extractfile(member) as stream:
                    if not path.is_file() or digest(path) != hashlib.sha256(stream.read()).hexdigest():
                        raise ValueError(f"Restore the matching deployment file first: {path}")
            continue
        identity = (mount["type"], mount["source"])
        if identity in pending:
            continue
        if nonempty(mount, helper):
            # Deployment inputs are restored by the operator before Compose is
            # resolved. Do not overwrite an existing mailbox/queue/database.
            if mount["type"] == "bind" and (mount["read_only"] or Path(mount["source"]).is_relative_to(suite)):
                if matching_directory(Path(mount["source"]), source / old["archive"]):
                    continue
            raise ValueError(f"Refusing to overwrite non-empty data: {mount['source']}")
        pending[identity] = (mount, old)
    for mount, old in pending.values():
        restore_mount(mount, source / old["archive"], helper)
    if (source / "images.tar").exists():
        run("docker", "image", "load", "--input", str(source / "images.tar"))
    print("Snapshot restored. Services remain stopped. Use the recorded images before startup.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--suite", type=Path, default=Path(__file__).resolve().parent)
    parser.add_argument("--helper-image", default=HELPER_IMAGE)
    sub = parser.add_subparsers(dest="action", required=True)
    sub.add_parser("backup").add_argument("destination", type=Path)
    restore_parser = sub.add_parser("restore")
    restore_parser.add_argument("source", type=Path)
    restore_parser.add_argument("--confirm-fresh-target", action="store_true", required=True)
    args = parser.parse_args()
    if sys.platform != "linux" or os.geteuid() != 0:
        parser.error("Run as root on the Linux Docker host (not against a remote Docker daemon)")
    if os.environ.get("DOCKER_HOST") or os.environ.get("DOCKER_CONTEXT"):
        parser.error("Remote/custom Docker contexts are not supported")
    if output("docker", "context", "show").strip() != "default":
        parser.error("Use the local default Docker context")
    os.umask(0o077)
    suite = args.suite.resolve()
    try:
        with (suite / "recovery.lock").open("w") as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            if args.action == "backup":
                backup(suite, args.destination.resolve(), args.helper_image)
            else:
                restore(suite, args.source.resolve(), args.helper_image)
    except (ValueError, OSError, subprocess.CalledProcessError, tarfile.TarError) as error:
        print(f"ERROR: {error}. Services have not been restarted; retain any partial snapshot/data.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
