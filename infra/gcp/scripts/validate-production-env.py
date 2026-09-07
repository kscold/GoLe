#!/usr/bin/env python3
"""Fail-closed validation for the exact initial GoLe production launch policy."""

from __future__ import annotations

import pathlib
import re
import sys


EXACT_VALUES = {
    "GOLE_ENVIRONMENT": "production",
    "GOLE_ONBOARDING_PHONE_REQUIRED": "false",
    "GOLE_ONBOARDING_LOG_VERIFICATION_CODES": "false",
    # SMTP is deliberately unavailable for the initial public launch, and
    # Spring's mail health probe stays off with it. Unlike the identity
    # fields below, this is not part of the email latch: flipping the latch
    # on does not also turn the health probe on by itself.
    "GOLE_MAIL_HEALTH_ENABLED": "false",
    "SMTP_HOST": "smtp.gmail.com",
    "SMTP_PORT": "587",
    # Plaintext/unauthenticated relays and unverified TLS certificates are
    # never acceptable, so these four stay pinned to "true" regardless of
    # whether GOLE_VERIFICATION_EMAIL_ENABLED is later switched on.
    "SMTP_AUTH": "true",
    "SMTP_STARTTLS": "true",
    "SMTP_STARTTLS_REQUIRED": "true",
    "SMTP_SSL_CHECKSERVERIDENTITY": "true",
    "GOLE_CATALOG_SEED": "false",
    "GOLE_LISTING_SEED": "false",
    "GOLE_PRICING_SEED": "false",
    "GOLE_PRICING_INCLUDE_DEMO": "false",
    "GOLE_PRICING_INCLUDE_LEGACY": "false",
    "GOLE_COMMUNITY_SEED": "false",
    "GOLE_REPORT_SEED": "false",
    "GOLE_REVIEW_SEED": "false",
    "GOLE_MEDIA_SEED": "false",
    "GOLE_SESSION_COOKIE_SECURE": "true",
    "GOLE_WEB_ALLOWED_ORIGINS": "https://gole.co.kr,https://www.gole.co.kr",
    "GOLE_OAUTH_ALLOWED_REDIRECT_URIS": (
        "https://gole.co.kr/auth/callback/google,"
        "https://gole.co.kr/auth/callback/kakao,"
        "https://gole.co.kr/auth/callback/naver"
    ),
    "GOLE_TERMS_VERSION": "2026-09-04",
    "GOLE_PRIVACY_VERSION": "2026-09-05",
    "GOLE_THIRD_PARTY_PROVISION_VERSION": "2026-09-04",
    "GOLE_SELLER_IDENTITY_VERIFICATION_READY": "false",
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
    "GOLE_SUPPORT_AGENT_ENABLED": "true",
    "GOLE_SUPPORT_AGENT_GRPC_TARGET": "support-agent:50051",
    "GOLE_SUPPORT_AGENT_TIMEOUT": "PT2S",
    "PORTONE_ENABLED": "false",
    "NEXT_PUBLIC_PAYMENT_MODE": "disabled",
    "GOLE_SETTLEMENT_MODE": "DISABLED",
    "GOLE_SETTLEMENT_PAYOUT_CONTRACT_VERIFIED": "false",
}

# The Stage 0 email latch itself and the identity fields it gates. These are
# no longer checked against a single fixed value: GOLE_VERIFICATION_EMAIL_ENABLED
# must be exactly "true" or "false", and the three identity fields must be
# empty together with it (false) or all present together with it (true).
# Fail-closed either way — a partially-filled identity is always rejected.
EMAIL_LATCH_KEY = "GOLE_VERIFICATION_EMAIL_ENABLED"
EMAIL_IDENTITY_KEYS = ("SMTP_USERNAME", "SMTP_PASSWORD", "GOLE_VERIFICATION_EMAIL_FROM")

KEY_PATTERN = re.compile(r"[A-Za-z_][A-Za-z0-9_]*")
class PolicyError(ValueError):
    """Production environment violates a deploy-time invariant."""


def parse_env(path: pathlib.Path) -> dict[str, str]:
    raw = path.read_bytes()
    if not raw or len(raw) > 128 * 1024 or b"\x00" in raw:
        raise PolicyError("environment file size or content is invalid")
    try:
        text = raw.decode("utf-8-sig").replace("\r\n", "\n").replace("\r", "\n")
    except UnicodeDecodeError as exception:
        raise PolicyError("environment file must be UTF-8") from exception

    values: dict[str, str] = {}
    for number, line in enumerate(text.splitlines(), start=1):
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        if "=" not in line:
            raise PolicyError(f"environment line {number} has invalid syntax")
        key, value = line.split("=", 1)
        if not KEY_PATTERN.fullmatch(key):
            raise PolicyError(f"environment line {number} has an invalid key")
        if key in values:
            raise PolicyError(f"environment contains duplicate key: {key}")
        values[key] = value
    return values


def validate(values: dict[str, str]) -> None:
    violations = [
        f"{key} must be explicitly set to {expected}"
        for key, expected in EXACT_VALUES.items()
        if values.get(key) != expected
    ]

    email_enabled = values.get(EMAIL_LATCH_KEY)
    if email_enabled not in ("true", "false"):
        violations.append(f"{EMAIL_LATCH_KEY} must be explicitly set to true or false")
    elif email_enabled == "false":
        violations.extend(
            f"{key} must be empty while {EMAIL_LATCH_KEY}=false"
            for key in EMAIL_IDENTITY_KEYS
            if values.get(key, "") != ""
        )
    else:
        violations.extend(
            f"{key} must be set while {EMAIL_LATCH_KEY}=true"
            for key in EMAIL_IDENTITY_KEYS
            if not values.get(key)
        )

    optional_public_ids = {
        "NEXT_PUBLIC_GA_MEASUREMENT_ID": r"G-[A-Z0-9]+",
        "NEXT_PUBLIC_GTM_ID": r"GTM-[A-Z0-9]+",
    }
    for key, pattern in optional_public_ids.items():
        value = values.get(key, "")
        if value and not re.fullmatch(pattern, value):
            # Do not include the value; validator output is retained in CI and
            # host journals.
            violations.append(f"{key} has an invalid format")

    if violations:
        # Never include actual values: this error is emitted into GitHub Actions logs.
        raise PolicyError("production environment policy rejected: " + "; ".join(violations))


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print("usage: validate-production-env.py ENV_FILE", file=sys.stderr)
        return 2
    try:
        validate(parse_env(pathlib.Path(argv[1])))
    except (OSError, PolicyError) as exception:
        print(str(exception), file=sys.stderr)
        return 1
    print("Production environment policy validated.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
