#!/usr/bin/env python3
"""Reject production Compose models that could escape the fixed Docker helper."""

from __future__ import annotations

import json
import pathlib
import re
import sys
from typing import Any


class ComposePolicyError(ValueError):
    """Rendered production Compose model violates the host privilege boundary."""


EXPECTED_SERVICES = {
    "backend",
    "budget-relay",
    "certbot",
    "frontend",
    "minio",
    "minio-init",
    "mongo",
    "mongo-init",
    "nginx",
    "redis",
    "support-agent",
}

EXPECTED_CONTAINER_NAMES = {
    "backend": "gole-backend",
    "budget-relay": "gole-budget-relay",
    "frontend": "gole-frontend",
    "minio": "gole-minio",
    "mongo": "gole-mongo",
    "nginx": "gole-nginx",
    "redis": "gole-redis",
    "support-agent": "gole-support-agent",
}

EXPECTED_IMAGES = {
    "backend": "gole/backend:local",
    "budget-relay": "gole/budget-relay:local",
    "certbot": (
        "certbot/certbot:latest@sha256:"
        "f70ad0adbb7e117f0fe42a63c553f28ea451edabc0148757b6efcd9735acaa20"
    ),
    "frontend": "gole/frontend:local",
    "minio": (
        "minio/minio@sha256:"
        "14cea493d9a34af32f524e538b8346cf79f3321eff8e708c1e2960462bd8936e"
    ),
    "minio-init": (
        "minio/mc:latest@sha256:"
        "a7fe349ef4bd8521fb8497f55c6042871b2ae640607cf99d9bede5e9bdf11727"
    ),
    "mongo": (
        "mongo:7@sha256:"
        "b6421fd6d1c5ded6377b397d8983e2f82e2100dc5123332dcfda2065a472be5b"
    ),
    "mongo-init": (
        "mongo:7@sha256:"
        "b6421fd6d1c5ded6377b397d8983e2f82e2100dc5123332dcfda2065a472be5b"
    ),
    "nginx": (
        "nginx:1.29-alpine@sha256:"
        "5616878291a2eed594aee8db4dade5878cf7edcb475e59193904b198d9b830de"
    ),
    "redis": (
        "redis:7-alpine@sha256:"
        "ff02b58f971e7d7d156a1267e283fcbbeee91773b6aa36c49dac28ecfe28eadf"
    ),
    "support-agent": "gole/support-agent:local",
}

LKG_PINNED_IMAGE_PATTERNS = {
    "certbot": r"certbot/certbot:latest@sha256:[0-9a-f]{64}",
    "minio": r"minio/minio@sha256:[0-9a-f]{64}",
    "minio-init": r"minio/mc:latest@sha256:[0-9a-f]{64}",
    "mongo": r"mongo:7@sha256:[0-9a-f]{64}",
    "mongo-init": r"mongo:7@sha256:[0-9a-f]{64}",
    "nginx": r"nginx:1\.29-alpine@sha256:[0-9a-f]{64}",
    "redis": r"redis:7-alpine@sha256:[0-9a-f]{64}",
}

LEGACY_ADOPTION_IMAGES = {
    **EXPECTED_IMAGES,
    "certbot": "certbot/certbot:latest",
    "minio-init": "minio/mc:latest",
    "mongo": "mongo:7",
    "mongo-init": "mongo:7",
    "nginx": "nginx:1.29-alpine",
    "redis": "redis:7-alpine",
}

EXPECTED_DOCKERFILES = {
    "backend": ("", "infra/gcp/docker/api.Dockerfile"),
    "budget-relay": ("infra/gcp/budget-relay", "Dockerfile"),
    "frontend": ("", "infra/gcp/docker/web.Dockerfile"),
    "support-agent": ("", "apps/support-agent/Dockerfile"),
}

EXPECTED_PORTS = {
    "backend": {("127.0.0.1", "8080", 8080, "tcp")},
    "frontend": {("127.0.0.1", "3000", 3000, "tcp")},
    "nginx": {(None, "80", 80, "tcp"), (None, "443", 443, "tcp")},
}

LEGACY_ADOPTION_PORTS = {
    **EXPECTED_PORTS,
    "minio": {
        ("127.0.0.1", "9000", 9000, "tcp"),
        ("127.0.0.1", "9001", 9001, "tcp"),
    },
    "mongo": {("127.0.0.1", "27017", 27017, "tcp")},
    "redis": {("127.0.0.1", "6379", 6379, "tcp")},
}

