#!/usr/bin/env python3
"""Bounded SMTP/Sieve and coordinated recovery on private, retained local copies."""

import argparse
import hashlib
import imaplib
import importlib.util
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import smtplib
import subprocess
import sys
import tarfile
import time
import uuid

from docker_fixture import DockerFixture, SUITE, run
from local_release_protocol import message, sieve_response


SPEC = importlib.util.spec_from_file_location("recovery", SUITE / "recovery.py")
recovery = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(recovery)


def require(condition, message):
    if not condition:
        raise ValueError(message)


def private_file(path):
    require(path.is_file() and not path.is_symlink(), "Private input must be a regular file")
    require(path.stat().st_uid == os.geteuid() and path.stat().st_mode & 0o077 == 0,
            "Private input must belong to the operator and have no group/other permissions")


def immutable_image(value):
    require(re.fullmatch(r"sha256:[a-f0-9]{64}", value), "Use an immutable local image ID")
    return value


def freeze_revision(root, revision=None):
    """Copy one real commit object, not source files or evolving primary refs."""
    source_git = SUITE.parent / ".git"
    revision = revision or run("git", "--git-dir", str(source_git), "rev-parse", "HEAD").strip()
    require(re.fullmatch(r"[a-f0-9]{40}", revision), "Invalid recorded source revision")
    metadata = root / "revision.git"
    environment = {key: value for key, value in os.environ.items()
                   if not key.startswith("GIT_")}
    run("git", "init", "--bare", "--quiet", str(metadata), env=environment)
    commit = subprocess.run(["git", "--git-dir", str(source_git), "cat-file", "commit", revision],
                            capture_output=True, check=True, env=environment, timeout=30).stdout
    copied = subprocess.run(["git", "--git-dir", str(metadata), "hash-object", "-t", "commit",
                             "-w", "--stdin"], input=commit, capture_output=True, check=True,
                            env=environment, timeout=30).stdout.decode("ascii").strip()
    require(copied == revision, "Copied Git commit object does not match its recorded hash")
    run("git", "--git-dir", str(metadata), "update-ref", "--no-deref", "HEAD", revision, env=environment)
    require(run("git", "--git-dir", str(metadata), "rev-parse", "HEAD", env=environment).strip() == revision,
            "Frozen Git metadata does not resolve the recorded revision")
    (root / "source-revision.json").write_text(json.dumps({
        "revision": revision, "scope": "detached metadata-only commit object; no source/worktree snapshot",
    }))
    os.environ["GIT_DIR"] = str(metadata)
    return revision


class RetainedFixture(DockerFixture):
    """Reuse fixture isolation/SQL helpers, but never delete evidence or volumes."""

    def __init__(self, root, project):
        # The normal fixture intentionally removes its disposable volumes.
        # This operational drill must instead retain every generation.
        self.root = root
        root.mkdir(mode=0o700, parents=True, exist_ok=False)
        self.project = project
        self.password = secrets.token_hex(32)
        self.created = False
        self.config = {"name": project, "services": {},
                       "networks": {"backend": {"internal": True}}, "volumes": {}}
        self.command = recovery.compose(root)

    def validate_isolation(self):
        super().validate_isolation()
        require(all(item.get("internal") is True for item in self.config["networks"].values()),
                "Every network must be internal")
        for item in self.config["services"].values():
            immutable_image(item["image"])
            require(not item.get("ports"), "Operational fixture ports must not be published")
            require(item.get("networks") and set(item["networks"]).issubset(self.config["networks"]),
                    "Every service must explicitly join only declared internal networks")
            require(not any(key in item for key in ("build", "devices", "pid", "ipc")),
                    "Builds and host/device namespaces are forbidden")

    def start(self, services=()):
        self.validate_isolation()
        (self.root / "docker-compose.yaml").write_text(json.dumps(self.config))
        self.created = True
        run(*self.command, "up", "-d", "--no-build", "--pull", "never",
            "--wait", "--wait-timeout", "120", *services)

    def sql(self, statement):
        return run(*self.command, "exec", "-T", "suite-postgres", "psql", "-X",
                   "-U", "postgres", "-d", "robin", "-v", "ON_ERROR_STOP=1",
                   "-At", input=statement)

    def close(self):
        if self.created:
            run(*self.command, "down", "--timeout", "30")
            require(not recovery.containers(self.root), "Owned containers remain after cleanup")
            networks = run("docker", "network", "ls", "-q", "--filter",
                           f"label=com.docker.compose.project={self.project}")
            require(not networks.strip(), "Owned networks remain after cleanup")

    def capture_logs(self):
        if self.created:
            records = recovery.containers(self.root)
            if records:
                (self.root / "container-logs.txt").write_text(
                    run(*self.command, "logs", "--no-color", "--tail", "200"))
                (self.root / "container-state.json").write_text(json.dumps(records))


