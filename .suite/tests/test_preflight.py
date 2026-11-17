import copy
import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location("preflight", Path(__file__).resolve().parents[1] / "preflight.py")
preflight = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(preflight)


class ProductionPreflightTest(unittest.TestCase):
    def fixture(self):
        files = {
            "server.json5": {"hostname": "mail.example.test", "allowSelfSigned": False,
                             "pemCert": "/run/robin-tls/fullchain.pem", "pemKey": "/run/robin-tls/privkey.pem",
                             "senderAuthorization": {"enabled": True},
                             "recipientRouting": {"enabled": True, "allowExternalForwarding": False,
                                                  "maxDepth": 10, "maxRecipients": 100},
                             "submissionLimits": {
                                 "enabled": True, "windowSeconds": 3600,
                                 "account": {"messages": 200, "recipients": 1000, "bytes": 104857600,
                                             "concurrentConnections": 5, "authFailures": 20},
                                 "domain": {"messages": 2000, "recipients": 10000, "bytes": 1073741824,
                                            "concurrentConnections": 50}},
                             **{name: {"authType": "basic", "authValue": "{$secret:ROBIN_API_AUTH}"} for name in ("api", "service")},
                             **{name: {"messageSizeLimit": 20971520} for name in ("smtpConfig", "secureConfig", "submissionConfig")}},
            "queue.json5": {"workerThreads": 8, "maxInFlight": 16,
                            "maxConcurrentPerDestination": 2, "destinationDeferSeconds": 5,
                            "retry": {"maxAgeSeconds": 432000, "jitterFraction": 0.2}},
            "dovecot.json5": {"saveLmtp": {"connectionPoolSize": 8}},
            "rspamd.json5": {"enabled": True, "requireDkimSigning": True, "discardEnabled": False},
            "clamav.json5": {"onVirus": "reject"},
        }
        inventory = {"rows": [{"server_id": "default", "file_name": name, "payload_json": value}
                              for name, value in files.items()],
                     "demoUsers": 0, "managedDomains": 1, "missingPrimaryGrants": 0}
        runtime = {"scope": "configuration-snapshot", "restartRequired": False,
                   "sync": {"enabled": True},
                   "sourceFiles": {name: {"sha256": preflight.canonical_hash(value)} for name, value in files.items()}}
        compose = {"services": {"suite-robin": {"image": "robin@sha256:" + "a" * 64,
                                               "mem_limit": 1024, "cpus": 1, "pids_limit": 128}}}
        lock = {"images": {"suite-robin": {"reference": compose["services"]["suite-robin"]["image"], "id": "sha256:" + "b" * 64}}}
        containers = [{"Image": lock["images"]["suite-robin"]["id"], "State": {"Running": True},
                       "HostConfig": {"Memory": 1024, "NanoCpus": 1000000000,
                                      "CpuQuota": 0, "CpuPeriod": 0, "PidsLimit": 128},
                       "Config": {"Labels": {"com.docker.compose.service": "suite-robin"}}}]
        return compose, inventory, runtime, lock, containers

    def test_accepts_local_evidence_but_never_claims_public_readiness(self):
        report = preflight.audit(*self.fixture(), "robin1")
        self.assertEqual([], report["failures"])
        self.assertEqual("local-state-accepted", report["status"])
        self.assertFalse(report["writesPerformed"])
        self.assertIn("not-proven", report["publicReadiness"])

    def test_stale_authoritative_override_cannot_be_hidden_by_new_templates(self):
        compose, inventory, runtime, lock, containers = self.fixture()
        inventory["rows"].append({"server_id": "robin1", "file_name": "server.json5",
                                  "payload_json": {"api": {"authType": "none"}}})
        report = preflight.audit(compose, inventory, runtime, lock, containers, "robin1")
        self.assertIn("api requires explicit Basic authentication", report["failures"])
        self.assertTrue(any("differs" in item for item in report["failures"]))

    def test_missing_runtime_or_restart_required_is_blocking(self):
        for change in ({"sourceFiles": {}}, {"restartRequired": True}):
            compose, inventory, runtime, lock, containers = self.fixture()
            runtime.update(change)
            self.assertEqual("blocked", preflight.audit(compose, inventory, runtime, lock, containers, "robin1")["status"])

    def test_disabled_missing_or_malformed_authoritative_mail_policies_are_blocked(self):
        for name in ("senderAuthorization", "recipientRouting", "submissionLimits"):
            for value in (None, {}, {"enabled": False}, {"enabled": "true"}):
                with self.subTest(policy=name, value=value):
                    compose, inventory, runtime, lock, containers = self.fixture()
                    server = inventory["rows"][0]["payload_json"]
                    server[name] = value
                    runtime["sourceFiles"]["server.json5"]["sha256"] = preflight.canonical_hash(server)
                    failures = preflight.audit(compose, inventory, runtime, lock, containers, "robin1")["failures"]
                    self.assertTrue(any("enabled" in item for item in failures))

    def test_unlimited_or_invalid_submission_budgets_are_blocked_even_with_matching_runtime(self):
        for scope, field in (("account", "messages"), ("account", "recipients"), ("account", "bytes"),
                             ("account", "concurrentConnections"), ("account", "authFailures"),
                             ("domain", "messages"), ("domain", "recipients"), ("domain", "bytes"),
                             ("domain", "concurrentConnections")):
            for value in (None, 0, -1, True, "200", 1.5, 2 ** 63):
                with self.subTest(scope=scope, field=field, value=value):
                    compose, inventory, runtime, lock, containers = self.fixture()
                    server = inventory["rows"][0]["payload_json"]
                    server["submissionLimits"][scope][field] = value
                    runtime["sourceFiles"]["server.json5"]["sha256"] = preflight.canonical_hash(server)
                    failures = preflight.audit(compose, inventory, runtime, lock, containers, "robin1")["failures"]
                    self.assertIn(f"submissionLimits.{scope}.{field} requires a positive budget", failures)

    def test_optional_scanning_or_signing_cannot_pass_production_preflight(self):
        for field in ("enabled", "requireDkimSigning"):
            compose, inventory, runtime, lock, containers = self.fixture()
            scanner = next(row["payload_json"] for row in inventory["rows"] if row["file_name"] == "rspamd.json5")
            scanner.pop(field)
            runtime["sourceFiles"]["rspamd.json5"]["sha256"] = preflight.canonical_hash(scanner)
            failures = preflight.audit(compose, inventory, runtime, lock, containers, "robin1")["failures"]
            self.assertIn("Rspamd scanning and mandatory outbound DKIM signing must be enabled", failures)

    def test_mutable_images_and_unprofiled_socket_are_blocked(self):
        compose, inventory, runtime, lock, containers = self.fixture()
        service = compose["services"]["suite-robin"]
        service.update({"image": "robin:latest", "volumes": [{"source": "/var/run/docker.sock"}]})
        failures = preflight.audit(compose, inventory, runtime, lock, containers, "robin1")["failures"]
        self.assertTrue(any("reviewed release image lock" in message for message in failures))
        self.assertTrue(any("explicit opt-in" in message for message in failures))

    def test_running_image_health_and_positive_budgets_are_required(self):
        for field in ("mem_limit", "cpus", "pids_limit"):
            compose, inventory, runtime, lock, containers = self.fixture()
            compose["services"]["suite-robin"][field] = -1
            containers[0]["Image"] = "sha256:" + "c" * 64
            containers[0]["State"]["Health"] = {"Status": "unhealthy"}
            failures = preflight.audit(compose, inventory, runtime, lock, containers, "robin1")["failures"]
            self.assertTrue(any(field in message for message in failures))
            self.assertTrue(any("deployed image" in message for message in failures))
            self.assertTrue(any("unhealthy" in message for message in failures))

    def test_inventory_query_is_read_only_and_does_not_interpolate_arbitrary_identifiers(self):
        with patch.object(preflight.subprocess, "run") as run:
            run.return_value.stdout = '{"rows":[]}'
            preflight.database_inventory("explicit-test-container", "robin1")
            self.assertIn("BEGIN READ ONLY", run.call_args.kwargs["input"])
            self.assertIn("ROLLBACK", run.call_args.kwargs["input"])
            self.assertIn("PGOPTIONS=-c default_transaction_read_only=on", run.call_args.args[0])
            with self.assertRaises(ValueError):
                preflight.database_inventory("explicit-test-container", "id'; DELETE FROM users; --")
            self.assertEqual(1, run.call_count)

    def test_canonical_hash_is_key_order_independent(self):
        self.assertEqual(preflight.canonical_hash({"b": {"z": 2, "a": 1}, "a": "<@>"}),
                         preflight.canonical_hash({"a": "<@>", "b": {"a": 1, "z": 2}}))

    def test_missing_host_config_never_accepts_reviewed_compose_only(self):
        for change in ({}, {"HostConfig": None}, {"HostConfig": []}):
            compose, inventory, runtime, lock, containers = self.fixture()
            containers[0].pop("HostConfig")
            containers[0].update(change)
            report = preflight.audit(compose, inventory, runtime, lock, containers, "robin1")
            self.assertEqual("blocked", report["status"])
            self.assertTrue(any("HostConfig" in message for message in report["failures"]))

    def test_compose_decimal_byte_strings_require_exact_deployed_memory(self):
        compose, inventory, runtime, lock, containers = self.fixture()
        compose["services"]["suite-robin"]["mem_limit"] = "1024"
        self.assertEqual([], preflight.audit(compose, inventory, runtime, lock, containers, "robin1")["failures"])
        containers[0]["HostConfig"]["Memory"] = 2048
        failures = preflight.audit(compose, inventory, runtime, lock, containers, "robin1")["failures"]
        self.assertIn("suite-robin: deployed Memory hard limit differs from reviewed mem_limit", failures)

    def test_queue_monopolization_unbounded_age_and_synchronized_retries_are_blocked(self):
        for field, value, message in (
                ("maxConcurrentPerDestination", 0, "Destination concurrency"),
                ("maxConcurrentPerDestination", 8, "Destination concurrency"),
                ("maxConcurrentPerDestination", True, "Destination concurrency"),
                ("destinationDeferSeconds", 0, "Destination deferral"),
                ("retry", {}, "Queue delivery lifetime"),
                ("retry", {"maxAgeSeconds": 0, "jitterFraction": 0.2}, "Queue delivery lifetime"),
                ("retry", {"maxAgeSeconds": 432000, "jitterFraction": 0}, "Queue retry jitter"),
                ("retry", {"maxAgeSeconds": 432000, "jitterFraction": 1}, "Queue retry jitter")):
            with self.subTest(field=field, value=value):
                compose, inventory, runtime, lock, containers = self.fixture()
                queue = next(row["payload_json"] for row in inventory["rows"] if row["file_name"] == "queue.json5")
                queue[field] = value
                runtime["sourceFiles"]["queue.json5"]["sha256"] = preflight.canonical_hash(queue)
                failures = preflight.audit(compose, inventory, runtime, lock, containers, "robin1")["failures"]
                self.assertTrue(any(message in failure for failure in failures))

    def test_unlimited_invalid_or_mismatched_deployed_memory_and_pids_are_blocked(self):
        for field in ("Memory", "PidsLimit"):
            for value in (None, 0, -1, True, "128", 1.5, float("inf"), 2 ** 63, 256):
                with self.subTest(field=field, value=value):
                    compose, inventory, runtime, lock, containers = self.fixture()
                    containers[0]["HostConfig"][field] = value
                    failures = preflight.audit(compose, inventory, runtime, lock, containers, "robin1")["failures"]
                    self.assertTrue(any(field in message for message in failures))

    def test_valid_quota_period_cpu_is_accepted_even_when_nano_cpus_is_zero(self):
        for host_fields in (
                {"NanoCpus": 0, "CpuQuota": 50000, "CpuPeriod": 100000},
                {"CpuQuota": 100000, "CpuPeriod": 200000},
                {"NanoCpus": 0, "CpuQuota": 50000, "CpuPeriod": 0},
                {"NanoCpus": 0, "CpuQuota": 50000},
                {"NanoCpus": 500000000, "CpuQuota": 0, "CpuPeriod": 0},
                {"NanoCpus": 500000000, "CpuQuota": 50000, "CpuPeriod": 100000}):
            with self.subTest(host_fields=host_fields):
                compose, inventory, runtime, lock, containers = self.fixture()
                compose["services"]["suite-robin"]["cpus"] = 0.5
                host = containers[0]["HostConfig"]
                for key in ("NanoCpus", "CpuQuota", "CpuPeriod"):
                    host.pop(key)
                host.update(host_fields)
                self.assertEqual([], preflight.audit(compose, inventory, runtime, lock,
                                                     containers, "robin1")["failures"])

    def test_unlimited_invalid_mismatched_or_conflicting_deployed_cpu_is_blocked(self):
        for cpu in (
                {}, {"NanoCpus": 0}, {"NanoCpus": 0, "CpuQuota": -1, "CpuPeriod": 100000},
                {"NanoCpus": 0, "CpuQuota": 0, "CpuPeriod": 100000},
                {"NanoCpus": 500000000}, {"NanoCpus": None}, {"NanoCpus": True},
                {"NanoCpus": "1000000000"}, {"NanoCpus": -1},
                {"CpuQuota": 50000, "CpuPeriod": 100000},
                {"CpuQuota": 100000, "CpuPeriod": -1},
                {"CpuQuota": 100000, "CpuPeriod": None},
                {"CpuQuota": -2, "CpuPeriod": 100000},
                {"CpuQuota": True, "CpuPeriod": 100000},
                {"CpuQuota": 100000, "CpuPeriod": 1.5},
                {"NanoCpus": 2 ** 63},
                {"NanoCpus": 1000000000, "CpuQuota": 50000, "CpuPeriod": 100000}):
            with self.subTest(cpu=cpu):
                compose, inventory, runtime, lock, containers = self.fixture()
                host = containers[0]["HostConfig"]
                for key in ("NanoCpus", "CpuQuota", "CpuPeriod"):
                    host.pop(key)
                host.update(cpu)
                report = preflight.audit(compose, inventory, runtime, lock, containers, "robin1")
                self.assertEqual("blocked", report["status"])
                self.assertTrue(any("CPU" in message for message in report["failures"]))

    def test_invalid_model_budgets_do_not_become_runtime_acceptance(self):
        for field, values in (
                ("cpus", (True, "1", 0, -1, float("nan"), float("inf"), 1e-10, 2 ** 63)),
                ("mem_limit", (True, "1g", 1.5, None, 2 ** 63)),
                ("pids_limit", (True, "128", 1.5, None, 2 ** 63))):
            for value in values:
                with self.subTest(field=field, value=value):
                    compose, inventory, runtime, lock, containers = self.fixture()
                    compose["services"]["suite-robin"][field] = value
                    report = preflight.audit(compose, inventory, runtime, lock, containers, "robin1")
                    self.assertEqual("blocked", report["status"])
                    self.assertIn(f"suite-robin: {field} budget is missing/invalid", report["failures"])

    def test_every_deployed_replica_requires_matching_hard_limits(self):
        compose, inventory, runtime, lock, containers = self.fixture()
        containers.insert(0, {**containers[0], "HostConfig": {"Memory": 0, "PidsLimit": -1, "NanoCpus": 0}})
        report = preflight.audit(compose, inventory, runtime, lock, containers, "robin1")
        self.assertEqual("blocked", report["status"])
        self.assertTrue(any("unlimited" in message for message in report["failures"]))

    def test_every_replica_requires_matching_image_and_healthy_unpaused_running_state(self):
        for change in (
                {"Image": "sha256:" + "c" * 64}, {"State": {"Running": False}},
                {"State": {"OOMKilled": True}}, {"State": {"Health": {"Status": "unhealthy"}}},
                {"State": {"Paused": True}}):
            for position in (0, 1):
                with self.subTest(change=change, position=position):
                    compose, inventory, runtime, lock, containers = self.fixture()
                    replica = copy.deepcopy(containers[0])
                    if "State" in change:
                        replica["State"].update(change["State"])
                    else:
                        replica.update(change)
                    containers.insert(position, replica)
                    report = preflight.audit(compose, inventory, runtime, lock, containers, "robin1")
                    self.assertEqual("blocked", report["status"])
                    self.assertTrue(any("deployed image" in message or "runtime" in message
                                        for message in report["failures"]))

    def test_missing_service_blocks_without_replica_evidence(self):
        compose, inventory, runtime, lock, _ = self.fixture()
        report = preflight.audit(compose, inventory, runtime, lock, [], "robin1")
        self.assertEqual("blocked", report["status"])
        self.assertIn("suite-robin: runtime is absent, unhealthy, stopped, paused or OOM-killed",
                      report["failures"])
