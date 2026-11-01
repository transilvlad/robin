#!/usr/bin/env python3
"""Read-only deployment gate. Never seed config, migrate data, or restart services."""

import argparse
from fractions import Fraction
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys


CRITICAL_FILES = ("server.json5", "queue.json5", "dovecot.json5", "rspamd.json5", "clamav.json5")
SHA256 = re.compile(r"(?:sha256:)?[0-9a-f]{64}")
MAX_DOCKER_INTEGER = 2 ** 63 - 1


def positive_integer(value):
    return type(value) is int and 0 < value <= MAX_DOCKER_INTEGER


def cpu_budget(value):
    if type(value) not in (int, float):
        return None
    try:
        budget = Fraction(str(value))
    except (ValueError, ZeroDivisionError):
        return None
    nanos = budget * 1000000000
    return budget if budget > 0 and nanos.denominator == 1 and nanos <= MAX_DOCKER_INTEGER else None


def deployed_budget_failures(service, record):
    failures = []
    host = record.get("HostConfig")
    if not isinstance(host, dict):
        return ["deployed HostConfig resource limits are missing/invalid"]
    for field, actual in (("mem_limit", "Memory"), ("pids_limit", "PidsLimit")):
        value = host.get(actual)
        if not positive_integer(value):
            failures.append(f"deployed {actual} hard limit is missing, invalid or unlimited")
        elif value != service.get(field):
            failures.append(f"deployed {actual} hard limit differs from reviewed {field}")
    nanos, quota, period = (host.get(field, 0) for field in ("NanoCpus", "CpuQuota", "CpuPeriod"))
    if (any(type(value) is not int or not 0 <= value <= MAX_DOCKER_INTEGER
            for value in (nanos, period))
            or type(quota) is not int or not -1 <= quota <= MAX_DOCKER_INTEGER):
        failures.append("deployed CPU hard-limit fields are invalid")
        return failures
    limits = []
    if nanos:
        limits.append(Fraction(nanos, 1000000000))
    if quota > 0:
        # Docker's CFS period defaults to 100000 microseconds when unspecified.
        limits.append(Fraction(quota, period or 100000))
    if not limits:
        failures.append("deployed CPU hard limit is missing or unlimited")
    elif any(limit != cpu_budget(service.get("cpus")) for limit in limits):
        failures.append("deployed CPU hard limit differs from reviewed cpus")
    return failures


def canonical_hash(value):
    encoded = json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False, allow_nan=False)
    return hashlib.sha256(encoded.encode("utf-8")).hexdigest()


def merge_known(base, override):
    """Config-store instance overrides cannot introduce keys absent from the base."""
    result = dict(base)
    for key, value in override.items():
        if key in result:
            result[key] = merge_known(result[key], value) if isinstance(result[key], dict) and isinstance(value, dict) else value
    return result


def effective_files(rows, server_id):
    default, instance = {}, {}
    for row in rows:
        scope, name, payload = row["server_id"], row["file_name"], row["payload_json"]
        if not isinstance(payload, dict) or not payload:
            raise ValueError(f"Empty or invalid authoritative config: {name}")
        if scope == "default":
            default[name] = payload
        elif scope == server_id:
            instance[name] = payload
    for name in instance:
        if name not in default:
            raise ValueError(f"Instance override has no default: {name}")
        default[name] = merge_known(default[name], instance[name])
    return default


def database_inventory(container, server_id):
    """The database, not the bootstrap templates, owns persisted configuration."""
    if not re.fullmatch(r"[a-zA-Z0-9_.-]{1,64}", server_id):
        raise ValueError("Invalid server identifier")
    query = f"""
BEGIN READ ONLY;
SELECT json_build_object(
 'rows', COALESCE((SELECT json_agg(t) FROM
   (SELECT server_id,file_name,payload_json FROM robin_config_store
    WHERE server_id IN ('default','{server_id}') ORDER BY server_id,file_name) t), '[]'::json),
 'demoUsers', (SELECT count(*) FROM users
    WHERE email IN ('tony@example.com','pepper@example.com','happy@example.com')),
 'managedDomains', (SELECT count(*) FROM mail_domains WHERE enabled),
 'missingPrimaryGrants', (SELECT count(*) FROM users u WHERE NOT EXISTS
    (SELECT 1 FROM mail_sender_grants g WHERE g.account=u.email AND g.address=u.email)));
ROLLBACK;
"""
    result = subprocess.run(
        ["docker", "exec", "-i", "-e", "PGOPTIONS=-c default_transaction_read_only=on",
         container, "psql", "-X", "-qAt", "-U", "postgres", "-d", "robin", "-v", "ON_ERROR_STOP=1"],
        input=query, check=True, capture_output=True, text=True)
    return json.loads(result.stdout)


