#!/usr/bin/env python3
"""NDK-free tests for the first-party native runtime source build (LEGAL-7).

These run without an Android SDK/NDK and cover:
  * the pinned source lock (schema + SHA-256 provenance against the kept .deb recipes)
  * source hash verification accepting/rejecting payloads
  * the ELF inspector used for ABI/SONAME verification
  * the JNI packaging contract shared with prepare_android_runtime_native_libs.py
"""

from __future__ import annotations

import importlib.util
import json
import re
import struct
import sys
import tempfile
import unittest
from hashlib import sha256
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))


def load_module(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    assert spec and spec.loader
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


BUILD = load_module("build_android_runtime_from_source", REPO_ROOT / "scripts" / "build_android_runtime_from_source.py")
NATIVE_LIBS = load_module(
    "prepare_android_runtime_native_libs", REPO_ROOT / "scripts" / "prepare_android_runtime_native_libs.py"
)
LOCK_PATH = REPO_ROOT / "runtime_tools" / "native_sources.lock.json"


class LockFileTest(unittest.TestCase):
    def setUp(self) -> None:
        self.lock = json.loads(LOCK_PATH.read_text(encoding="utf-8"))

    def test_ndk_is_pinned(self) -> None:
        ndk = self.lock["ndk"]
        self.assertEqual(ndk["release"], "r29")
        self.assertTrue(ndk["version"])
        self.assertEqual(len(ndk["sha256"]), 64)
        self.assertTrue(ndk["url"].startswith("https://"))
        self.assertIn(ndk["api_level"], (24, 26))

    def test_sources_are_pinned_with_valid_sha256(self) -> None:
        self.assertEqual(set(self.lock["sources"]), {"proot", "libtalloc", "libandroid-shmem"})
        for name, entry in self.lock["sources"].items():
            self.assertEqual(len(entry["sha256"]), 64, name)
            int(entry["sha256"], 16)
            self.assertTrue(entry["url"].startswith("https://"), name)

    def test_source_hashes_match_kept_deb_recipes(self) -> None:
        recipes = REPO_ROOT / "runtime_tools" / "termux-packaging-recipes"
        expected = {}
        for recipe in recipes.glob("*.build.sh"):
            text = recipe.read_text(encoding="utf-8")
            version = re.search(r'TERMUX_PKG_VERSION="?([0-9][0-9.]*)"?', text)
            digest = re.search(r"TERMUX_PKG_SHA256=([0-9a-f]{64})", text)
            if version and digest:
                expected[version.group(1)] = digest.group(1)
        for entry in self.lock["sources"].values():
            self.assertIn(entry["version"], expected, entry["name"])
            self.assertEqual(entry["sha256"], expected[entry["version"]], entry["name"])

    def test_abi_matrix(self) -> None:
        self.assertEqual(set(self.lock["abis"]), {"arm64-v8a", "x86_64"})
        self.assertEqual(self.lock["abis"]["arm64-v8a"]["loader32_triple"], "armv7a-linux-androideabi")
        self.assertEqual(self.lock["abis"]["x86_64"]["loader32_triple"], "i686-linux-android")


class HashVerificationTest(unittest.TestCase):
    def test_accepts_matching_and_rejects_mismatched(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            archive = Path(tmp) / "payload.bin"
            payload = b"tariffiacode-native-source-test"
            archive.write_bytes(payload)
            BUILD.verify_archive(archive, sha256(payload).hexdigest())
            with self.assertRaises(BUILD.BuildError):
                BUILD.verify_archive(archive, sha256(payload + b"!").hexdigest())

    def test_rejects_size_mismatch(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            archive = Path(tmp) / "payload.bin"
            payload = b"abc"
            archive.write_bytes(payload)
            with self.assertRaises(BUILD.BuildError):
                BUILD.verify_archive(archive, sha256(payload).hexdigest(), expected_size=99)


class ElfInspectorTest(unittest.TestCase):
    def _write_elf64(self, path: Path) -> None:
        header = bytearray(64)
        header[0:4] = b"\x7fELF"
        header[4] = BUILD.ELF_CLASS_64
        header[5] = 1
        header[6] = 1
        struct.pack_into("<HH", header, 16, BUILD.ET_DYN, BUILD.EM_AARCH64)
        struct.pack_into("<H", header, 52, 64)
        path.write_bytes(bytes(header))

    def _write_elf32(self, path: Path) -> None:
        header = bytearray(52)
        header[0:4] = b"\x7fELF"
        header[4] = BUILD.ELF_CLASS_32
        header[5] = 1
        header[6] = 1
        struct.pack_into("<HH", header, 16, BUILD.ET_EXEC, BUILD.EM_ARM)
        struct.pack_into("<H", header, 40, 52)
        path.write_bytes(bytes(header))

    def test_elf64_header(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "a.elf"
            self._write_elf64(path)
            info = BUILD.inspect_elf(path)
            self.assertEqual(info.class_name, "ELF64")
            self.assertEqual(info.type_name, "ET_DYN")
            self.assertEqual(info.machine_name, "aarch64")
            self.assertIsNone(info.interpreter)

    def test_elf32_header(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "a.elf"
            self._write_elf32(path)
            info = BUILD.inspect_elf(path)
            self.assertEqual(info.class_name, "ELF32")
            self.assertEqual(info.type_name, "ET_EXEC")
            self.assertEqual(info.machine_name, "arm")


class JniContractTest(unittest.TestCase):
    def test_build_script_matches_native_libs_mapping(self) -> None:
        self.assertEqual(BUILD.NATIVE_EXECUTABLES, NATIVE_LIBS.NATIVE_EXECUTABLES)
        for name, target in BUILD.RUNTIME_LIBRARIES.items():
            if name in NATIVE_LIBS.RUNTIME_LIBRARIES:
                self.assertEqual(target, NATIVE_LIBS.RUNTIME_LIBRARIES[name])

    def test_native_libs_packaging_and_patch(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            assets = Path(tmp) / "assets"
            for abi in NATIVE_LIBS.ANDROID_ABIS:
                prefix = assets / "opencode-runtime" / abi / "prefix"
                (prefix / "bin").mkdir(parents=True)
                (prefix / "libexec" / "proot").mkdir(parents=True)
                (prefix / "lib").mkdir(parents=True)
                for relative in NATIVE_LIBS.NATIVE_EXECUTABLES:
                    (prefix / relative).write_bytes(b"\x7fELF")
                (prefix / "lib" / "libandroid-shmem.so").write_bytes(b"\x7fELF")
                # proot carries the pre-patch DT_NEEDED that the packaging step rewrites.
                (prefix / "bin" / "proot").write_bytes(b"DT_NEEDED\0libtalloc.so.2\0")
                (prefix / "lib" / "libtalloc.so.2.4.3").write_bytes(b"\x7fELF")

            output = Path(tmp) / "jni"
            NATIVE_LIBS.prepare_native_libs(linux_assets_dir=assets, output_dir=output)

            abi_out = output / "arm64-v8a"
            self.assertTrue((abi_out / "libopencode_android_proot.so").is_file())
            self.assertTrue((abi_out / "libopencode_android_proot_loader.so").is_file())
            self.assertTrue((abi_out / "libopencode_android_proot_loader32.so").is_file())
            self.assertTrue((abi_out / "libtalloc.so").is_file())
            self.assertTrue((abi_out / "libandroid-shmem.so").is_file())
            patched = (abi_out / "libopencode_android_proot.so").read_bytes()
            self.assertIn(b"libtalloc.so\0", patched)
            self.assertNotIn(b"libtalloc.so.2\0", patched)


class NdksysrootPatchTest(unittest.TestCase):
    PATCH_PATH = REPO_ROOT / "runtime_tools" / "ndk-patches" / "29" / "paths.h.patch"

    def _synthetic_paths_h(self) -> str:
        return "\n".join(
            [
                "/* paths.h */",
                "#include <sys/cdefs.h>",
                "",
                "/** Path to the default system shell. Historically the 'B' was to specify the Bourne shell. */",
                '#define _PATH_BSHELL "/system/bin/sh"',
                "",
                "/** Path to the system console. */",
                '#define _PATH_CONSOLE "/dev/console"',
                "",
                "/** Default shell search path. */",
                '#define _PATH_DEFPATH "/product/bin:/apex/com.android.runtime/bin:/apex/com.android.art/bin:/system_ext/bin:/system/bin:/system/xbin:/odm/bin:/vendor/bin:/vendor/xbin"',
                "",
                "/** Path to the directory containing device files. */",
                '#define _PATH_DEV "/dev/"',
                "",
                "/** Path to the calling process' tty. */",
                '#define _PATH_TTY "/dev/tty"',
                "",
            ]
        )

    def test_lock_pins_patch_with_matching_sha256(self) -> None:
        lock = json.loads(LOCK_PATH.read_text(encoding="utf-8"))
        patches = lock["ndk"]["sysroot_patches"]
        self.assertTrue(patches)
        for entry in patches:
            path = REPO_ROOT / entry["path"]
            self.assertTrue(path.is_file(), entry["path"])
            self.assertEqual(sha256(path.read_bytes()).hexdigest(), entry["sha256"], entry["path"])

    def test_apply_patch_adds_path_tmp(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            target = Path(tmp) / "paths.h"
            target.write_text(self._synthetic_paths_h(), encoding="utf-8")
            diff_text = self.PATCH_PATH.read_text(encoding="utf-8").replace(
                "@TERMUX_PREFIX@", "/data/data/com.termux/files/usr"
            )
            self.assertTrue(BUILD.apply_unified_diff(target, diff_text))
            content = target.read_text(encoding="utf-8")
            self.assertIn('_PATH_TMP       "/data/data/com.termux/files/usr/tmp/"', content)
            self.assertIn('_PATH_BSHELL "/data/data/com.termux/files/usr/bin/sh"', content)
            # Idempotent: re-applying must be a no-op and must not duplicate the appended block.
            self.assertFalse(BUILD.apply_unified_diff(target, diff_text))
            self.assertEqual(content.count("_PATH_TMP"), target.read_text(encoding="utf-8").count("_PATH_TMP"))

    def test_patch_target_relative(self) -> None:
        diff_text = self.PATCH_PATH.read_text(encoding="utf-8")
        self.assertEqual(BUILD._patch_target_relative(diff_text), "usr/include/paths.h")


class SelfTestTest(unittest.TestCase):
    def test_build_script_self_test(self) -> None:
        BUILD.self_test(LOCK_PATH)


if __name__ == "__main__":
    unittest.main()