def volume_archive(image, project, volume, destination):
    with destination.open("wb") as stream:
        subprocess.run(["docker", "run", "--name", f"{project}-archive", "--rm",
                        "--network", "none", "--mount",
                        f"type=volume,src={volume},dst=/source,readonly",
                        image, "tar", "-C", "/source", "-cpf", "-", "."],
                       stdout=stream, stderr=subprocess.PIPE, check=True, timeout=300)


def raw_messages(archive):
    result = {}
    with tarfile.open(archive) as contents:
        for member in contents:
            if member.isfile() and re.search(r"/mail/.*/[0-9]+\.$", member.name):
                with contents.extractfile(member) as stream:
                    result[member.name] = hashlib.file_digest(stream, "sha256").hexdigest()
    require(result, "No original Cyrus raw messages found")
    return result


def prepare(fixture, images, runtime_config):
    fixture.postgres()
    postgres = fixture.config["services"].pop("postgres")
    postgres["image"] = images["postgres"]
    postgres["environment"].pop("POSTGRES_PASSWORD")
    postgres["environment"]["POSTGRES_PASSWORD_FILE"] = "/run/secrets/postgres_password"
    postgres["environment"].pop("POSTGRES_INITDB_ARGS")
    postgres["volumes"].append(f"{fixture.root / 'secrets' / 'postgres_password'}:"
                               "/run/secrets/postgres_password:ro")
    fixture.config["services"]["suite-postgres"] = postgres
    secret_dir = fixture.root / "secrets"
    secret_dir.mkdir(mode=0o700)
    for name in ("postgres_password", "mta_db_password", "mailbox_db_password",
                 "cyrus_admin_password"):
        (secret_dir / name).write_text(fixture.password)

    # The operator supplies a protected copy of the exact runtime configuration.
    # Never resolve the live Compose chain or modify its database/cache.
    cfg = fixture.root / "cfg"
    shutil.copytree(runtime_config, cfg)
    input_hashes = {str(item.relative_to(runtime_config)): recovery.digest(item)
                    for item in runtime_config.rglob("*") if item.is_file()}
    require(input_hashes == {str(item.relative_to(cfg)): recovery.digest(item)
                             for item in cfg.rglob("*") if item.is_file()},
            "Runtime configuration changed while copying")
    (fixture.root / "runtime-input-hashes.json").write_text(json.dumps(input_hashes))
    def write(name, value):
        (cfg / f"{name}.json5").write_text(json.dumps(value))

    write("config", {
        "enabled": True, "required": True, "serverId": "local-release",
        "jdbcUrl": "jdbc:postgresql://postgres-backend:5432/robin",
        "username": "robin_mta", "password": "{$secret:MTA_DB_PASSWORD}",
        "tableName": "robin_config_store",
    })
    write("server", {
        "hostname": "localhost", "bind": "0.0.0.0", "smtpPort": 25,
        "securePort": 465, "submissionPort": 587, "auth": True, "starttls": True,
        "pemCert": "/fixture/tls/fullchain.pem", "pemKey": "/fixture/tls/privkey.pem",
        "truststore": "", "allowSelfSigned": False,
        "senderAuthorization": {"enabled": True}, "chaosHeaders": False,
        "rbl": {"enabled": False},
        "renameHeaderEnabled": False, "xclientEnabled": False,
        "api": {"port": 8090, "authType": "none"},
        "service": {"port": 8080, "authType": "none"},
        "smtpConfig": {"tarpitDelayMillis": 0, "errorLimit": 50, "dosProtectionEnabled": False},
        "submissionConfig": {"tarpitDelayMillis": 0, "errorLimit": 50, "dosProtectionEnabled": False},
        "secureConfig": {"tarpitDelayMillis": 0, "errorLimit": 50, "dosProtectionEnabled": False},
    })
    write("storage", {"path": "/usr/local/robin/store/", "enabled": True,
                      "autoDelete": True, "clean": False, "localMailbox": False})
    write("relay", {"enabled": True, "inboundEnabled": True, "outboundEnabled": False,
                    "outboundMxEnabled": False, "disableRelayHeader": True, "bounce": False,
                    "host": "lmtp-backend", "port": 2525, "protocol": "esmtp", "tls": False})
    # Queued messages must not be replayed, even during restored startup.
    write("queue", {
        "startDelaySeconds": 86400, "housekeepingIntervalSeconds": 86400,
        "queueMapDB": {"enabled": False}, "queueRedis": {"enabled": False},
        "queueMariaDB": {"enabled": False},
        "queuePgSQL": {"enabled": True, "jdbcUrl": "jdbc:postgresql://postgres-backend:5432/robin",
                       "username": "robin_mta", "password": "{$secret:MTA_DB_PASSWORD}",
                       "tableName": "robin_queue"},
    })
    for name in ("clamav", "rspamd", "stalwart", "prometheus",
                 "distributed-rate", "geoip", "vault", "proxy", "whitelist",
                 "blackhole", "blocklist"):
        write(name, {"enabled": False})
    write("webhooks", {})
    write("bots", {"bots": []})
    write("users", {"listEnabled": False, "list": []})
    tls = fixture.root / "tls"
    tls.mkdir(mode=0o700)
    run("openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "2",
        "-subj", "/CN=localhost", "-addext", "subjectAltName=DNS:localhost,DNS:mta-backend,IP:127.0.0.1",
        "-keyout", str(tls / "privkey.pem"), "-out", str(tls / "fullchain.pem"))

    write("dovecot", {
        "auth": True, "userLookup": True, "authSocket": {"enabled": False},
        "authSql": {
            "enabled": True, "jdbcUrl": "jdbc:postgresql://postgres-backend:5432/robin",
            "user": "robin_mailbox", "password": "{$secret:MAILBOX_DB_PASSWORD}",
            "passwordQuery": "SELECT password FROM users WHERE email = ?",
            "userQuery": "SELECT home,uid,gid,maildir AS mail FROM users WHERE email = ?",
            "usersQuery": "SELECT email FROM users",
            "domainQuery": "SELECT 1 FROM mail_domains WHERE domain=? AND enabled=true",
            "aliasQuery": ("SELECT destination FROM (SELECT destination,0 AS priority FROM aliases WHERE source=?"
                           " UNION SELECT email,1 FROM users WHERE email=?"
                           " UNION SELECT destination,2 FROM auto_aliases WHERE source=?) t"
                           " ORDER BY priority LIMIT 1"),
        },
        "saveLmtp": {"enabled": True, "inline": True, "servers": ["lmtp-backend"],
                     "port": 24, "tls": False, "connectionPoolSize": 2},
        "saveLda": {"enabled": False}, "inlineSaveMaxAttempts": 1,
        "inlineSaveRetryDelay": 0, "failureBehaviour": "retry",
    })
    (fixture.root / "imapd.conf").write_text((SUITE / "etc/cyrus/imapd.conf").read_text())
    (fixture.root / "imapd-jmap.conf").write_text((SUITE / "etc/cyrus/imapd-jmap.conf").read_text())
    # No expiration or reindexing of the cloned original corpus in this gate.
    (fixture.root / "cyrus-jmap.conf").write_text("""START {
  recover cmd="ctl_cyrusdb -r"
}
SERVICES {
  imap cmd="imapd" listen="0.0.0.0:143" prefork=1 maxchild=8
  sieve cmd="timsieved" listen="0.0.0.0:4190" prefork=0 maxchild=4
  lmtp cmd="lmtpd -a" listen="0.0.0.0:24" prefork=1 maxchild=4
}
""")
    shutil.copyfile(Path(__file__).parent / "local_release_protocol.py", fixture.root / "protocol.py")
    fixture.config["volumes"].update({"payloads": {}, "mailboxes": {}})
    fixture.config["services"]["robin"] = {
        "image": images["robin"], "mem_limit": "768m", "cpus": 1, "pids_limit": 256,
        "volumes": [f"{cfg}:/usr/local/robin/cfg",
                    f"{tls}:/fixture/tls:ro", f"{secret_dir}:/run/secrets:ro",
                    "payloads:/usr/local/robin/store"],
        "environment": {"MTA_DB_PASSWORD_FILE": "/run/secrets/mta_db_password",
                        "MAILBOX_DB_PASSWORD_FILE": "/run/secrets/mailbox_db_password",
                        "MAIL_HOSTNAME": "localhost"},
        "networks": {"backend": {"aliases": ["mta-backend"]}},
        "depends_on": {"suite-postgres": {"condition": "service_healthy"}},
        "healthcheck": {"test": ["CMD", "nc", "-z", "localhost", "25"],
                        "interval": "2s", "timeout": "5s", "retries": 50},
    }
    fixture.config["services"]["mailbox"] = {
        "image": images["cyrus"], "init": True, "mem_limit": "768m", "cpus": 1,
        "pids_limit": 256,
        "environment": {"JMAP_SERVER_NAME": "local-release.test",
                        "ROBIN_AUTH_URL": "http://mta-backend:8090/users/authenticate"},
        "volumes": ["mailboxes:/var/lib/cyrus",
                    f"{fixture.root / 'imapd.conf'}:/etc/imapd-base.conf:ro",
                    f"{fixture.root / 'imapd-jmap.conf'}:/etc/imapd-jmap.conf:ro",
                    f"{fixture.root / 'cyrus-jmap.conf'}:/etc/cyrus-jmap.conf:ro",
                    f"{fixture.root / 'protocol.py'}:/fixture/protocol.py:ro",
                    f"{tls}:/fixture/tls:ro",
                    f"{secret_dir / 'cyrus_admin_password'}:/run/secrets/cyrus_admin_password:ro"],
        "networks": {"backend": {"aliases": ["lmtp-backend"]}},
        "depends_on": {"robin": {"condition": "service_healthy"}},
        "healthcheck": {"test": ["CMD", "nc", "-z", "localhost", "143"],
                        "interval": "2s", "timeout": "5s", "retries": 50},
    }
    # Real SQL password verification, never a fake authentication endpoint.
    schema = fixture.root / "init/00-schema.sql"
    schema.write_text(schema.read_text() + f"""
ALTER ROLE robin_mta PASSWORD '{fixture.password}';
ALTER ROLE robin_mailbox PASSWORD '{fixture.password}';
GRANT CREATE ON SCHEMA public TO robin_mta;
UPDATE users SET password='{{PLAIN}}{fixture.password}',
 home='/unused/' || split_part(email,'@',1), maildir='unused';
""")
    schema.write_text(schema.read_text() +
                      (SUITE / "migration/001-mail-domains-and-sender-grants.sql").read_text() + """
INSERT INTO aliases(source,destination) VALUES ('alias@example.test','alice@example.test'),
 ('receive-only@example.test','alice@example.test'), ('bob-alias@example.test','bob@example.test');
INSERT INTO mail_sender_grants VALUES ('alice@example.test','alias@example.test','alias');
""")
    # PostgreSQL drops root before reading initialization files and its secret.
    # The enclosing operator-private fixture root still prevents host access.
    schema.parent.chmod(0o755)
    schema.chmod(0o644)
    (secret_dir / "postgres_password").chmod(0o444)
    fixture.validate_isolation()
    (fixture.root / "docker-compose.yaml").write_text(json.dumps(fixture.config))