EXPECTED_SERVICE_NETWORKS = {
    "backend": {"agent", "data", "edge"},
    "budget-relay": {"edge"},
    "certbot": {"edge"},
    "frontend": {"edge"},
    "minio": {"data"},
    "minio-init": {"data"},
    "mongo": {"data"},
    "mongo-init": {"data"},
    "nginx": {"edge"},
    "redis": {"data"},
    "support-agent": {"agent"},
}

EXPECTED_NETWORK_NAMES = {
    "agent": "gole_agent",
    "data": "gole_data",
    "edge": "gole_edge",
}

EXPECTED_VOLUME_NAMES = {
    "budget-relay-state": "gole_budget-relay-state",
    "certbot-webroot": "gole_certbot-webroot",
    "letsencrypt": "gole_letsencrypt",
    "minio-data": "gole_minio-data",
    "mongo-data": "gole_mongo-data",
    "redis-data": "gole_redis-data",
}

EXPECTED_LOGGING = {
    "driver": "local",
    "options": {"max-file": "3", "max-size": "10m"},
}

EXPECTED_RESTART_POLICIES = {
    "backend": "unless-stopped",
    "budget-relay": "unless-stopped",
    "certbot": "no",
    "frontend": "unless-stopped",
    "minio": "unless-stopped",
    "minio-init": "no",
    "mongo": "unless-stopped",
    "mongo-init": "no",
    "nginx": "unless-stopped",
    "redis": "unless-stopped",
    "support-agent": "unless-stopped",
}

EXPECTED_HEALTHCHECKS = {
    "backend": {
        "test": [
            "CMD",
            "curl",
            "-fsS",
            "http://localhost:8080/actuator/health/readiness",
        ],
        "timeout": "5s",
        "interval": "10s",
        "retries": 30,
        "start_period": "30s",
    },
    "budget-relay": {
        "test": [
            "CMD",
            "python",
            "-c",
            "import os,time; p='/tmp/gole-cost-guard-heartbeat'; "
            "raise SystemExit(0 if os.path.exists(p) and "
            "time.time()-os.path.getmtime(p)<35 else 1)",
        ],
        "timeout": "3s",
        "interval": "10s",
        "retries": 3,
        "start_period": "20s",
    },
    "frontend": {
        "test": [
            "CMD",
            "node",
            "-e",
            "fetch('http://localhost:3000/icon.svg').then(r=>{if(!r.ok)"
            "process.exit(1)}).catch(()=>process.exit(1))",
        ],
        "timeout": "5s",
        "interval": "10s",
        "retries": 20,
        "start_period": "20s",
    },
    "minio": {
        "test": ["CMD", "curl", "-f", "http://localhost:9000/minio/health/live"],
        "timeout": "5s",
        "interval": "10s",
        "retries": 20,
    },
    "mongo": {
        "test": [
            "CMD",
            "mongosh",
            "--quiet",
            "--eval",
            "db.adminCommand('ping').ok",
        ],
        "timeout": "5s",
        "interval": "10s",
        "retries": 20,
    },
    "nginx": {
        "test": ["CMD", "nginx", "-t"],
        "timeout": "5s",
        "interval": "15s",
        "retries": 3,
    },
    "redis": {
        "test": ["CMD", "redis-cli", "ping"],
        "timeout": "5s",
        "interval": "10s",
        "retries": 20,
    },
    "support-agent": {
        "test": [
            "CMD",
            "python",
            "-m",
            "gole_support_agent.healthcheck",
        ],
        "timeout": "3s",
        "interval": "10s",
        "retries": 12,
        "start_period": "10s",
    },
}

EXPECTED_CPUS = {
    "mongo": 1.0,
    "mongo-init": 0.25,
    "redis": 0.5,
    "minio": 0.75,
    "minio-init": 0.25,
    "support-agent": 0.25,
    "backend": 1.5,
    "budget-relay": 0.25,
    "frontend": 0.75,
    "nginx": 0.5,
    "certbot": 0.5,
}
EXPECTED_MEMORY_LIMITS = {
    "mongo": "1879048192",
    "mongo-init": "268435456",
    "redis": "402653184",
    "minio": "805306368",
    "minio-init": "134217728",
    "support-agent": "201326592",
    "backend": "2147483648",
    "budget-relay": "134217728",
    "frontend": "671088640",
    "nginx": "201326592",
    "certbot": "268435456",
}

