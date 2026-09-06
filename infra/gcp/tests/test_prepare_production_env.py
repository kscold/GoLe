#!/usr/bin/env python3
from __future__ import annotations

import hashlib
import importlib.util
import pathlib
import stat
import subprocess
import tempfile
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[3]
PREPARER = ROOT / "infra/gcp/scripts/prepare-production-env.py"
VALIDATOR_PATH = ROOT / "infra/gcp/scripts/validate-production-env.py"
PRODUCTION_FIXTURE = ROOT / "infra/gcp/tests/fixtures/production.env"
RUNBOOK = ROOT / "infra/gcp/README.md"


def load_validator():
    spec = importlib.util.spec_from_file_location("gole_env_validator", VALIDATOR_PATH)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class PrepareProductionEnvironmentTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tempdir = tempfile.TemporaryDirectory()
        self.directory = pathlib.Path(self.tempdir.name)
        self.validator = load_validator()
        # EXACT_VALUES no longer carries the email latch or the identity
        # fields it gates — those are validated conditionally instead — so
        # every test below supplies its own latch/identity lines explicitly.
        skipped_keys = (
            set(self.validator.EXACT_VALUES)
            | {self.validator.EMAIL_LATCH_KEY}
            | set(self.validator.EMAIL_IDENTITY_KEYS)
        )
        self.base_lines = [
            line
            for line in PRODUCTION_FIXTURE.read_text().splitlines()
            if line.split("=", 1)[0] not in skipped_keys
        ]

    def tearDown(self) -> None:
        self.tempdir.cleanup()

    def write_input(self, name: str, extra_lines: list[str]) -> pathlib.Path:
        input_path = self.directory / name
        input_path.write_text("\n".join(self.base_lines + extra_lines) + "\n")
        return input_path

    def run_preparer(self, input_path: pathlib.Path, attempted_password: str | None = None):
        command = [
            "python3",
            str(PREPARER),
            str(input_path),
            "--output-directory",
            str(self.directory),
        ]
        if attempted_password is not None:
            command.append("--smtp-password-stdin")
        return subprocess.run(
            command,
            input=None if attempted_password is None else attempted_password + "\n",
            check=False,
            capture_output=True,
            text=True,
        )

    def test_preserves_payload_scrubs_commented_smtp_and_enforces_disabled_policy(self) -> None:
        input_path = self.write_input(
            "legacy-v5-disabled.env",
            [
                "# existing payload must remain a plain file",
                "# SMTP_USERNAME=commented-private-mailbox@example.test",
                "#SMTP_PASSWORD=commented-private-app-password",
                "  # export GOLE_VERIFICATION_EMAIL_FROM=commented-private-sender@example.test",
                "GOLE_VERIFICATION_EMAIL_ENABLED=false",
                "SMTP_USERNAME=",
                "SMTP_PASSWORD=",
                "GOLE_VERIFICATION_EMAIL_FROM=",
                "CUSTOM_PRESERVED_SECRET=do-not-log-this-value",
            ],
        )
        original_hash = hashlib.sha256(input_path.read_bytes()).hexdigest()

        result = self.run_preparer(input_path)
        self.assertEqual(0, result.returncode, result.stderr)
        candidate = pathlib.Path(result.stdout.strip())
        self.assertEqual(self.directory.resolve(), candidate.parent)
        self.assertEqual(0o600, stat.S_IMODE(candidate.stat().st_mode))
        contents = candidate.read_text()
        self.assertIn("# existing payload must remain a plain file", contents)
        self.assertIn("CUSTOM_PRESERVED_SECRET=do-not-log-this-value", contents)
        for stale_secret in (
            "commented-private-mailbox@example.test",
            "commented-private-app-password",
            "commented-private-sender@example.test",
        ):
            self.assertNotIn(stale_secret, contents)
            self.assertNotIn(stale_secret, result.stdout + result.stderr)
        values = self.validator.parse_env(candidate)
        self.validator.validate(values)
        for key, value in self.validator.EXACT_VALUES.items():
            self.assertEqual(value, values[key])
        self.assertEqual("false", values["GOLE_VERIFICATION_EMAIL_ENABLED"])
        self.assertEqual("", values["SMTP_USERNAME"])
        self.assertEqual("", values["SMTP_PASSWORD"])
        self.assertEqual("", values["GOLE_VERIFICATION_EMAIL_FROM"])
        self.assertEqual(original_hash, hashlib.sha256(input_path.read_bytes()).hexdigest())

    def test_rejects_stale_identity_left_over_while_latch_disabled(self) -> None:
        # A leftover live SMTP identity with the latch still off is an
        # inconsistent payload. The preparer no longer force-blanks it —
        # it passes the input through — so this must be rejected instead of
        # silently scrubbed.
        input_path = self.write_input(
            "legacy-v5-inconsistent.env",
            [
                "GOLE_VERIFICATION_EMAIL_ENABLED=false",
                "SMTP_USERNAME=legacy-private-mailbox@example.test",
                "SMTP_PASSWORD=legacy-private-app-password",
                "GOLE_VERIFICATION_EMAIL_FROM=legacy-private-sender@example.test",
            ],
        )
        original_hash = hashlib.sha256(input_path.read_bytes()).hexdigest()

        result = self.run_preparer(input_path)
        self.assertEqual(1, result.returncode)
        self.assertEqual([], list(self.directory.glob("gole-env.*")))
        for stale_secret in (
            "legacy-private-mailbox@example.test",
            "legacy-private-app-password",
            "legacy-private-sender@example.test",
        ):
            self.assertNotIn(stale_secret, result.stdout + result.stderr)
        self.assertEqual(original_hash, hashlib.sha256(input_path.read_bytes()).hexdigest())

    def test_accepts_and_preserves_a_full_identity_when_latch_enabled(self) -> None:
        input_path = self.write_input(
            "legacy-v5-enabled.env",
            [
                "GOLE_VERIFICATION_EMAIL_ENABLED=true",
                "SMTP_USERNAME=verified-mailbox@example.test",
                "SMTP_PASSWORD=verified-app-password",
                "GOLE_VERIFICATION_EMAIL_FROM=verified-sender@example.test",
            ],
        )
        original_hash = hashlib.sha256(input_path.read_bytes()).hexdigest()

        result = self.run_preparer(input_path)
        self.assertEqual(0, result.returncode, result.stderr)
        candidate = pathlib.Path(result.stdout.strip())
        self.assertEqual(0o600, stat.S_IMODE(candidate.stat().st_mode))
        values = self.validator.parse_env(candidate)
        self.validator.validate(values)
        self.assertEqual("true", values["GOLE_VERIFICATION_EMAIL_ENABLED"])
        self.assertEqual("verified-mailbox@example.test", values["SMTP_USERNAME"])
        self.assertEqual("verified-app-password", values["SMTP_PASSWORD"])
        self.assertEqual("verified-sender@example.test", values["GOLE_VERIFICATION_EMAIL_FROM"])
        # The always-on transport security invariants stay pinned regardless.
        for key in (
            "SMTP_AUTH",
            "SMTP_STARTTLS",
            "SMTP_STARTTLS_REQUIRED",
            "SMTP_SSL_CHECKSERVERIDENTITY",
        ):
            self.assertEqual("true", values[key])
        self.assertEqual(original_hash, hashlib.sha256(input_path.read_bytes()).hexdigest())

    def test_explicitly_rejects_legacy_smtp_stdin_mode_without_leaving_candidate(self) -> None:
        input_path = self.write_input(
            "legacy-v5-stdin.env",
            [
                "GOLE_VERIFICATION_EMAIL_ENABLED=false",
                "SMTP_USERNAME=",
                "SMTP_PASSWORD=",
                "GOLE_VERIFICATION_EMAIL_FROM=",
            ],
        )
        password = "do-not-print-this-app-password"
        result = self.run_preparer(input_path, password)
        self.assertEqual(1, result.returncode)
        self.assertEqual("", result.stdout)
        self.assertIn("--smtp-password-stdin is unavailable", result.stderr)
        self.assertEqual([], list(self.directory.glob("gole-env.*")))
        self.assertNotIn(password, result.stdout + result.stderr)

    def test_rejects_duplicate_input_key(self) -> None:
        input_path = self.write_input(
            "legacy-v5-duplicate.env",
            [
                "GOLE_VERIFICATION_EMAIL_ENABLED=false",
                "SMTP_USERNAME=",
                "SMTP_PASSWORD=",
                "GOLE_VERIFICATION_EMAIL_FROM=",
            ],
        )
        with input_path.open("a") as stream:
            stream.write("MONGODB_URI=duplicate\n")
        result = self.run_preparer(input_path)
        self.assertEqual(1, result.returncode)
        self.assertEqual([], list(self.directory.glob("gole-env.*")))

    def test_runbook_prepares_stage_zero_without_collecting_an_smtp_secret(self) -> None:
        runbook = RUNBOOK.read_text(encoding="utf-8")
        self.assertIn(
            'candidate_path="$(python3 infra/gcp/scripts/prepare-production-env.py',
            runbook,
        )
        self.assertNotIn("IFS= read -r -s SMTP_APP_PASSWORD", runbook)
        self.assertNotIn("printf '%s' \"$SMTP_APP_PASSWORD\"", runbook)
        self.assertIn(
            "`--smtp-password-stdin`은 Stage 0에서 명시적으로 실패한다",
            runbook,
        )


if __name__ == "__main__":
    unittest.main()