def smtp_sieve(fixture):
    results = {}
    def protocol(stage):
        result = run(*fixture.command, "exec", "-T", "mailbox",
                     "python3", "/fixture/protocol.py", stage)
        parsed = json.loads(result)
        require(parsed["stage"] == stage, "Protocol stage did not finish")
        results[stage] = parsed
        with (fixture.root / "protocol-results.txt").open("a") as evidence:
            evidence.write(result)
    protocol("smtp")
    fixture.sql("DELETE FROM mail_sender_grants WHERE address='alias@example.test';")
    protocol("revoked")
    fixture.sql("REVOKE SELECT ON mail_sender_grants FROM robin_mta, robin_mailbox;")
    try:
        protocol("outage")
    finally:
        fixture.sql("GRANT SELECT ON mail_sender_grants TO robin_mta, robin_mailbox;")
    protocol("sieve")
    protocol("queue")
    require(fixture.sql("SELECT count(*) FROM robin_queue;").strip() == "2",
            "Expected two real pending Robin queue records from local fixed-host relay")
    return results


def state(fixture, images, directory, queue_count):
    directory.mkdir(mode=0o700)
    # Canonical complete rows include serialized queue data, claims and timestamps.
    tables = ("users", "aliases", "auto_aliases", "mail_domains", "mail_sender_grants",
              "robin_config_store", "robin_queue")
    rows = {table: fixture.sql(f"SELECT row_to_json(t)::text FROM {table} t ORDER BY row_to_json(t)::text;")
            for table in tables}
    (directory / "rows.json").write_text(json.dumps(rows))
    volume_archive(images["helper"], fixture.project, fixture.project + "_payloads",
                   directory / "payloads.tar")
    files = {}
    with tarfile.open(directory / "payloads.tar") as archive:
        for item in archive:
            if item.isfile():
                with archive.extractfile(item) as stream:
                    files[item.name] = [hashlib.file_digest(stream, "sha256").hexdigest(),
                                        item.uid, item.gid, item.mode]
    require(sum("/queue/qeml-" in path for path in files) == queue_count,
            "Durable queue body count differs from accepted queue records")
    (directory / "payload-files.json").write_text(json.dumps(files))
    return rows, files


