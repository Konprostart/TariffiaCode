#!/usr/bin/env python3
"""Create verified metadata for an APK asset consumed by the in-app updater."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
from pathlib import Path


CHANNELS = {
    "release": "com.konprostart.tariffiacode",
    "debug": "com.konprostart.tariffiacode.debug",
}
PACKAGE_PATTERN = re.compile(r"^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']*)'", re.MULTILINE)
COMMIT_PATTERN = re.compile(r"^[0-9a-f]{40,64}$")


def parse_apk_badging(output: str) -> tuple[str, int, str]:
    match = PACKAGE_PATTERN.search(output)
    if not match:
        raise ValueError("aapt2 output did not include APK package/version metadata")
    return match.group(1), int(match.group(2)), match.group(3)


def parse_signer_sha256(output: str) -> str:
    if "Verifies" not in output:
        raise ValueError("apksigner did not verify the APK")
    matches = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})$", output, re.MULTILINE)
    if len(matches) != 1:
        raise ValueError(f"expected exactly one APK signer, found {len(matches)}")
    return matches[0].lower()


def validate_package_id(channel: str, package_id: str) -> None:
    if channel not in CHANNELS:
        raise ValueError(f"unsupported update channel: {channel}")
    expected_package = CHANNELS[channel]
    if package_id != expected_package:
        raise ValueError(f"{channel} APK package must be {expected_package}, got {package_id}")


def tool_paths(android_home: Path) -> tuple[Path, Path]:
    build_tools = sorted(
        (path for path in (android_home / "build-tools").iterdir() if path.is_dir()),
        key=lambda path: tuple(int(part) if part.isdigit() else 0 for part in path.name.split(".")),
    )
    if not build_tools:
        raise FileNotFoundError("Android SDK build-tools are not installed")
    return build_tools[-1] / "aapt2", build_tools[-1] / "apksigner"


def create_manifest(
    apk: Path,
    channel: str,
    commit_sha: str,
    asset_name: str,
    aapt2: Path,
    apksigner: Path,
    expected_signer_sha256: str | None = None,
) -> dict[str, object]:
    if channel not in CHANNELS:
        raise ValueError(f"unsupported update channel: {channel}")
    if not COMMIT_PATTERN.fullmatch(commit_sha):
        raise ValueError("commit SHA must be a full 40- or 64-character hex hash")
    if not apk.is_file():
        raise FileNotFoundError(f"APK not found: {apk}")

    badging = subprocess.run([str(aapt2), "dump", "badging", str(apk)], check=True, capture_output=True, text=True)
    package_id, version_code, version_name = parse_apk_badging(badging.stdout)
    validate_package_id(channel, package_id)

    signing = subprocess.run(
        [str(apksigner), "verify", "--verbose", "--print-certs", str(apk)],
        check=True,
        capture_output=True,
        text=True,
    )
    signer_sha256 = parse_signer_sha256(signing.stdout)
    if expected_signer_sha256 is not None:
        expected_signer_sha256 = expected_signer_sha256.lower().replace(":", "")
        if not re.fullmatch(r"[0-9a-f]{64}", expected_signer_sha256):
            raise ValueError("expected signer SHA-256 must be 64 hexadecimal characters")
        if signer_sha256 != expected_signer_sha256:
            raise ValueError("APK signer does not match the configured signing key")
    apk_sha256 = hashlib.sha256(apk.read_bytes()).hexdigest()
    return {
        "schemaVersion": 1,
        "channel": channel,
        "applicationId": package_id,
        "versionName": version_name,
        "versionCode": version_code,
        "commitSha": commit_sha.lower(),
        "apkAssetName": asset_name,
        "apkSha256": apk_sha256,
        "apkSizeBytes": apk.stat().st_size,
        "signerSha256": signer_sha256,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--channel", required=True, choices=sorted(CHANNELS))
    parser.add_argument("--commit-sha", required=True)
    parser.add_argument("--asset-name", required=True)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--expected-signer-sha256")
    parser.add_argument("--android-home", type=Path, default=Path(os.environ.get("ANDROID_HOME", "")))
    args = parser.parse_args()
    aapt2, apksigner = tool_paths(args.android_home)
    manifest = create_manifest(
        args.apk,
        args.channel,
        args.commit_sha,
        args.asset_name,
        aapt2,
        apksigner,
        args.expected_signer_sha256,
    )
    args.output.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
