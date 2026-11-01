#!/usr/bin/env python3
"""Cold, local suite snapshots. Run on the Linux Docker host as root."""

import argparse
import hashlib
import fcntl
import json
import os
from pathlib import Path, PurePosixPath
import subprocess
import sys
import tarfile


HELPER_IMAGE = "alpine:3.23"
DEPLOYMENT_DIRECTORIES = ("cfg", "cfg-production", "etc", "secrets", "db-init",
                          "db-init-production", "mailbox", "migration", "cyrus")
RESOLVED_COMPOSE = "compose.resolved.json"


def run(*args, **kwargs):
    return subprocess.run(args, check=True, **kwargs)


def output(*args):
    return run(*args, stdout=subprocess.PIPE, text=True).stdout


def compose(suite, *args):
    return ["docker", "compose", "--project-directory", str(suite), "--profile", "*", *args]


def configuration(suite, resolve_environment=True):
    options = [] if resolve_environment else ["--no-env-resolution"]
    return json.loads(output(*compose(suite, "config", *options, "--format", "json")))


def containers(suite):
    ids = output(*compose(suite, "ps", "--all", "--quiet")).split()
    return json.loads(output("docker", "inspect", *ids)) if ids else []


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def deployment_inputs(suite, config, unresolved, records):
    """Capture local inputs; the resolved model removes transitive include dependencies."""
    paths = set()
    compose_files = []

    def add(path, file_only=False):
        path = Path(path)
        if not path.is_absolute():
            path = suite / path
        path = path.absolute()
        if file_only and not path.is_file():
            raise ValueError(f"Required deployment input file is missing/invalid: {path}")
        if not path.is_file() and not path.is_dir():
            raise ValueError(f"Required deployment input is missing: {path}")
        if path.is_dir() and (path == Path(path.anchor) or path == Path.home() or path in suite.parents):
            raise ValueError(f"Refusing broad deployment input directory: {path}")
        paths.add(path)

    for name in DEPLOYMENT_DIRECTORIES:
        path = suite / name
        if path.exists():
            add(path)
    for pattern in (".env", "*.sh", "docker-compose*.yaml", "docker-compose*.yml", ".Dockerfile"):
        for path in suite.glob(pattern):
            add(path, file_only=True)
    for record in records:
        labels = record["Config"]["Labels"]
        selected = labels.get("com.docker.compose.project.config_files", "")
        compose_files.extend(path for path in selected.split(",") if path)
    if not compose_files:
        compose_files.append(str(suite / "docker-compose.yaml"))
    for path in compose_files:
        if path.startswith(("https://", "http://")):
            continue
        add(path, file_only=True)
    for definition in unresolved.get("services", {}).values():
        for item in definition.get("env_file", []):
            path = item if isinstance(item, str) else item["path"]
            required = isinstance(item, str) or item.get("required", True)
            target = Path(path) if Path(path).is_absolute() else suite / path
            if required or target.exists():
                add(target, file_only=True)
    for section in ("configs", "secrets"):
        for definition in config.get(section, {}).values():
            if "file" in definition:
                add(definition["file"], file_only=True)
    for definition in config["services"].values():
        for mount in definition.get("volumes", []):
            if mount["type"] == "bind" and mount.get("read_only"):
                path = Path(mount["source"])
                if not path.exists():
                    raise ValueError(f"Required read-only deployment input is missing: {path}")
                # Outside-suite bind inputs remain in the independent mount inventory.
                if path.is_relative_to(suite):
                    add(path)
    roots = sorted(paths, key=lambda path: (len(path.parts), str(path)))
    roots = [path for path in roots if not any(parent in paths for parent in path.parents)]
    result = {}
    for path in roots:
        name = (path.relative_to(suite).as_posix() if path.is_relative_to(suite)
                else f"external-inputs/{len(result):03d}/{path.name}")
        result[name] = path
    return result, {
        "composeFiles": list(dict.fromkeys(compose_files)),
        "composeEnvironment": {name: os.environ[name] for name in
                               ("COMPOSE_FILE", "COMPOSE_PROFILES", "COMPOSE_PROJECT_NAME",
                                "COMPOSE_PATH_SEPARATOR") if name in os.environ},
        "externalInputs": {name: str(path) for name, path in result.items()
                           if name.startswith("external-inputs/")},
    }