def audit_snapshot(fixture, snapshot, images):
    config = recovery.configuration(fixture.root)
    expected = recovery.mounts(config, fixture.root)
    manifest = json.loads((snapshot / "manifest.json").read_text())
    require(recovery.digest(snapshot / "manifest.json") == (snapshot / "COMPLETE").read_text().strip(),
            "Snapshot manifest checksum mismatch")
    require(set(expected) == set(manifest["mounts"]), "Snapshot omitted a resolved persistent input")
    require(manifest["helper"] == images["helper"], "Snapshot used a different helper image")
    require({key: value["id"] for key, value in manifest["images"].items()} ==
            {key: value["image"] for key, value in config["services"].items()},
            "Snapshot runtime images differ from the flattened fixture")
    for filename, checksum in manifest["checksums"].items():
        require(Path(filename).name == filename and recovery.digest(snapshot / filename) == checksum,
                "A snapshot archive checksum differs")
    for key, mount in expected.items():
        saved = manifest["mounts"][key]
        require(saved["archive"] in manifest["checksums"], "An input archive lacks a checksum")
        require(saved["type"] == mount["type"] and saved["source"] == mount["source"],
                "A snapshot input points at different data")
        if mount["type"] == "bind":
            path = Path(mount["source"])
            if path.is_dir():
                require(recovery.matching_directory(path, snapshot / saved["archive"]),
                        "A mounted directory is not fully preserved")
            else:
                with tarfile.open(snapshot / saved["archive"]) as archive:
                    with archive.extractfile(archive.getmembers()[0]) as stream:
                        require(recovery.digest(path) == hashlib.file_digest(stream, "sha256").hexdigest(),
                                "A mounted file is not fully preserved")
    (snapshot / "resolved-compose.json").write_text(json.dumps(config))
    (snapshot / "input-audit.json").write_text(json.dumps({
        "all_resolved_mounts_checked": len(expected), "runtime_images_checked": len(manifest["images"]),
        "helper_image": images["helper"], "deployment_tar_alone_is_sufficient": False,
    }))
    return len(expected)