def audit(compose, inventory, runtime, image_lock, containers, server_id):
    failures = []

    def require(condition, message):
        if not condition:
            failures.append(message)

    files = effective_files(inventory["rows"], server_id)
    require(not inventory.get("demoUsers", 1), "Demo accounts remain: review mail and rotate/remove them explicitly")
    require(inventory.get("managedDomains", 0) > 0, "No enabled managed domains")
    require(inventory.get("missingPrimaryGrants") == 0, "Existing users lack explicit primary sender grants")
    require(runtime.get("scope") == "configuration-snapshot", "Runtime configuration-snapshot proof is missing")
    require(runtime.get("restartRequired") is False, "Runtime components require restart/acceptance")
    require(runtime.get("sync", {}).get("enabled") is True
            and not runtime.get("sync", {}).get("lastError"), "Config-store synchronization is disabled/failing")
    for name in CRITICAL_FILES:
        require(name in files, f"Authoritative {name} is missing")
        proof = runtime.get("sourceFiles", {}).get(name, {})
        require(name in files and proof.get("sha256") == canonical_hash(files[name]),
                f"Loaded {name} differs from authoritative state or has no runtime proof")
    server = files.get("server.json5", {})
    hostname = server.get("hostname", "")
    require(hostname not in ("", "localhost", "example.com"), "A real mail identity is required")
    require(server.get("allowSelfSigned") is False, "Self-signed trust must be disabled")
    require(server.get("pemCert") == "/run/robin-tls/fullchain.pem"
            and server.get("pemKey") == "/run/robin-tls/privkey.pem", "Operator mail TLS directory is not configured")
    for api in ("service", "api"):
        settings = server.get(api, {})
        require(settings.get("authType") == "basic" and bool(settings.get("authValue")),
                f"{api} requires explicit Basic authentication")
    for listener in ("smtpConfig", "secureConfig", "submissionConfig"):
        require(server.get(listener, {}).get("messageSizeLimit") == 20971520,
                f"{listener} must use canonical messageSizeLimit=20971520")
    queue = files.get("queue.json5", {})
    require(not any(key in queue for key in ("queueInitialDelay", "queueInterval", "maxDequeuePerTick")),
            "Legacy queue timing keys do not control the dispatcher")
    require(0 < queue.get("workerThreads", 0) <= queue.get("maxInFlight", 0) <= 64,
            "Queue worker/in-flight budget is missing or unbounded")
    require(files.get("rspamd.json5", {}).get("discardEnabled") is False, "Silent spam discard must be disabled")
    require(files.get("clamav.json5", {}).get("onVirus") == "reject", "Virus policy must reject rather than discard")
    deployed = {record.get("Config", {}).get("Labels", {}).get("com.docker.compose.service"): record
                for record in containers}
    for name, service in compose.get("services", {}).items():
        if name == "suite-config-init":
            continue
        if service.get("profiles") and name not in deployed:
            continue
        lock = image_lock.get("images", {}).get(name, {})
        reference = service.get("image", "")
        require(bool(SHA256.fullmatch(lock.get("id", ""))) and lock.get("reference") == reference,
                f"{name}: reviewed release image lock missing/mismatched")
        require(bool(re.search(r"@sha256:[0-9a-f]{64}$", reference))
                or bool(re.fullmatch(r"[0-9a-f]{40,64}", lock.get("sourceRevision", "")))
                and not reference.endswith((":latest", ":main", ":master")),
                f"{name}: image is neither digest-pinned nor a reviewed local build")
        for field in ("mem_limit", "cpus", "pids_limit"):
            value = service.get(field)
            require(cpu_budget(value) is not None if field == "cpus" else positive_integer(value),
                    f"{name}: {field} budget is missing/invalid")
        matching = [item for item in containers
                    if item.get("Config", {}).get("Labels", {}).get("com.docker.compose.service") == name]
        for item in matching or [{}]:
            require(item.get("Image") == lock.get("id"), f"{name}: deployed image differs from reviewed lock")
            state = item.get("State", {})
            require(state.get("Running") is True and not state.get("Paused") and not state.get("OOMKilled")
                    and state.get("Health", {}).get("Status", "healthy") == "healthy",
                    f"{name}: runtime is absent, unhealthy, stopped, paused or OOM-killed")
            failures.extend(f"{name}: {message}" for message in deployed_budget_failures(service, item))
        if name == "suite-postgres":
            require(lock.get("version", "").startswith("18."), "PostgreSQL release must be reviewed major 18")
        if name == "suite-dovecot":
            require(lock.get("version", "").startswith("2.4."), "Dovecot release must be reviewed 2.4")
        for port in service.get("ports", []):
            target = int(port.get("target", 0))
            if target not in (25, 465, 587, 993):
                require(port.get("host_ip") in ("127.0.0.1", "::1"), f"{name}: non-mail listener is publicly published")
        for mount in service.get("volumes", []):
            if "docker.sock" in mount.get("source", ""):
                require("host-control" in service.get("profiles", []),
                        f"{name}: host-root-equivalent Docker socket is not explicit opt-in")
    return {
        "status": "blocked" if failures else "local-state-accepted",
        "failures": failures,
        "authoritativeHashes": {name: canonical_hash(value) for name, value in sorted(files.items())},
        "publicReadiness": "not-proven: verify external DNS, reputation, firewall, TLS and delivery separately",
        "writesPerformed": False,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--compose-json", type=Path, required=True, help="Private rendered Compose JSON; may contain secrets")
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--database-json", type=Path)
    source.add_argument("--postgres-container", help="Explicit deployment PostgreSQL container ID/name; read-only SQL only")
    parser.add_argument("--runtime-status", type=Path, required=True, help="Authenticated GET /config/status response")
    parser.add_argument("--image-lock", type=Path, required=True, help="Reviewed immutable release image inventory")
    parser.add_argument("--containers-json", type=Path, required=True, help="Read-only docker inspect of this deployment")
    parser.add_argument("--server-id", default="robin1")
    arguments = parser.parse_args()
    try:
        read = lambda path: json.loads(path.read_text(encoding="utf-8-sig"))
        inventory = read(arguments.database_json) if arguments.database_json else database_inventory(arguments.postgres_container, arguments.server_id)
        report = audit(read(arguments.compose_json), inventory, read(arguments.runtime_status),
                       read(arguments.image_lock), read(arguments.containers_json), arguments.server_id)
    except (OSError, ValueError, KeyError, TypeError, subprocess.CalledProcessError) as error:
        # Never echo subprocess output or raw config/secret values.
        print(json.dumps({"status": "blocked", "errorType": type(error).__name__, "writesPerformed": False}))
        return 2
    print(json.dumps(report, indent=2))
    return 1 if report["failures"] else 0


if __name__ == "__main__":
    sys.exit(main())