EXPECTED_ENVIRONMENT_VALUES = {
    "backend": {
        "GOLE_ENVIRONMENT": "production",
        # GOLE_VERIFICATION_EMAIL_ENABLED and the SMTP identity it gates are
        # validated conditionally below, not as a fixed exact value.
        "GOLE_MAIL_HEALTH_ENABLED": "false",
        "PORTONE_ENABLED": "false",
        "GOLE_SETTLEMENT_MODE": "DISABLED",
        "GOLE_SETTLEMENT_PAYOUT_CONTRACT_VERIFIED": "false",
        "GOLE_AUTH_EMAIL_RECIPIENT_COOLDOWN_MAXIMUM": "1",
        "GOLE_AUTH_EMAIL_RECIPIENT_COOLDOWN_WINDOW": "PT1M",
        "GOLE_AUTH_EMAIL_RECIPIENT_DAILY_MAXIMUM": "8",
        "GOLE_AUTH_EMAIL_RECIPIENT_DAILY_WINDOW": "P1D",
        "GOLE_AUTH_EMAIL_CLIENT_BURST_MAXIMUM": "5",
        "GOLE_AUTH_EMAIL_CLIENT_BURST_WINDOW": "PT1M",
        "GOLE_AUTH_EMAIL_CLIENT_HOURLY_MAXIMUM": "30",
        "GOLE_AUTH_EMAIL_CLIENT_HOURLY_WINDOW": "PT1H",
        "GOLE_AUTH_EMAIL_GLOBAL_BURST_MAXIMUM": "60",
        "GOLE_AUTH_EMAIL_GLOBAL_BURST_WINDOW": "PT1M",
        "GOLE_AUTH_EMAIL_GLOBAL_DAILY_MAXIMUM": "300",
        "GOLE_AUTH_EMAIL_GLOBAL_DAILY_WINDOW": "P1D",
        "GOLE_AUTH_OAUTH_CLIENT_BURST_MAXIMUM": "20",
        "GOLE_AUTH_OAUTH_CLIENT_BURST_WINDOW": "PT1M",
        "GOLE_AUTH_OAUTH_CLIENT_HOURLY_MAXIMUM": "120",
        "GOLE_AUTH_OAUTH_CLIENT_HOURLY_WINDOW": "PT1H",
        "GOLE_AUTH_OAUTH_GLOBAL_BURST_MAXIMUM": "120",
        "GOLE_AUTH_OAUTH_GLOBAL_BURST_WINDOW": "PT1M",
        "GOLE_AUTH_OAUTH_GLOBAL_DAILY_MAXIMUM": "2000",
        "GOLE_AUTH_OAUTH_GLOBAL_DAILY_WINDOW": "P1D",
        "GOLE_THIRD_PARTY_PROVISION_VERSION": "2026-09-04",
        "GOLE_TERMS_VERSION": "2026-09-04",
        "GOLE_PRIVACY_VERSION": "2026-09-05",
        "GOLE_SELLER_IDENTITY_VERIFICATION_READY": "false",
    },
    "budget-relay": {
        "GOLE_CLOUD_BROKER_SOCKET": "/run/gole-cloud-broker/broker.sock",
        "GCP_CREDIT_AMOUNT_KRW": "395600.60",
        "GCP_CREDIT_DEADLINE": "2026-10-28",
        "GCP_FIXED_HOURLY_COST_KRW": "153.390555330",
        "GCP_HIGH_RATE_HOURLY_COST_KRW": "240.749900000",
        "GCP_RUNTIME_RATE_TRANSITION_AT": "2026-09-06T00:00:00+09:00",
        "GCP_SNAPSHOT_MAX_HOURLY_COST_KRW": "39.041010000",
        "GCP_SNAPSHOT_RETENTION_HOURS": "72",
        "GCP_MANUAL_SNAPSHOT_HOURLY_COST_KRW": "13.013670000",
        "GCP_HARD_STOP_ENABLED": "true",
        "GCP_HARD_STOP_DRY_RUN": "false",
        "GCP_HARD_STOP_BILLING_COST_KRW": "320000",
        "GCP_HARD_STOP_MIN_RESERVE_KRW": "75000",
        "GCP_HARD_STOP_ALL_IN_COST_KRW": "350000",
        "GCP_COST_GUARD_WARNING_KRW": "330000",
        "GCP_COST_GUARD_DANGER_KRW": "340000",
        "GCP_HARD_STOP_NETWORK_GIB": "30",
        "GCP_COST_GUARD_NETWORK_WARNING_GIB": "15",
        "GCP_COST_GUARD_NETWORK_DANGER_GIB": "25",
        "GCP_HARD_STOP_MAX_RUNTIME_HOURS": "1350",
        "GCP_COST_GUARD_RUNTIME_WARNING_HOURS": "1250",
        "GCP_COST_GUARD_RUNTIME_DANGER_HOURS": "1320",
        "GCP_HARD_STOP_EXPECTED_BUDGET_KRW": "370000",
        "GCP_HARD_STOP_BUDGET_DISPLAY_NAME": "GoLe production credit guard",
        "GCP_HARD_STOP_PERIOD_START": "2026-09-01",
        "GCP_VM_COST_START": "2026-09-01T19:57:05+09:00",
        "GCP_HARD_STOP_AT": "2026-10-28T01:50:00+09:00",
        "GCP_HARD_STOP_ARM_ID": "2026-09-e2-standard-2-ipv4-v3",
        "GCP_INSTANCE_ZONE": "asia-northeast3-a",
        "GCP_INSTANCE_NAME": "gole-production",
        "GCP_VAT_RATE": "0.10",
        "GCP_NETWORK_EGRESS_KRW_PER_GIB": "318.154399937",
        "GCP_STOPPED_RESOURCE_HOURLY_COST_KRW": "45.725095000",
        "GCP_COST_GUARD_INTERVAL_SECONDS": "10",
        "GCP_HARD_STOP_RETRY_SECONDS": "300",
    },
}

