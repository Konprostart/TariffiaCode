from __future__ import annotations

import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from types import SimpleNamespace
from unittest.mock import patch

from scripts.create_app_update_manifest import create_manifest, parse_apk_badging, parse_signer_sha256, validate_package_id


class AppUpdateManifestTest(unittest.TestCase):
    def test_parses_package_and_build_metadata(self) -> None:
        package_id, version_code, version_name = parse_apk_badging(
            "package: name='com.konprostart.tariffiacode.debug' versionCode='74' versionName='1.2.35'"
        )
        self.assertEqual(("com.konprostart.tariffiacode.debug", 74, "1.2.35"), (package_id, version_code, version_name))

    def test_requires_exactly_one_verified_signer(self) -> None:
        digest = "a" * 64
        self.assertEqual(digest, parse_signer_sha256(f"Verifies\nSigner #1 certificate SHA-256 digest: {digest.upper()}"))
        with self.assertRaises(ValueError):
            parse_signer_sha256("DOES NOT VERIFY")

    def test_rejects_wrong_channel_package_id(self) -> None:
        with self.assertRaisesRegex(ValueError, "debug APK package"):
            validate_package_id("debug", "com.konprostart.tariffiacode")

    def test_accepts_expected_channel_package_ids(self) -> None:
        validate_package_id("debug", "com.konprostart.tariffiacode.debug")
        validate_package_id("release", "com.konprostart.tariffiacode")

    def test_manifest_can_require_the_configured_signer(self) -> None:
        signer = "a" * 64
        with TemporaryDirectory() as temp_dir:
            apk = Path(temp_dir) / "app.apk"
            apk.write_bytes(b"signed apk")
            with patch("scripts.create_app_update_manifest.subprocess.run") as run:
                run.side_effect = [
                    SimpleNamespace(stdout="package: name='com.konprostart.tariffiacode' versionCode='74' versionName='1.2.35'"),
                    SimpleNamespace(stdout=f"Verifies\nSigner #1 certificate SHA-256 digest: {signer}"),
                ]
                manifest = create_manifest(
                    apk,
                    "release",
                    "c" * 40,
                    "tariffiacode-v1.2.35-release.apk",
                    Path("aapt2"),
                    Path("apksigner"),
                    expected_signer_sha256=":".join(signer[index : index + 2] for index in range(0, 64, 2)).upper(),
                )
                self.assertEqual(signer, manifest["signerSha256"])

                run.side_effect = [
                    SimpleNamespace(stdout="package: name='com.konprostart.tariffiacode' versionCode='74' versionName='1.2.35'"),
                    SimpleNamespace(stdout=f"Verifies\nSigner #1 certificate SHA-256 digest: {signer}"),
                ]
                with self.assertRaisesRegex(ValueError, "does not match"):
                    create_manifest(
                        apk,
                        "release",
                        "c" * 40,
                        "tariffiacode-v1.2.35-release.apk",
                        Path("aapt2"),
                        Path("apksigner"),
                        expected_signer_sha256="b" * 64,
                    )


if __name__ == "__main__":
    unittest.main()
