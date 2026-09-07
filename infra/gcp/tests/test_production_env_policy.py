#!/usr/bin/env python3
from __future__ import annotations

import pathlib
import runpy
import subprocess
import tempfile
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[3]
VALIDATOR = ROOT / "infra/gcp/scripts/validate-production-env.py"
PRODUCTION_FIXTURE = ROOT / "infra/gcp/tests/fixtures/production.env"
DEVELOPMENT_FIXTURE = ROOT / "infra/gcp/tests/fixtures/development.env"


def run_validator(path: pathlib.Path) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["python3", str(VALIDATOR), str(path)],
        check=False,
        capture_output=True,
        text=True,
    )


class ProductionEnvironmentPolicyTest(unittest.TestCase):
    def test_accepts_exact_initial_production_policy(self) -> None:
        result = run_validator(PRODUCTION_FIXTURE)
        self.assertEqual(0, result.returncode, result.stderr)

    def test_rejects_development_fixture_without_echoing_secret_values(self) -> None:
        result = run_validator(DEVELOPMENT_FIXTURE)
        self.assertEqual(1, result.returncode)
        self.assertIn("GOLE_ENVIRONMENT must be explicitly set to production", result.stderr)
        self.assertNotIn("developer@example.test", result.stderr)

    def test_rejects_every_exact_policy_regression(self) -> None:
        original = self._read_fixture()
        for key in self._exact_policy_keys():
            with self.subTest(key=key):
                mutated = dict(original)
                mutated[key] = "unsafe-value"
                result = self._validate_mapping(mutated)
                self.assertEqual(1, result.returncode)
                self.assertIn(key, result.stderr)

    def test_rejects_any_stage_zero_smtp_identity_without_echoing_values(self) -> None:
        original = self._read_fixture()
        for key, value in (
            ("SMTP_USERNAME", "private-mailbox@example.test"),
            ("SMTP_PASSWORD", "do-not-print-this-app-password"),
            ("GOLE_VERIFICATION_EMAIL_FROM", "private-sender@example.test"),
        ):
            with self.subTest(key=key):
                mutated = dict(original)
                mutated[key] = value
                result = self._validate_mapping(mutated)
                self.assertEqual(1, result.returncode)
                self.assertIn(key, result.stderr)
                self.assertNotIn(value, result.stderr)

    def test_stage_zero_mail_health_and_transport_security_are_exact(self) -> None:
        namespace = runpy.run_path(str(VALIDATOR), run_name="gole_production_env_policy")
        exact = namespace["EXACT_VALUES"]
        self.assertEqual("false", exact["GOLE_MAIL_HEALTH_ENABLED"])
        for key in (
            "SMTP_AUTH",
            "SMTP_STARTTLS",
            "SMTP_STARTTLS_REQUIRED",
            "SMTP_SSL_CHECKSERVERIDENTITY",
        ):
            self.assertEqual("true", exact[key])
        # The latch itself and the identity fields it gates are no longer
        # exact-matched: they are validated conditionally instead.
        self.assertNotIn("GOLE_VERIFICATION_EMAIL_ENABLED", exact)
        for key in ("SMTP_USERNAME", "SMTP_PASSWORD", "GOLE_VERIFICATION_EMAIL_FROM"):
            self.assertNotIn(key, exact)

    def test_email_latch_rejects_anything_other_than_true_or_false(self) -> None:
        original = self._read_fixture()
        for value in ("True", "FALSE", "1", "0", ""):
            with self.subTest(value=value):
                mutated = dict(original)
                mutated["GOLE_VERIFICATION_EMAIL_ENABLED"] = value
                result = self._validate_mapping(mutated)
                self.assertEqual(1, result.returncode)
                self.assertIn("GOLE_VERIFICATION_EMAIL_ENABLED", result.stderr)

    def test_email_latch_true_requires_a_full_identity(self) -> None:
        original = self._read_fixture()
        full_identity = {
            "SMTP_USERNAME": "verified-mailbox@example.test",
            "SMTP_PASSWORD": "verified-app-password",
            "GOLE_VERIFICATION_EMAIL_FROM": "verified-sender@example.test",
        }
        for missing_key in full_identity:
            with self.subTest(missing=missing_key):
                mutated = dict(original)
                mutated["GOLE_VERIFICATION_EMAIL_ENABLED"] = "true"
                mutated.update(full_identity)
                mutated[missing_key] = ""
                result = self._validate_mapping(mutated)
                self.assertEqual(1, result.returncode)
                self.assertIn(missing_key, result.stderr)

    def test_email_latch_true_accepts_a_full_identity_without_echoing_it(self) -> None:
        original = self._read_fixture()
        mutated = dict(original)
        mutated["GOLE_VERIFICATION_EMAIL_ENABLED"] = "true"
        mutated.update(
            {
                "SMTP_USERNAME": "verified-mailbox@example.test",
                "SMTP_PASSWORD": "verified-app-password",
                "GOLE_VERIFICATION_EMAIL_FROM": "verified-sender@example.test",
            }
        )
        result = self._validate_mapping(mutated)
        self.assertEqual(0, result.returncode, result.stderr)
        # The always-on transport security invariants stay pinned even once
        # the latch is enabled.
        for key in (
            "SMTP_AUTH",
            "SMTP_STARTTLS",
            "SMTP_STARTTLS_REQUIRED",
            "SMTP_SSL_CHECKSERVERIDENTITY",
        ):
            with self.subTest(key=key):
                weakened = dict(mutated)
                weakened[key] = "false"
                result = self._validate_mapping(weakened)
                self.assertEqual(1, result.returncode)
                self.assertIn(key, result.stderr)

    def test_policy_validation_precedes_privileged_install(self) -> None:
        hostctl = (ROOT / "infra/gcp/scripts/gole-hostctl.sh").read_text()
        sync_start = hostctl.index("sync_secret_environment()")
        sync_end = hostctl.index("\n}\n", sync_start)
        sync_body = hostctl[sync_start:sync_end]
        validation_offset = sync_body.index("validate_production_environment")
        install_offset = sync_body.index("begin_environment_transaction")
        self.assertLess(validation_offset, install_offset)

        # The unprivileged wrapper must not read the payload or run a validator
        # from its runner-owned checkout.  It may only request the fixed
        # root-owned transaction with an exact version and request id.
        apply_script = (ROOT / "infra/gcp/scripts/apply-secret-env.sh").read_text()
        self.assertNotIn("gcloud secrets versions access", apply_script)
        self.assertNotIn("validate-production-env.py", apply_script)
        self.assertIn('sudo -n "$HOSTCTL" secret-sync "$SECRET_VERSION" "$REQUEST_ID"', apply_script)

    @staticmethod
    def _read_fixture() -> dict[str, str]:
        return dict(
            line.split("=", 1)
            for line in PRODUCTION_FIXTURE.read_text().splitlines()
            if line and not line.startswith("#")
        )

    @staticmethod
    def _exact_policy_keys() -> tuple[str, ...]:
        namespace = runpy.run_path(str(VALIDATOR), run_name="gole_production_env_policy")
        return tuple(namespace["EXACT_VALUES"])

    def _validate_mapping(self, values: dict[str, str]) -> subprocess.CompletedProcess[str]:
        # delete=False + manual cleanup: an open NamedTemporaryFile cannot be
        # reopened by the validator subprocess on Windows.
        candidate = tempfile.NamedTemporaryFile(
            "w", encoding="utf-8", delete=False, suffix=".env"
        )
        try:
            candidate.write("".join(f"{key}={value}\n" for key, value in values.items()))
            candidate.close()
            return run_validator(pathlib.Path(candidate.name))
        finally:
            pathlib.Path(candidate.name).unlink(missing_ok=True)


if __name__ == "__main__":
    unittest.main()