EXPECTED_MOUNTS = {
    "budget-relay": {
        ("volume", "budget-relay-state", "/state", False, None),
        (
            "bind",
            "/run/gole-cloud-broker",
            "/run/gole-cloud-broker",
            True,
            False,
        ),
        ("bind", "/sys/class/net/ens4/statistics/tx_bytes", "/host-metrics/tx_bytes", True, None),
        ("bind", "/proc/sys/kernel/random/boot_id", "/host-metrics/boot_id", True, None),
    },
    "certbot": {
        ("volume", "certbot-webroot", "/var/www/certbot", False, None),
        ("volume", "letsencrypt", "/etc/letsencrypt", False, None),
    },
    "minio": {("volume", "minio-data", "/data", False, None)},
    "mongo": {("volume", "mongo-data", "/data/db", False, None)},
    "nginx": {
        ("bind", "/etc/gole/nginx.conf", "/etc/nginx/conf.d/default.conf", True, None),
        ("volume", "certbot-webroot", "/var/www/certbot", True, None),
        ("volume", "letsencrypt", "/etc/letsencrypt", True, None),
    },
    "redis": {("volume", "redis-data", "/data", False, None)},
}

LEGACY_ADOPTION_MOUNTS = {
    **EXPECTED_MOUNTS,
    "budget-relay": {
        ("volume", "budget-relay-state", "/state", False, None),
        ("bind", "/sys/class/net/ens4/statistics/tx_bytes", "/host-metrics/tx_bytes", True, None),
        ("bind", "/proc/sys/kernel/random/boot_id", "/host-metrics/boot_id", True, None),
    },
}

DANGEROUS_SERVICE_KEYS = {
    "cap_add",
    "cgroup",
    "cgroup_parent",
    "configs",
    "credential_spec",
    "devices",
    "device_cgroup_rules",
    "ipc",
    "isolation",
    "links",
    "network_mode",
    "pid",
    "privileged",
    "runtime",
    "secrets",
    "sysctls",
    "tmpfs",
    "ulimits",
    "use_api_socket",
    "userns_mode",
    "uts",
    "volumes_from",
}

# Reject future Compose service capabilities by default. A denylist alone is
# unsafe because newer Compose releases can add engine-access features such as
# use_api_socket before this validator knows their names.
ALLOWED_SERVICE_KEYS = {
    "build",
    "command",
    "container_name",
    "cpus",
    "depends_on",
    "entrypoint",
    "environment",
    "expose",
    "healthcheck",
    "image",
    "logging",
    "mem_limit",
    "networks",
    "ports",
    "profiles",
    "restart",
    "security_opt",
    "volumes",
}


def reject(condition: bool, message: str) -> None:
    if condition:
        raise ComposePolicyError(message)


def normalized_ports(service: dict[str, Any]) -> set[tuple[str | None, str, int, str]]:
    result = set()
    for port in service.get("ports", []):
        reject(not isinstance(port, dict), "a service port has invalid structure")
        result.add(
            (
                port.get("host_ip"),
                str(port.get("published")),
                int(port.get("target")),
                str(port.get("protocol", "tcp")),
            )
        )
    return result