def input_inventory(archive):
    files, directories = {}, []
    with tarfile.open(archive) as source:
        for member in source:
            path = Path(member.name)
            if path.is_absolute() or ".." in path.parts:
                raise ValueError("Unsafe deployment input archive path")
            if member.name in files or member.name in directories:
                raise ValueError(f"Duplicate deployment input archive member: {member.name}")
            if member.isfile():
                with source.extractfile(member) as stream:
                    files[member.name] = hashlib.file_digest(stream, "sha256").hexdigest()
            elif member.isdir():
                directories.append(member.name)
            else:
                raise ValueError(f"Unsupported deployment input archive member: {member.name}")
    return {"files": files, "directories": sorted(directories)}


def deployment_source_inventory(inputs):
    files, directories = {}, []

    def visit(name, path):
        if path.is_dir():
            if path.is_symlink():
                raise ValueError(f"Deployment directory symlink needs an explicit separate backup: {name}")
            directories.append(name)
            for child in sorted(path.iterdir()):
                visit(f"{name}/{child.name}", child)
        elif path.is_file():
            files[name] = digest(path)
        else:
            raise ValueError(f"Required deployment input is missing or unsupported: {name}")

    for name, path in inputs.items():
        visit(name, path)
    return {"files": files, "directories": sorted(directories)}


def compose_dollars(value, escape):
    if isinstance(value, str):
        return value.replace("$", "$$") if escape else value.replace("$$", "$")
    if isinstance(value, list):
        return [compose_dollars(item, escape) for item in value]
    if isinstance(value, dict):
        return {key: compose_dollars(item, escape) for key, item in value.items()}
    return value


def reconstruction_model(config):
    """Host paths/build contexts may relocate; runtime settings must not change."""
    model = json.loads(json.dumps(config))
    for service, definition in model["services"].items():
        definition.pop("build", None)
        for mount in definition.get("volumes", []):
            if mount["type"] == "bind":
                mount["source"] = f"bind:{service}:{mount['target']}"
    for section in ("configs", "secrets"):
        for name, definition in model.get(section, {}).items():
            if "file" in definition:
                definition["file"] = f"{section}:{name}"
    return model


def require_deployment_inputs(suite, source, manifest):
    expected = manifest.get("deploymentInputs")
    if not expected:
        raise ValueError("Snapshot lacks deployment input inventory; legacy recovery needs an independent audit")
    if expected != input_inventory(source / "deployment.tar"):
        raise ValueError("Deployment input archive inventory mismatch")
    for name, checksum in expected["files"].items():
        if name.startswith("external-inputs/"):
            continue
        target = suite / name
        if not target.is_file():
            raise ValueError(f"Restore the required deployment input first: {name}")
        # Path-only .env relocation is checked by comparing resolved runtime settings.
        if name != ".env" and digest(target) != checksum:
            raise ValueError(f"Restore the matching deployment input first: {name}")
    for name in expected["directories"]:
        if not name.startswith("external-inputs/") and not (suite / name).is_dir():
            raise ValueError(f"Restore the required deployment directory first: {name}")


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
                if source in ("/var/run/docker.sock", "/run/docker.sock"):
                    continue
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
    for section, prefix in (("secrets", "secret"), ("configs", "config")):
        for name, definition in config.get(section, {}).items():
            if definition.get("external"):
                raise ValueError(f"External {prefix} must be backed up separately: {name}")
            if "file" in definition:
                result[f"{prefix}:{name}"] = {"type": "bind",
                                            "source": str(Path(definition["file"]).resolve()),
                                            "read_only": True}
            elif section == "secrets" or "content" not in definition:
                raise ValueError(f"Unsupported {prefix} source requires an explicit backup: {name}")
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