def coordinated_restore(source, root, images):
    queue_count = int(source.sql("SELECT count(*) FROM robin_queue;").strip())
    before = state(source, images, root / "before-recovery", queue_count)
    snapshot = root / "snapshot"
    recovery.backup(source.root, snapshot, images["helper"])
    audited = audit_snapshot(source, snapshot, images)
    source.capture_logs()
    source.close()
    target = RetainedFixture(root / "restored/.suite", source.project + "-restore")
    shutil.copytree(source.root, target.root, dirs_exist_ok=True)
    target.config = json.loads((target.root / "docker-compose.yaml").read_text())
    target.config["name"] = target.project
    # Preserve relative basenames/service targets while relocating private inputs.
    text = json.dumps(target.config).replace(str(source.root), str(target.root))
    target.config = json.loads(text)
    target.validate_isolation()
    (target.root / "docker-compose.yaml").write_text(json.dumps(target.config))
    try:
        recovery.restore(target.root, snapshot, images["helper"])
        require(not recovery.containers(target.root), "Restore started services unexpectedly")
        volume_archive(images["helper"], target.project, target.project + "_mailboxes",
                       root / "restored-cyrus.tar")
        require(raw_messages(root / "after-sieve.tar") == raw_messages(root / "restored-cyrus.tar"),
                "Restored Cyrus raw messages differ from the coherent snapshot")
        target.start(("suite-postgres",))
        after = state(target, images, root / "after-recovery", queue_count)
        require(before == after, "Coordinated database rows or payload bytes/ownership differ")
        # Exercise process startup too, without queue replay or external networks.
        target.start()
        started = state(target, images, root / "after-restored-startup", queue_count)
        require(before[0]["robin_queue"] == started[0]["robin_queue"] and before[1] == started[1],
                "Restored startup changed, lost or replayed pending queue records/payloads")
        target.capture_logs()
        return {"passed": True, "queue_records": queue_count, "queue_payloads": queue_count,
                "tables_compared": len(before[0]),
                "resolved_mounts_audited": audited, "restored_project": target.project}
    finally:
        target.capture_logs()
        target.close()