def normalized_mounts(
    service: dict[str, Any],
) -> set[tuple[str, str, str, bool, bool | None]]:
    result = set()
    for mount in service.get("volumes", []):
        reject(not isinstance(mount, dict), "a service mount has invalid structure")
        mount_type = mount.get("type")
        source = mount.get("source")
        target = mount.get("target")
        read_only = mount.get("read_only", False)
        reject(not isinstance(mount_type, str), "a service mount has invalid type")
        reject(not isinstance(source, str), "a service mount has invalid source")
        reject(not isinstance(target, str), "a service mount has invalid target")
        reject(not isinstance(read_only, bool), "a service mount has invalid read-only flag")
        bind_options_present = "bind" in mount
        bind_options = mount.get("bind", {})
        reject(not isinstance(bind_options, dict), "a bind mount has invalid options")
        create_host_path = bind_options.get("create_host_path")
        reject(
            create_host_path is not None and not isinstance(create_host_path, bool),
            "a bind mount has invalid create-host-path flag",
        )

        if mount_type == "bind":
            reject(
                set(bind_options).difference({"create_host_path"}),
                "a bind mount uses unreviewed options",
            )
            if (source, target) == (
                "/run/gole-cloud-broker",
                "/run/gole-cloud-broker",
            ):
                # Compose 2.38 drops an explicitly configured
                # `create_host_path: false` from its JSON model, whereas
                # Compose 5 retains it. It still preserves an empty `bind`
                # object, while a source mount with no bind options has no
                # `bind` key. Use that distinction so removing the reviewed
                # false setting or changing it to true still fails closed.
                if create_host_path is None and bind_options_present:
                    create_host_path = False
            elif create_host_path is True:
                # Compose 2.x materializes the effective `true` default for
                # short bind syntax. Compose 5 emits an empty bind object for
                # the same source, so normalize only that renderer variance.
                create_host_path = None
        else:
            reject(bool(bind_options), "a non-bind mount uses bind options")
            create_host_path = None

        result.add(
            (
                mount_type,
                source,
                target,
                read_only,
                create_host_path,
            )
        )
    return result