def require_bind_archive_target(mount, archive):
    # Bind archives include their basename; relocation may change the parent,
    # but not the mounted directory/file name.
    with tarfile.open(archive) as source_archive:
        members = source_archive.getmembers()
    if not members:
        raise ValueError("Bind archive is empty")
    first = PurePosixPath(members[0].name)
    if (len(first.parts) != 1 or first.is_absolute()
            or first.parts[0] in (".", "..") or not (members[0].isdir() or members[0].isfile())):
        raise ValueError("Invalid bind archive root")
    original = first.parts[0]
    for member in members:
        path = PurePosixPath(member.name)
        if not path.parts or path.is_absolute() or ".." in path.parts or path.parts[0] != original:
            raise ValueError("Invalid bind archive root")
    source = Path(mount["source"])
    if original != source.name:
        raise ValueError(f"Bind basename changed: {original} -> {source.name}")


def restore_mount(mount, archive, helper):
    if mount["type"] == "bind":
        require_bind_archive_target(mount, archive)
        source = Path(mount["source"])
        source.parent.mkdir(parents=True, exist_ok=True)
        root = str(source.parent)
    else:
        root = mount["source"]
        run("docker", "volume", "create", root, stdout=subprocess.DEVNULL)
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
    unresolved = configuration(suite, resolve_environment=False)
    all_mounts = mounts(config, suite)
    for key, mount in all_mounts.items():
        if key.startswith(("secret:", "config:")) and not Path(mount["source"]).is_file():
            raise ValueError(f"Required secret/config file missing: {key}")
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
    records = containers(suite)
    if not any(record["Config"]["Labels"]["com.docker.compose.service"] == "suite-postgres"
               for record in records):
        raise ValueError("No suite PostgreSQL container found; refusing an incomplete deployment backup")
    require_matching_configuration(records, suite)
    inputs, operator_inputs = deployment_inputs(suite, config, unresolved, records)
    expected_inputs = deployment_source_inventory(inputs)
    if any(path == destination or path in destination.parents for path in inputs.values()):
        raise ValueError("Backup destination must be outside deployment inputs")
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
    destination.mkdir(mode=0o700, parents=True, exist_ok=False)
    run("docker", "image", "inspect", helper, stdout=subprocess.DEVNULL)
    revision = output("git", "-C", str(suite.parent), "rev-parse", "HEAD").strip()
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
                "running_before_backup": running, "mounts": {}, "images": {},
                "operatorInputs": operator_inputs}
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
        for name, path in inputs.items():
            archive.add(path, arcname=name)
    manifest["deploymentInputs"] = expected_inputs
    (destination / RESOLVED_COMPOSE).write_text(
        json.dumps(compose_dollars(config, escape=True), indent=2) + "\n")
    manifest["checksums"] = {path.name: digest(path) for path in destination.glob("*.tar")}
    manifest["checksums"][RESOLVED_COMPOSE] = digest(destination / RESOLVED_COMPOSE)
    if images:
        manifest["checksums"]["images.yaml"] = digest(destination / "images.yaml")
    (destination / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    require_deployment_inputs(suite, destination, manifest)
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
    required.add(RESOLVED_COMPOSE)
    if not required.issubset(manifest["checksums"]):
        raise ValueError("Snapshot references unchecked archives")
    for filename, checksum in manifest["checksums"].items():
        if Path(filename).name != filename or digest(source / filename) != checksum:
            raise ValueError(f"Archive checksum mismatch: {filename}")
    require_deployment_inputs(suite, source, manifest)
    run("docker", "image", "inspect", helper, stdout=subprocess.DEVNULL)
    config = configuration(suite)
    original = compose_dollars(json.loads((source / RESOLVED_COMPOSE).read_text()), escape=False)
    if reconstruction_model(config) != reconstruction_model(original):
        raise ValueError("Resolved deployment settings differ; restore the recorded operator inputs first")
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
        if mount["type"] == "bind":
            require_bind_archive_target(mount, source / old["archive"])
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