def encrypt(root, destination, key):
    private_file(key)
    archive = root / "evidence.tar"
    require(not destination.exists(), "Encrypted evidence destination already exists")
    require(not archive.exists() and not (root / "verified.tar").exists(),
            "Use a fresh evidence root; previous archive bytes must be retained")
    with tarfile.open(archive, "w") as stream:
        for item in root.iterdir():
            if item != archive:
                stream.add(item, arcname=item.name)
    run("gpg", "--batch", "--pinentry-mode", "loopback", "--passphrase-file",
        str(key), "--symmetric", "--cipher-algo", "AES256", "--compress-algo", "none",
        "--output", str(destination), str(archive), timeout=600)
    # Verify independently decrypted bytes, not merely successful encryption.
    with (root / "verified.tar").open("wb") as output:
        subprocess.run(["gpg", "--batch", "--pinentry-mode", "loopback",
                        "--passphrase-file", str(key), "--decrypt", str(destination)],
                       stdout=output, stderr=subprocess.PIPE, check=True, timeout=600)
    require(recovery.digest(archive) == recovery.digest(root / "verified.tar"),
            "Encrypted evidence did not decrypt exactly")
    (destination.parent / (destination.name + ".sha256")).write_text(recovery.digest(destination) + "\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--encrypted-evidence", type=Path, required=True)
    parser.add_argument("--key-file", type=Path, required=True)
    parser.add_argument("--runtime-config", type=Path, required=True)
    parser.add_argument("--robin-provenance", type=Path)
    parser.add_argument("--source-cyrus-volume", required=True)
    for name in ("robin", "postgres", "cyrus", "helper"):
        parser.add_argument(f"--{name}-image", type=immutable_image, required=True)
    args = parser.parse_args()
    require(sys.platform == "linux", "Use private Linux filesystem storage")
    require(not os.environ.get("DOCKER_HOST") and not os.environ.get("DOCKER_CONTEXT"),
            "Use the local default Docker context")
    require(run("docker", "context", "show").strip() == "default", "Use the local Docker context")
    require(str(args.root.resolve()).startswith("/home/") and not args.root.exists(),
            "Use a new, private Linux home directory, never NTFS or an existing directory")
    private_file(args.key_file)
    if args.robin_provenance:
        private_file(args.robin_provenance)
    require(args.runtime_config.is_dir() and not args.runtime_config.is_symlink(),
            "Supply a protected copy of the exact runtime configuration")
    require(args.runtime_config.stat().st_uid == os.geteuid()
            and args.runtime_config.stat().st_mode & 0o077 == 0
            and not str(args.runtime_config.resolve()).startswith("/mnt/"),
            "Runtime configuration must be owner-private Linux storage")
    require(re.fullmatch(r"robin-cyrus-rehearsal-actual-[a-z0-9-]+", args.source_cyrus_volume),
            "Clone a retained completed actual-data rehearsal volume, never the live mailbox")
    os.umask(0o077)
    args.root.mkdir(mode=0o700, parents=True)
    require(not args.encrypted_evidence.exists(), "Never overwrite previous encrypted evidence")
    args.encrypted_evidence.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    images = {name: getattr(args, name + "_image") for name in ("robin", "postgres", "cyrus", "helper")}
    for image in images.values():
        run("docker", "image", "inspect", image)
    project = "robin-acceptance-release-" + uuid.uuid4().hex[:12]
    source = RetainedFixture(args.root / "source/.suite", project)
    result = {"status": "failed", "images": images, "project": project, "started_epoch": time.time()}
    try:
        result["source_revision"] = freeze_revision(args.root)
        harness = args.root / "harness"
        harness.mkdir(mode=0o700)
        for path in (Path(__file__), Path(__file__).parent / "docker_fixture.py",
                     Path(__file__).parent / "local_release_protocol.py", SUITE / "recovery.py"):
            shutil.copyfile(path, harness / path.name)
        if args.robin_provenance:
            proof = json.loads(args.robin_provenance.read_text())
            require(proof["candidate_image"] == args.robin_image, "Candidate provenance identifies another image")
            labels = json.loads(run("docker", "image", "inspect", args.robin_image))[0]["Config"]["Labels"]
            require(labels["org.robin.acceptance.artifact.sha256"] == proof["loaded_jar_sha256"]
                    and labels["org.robin.acceptance.base-image"] == proof["base_image"],
                    "Candidate image labels do not match verified runtime provenance")
            shutil.copyfile(args.robin_provenance, harness / "robin-image-proof.json")
            shutil.copyfile(args.robin_provenance.parent / "Dockerfile", harness / "robin-image.Dockerfile")
        supplied_manifest = args.runtime_config.parent / "robin-runtime-config-hashes.json"
        if supplied_manifest.is_file():
            shutil.copyfile(supplied_manifest, harness / supplied_manifest.name)
        prepare(source, images, args.runtime_config)
        run("docker", "volume", "inspect", args.source_cyrus_volume)
        recovery.require_no_writers({"source": {"source": args.source_cyrus_volume}})
        volume_archive(images["helper"], project, args.source_cyrus_volume, args.root / "original-cyrus.tar")
        originals = raw_messages(args.root / "original-cyrus.tar")
        with tarfile.open(args.root / "original-cyrus.tar") as archive:
            require(not any("example.test" in item.name for item in archive),
                    "Synthetic domain exists in the source; choose a different fixture domain")
        recovery.restore_mount({"type": "volume", "source": project + "_mailboxes"},
                               args.root / "original-cyrus.tar", images["helper"])
        source.start()
        protocols = smtp_sieve(source)
        (args.root / "cyr-buildinfo.json").write_text(
            run(*source.command, "exec", "-T", "mailbox", "cyr_buildinfo"))
        run(*source.command, "stop", "--timeout", "30", "mailbox")
        volume_archive(images["helper"], project, project + "_mailboxes", args.root / "after-sieve.tar")
        after = raw_messages(args.root / "after-sieve.tar")
        require(all(after.get(path) == checksum for path, checksum in originals.items()),
                "Original cloned message bytes changed")
        result["protocols"] = protocols
        result["original_messages_unchanged"] = len(originals)
        result["smtp_authorized_local_deliveries"] = 2
        result["sieve_local_deliveries"] = 2
        result["recovery"] = coordinated_restore(source, args.root, images)
        result["status"] = "passed" if all(item["passed"] for item in protocols.values()) else "failed"
    except (ValueError, OSError, subprocess.SubprocessError, smtplib.SMTPException,
            imaplib.IMAP4.error) as error:
        (args.root / "private-error.txt").write_text(str(error) + "\n" + str(getattr(error, "stderr", "")))
        raise
    finally:
        source.capture_logs()
        source.close()
        result["finished_epoch"] = time.time()
        (args.root / "result.json").write_text(json.dumps(result, indent=2))
        try:
            encrypt(args.root, args.encrypted_evidence, args.key_file)
        except (ValueError, OSError, subprocess.SubprocessError) as error:
            (args.root / "evidence-error.txt").write_text(
                str(error) + "\n" + str(getattr(error, "stderr", "")))
            raise
    print(json.dumps(result, indent=2))
    return 0 if result["status"] == "passed" else 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (ValueError, OSError, subprocess.SubprocessError, smtplib.SMTPException,
            imaplib.IMAP4.error):
        print("Local gates failed; inspect protected evidence. No volumes were deleted.", file=sys.stderr)
        sys.exit(1)