def validate(
    model: dict[str, Any],
    allow_legacy_adoption: bool = False,
    allow_lkg_image_pins: bool = False,
    allow_missing_discord_overlay: bool = False,
    release_root: str = "/app",
) -> None:
    reject(
        allow_legacy_adoption and allow_lkg_image_pins,
        "legacy and strict LKG image modes cannot be combined",
    )
    if release_root != "/app":
        reject(
            not release_root.startswith("/var/lib/gole/releases/")
            or len(release_root) != len("/var/lib/gole/releases/") + 40
            or any(character not in "0123456789abcdef" for character in release_root.rsplit("/", 1)[-1]),
            "immutable release root is invalid",
        )
    reject(model.get("name") != "gole", "production Compose project name must remain gole")
    services = model.get("services")
    reject(not isinstance(services, dict), "production Compose services are missing")

    accepted_service_sets = [EXPECTED_SERVICES]
    if allow_legacy_adoption:
        accepted_service_sets.extend(
            [
                EXPECTED_SERVICES - {"certbot"},
                EXPECTED_SERVICES - {"support-agent"},
                EXPECTED_SERVICES - {"certbot", "support-agent"},
            ]
        )
    reject(
        not any(set(services) == accepted for accepted in accepted_service_sets),
        "production Compose service set changed",
    )

    for name, service in services.items():
        reject(not isinstance(service, dict), f"service {name} has invalid structure")
        dangerous = sorted(DANGEROUS_SERVICE_KEYS.intersection(service))
        reject(bool(dangerous), f"service {name} uses a forbidden host-escape setting")
        unexpected = sorted(set(service).difference(ALLOWED_SERVICE_KEYS))
        reject(bool(unexpected), f"service {name} uses an unreviewed Compose setting")
        expected_profiles = ["certificate"] if name == "certbot" else []
        reject(
            service.get("profiles", []) != expected_profiles,
            f"service {name} profiles changed",
        )
        # An omitted restart value and the rendered literal `no` have the same
        # Docker runtime meaning. No other policy is accepted for one-shot
        # services, and every daemon must survive host or Docker restarts.
        actual_restart = service.get("restart", "no")
        reject(
            actual_restart != EXPECTED_RESTART_POLICIES[name],
            f"service {name} restart policy changed",
        )
        expected_healthcheck = EXPECTED_HEALTHCHECKS.get(name)
        if allow_legacy_adoption and name == "nginx":
            expected_healthcheck = None
        reject(
            service.get("healthcheck") != expected_healthcheck,
            f"service {name} healthcheck changed",
        )
        if allow_legacy_adoption:
            reject("cpus" in service, f"legacy service {name} unexpectedly has a CPU limit")
            reject(
                "mem_limit" in service,
                f"legacy service {name} unexpectedly has a memory limit",
            )
        else:
            reject(
                service.get("cpus") != EXPECTED_CPUS.get(name),
                f"service {name} CPU limit changed",
            )
            reject(
                service.get("mem_limit") != EXPECTED_MEMORY_LIMITS.get(name),
                f"service {name} memory limit changed",
            )
        if name == "support-agent":
            reject(
                service.get("entrypoint") is not None
                or service.get("command") is not None,
                "support-agent process command must use the reviewed image default",
            )
        if not allow_legacy_adoption:
            reject(
                service.get("security_opt") != ["no-new-privileges:true"],
                f"service {name} must retain no-new-privileges",
            )
        expected_images = LEGACY_ADOPTION_IMAGES if allow_legacy_adoption else EXPECTED_IMAGES
        if allow_lkg_image_pins and name in LKG_PINNED_IMAGE_PATTERNS:
            reject(
                re.fullmatch(LKG_PINNED_IMAGE_PATTERNS[name], str(service.get("image", "")))
                is None,
                f"service {name} LKG image repository or immutable pin changed",
            )
        else:
            reject(service.get("image") != expected_images[name], f"service {name} image changed")
        expected_name = EXPECTED_CONTAINER_NAMES.get(name)
        reject(service.get("container_name") != expected_name, f"service {name} name changed")
        expected_ports = LEGACY_ADOPTION_PORTS if allow_legacy_adoption else EXPECTED_PORTS
        reject(
            normalized_ports(service) != expected_ports.get(name, set()),
            f"service {name} published ports changed",
        )
        service_network_attachments = service.get("networks")
        reject(
            not isinstance(service_network_attachments, dict),
            f"service {name} networks are invalid",
        )
        service_networks = set(service_network_attachments)
        if allow_legacy_adoption:
            reject(service_networks != {"default"}, f"legacy service {name} network changed")
        else:
            reject(
                service_networks != EXPECTED_SERVICE_NETWORKS[name],
                f"service {name} network boundary changed",
            )
        for network_name, attachment in service_network_attachments.items():
            reject(
                attachment is not None
                and (not isinstance(attachment, dict) or bool(attachment)),
                f"service {name} network {network_name} uses unreviewed attachment options",
            )
        if not allow_legacy_adoption:
            reject(
                service.get("logging") != EXPECTED_LOGGING,
                f"service {name} logging rotation changed",
            )
        expected_mounts = LEGACY_ADOPTION_MOUNTS if allow_legacy_adoption else EXPECTED_MOUNTS
        reject(
            normalized_mounts(service) != expected_mounts.get(name, set()),
            f"service {name} mounts changed",
        )
        environment = service.get("environment", {})
        reject(not isinstance(environment, dict), f"service {name} environment is invalid")
        protected_environment = (
            {} if allow_legacy_adoption else EXPECTED_ENVIRONMENT_VALUES.get(name, {})
        )
        for key, expected_value in protected_environment.items():
            reject(
                str(environment.get(key)) != expected_value,
                f"service {name} protected environment value changed: {key}",
            )
        build = service.get("build")
        if name in EXPECTED_DOCKERFILES:
            reject(not isinstance(build, dict), f"service {name} build definition is missing")
            reject(
                set(build).difference({"context", "dockerfile", "args"}),
                f"service {name} build uses a forbidden privilege setting",
            )
            relative_context, dockerfile = EXPECTED_DOCKERFILES[name]
            expected_context = str(pathlib.PurePosixPath(release_root) / relative_context)
            reject(
                (build.get("context"), build.get("dockerfile"))
                != (expected_context, dockerfile),
                f"service {name} build source changed",
            )
            build_arguments = build.get("args", {})
            reject(
                not isinstance(build_arguments, dict),
                f"service {name} build arguments are invalid",
            )
            if name != "frontend":
                reject(bool(build_arguments), f"service {name} unexpectedly receives build arguments")
            else:
                expected_argument_keys = {
                    "NEXT_PUBLIC_API_BASE_URL",
                    "NEXT_PUBLIC_SITE_URL",
                    "NEXT_PUBLIC_PAYMENT_MODE",
                    "NEXT_PUBLIC_PORTONE_STORE_ID",
                    "NEXT_PUBLIC_PORTONE_CHANNEL_KEY",
                    "NEXT_PUBLIC_PORTONE_CARD_CHANNEL_KEY",
                }
                if not allow_legacy_adoption:
                    expected_argument_keys.update(
                        {"NEXT_PUBLIC_GA_MEASUREMENT_ID", "NEXT_PUBLIC_GTM_ID"}
                    )
                reject(
                    set(build_arguments) != expected_argument_keys,
                    "frontend build argument set changed",
                )
                for key, expected in {
                    "NEXT_PUBLIC_API_BASE_URL": "https://gole.co.kr",
                    "NEXT_PUBLIC_SITE_URL": "https://gole.co.kr",
                }.items():
                    reject(
                        str(build_arguments.get(key)) != expected,
                        f"frontend protected build argument changed: {key}",
                    )
                # The single reviewed legacy runtime was built with PortOne's
                # test channel. Its first read-only validation therefore sees
                # `portone-test`, while the staged v6 environment rendered from
                # the same historical Compose source already sees `disabled`.
                # No other value is accepted, and every ordinary/strict build
                # remains pinned to disabled below.
                payment_mode = str(build_arguments.get("NEXT_PUBLIC_PAYMENT_MODE"))
                if allow_legacy_adoption:
                    reject(
                        payment_mode not in {"portone-test", "disabled"},
                        "frontend protected build argument changed: NEXT_PUBLIC_PAYMENT_MODE",
                    )
                else:
                    reject(
                        payment_mode != "disabled",
                        "frontend protected build argument changed: NEXT_PUBLIC_PAYMENT_MODE",
                    )
                if not allow_legacy_adoption:
                    for key, pattern in {
                        "NEXT_PUBLIC_GA_MEASUREMENT_ID": r"(?:|G-[A-Z0-9]+)",
                        "NEXT_PUBLIC_GTM_ID": r"(?:|GTM-[A-Z0-9]+)",
                    }.items():
                        reject(
                            re.fullmatch(pattern, str(build_arguments.get(key, ""))) is None,
                            f"frontend analytics identifier is invalid: {key}",
                        )
        else:
            reject(build is not None, f"service {name} unexpectedly builds a local image")

    if not allow_legacy_adoption:
        # GOLE_VERIFICATION_EMAIL_ENABLED must be exactly "true" or "false",
        # and the SMTP identity it gates must be empty together with it
        # (false) or fully present together with it (true). A partially
        # filled identity is rejected either way — this stays fail-closed.
        backend_environment = services["backend"].get("environment", {})
        email_enabled = str(backend_environment.get("GOLE_VERIFICATION_EMAIL_ENABLED"))
        reject(
            email_enabled not in {"true", "false"},
            "service backend protected environment value changed: GOLE_VERIFICATION_EMAIL_ENABLED",
        )
        email_identity_keys = ("SMTP_USERNAME", "SMTP_PASSWORD", "GOLE_VERIFICATION_EMAIL_FROM")
        if email_enabled == "false":
            for key in email_identity_keys:
                reject(
                    str(backend_environment.get(key, "")) != "",
                    f"service backend protected environment value changed: {key}",
                )
        else:
            for key in email_identity_keys:
                reject(
                    not backend_environment.get(key),
                    f"service backend protected environment value changed: {key}",
                )

    if not allow_legacy_adoption and not allow_missing_discord_overlay:
        backend_environment = services["backend"].get("environment", {})
        budget_environment = services["budget-relay"].get("environment", {})
        reject(
            backend_environment.get("GOLE_DISCORD_ALERTS_ENABLED") != "true",
            "production Discord alerts must remain enabled",
        )
        reject(
            backend_environment.get("DISCORD_SUPPRESS_NOTIFICATIONS") not in {"true", "false"},
            "production Discord suppression flag is invalid",
        )
        webhook_pattern = re.compile(
            r"https://(?:discord\.com|discordapp\.com)/api/webhooks/"
            r"[0-9]{1,32}/[A-Za-z0-9._-]{20,256}"
        )
        for key in (
            "DISCORD_DEPLOY_WEBHOOK_URL",
            "DISCORD_OPERATIONS_WEBHOOK_URL",
            "DISCORD_ACCOUNT_WEBHOOK_URL",
            "DISCORD_PAYMENT_WEBHOOK_URL",
            "DISCORD_SUPPORT_WEBHOOK_URL",
        ):
            reject(
                webhook_pattern.fullmatch(str(backend_environment.get(key, ""))) is None,
                f"production Discord route is missing or invalid: {key}",
            )
        reject(
            budget_environment.get("DISCORD_OPERATIONS_WEBHOOK_URL")
            != backend_environment.get("DISCORD_OPERATIONS_WEBHOOK_URL"),
            "backend and budget relay Discord operations routes must match",
        )

    minio_init = services["minio-init"].get("entrypoint")
    minio_command = "\n".join(str(item) for item in minio_init or [])
    if not allow_legacy_adoption:
        reject(
            "mc anonymous set none local/gole" not in minio_command,
            "production MinIO bucket must be explicitly private",
        )
    reject(
        "mc anonymous set download" in minio_command,
        "production MinIO bucket must not allow anonymous downloads",
    )

    volumes = model.get("volumes")
    reject(not isinstance(volumes, dict), "production Compose volumes are missing")
    reject(set(volumes) != set(EXPECTED_VOLUME_NAMES), "production Compose volume set changed")
    for volume_name, volume in volumes.items():
        reject(not isinstance(volume, dict), "production volume has invalid structure")
        reject(bool(volume.get("external", False)), "external Docker volumes are forbidden")
        reject("driver_opts" in volume, "Docker volume driver options are forbidden")
        if not allow_legacy_adoption:
            reject(
                set(volume).difference({"name", "driver"}),
                f"production volume {volume_name} has an unreviewed setting",
            )
            reject(
                volume.get("name") != EXPECTED_VOLUME_NAMES[volume_name],
                f"production volume {volume_name} runtime name changed",
            )
            reject(
                volume.get("driver", "local") != "local",
                f"production volume {volume_name} driver changed",
            )

    networks = model.get("networks", {})
    expected_networks = (
        {"default"} if allow_legacy_adoption else set(EXPECTED_NETWORK_NAMES)
    )
    reject(set(networks) != expected_networks, "production Compose network set changed")
    for name, network in networks.items():
        reject(not isinstance(network, dict), f"Docker network {name} is invalid")
        reject(bool(network.get("external", False)), "external Docker networks are forbidden")
        reject("driver_opts" in network, "Docker network driver options are forbidden")
    if not allow_legacy_adoption:
        for name, expected_name in EXPECTED_NETWORK_NAMES.items():
            reject(
                # Compose 2.x materializes an empty ipam object even when the
                # source declares none. Accept only that semantic no-op; any
                # subnet, gateway or driver configuration remains forbidden.
                set(networks[name]).difference({"name", "driver", "internal", "ipam"}),
                f"Docker network {name} has an unreviewed setting",
            )
            reject(
                networks[name].get("ipam", {}) != {},
                f"Docker network {name} IPAM changed",
            )
            reject(
                networks[name].get("name") != expected_name,
                f"Docker network {name} runtime name changed",
            )
            reject(
                networks[name].get("driver", "bridge") != "bridge",
                f"Docker network {name} driver changed",
            )
        reject(networks["data"].get("internal") is not True, "data network must remain internal")
        reject(networks["agent"].get("internal") is not True, "agent network must remain internal")
        reject(bool(networks["edge"].get("internal", False)), "edge network must retain egress")


