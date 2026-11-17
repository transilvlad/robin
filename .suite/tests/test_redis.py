import os
import time
import unittest

from docker_fixture import DockerFixture, run
from test_compose import production_model
from test_rspamd import production_redis


@unittest.skipUnless(os.environ.get("ROBIN_SUITE_DOCKER_TEST") == "1",
                     "Opt in to isolated Docker acceptance")
class RedisDurabilityDockerTest(unittest.TestCase):
    def command(self, fixture, *arguments, **kwargs):
        password = (fixture.root / ".suite" / "secrets" / "redis_password").read_text().strip()
        return run(*fixture.command, "exec", "-T", "redis", "env", f"REDISCLI_AUTH={password}",
                   "redis-cli", "--raw", *arguments, **kwargs).strip()

    def info(self, fixture, section):
        return dict(line.split(":", 1) for line in self.command(fixture, "info", section).splitlines()
                    if ":" in line and not line.startswith("#"))

    def wait_for_aof(self, fixture):
        for _ in range(100):
            state = self.info(fixture, "persistence")
            if (state["aof_enabled"] == "1" and state["aof_rewrite_in_progress"] == "0"
                    and state.get("aof_rewrite_scheduled", "0") == "0"
                    and state["aof_last_bgrewrite_status"] == "ok"
                    and int(state["aof_current_size"]) > 0):
                return
            time.sleep(0.1)
        self.fail("AOF conversion did not complete successfully")

    def test_memory_pressure_preserves_keys_and_aof_survives_abrupt_restart(self):
        with DockerFixture() as fixture:
            production_redis(fixture, production_model(fixture))
            fixture.start()
            self.assertEqual("maxmemory-policy\nnoeviction", self.command(fixture, "config", "get", "maxmemory-policy"))
            self.assertEqual("appendfsync\neverysec", self.command(fixture, "config", "get", "appendfsync"))
            self.assertEqual("OK", self.command(fixture, "set", "fixture:preserved", "yes"))
            used = int(self.info(fixture, "memory")["used_memory"])
            self.assertEqual("OK", self.command(fixture, "config", "set", "maxmemory", str(used + 512 * 1024)))
            for attempt in range(100):
                result = self.command(fixture, "-x", "set", f"fixture:pressure:{attempt}", input="x" * 65536)
                if result.startswith("OOM"):
                    break
                self.assertEqual("OK", result)
            else:
                self.fail("Memory pressure did not produce an explicit write refusal")
            self.assertEqual("yes", self.command(fixture, "get", "fixture:preserved"))
            self.assertEqual("0", self.info(fixture, "stats")["evicted_keys"])
            self.assertEqual("OK", self.command(fixture, "config", "set", "maxmemory", "192mb"))
            self.assertEqual("OK", self.command(fixture, "config", "set", "appendfsync", "always"))
            self.assertEqual("OK", self.command(fixture, "set", "fixture:durable", "after-pressure"))
            self.assertEqual("OK", self.command(fixture, "config", "rewrite"))
            run(*fixture.command, "kill", "-s", "SIGKILL", "redis")
            fixture.start()
            self.assertEqual("yes", self.command(fixture, "get", "fixture:preserved"))
            self.assertEqual("after-pressure", self.command(fixture, "get", "fixture:durable"))
            self.assertEqual("appendfsync\neverysec", self.command(fixture, "config", "get", "appendfsync"))
            self.assertEqual("maxmemory\n201326592", self.command(fixture, "config", "get", "maxmemory"))

    def test_rdb_only_upgrade_refuses_until_explicit_online_aof_conversion(self):
        with DockerFixture() as fixture:
            service = production_redis(fixture, production_model(fixture))
            production = service["command"]
            legacy = ["sh", "-ec",
                      "if [ ! -f /data/redis.conf ]; then "
                      "printf 'dir /data\\nsave 60 1\\nprotected-mode yes\\n' > /data/redis.conf; fi; "
                      "exec redis-server /data/redis.conf --include /run/secrets/redis_auth.conf"]
            service["command"] = legacy
            fixture.start()
            self.assertEqual("OK", self.command(fixture, "set", "fixture:legacy", "preserved"))
            self.assertEqual("OK", self.command(fixture, "save"))
            before = run(*fixture.command, "exec", "-T", "redis", "sha256sum", "/data/dump.rdb")
            run(*fixture.command, "kill", "-s", "SIGKILL", "redis")
            service["command"] = production
            with self.assertRaisesRegex(RuntimeError, "requires verified AOF conversion"):
                fixture.start()
            service["command"] = legacy
            fixture.start()
            self.assertEqual(before, run(*fixture.command, "exec", "-T", "redis", "sha256sum", "/data/dump.rdb"))
            self.assertEqual("preserved", self.command(fixture, "get", "fixture:legacy"))
            self.assertEqual("OK", self.command(fixture, "config", "set", "appendonly", "yes"))
            self.wait_for_aof(fixture)
            self.assertEqual("OK", self.command(fixture, "config", "rewrite"))
            run(*fixture.command, "stop", "redis")
            service["command"] = production
            fixture.start()
            self.assertEqual("preserved", self.command(fixture, "get", "fixture:legacy"))
