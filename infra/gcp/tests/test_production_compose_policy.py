#!/usr/bin/env python3
from __future__ import annotations

import copy
import importlib.util
import json
import os
import pathlib
import subprocess
import tempfile
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[3]
COMPOSE_FILE = ROOT / "infra/gcp/docker-compose.yml"
ENV_FIXTURE = ROOT / "infra/gcp/tests/fixtures/production.env"
VALIDATOR = ROOT / "infra/gcp/scripts/validate-production-compose.py"
LEGACY_ADOPTION_SHA = "8913e5718ac2026ba754083a30e2f4408b726941"


def load_validator():
    spec = importlib.util.spec_from_file_location("gole_compose_policy", VALIDATOR)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def render_model() -> dict:
    environment = dict(os.environ)
    environment.update(
        {
            "GCP_HARD_STOP_ENABLED": "true",
            "GCP_HARD_STOP_DRY_RUN": "false",
            "GCP_HARD_STOP_PERIOD_START": "2026-09-01",
            "GCP_VM_COST_START": "2026-09-01T19:57:05+09:00",
            "GCP_HARD_STOP_AT": "2026-10-28T01:50:00+09:00",
            "GCP_HARD_STOP_ARM_ID": "2026-09-e2-standard-2-ipv4-v3",
            "GOLE_DISCORD_ALERTS_ENABLED": "true",
            "DISCORD_DEPLOY_WEBHOOK_URL": "https://discord.com/api/webhooks/100000000000000001/ABCDEFGHIJKLMNOPQRSTUVWXYZabcdef_1000000001",
            "DISCORD_OPERATIONS_WEBHOOK_URL": "https://discord.com/api/webhooks/100000000000000002/ABCDEFGHIJKLMNOPQRSTUVWXYZabcdef_1000000002",
            "DISCORD_ACCOUNT_WEBHOOK_URL": "https://discord.com/api/webhooks/100000000000000003/ABCDEFGHIJKLMNOPQRSTUVWXYZabcdef_1000000003",
            "DISCORD_PAYMENT_WEBHOOK_URL": "https://discord.com/api/webhooks/100000000000000004/ABCDEFGHIJKLMNOPQRSTUVWXYZabcdef_1000000004",
            "DISCORD_SUPPORT_WEBHOOK_URL": "https://discord.com/api/webhooks/100000000000000002/ABCDEFGHIJKLMNOPQRSTUVWXYZabcdef_1000000002",
            "DISCORD_SUPPRESS_NOTIFICATIONS": "false",
        }
    )
    environment["GOLE_APP_ENV_FILE"] = str(ENV_FIXTURE)
    environment["GOLE_INFRA_ENV_FILE"] = "/dev/null"
    result = subprocess.run(
        [
            "docker",
            "compose",
            "--env-file",
            "/dev/null",
            "--env-file",
            str(ENV_FIXTURE),
            "-f",
            str(COMPOSE_FILE),
            "--profile",
            "certificate",
            "config",
            "--format",
            "json",
        ],
        check=True,
        capture_output=True,
        env=environment,
        text=True,
    )
    model = json.loads(result.stdout)
    for service in model["services"].values():
        build = service.get("build")
        if not build:
            continue
        context = pathlib.Path(build["context"])
        build["context"] = str(pathlib.Path("/app") / context.relative_to(ROOT))
    return model


def render_historical_adoption_model() -> tuple[dict, str]:
    source = subprocess.run(
        [
            "git",
            "show",
            f"{LEGACY_ADOPTION_SHA}:infra/gcp/docker-compose.yml",
        ],
        cwd=ROOT,
        check=True,
        capture_output=True,
        text=True,
    ).stdout
    environment = dict(os.environ)
    environment.update(
        {
            "GOLE_APP_ENV_FILE": str(ENV_FIXTURE),
            "GOLE_INFRA_ENV_FILE": "/dev/null",
            # Match the already-running legacy frontend exactly. The adoption
            # candidate advances this to disabled without rebuilding the LKG.
            "NEXT_PUBLIC_PAYMENT_MODE": "portone-test",
        }
    )
    # Keep the temporary file beside the historical path so Compose resolves
    # its ../../ build contexts exactly as it did on the production checkout.
    with tempfile.NamedTemporaryFile(
        "w", suffix=".yml", dir=COMPOSE_FILE.parent
    ) as compose_file:
        compose_file.write(source)
        compose_file.flush()
        result = subprocess.run(
            [
                "docker",
                "compose",
                "--env-file",
                "/dev/null",
                "--env-file",
                str(ENV_FIXTURE),
                "-f",
                compose_file.name,
                "--profile",
                "certificate",
                "config",
                "--format",
                "json",
            ],
            check=True,
            capture_output=True,
            env=environment,
            text=True,
        )
    model = json.loads(result.stdout)
    release_root = f"/var/lib/gole/releases/{LEGACY_ADOPTION_SHA}"
    for service in model["services"].values():
        build = service.get("build")
        if not build:
            continue
        context = pathlib.Path(build["context"])
        build["context"] = str(
            pathlib.Path(release_root) / context.relative_to(ROOT)
        )
    return model, release_root


class ProductionComposePolicyTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.validator = load_validator()
        cls.model = render_model()

    def test_accepts_fixed_privilege_and_network_model(self) -> None:
        self.validator.validate(copy.deepcopy(self.model))
        self.assertEqual(
            set(self.model["services"]["support-agent"]["networks"]),
            {"agent"},
        )
        self.assertEqual(
            set(self.model["services"]["backend"]["networks"]),
            {"agent", "data", "edge"},
        )
        self.assertTrue(self.model["networks"]["agent"]["internal"])

    def test_rejects_missing_or_invalid_discord_overlay(self) -> None:
        for key, value in (
            ("DISCORD_ACCOUNT_WEBHOOK_URL", ""),
            ("DISCORD_SUPPORT_WEBHOOK_URL", "https://attacker.test/api/webhooks/1/token"),
            ("DISCORD_SUPPRESS_NOTIFICATIONS", "maybe"),
            ("GOLE_DISCORD_ALERTS_ENABLED", "false"),
        ):
            with self.subTest(key=key):
                model = copy.deepcopy(self.model)
                model["services"]["backend"]["environment"][key] = value
                with self.assertRaises(self.validator.ComposePolicyError):
                    self.validator.validate(model)

    def test_rejects_budget_and_backend_operations_route_mismatch(self) -> None:
        model = copy.deepcopy(self.model)
        model["services"]["budget-relay"]["environment"][
            "DISCORD_OPERATIONS_WEBHOOK_URL"
        ] = "https://discord.com/api/webhooks/100000000000000099/ABCDEFGHIJKLMNOPQRSTUVWXYZabcdef_1000000099"
        with self.assertRaises(self.validator.ComposePolicyError):
            self.validator.validate(model)

    def test_empty_host_validation_can_explicitly_allow_missing_overlay(self) -> None:
        model = copy.deepcopy(self.model)
        for key in (
            "DISCORD_DEPLOY_WEBHOOK_URL",
            "DISCORD_OPERATIONS_WEBHOOK_URL",
            "DISCORD_ACCOUNT_WEBHOOK_URL",
            "DISCORD_PAYMENT_WEBHOOK_URL",
            "DISCORD_SUPPORT_WEBHOOK_URL",
        ):
            model["services"]["backend"]["environment"][key] = ""
        model["services"]["backend"]["environment"]["GOLE_DISCORD_ALERTS_ENABLED"] = "false"
        model["services"]["budget-relay"]["environment"][
            "DISCORD_OPERATIONS_WEBHOOK_URL"
        ] = ""
        self.validator.validate(model, allow_missing_discord_overlay=True)

    def test_accepts_only_the_exact_root_owned_release_build_context(self) -> None:
        release_root = "/var/lib/gole/releases/" + "a" * 40
        model = copy.deepcopy(self.model)
        for service in model["services"].values():
            build = service.get("build")
            if build:
                build["context"] = build["context"].replace("/app", release_root, 1)
        self.validator.validate(model, release_root=release_root)
        model["services"]["backend"]["build"]["context"] = "/app"
        with self.assertRaises(self.validator.ComposePolicyError):
            self.validator.validate(model, release_root=release_root)

    def test_rejects_data_service_host_publishing(self) -> None:
        for service, target in (("mongo", 27017), ("redis", 6379), ("minio", 9000)):
            with self.subTest(service=service):
                model = copy.deepcopy(self.model)
                model["services"][service]["ports"] = [
                    {
                        "host_ip": "127.0.0.1",
                        "published": str(target),
                        "target": target,
                        "protocol": "tcp",
                    }
                ]
                with self.assertRaises(self.validator.ComposePolicyError):
                    self.validator.validate(model)

    def test_rejects_persistent_volume_name_driver_or_options_drift(self) -> None:
        mutations = []
        renamed = copy.deepcopy(self.model)
        renamed["volumes"]["mongo-data"]["name"] = "gole_mongo-data-v2"
        mutations.append(renamed)
        foreign_driver = copy.deepcopy(self.model)
        foreign_driver["volumes"]["redis-data"]["driver"] = "nfs"
        mutations.append(foreign_driver)
        labeled = copy.deepcopy(self.model)
        labeled["volumes"]["minio-data"]["labels"] = {"owner": "other"}
        mutations.append(labeled)
        for model in mutations:
            with self.assertRaises(self.validator.ComposePolicyError):
                self.validator.validate(model)

    def test_only_lkg_mode_accepts_changed_external_digest_in_same_repository(self) -> None:
        model = copy.deepcopy(self.model)
        replacement = "a" * 64
        model["services"]["mongo"]["image"] = f"mongo:7@sha256:{replacement}"
        model["services"]["mongo-init"]["image"] = f"mongo:7@sha256:{replacement}"
        with self.assertRaises(self.validator.ComposePolicyError):
            self.validator.validate(model)
        self.validator.validate(model, allow_lkg_image_pins=True)
        model["services"]["mongo"]["image"] = f"attacker/mongo:7@sha256:{replacement}"
        with self.assertRaises(self.validator.ComposePolicyError):
            self.validator.validate(model, allow_lkg_image_pins=True)

    def test_rejects_edge_service_data_network_membership(self) -> None:
        for service in ("frontend", "nginx", "budget-relay"):
            with self.subTest(service=service):
                model = copy.deepcopy(self.model)
                model["services"][service]["networks"]["data"] = None
                with self.assertRaises(self.validator.ComposePolicyError):
                    self.validator.validate(model)

    def test_accepts_only_empty_service_network_attachments(self) -> None:
        model = copy.deepcopy(self.model)
        model["services"]["budget-relay"]["networks"]["edge"] = {}
        model["services"]["backend"]["networks"]["agent"] = {}
        self.validator.validate(model)

    def test_rejects_profiles_on_core_services(self) -> None:
        for service in ("backend", "nginx"):
            with self.subTest(service=service):
                model = copy.deepcopy(self.model)
                model["services"][service]["profiles"] = ["certificate"]
                with self.assertRaises(self.validator.ComposePolicyError):
                    self.validator.validate(model)

    def test_rejects_unexpected_certbot_profile(self) -> None:
        model = copy.deepcopy(self.model)
        model["services"]["certbot"]["profiles"] = [
            "certificate",
            "unexpected",
        ]
        with self.assertRaises(self.validator.ComposePolicyError):
            self.validator.validate(model)

    def test_rejects_disabled_restart_for_long_running_services(self) -> None:
        for service in ("backend", "budget-relay", "nginx"):
            with self.subTest(service=service):
                model = copy.deepcopy(self.model)
                model["services"][service]["restart"] = "no"
                with self.assertRaises(self.validator.ComposePolicyError):
                    self.validator.validate(model)

    def test_rejects_restart_for_one_shot_services(self) -> None:
        for service in ("mongo-init", "minio-init", "certbot"):
            with self.subTest(service=service):
                model = copy.deepcopy(self.model)
                model["services"][service]["restart"] = "unless-stopped"
                with self.assertRaises(self.validator.ComposePolicyError):
                    self.validator.validate(model)

    def test_accepts_omitted_restart_as_docker_no_for_one_shot_services(self) -> None:
        model = copy.deepcopy(self.model)
        for service in ("mongo-init", "minio-init", "certbot"):
            model["services"][service].pop("restart", None)
        self.validator.validate(model)

    def test_rejects_bypassed_or_changed_healthchecks(self) -> None:
        mutations = []
        always_healthy = copy.deepcopy(self.model)
        always_healthy["services"]["backend"]["healthcheck"]["test"] = [
            "CMD",
            "true",
        ]
        mutations.append(always_healthy)
        disabled_guard = copy.deepcopy(self.model)
        disabled_guard["services"]["budget-relay"]["healthcheck"] = {
            "disable": True
        }
        mutations.append(disabled_guard)
        missing_proxy_check = copy.deepcopy(self.model)
        del missing_proxy_check["services"]["nginx"]["healthcheck"]
        mutations.append(missing_proxy_check)
        tcp_only_agent_check = copy.deepcopy(self.model)
        tcp_only_agent_check["services"]["support-agent"]["healthcheck"]["test"] = [
            "CMD",
            "python",
            "-c",
            "import socket; socket.create_connection(('127.0.0.1', 50051), 2).close()",
        ]
        mutations.append(tcp_only_agent_check)
        for model in mutations:
            with self.assertRaises(self.validator.ComposePolicyError):
                self.validator.validate(model)

    def test_rejects_support_agent_process_overrides(self) -> None:
        fake_entrypoint = copy.deepcopy(self.model)
        fake_entrypoint["services"]["support-agent"]["entrypoint"] = [
            "python",
            "-c",
            "import socket,time; s=socket.socket(); s.bind(('0.0.0.0',50051)); s.listen(); time.sleep(999999)",
        ]
        fake_command = copy.deepcopy(self.model)
        fake_command["services"]["support-agent"]["command"] = ["sleep", "infinity"]
        for model in (fake_entrypoint, fake_command):
            with self.assertRaises(self.validator.ComposePolicyError):
                self.validator.validate(model)

    def test_rejects_changed_service_resource_contract(self) -> None:
        mutations = []
        for service in self.validator.EXPECTED_CPUS:
            unbounded_cpu = copy.deepcopy(self.model)
            del unbounded_cpu["services"][service]["cpus"]
            mutations.append(unbounded_cpu)
            unbounded_memory = copy.deepcopy(self.model)
            del unbounded_memory["services"][service]["mem_limit"]
            mutations.append(unbounded_memory)
        throttled_backend = copy.deepcopy(self.model)
        throttled_backend["services"]["backend"]["cpus"] = 0.01
        mutations.append(throttled_backend)
        oversized_backend = copy.deepcopy(self.model)
        oversized_backend["services"]["backend"]["mem_limit"] = "7516192768"
        mutations.append(oversized_backend)
        for model in mutations:
            with self.assertRaises(self.validator.ComposePolicyError):
                self.validator.validate(model)

    def test_accepts_exact_historical_adoption_without_resource_limits(self) -> None:
        model, release_root = render_historical_adoption_model()
        self.assertNotIn("support-agent", model["services"])
        for service in model["services"].values():
            self.assertNotIn("cpus", service)
            self.assertNotIn("mem_limit", service)
        self.validator.validate(
            copy.deepcopy(model),
            allow_legacy_adoption=True,
            release_root=release_root,
        )

        disabled_candidate = copy.deepcopy(model)
        disabled_candidate["services"]["frontend"]["build"]["args"][
            "NEXT_PUBLIC_PAYMENT_MODE"
        ] = "disabled"
        self.validator.validate(
            disabled_candidate,
            allow_legacy_adoption=True,
            release_root=release_root,
        )

        unexpected_payment_mode = copy.deepcopy(model)
        unexpected_payment_mode["services"]["frontend"]["build"]["args"][
            "NEXT_PUBLIC_PAYMENT_MODE"
        ] = "stub"
        with self.assertRaises(self.validator.ComposePolicyError):
            self.validator.validate(
                unexpected_payment_mode,
                allow_legacy_adoption=True,
                release_root=release_root,
            )

        model["services"]["backend"]["cpus"] = 1.5
        with self.assertRaises(self.validator.ComposePolicyError):
            self.validator.validate(
                model,
                allow_legacy_adoption=True,
                release_root=release_root,
            )

    def test_strict_mode_never_accepts_legacy_payment_mode(self) -> None:
        model = copy.deepcopy(self.model)
        model["services"]["frontend"]["build"]["args"][
            "NEXT_PUBLIC_PAYMENT_MODE"
        ] = "portone-test"
        with self.assertRaises(self.validator.ComposePolicyError):
            self.validator.validate(model)

    def test_rejects_per_service_network_attachment_options(self) -> None:
        mutations = (
            ("budget-relay", "edge", {"aliases": ["backend"]}),
            ("backend", "edge", {"gw_priority": 1}),
            ("backend", "edge", {"ipv4_address": "172.30.0.10"}),
            ("backend", "edge", {"ipv6_address": "fd00::10"}),
            ("backend", "edge", {"link_local_ips": ["169.254.10.10"]}),
            ("backend", "edge", {"mac_address": "02:42:ac:1e:00:0a"}),
            ("backend", "edge", {"priority": 1000}),
            ("backend", "edge", {"driver_opts": {"com.docker.network.endpoint.sysctls": "x"}}),
            ("backend", "edge", {"future_attachment_option": True}),
        )
        for service, network, attachment in mutations:
            with self.subTest(service=service, network=network, attachment=attachment):
                model = copy.deepcopy(self.model)
                model["services"][service]["networks"][network] = attachment
                with self.assertRaises(self.validator.ComposePolicyError):
                    self.validator.validate(model)

    def test_rejects_agent_data_access_or_relaxed_internal_network(self) -> None:
        support_data = copy.deepcopy(self.model)
        support_data["services"]["support-agent"]["networks"]["data"] = None
        backend_without_agent = copy.deepcopy(self.model)
        del backend_without_agent["services"]["backend"]["networks"]["agent"]
        external_agent = copy.deepcopy(self.model)
        external_agent["networks"]["agent"] = {
            "name": "shared_agent",
            "external": True,
        }
        public_agent = copy.deepcopy(self.model)
        public_agent["networks"]["agent"]["internal"] = False
        for model in (
            support_data,
            backend_without_agent,
            external_agent,
            public_agent,
        ):
            with self.assertRaises(self.validator.ComposePolicyError):
                self.validator.validate(model)

    def test_rejects_network_driver_or_unreviewed_top_level_options(self) -> None:
        mutations = []
        host_driver = copy.deepcopy(self.model)
        host_driver["networks"]["edge"]["driver"] = "host"
        mutations.append(host_driver)
        attachable = copy.deepcopy(self.model)
        attachable["networks"]["data"]["attachable"] = True
        mutations.append(attachable)
        custom_ipam = copy.deepcopy(self.model)
        custom_ipam["networks"]["agent"]["ipam"] = {
            "config": [{"subnet": "169.254.0.0/16"}]
        }
        mutations.append(custom_ipam)
        for model in mutations:
            with self.assertRaises(self.validator.ComposePolicyError):
                self.validator.validate(model)

    def test_rejects_docker_escape_settings(self) -> None:
        mutations = []
        privileged = copy.deepcopy(self.model)
        privileged["services"]["backend"]["privileged"] = True
        mutations.append(privileged)
        socket_mount = copy.deepcopy(self.model)
        socket_mount["services"]["backend"]["volumes"] = [
            {
                "type": "bind",
                "source": "/var/run/docker.sock",
                "target": "/var/run/docker.sock",
            }
        ]
        mutations.append(socket_mount)
        host_network = copy.deepcopy(self.model)
        host_network["services"]["backend"]["network_mode"] = "host"
        mutations.append(host_network)
        api_socket = copy.deepcopy(self.model)
        api_socket["services"]["backend"]["use_api_socket"] = True
        mutations.append(api_socket)
        future_engine_capability = copy.deepcopy(self.model)
        future_engine_capability["services"]["backend"]["provider"] = {
            "type": "attacker"
        }
        mutations.append(future_engine_capability)
        for model in mutations:
            with self.assertRaises(self.validator.ComposePolicyError):
                self.validator.validate(model)

    def test_rejects_missing_or_redirected_cloud_broker_socket(self) -> None:
        for mutation in ("environment", "mount"):
            with self.subTest(mutation=mutation):
                model = copy.deepcopy(self.model)
                if mutation == "environment":
                    model["services"]["budget-relay"]["environment"][
                        "GOLE_CLOUD_BROKER_SOCKET"
                    ] = "/tmp/fake.sock"
                else:
                    model["services"]["budget-relay"]["volumes"] = [
                        mount
                        for mount in model["services"]["budget-relay"]["volumes"]
                        if mount.get("target") != "/run/gole-cloud-broker"
                    ]
                with self.assertRaises(self.validator.ComposePolicyError):
                    self.validator.validate(model)

    def test_accepts_compose_2_bind_option_rendering_without_weakening_mounts(self) -> None:
        model = copy.deepcopy(self.model)
        for service in model["services"].values():
            for mount in service.get("volumes", []):
                if mount.get("type") != "bind":
                    continue
                if mount.get("target") == "/run/gole-cloud-broker":
                    # Compose 2.38 omits the explicitly configured false.
                    mount["bind"] = {}
                else:
                    # The same release materializes the short-syntax default.
                    mount["bind"] = {"create_host_path": True}
        self.validator.validate(model)

    def test_rejects_unsafe_or_changed_cloud_broker_mounts_across_renderers(self) -> None:
        mutations = []

        create_host_path = copy.deepcopy(self.model)
        for mount in create_host_path["services"]["budget-relay"]["volumes"]:
            if mount.get("target") == "/run/gole-cloud-broker":
                mount["bind"] = {"create_host_path": True}
        mutations.append(create_host_path)

        missing_bind_options = copy.deepcopy(self.model)
        for mount in missing_bind_options["services"]["budget-relay"]["volumes"]:
            if mount.get("target") == "/run/gole-cloud-broker":
                mount.pop("bind", None)
        mutations.append(missing_bind_options)

        writable = copy.deepcopy(self.model)
        for mount in writable["services"]["budget-relay"]["volumes"]:
            if mount.get("target") == "/run/gole-cloud-broker":
                mount["read_only"] = False
        mutations.append(writable)

        redirected = copy.deepcopy(self.model)
        for mount in redirected["services"]["budget-relay"]["volumes"]:
            if mount.get("target") == "/run/gole-cloud-broker":
                mount["source"] = "/tmp/attacker-broker"
        mutations.append(redirected)

        foreign = copy.deepcopy(self.model)
        foreign["services"]["budget-relay"]["volumes"].append(
            {
                "type": "bind",
                "source": "/tmp/foreign",
                "target": "/tmp/foreign",
                "read_only": True,
                "bind": {"create_host_path": True},
            }
        )
        mutations.append(foreign)

        for model in mutations:
            with self.assertRaises(self.validator.ComposePolicyError):
                self.validator.validate(model)

    def test_rejects_relaxed_auth_or_snapshot_cost_policy(self) -> None:
        mutations = []
        auth = copy.deepcopy(self.model)
        auth["services"]["backend"]["environment"][
            "GOLE_AUTH_OAUTH_GLOBAL_DAILY_MAXIMUM"
        ] = "999999"
        mutations.append(auth)
        snapshot = copy.deepcopy(self.model)
        snapshot["services"]["budget-relay"]["environment"][
            "GCP_SNAPSHOT_MAX_HOURLY_COST_KRW"
        ] = "0"
        mutations.append(snapshot)
        fixed_cost = copy.deepcopy(self.model)
        fixed_cost["services"]["budget-relay"]["environment"][
            "GCP_FIXED_HOURLY_COST_KRW"
        ] = "1"
        mutations.append(fixed_cost)
        for model in mutations:
            with self.assertRaises(self.validator.ComposePolicyError):
                self.validator.validate(model)

    def test_rejects_changed_launch_payment_and_settlement_policy(self) -> None:
        for key, value in (
            ("GOLE_ENVIRONMENT", "development"),
            ("GOLE_TERMS_VERSION", "2026-09-03"),
            ("GOLE_PRIVACY_VERSION", "2026-09-04"),
            ("PORTONE_ENABLED", "true"),
            ("GOLE_SETTLEMENT_MODE", "AUTOMATIC"),
            ("GOLE_SETTLEMENT_PAYOUT_CONTRACT_VERIFIED", "true"),
        ):
            with self.subTest(key=key):
                model = copy.deepcopy(self.model)
                model["services"]["backend"]["environment"][key] = value
                with self.assertRaises(self.validator.ComposePolicyError):
                    self.validator.validate(model)

    def test_rejects_credentialed_stage_zero_mail_while_latch_stays_off(self) -> None:
        for key, value in (
            ("GOLE_MAIL_HEALTH_ENABLED", "true"),
            ("SMTP_USERNAME", "private-mailbox@example.test"),
            ("SMTP_PASSWORD", "do-not-print-this-app-password"),
            ("GOLE_VERIFICATION_EMAIL_FROM", "private-sender@example.test"),
        ):
            with self.subTest(key=key):
                model = copy.deepcopy(self.model)
                model["services"]["backend"]["environment"][key] = value
                with self.assertRaisesRegex(self.validator.ComposePolicyError, key):
                    self.validator.validate(model)

    def test_rejects_email_latch_with_invalid_or_partial_identity(self) -> None:
        # Neither "true" nor "false" is rejected outright.
        model = copy.deepcopy(self.model)
        model["services"]["backend"]["environment"]["GOLE_VERIFICATION_EMAIL_ENABLED"] = "TRUE"
        with self.assertRaisesRegex(
            self.validator.ComposePolicyError, "GOLE_VERIFICATION_EMAIL_ENABLED"
        ):
            self.validator.validate(model)

        # Enabling without a full identity fails closed.
        for missing_key in ("SMTP_USERNAME", "SMTP_PASSWORD", "GOLE_VERIFICATION_EMAIL_FROM"):
            with self.subTest(missing=missing_key):
                model = copy.deepcopy(self.model)
                model["services"]["backend"]["environment"].update(
                    {
                        "GOLE_VERIFICATION_EMAIL_ENABLED": "true",
                        "SMTP_USERNAME": "verified-mailbox@example.test",
                        "SMTP_PASSWORD": "verified-app-password",
                        "GOLE_VERIFICATION_EMAIL_FROM": "verified-sender@example.test",
                    }
                )
                model["services"]["backend"]["environment"][missing_key] = ""
                with self.assertRaisesRegex(self.validator.ComposePolicyError, missing_key):
                    self.validator.validate(model)

    def test_accepts_email_latch_enabled_with_a_full_identity(self) -> None:
        model = copy.deepcopy(self.model)
        model["services"]["backend"]["environment"].update(
            {
                "GOLE_VERIFICATION_EMAIL_ENABLED": "true",
                "SMTP_USERNAME": "verified-mailbox@example.test",
                "SMTP_PASSWORD": "verified-app-password",
                "GOLE_VERIFICATION_EMAIL_FROM": "verified-sender@example.test",
            }
        )
        self.validator.validate(model)

    def test_rejects_missing_or_unbounded_log_rotation(self) -> None:
        for service in self.model["services"]:
            with self.subTest(service=service):
                model = copy.deepcopy(self.model)
                model["services"][service]["logging"] = {
                    "driver": "json-file",
                    "options": {"max-size": "1g"},
                }
                with self.assertRaises(self.validator.ComposePolicyError):
                    self.validator.validate(model)

    def test_rejects_public_minio_bucket_policy(self) -> None:
        model = copy.deepcopy(self.model)
        command = model["services"]["minio-init"]["entrypoint"]
        model["services"]["minio-init"]["entrypoint"] = [
            str(item).replace(
                "mc anonymous set none local/gole",
                "mc anonymous set download local/gole",
            )
            for item in command
        ]
        with self.assertRaises(self.validator.ComposePolicyError):
            self.validator.validate(model)

    def test_legacy_mode_only_accepts_known_loopback_model(self) -> None:
        model = copy.deepcopy(self.model)
        del model["services"]["support-agent"]
        del model["services"]["certbot"]
        for service in model["services"].values():
            service["networks"] = {"default": None}
            service.pop("cpus", None)
            service.pop("mem_limit", None)
        model["networks"] = {"default": {"name": "gole_default"}}
        model["services"]["mongo"]["ports"] = [
            {"host_ip": "127.0.0.1", "published": "27017", "target": 27017, "protocol": "tcp"}
        ]
        model["services"]["redis"]["ports"] = [
            {"host_ip": "127.0.0.1", "published": "6379", "target": 6379, "protocol": "tcp"}
        ]
        model["services"]["minio"]["ports"] = [
            {"host_ip": "127.0.0.1", "published": "9000", "target": 9000, "protocol": "tcp"},
            {"host_ip": "127.0.0.1", "published": "9001", "target": 9001, "protocol": "tcp"},
        ]
        for service, image in self.validator.LEGACY_ADOPTION_IMAGES.items():
            if service in model["services"]:
                model["services"][service]["image"] = image
        model["services"]["budget-relay"]["volumes"] = [
            mount
            for mount in model["services"]["budget-relay"]["volumes"]
            if mount.get("target") != "/run/gole-cloud-broker"
        ]
        model["services"]["nginx"].pop("healthcheck", None)
        for key in ("NEXT_PUBLIC_GA_MEASUREMENT_ID", "NEXT_PUBLIC_GTM_ID"):
            model["services"]["frontend"]["build"]["args"].pop(key)
        self.validator.validate(model, allow_legacy_adoption=True)
        model["services"]["mongo"]["ports"][0]["host_ip"] = "0.0.0.0"
        with self.assertRaises(self.validator.ComposePolicyError):
            self.validator.validate(model, allow_legacy_adoption=True)


if __name__ == "__main__":
    unittest.main()