def main(argv: list[str]) -> int:
    arguments = argv[1:]
    allow_legacy = False
    allow_lkg_pins = False
    allow_missing_discord = False
    release_root = "/app"
    while arguments:
        argument = arguments.pop(0)
        if argument == "--allow-legacy-adoption":
            allow_legacy = True
        elif argument == "--allow-lkg-image-pins":
            allow_lkg_pins = True
        elif argument == "--allow-missing-discord-overlay":
            allow_missing_discord = True
        elif argument == "--release-root" and arguments:
            release_root = arguments.pop(0)
        else:
            print("invalid validator arguments", file=sys.stderr)
            return 2
    try:
        raw = sys.stdin.buffer.read(4 * 1024 * 1024 + 1)
        if not raw or len(raw) > 4 * 1024 * 1024:
            raise ComposePolicyError("rendered production Compose size is invalid")
        model = json.loads(raw)
        if not isinstance(model, dict):
            raise ComposePolicyError("rendered production Compose root is invalid")
        validate(
            model,
            allow_legacy_adoption=allow_legacy,
            allow_lkg_image_pins=allow_lkg_pins,
            allow_missing_discord_overlay=allow_missing_discord,
            release_root=release_root,
        )
    except (ComposePolicyError, json.JSONDecodeError, TypeError, ValueError) as exception:
        # Never print the JSON because rendered environment entries contain secrets.
        print(f"production Compose policy rejected: {exception}", file=sys.stderr)
        return 1
    print("Production Compose privilege policy validated.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
