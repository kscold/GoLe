#!/usr/bin/env python3
"""Create a fail-closed production env without sourcing the input.

The Stage 0 email latch (GOLE_VERIFICATION_EMAIL_ENABLED) and the SMTP
identity it gates pass through from the input unchanged: this script only
forces the exact policy values in validate-production-env.py's EXACT_VALUES,
which no longer includes the latch or its identity fields. Whatever
combination the input carries still has to satisfy the validator's
conditional check below, so a mismatched latch/identity pair is rejected the
same as before.
"""

from __future__ import annotations

import argparse
import importlib.util
import os
import pathlib
import re
import secrets
import sys
from types import ModuleType


SCRIPT_DIR = pathlib.Path(__file__).resolve().parent
VALIDATOR_PATH = SCRIPT_DIR / "validate-production-env.py"
KEY_PATTERN = re.compile(r"[A-Za-z_][A-Za-z0-9_]*")
COMMENTED_ASSIGNMENT_PATTERN = re.compile(
    r"^\s*#\s*(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*="
)
# Stray commented-out copies of these are dropped from the rendered candidate
# regardless of the latch state below, so an old draft can never leave a
# leftover secret sitting in a comment.
EMAIL_LATCH_SECRET_KEYS = frozenset(
    {"SMTP_USERNAME", "SMTP_PASSWORD", "GOLE_VERIFICATION_EMAIL_FROM"}
)


def load_validator() -> ModuleType:
    spec = importlib.util.spec_from_file_location("gole_production_env_policy", VALIDATOR_PATH)
    if spec is None or spec.loader is None:
        raise RuntimeError("production validator could not be loaded")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def read_input(path: pathlib.Path) -> tuple[list[str], dict[str, str]]:
    if path.is_symlink() or not path.is_file():
        raise ValueError("input environment must be a regular non-symlink file")
    raw = path.read_bytes()
    if not raw or len(raw) > 128 * 1024 or b"\x00" in raw:
        raise ValueError("input environment size or content is invalid")
    try:
        text = raw.decode("utf-8-sig").replace("\r\n", "\n").replace("\r", "\n")
    except UnicodeDecodeError as exception:
        raise ValueError("input environment must be UTF-8") from exception

    lines = text.splitlines()
    values: dict[str, str] = {}
    for number, line in enumerate(lines, start=1):
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        if "=" not in line:
            raise ValueError(f"input environment line {number} has invalid syntax")
        key, value = line.split("=", 1)
        if not KEY_PATTERN.fullmatch(key):
            raise ValueError(f"input environment line {number} has an invalid key")
        if key in values:
            raise ValueError(f"input environment contains duplicate key: {key}")
        values[key] = value
    return lines, values


def render_candidate(
    original_lines: list[str], values: dict[str, str], policy_values: dict[str, str]
) -> str:
    replaced: set[str] = set()
    output: list[str] = []
    for line in original_lines:
        commented_assignment = COMMENTED_ASSIGNMENT_PATTERN.match(line)
        if (
            commented_assignment is not None
            and commented_assignment.group(1) in EMAIL_LATCH_SECRET_KEYS
        ):
            continue
        if not line.strip() or line.lstrip().startswith("#") or "=" not in line:
            output.append(line)
            continue
        key = line.split("=", 1)[0]
        if key in policy_values:
            output.append(f"{key}={values[key]}")
            replaced.add(key)
        else:
            output.append(line)

    for key in policy_values:
        if key not in replaced:
            output.append(f"{key}={values[key]}")
    return "\n".join(output) + "\n"


def create_candidate(contents: str, output_directory: pathlib.Path) -> pathlib.Path:
    if not output_directory.is_absolute():
        raise ValueError("output directory must be absolute")
    output_directory = output_directory.resolve(strict=True)
    if not output_directory.is_dir():
        raise ValueError("output directory must be an existing directory")
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL
    if hasattr(os, "O_NOFOLLOW"):
        flags |= os.O_NOFOLLOW
    for _ in range(10):
        candidate = output_directory / f"gole-env.{secrets.token_hex(16)}"
        try:
            descriptor = os.open(candidate, flags, 0o600)
        except FileExistsError:
            continue
        try:
            os.fchmod(descriptor, 0o600)
            with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as stream:
                stream.write(contents)
        except BaseException:
            candidate.unlink(missing_ok=True)
            raise
        return candidate
    raise RuntimeError("could not allocate a unique production environment candidate")


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(
        description="Preserve an env payload while enforcing the production environment policy."
    )
    parser.add_argument("input", type=pathlib.Path)
    parser.add_argument(
        "--smtp-password-stdin",
        action="store_true",
        help=argparse.SUPPRESS,
    )
    parser.add_argument("--output-directory", type=pathlib.Path, default=pathlib.Path("/tmp"))
    arguments = parser.parse_args(argv[1:])

    candidate: pathlib.Path | None = None
    try:
        if arguments.smtp_password_stdin:
            raise ValueError(
                "--smtp-password-stdin is unavailable while the Stage 0 production email channel is disabled"
            )
        validator = load_validator()
        lines, values = read_input(arguments.input)
        values.update(validator.EXACT_VALUES)
        validator.validate(values)
        rendered = render_candidate(lines, values, validator.EXACT_VALUES)
        # Reparse the final bytes too, so rendering cannot weaken duplicate or syntax checks.
        candidate = create_candidate(rendered, arguments.output_directory)
        validator.validate(validator.parse_env(candidate))
    except (OSError, RuntimeError, ValueError) as exception:
        if candidate is not None:
            candidate.unlink(missing_ok=True)
        print(str(exception), file=sys.stderr)
        return 1

    print(candidate)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
