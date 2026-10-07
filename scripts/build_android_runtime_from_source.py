#!/usr/bin/env python3
"""Build the embedded Android PRoot runtime from pinned first-party sources.

This replaces the build-time ``.deb`` dependency (``prepare_android_runtime_assets.py`` +
``runtime_tools/termux_assets.lock.json``) with a self-contained source build driven by a pinned
Android NDK. The old ``.deb`` recipe/lock files are intentionally kept as provenance.

The output layout is byte-for-byte compatible with what ``prepare_android_runtime_native_libs.py``
already consumes (``<output>/opencode-runtime/<abi>/prefix``), so the JNI layout, file names, SONAMEs
and packaging contract are unchanged. No Kotlin/Java runtime behaviour is touched.

Pinned inputs live in ``runtime_tools/native_sources.lock.json``:
  * Android NDK r29 (29.0.14206865) with a SHA-256 taken from Termux's ``setup-android-sdk.sh``.
  * proot 5.1.107.92, libtalloc 2.4.3, libandroid-shmem 0.7 source archives with SHA-256 pins.

Build matrix:
  * arm64-v8a: 64-bit proot + 64-bit loader + 32-bit ARM (armv7a) loader32
  * x86_64:    64-bit proot + 64-bit loader + 32-bit i386 loader32
"""

from __future__ import annotations

import argparse
import json
import os
import platform
import re
import shutil
import struct
import subprocess
import sys
import tarfile
import time
import urllib.request
import zipfile
from dataclasses import dataclass, field
from hashlib import sha1, sha256
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

from runtime_tools.termux_assets import (  # noqa: E402
    ANDROID_LINUX_ASSET_ROOT,
    ANDROID_TO_TERMUX_ARCH,
    asset_manifest_path,
    asset_prefix_dir,
    write_manifest,
)

DEFAULT_LOCK_FILE = REPO_ROOT / "runtime_tools" / "native_sources.lock.json"

# Must stay in sync with scripts/prepare_android_runtime_native_libs.py.
NATIVE_EXECUTABLES = {
    "bin/proot": "libopencode_android_proot.so",
    "libexec/proot/loader": "libopencode_android_proot_loader.so",
    "libexec/proot/loader32": "libopencode_android_proot_loader32.so",
}
RUNTIME_LIBRARIES = {
    "libandroid-shmem.so": "libandroid-shmem.so",
    "libtalloc.so.2.4.3": "libtalloc.so",
}

# proot build knobs mirrored from runtime_tools/termux-packaging-recipes/proot.build.sh.
PROOT_VERSION = "5.1.107.92"
PROOT_CPPFLAGS = "-DARG_MAX=131072"
# Kept identical to the Termux build so the compile-time fallback path matches the previous binary.
PROOT_UNBUNDLE_LOADER = "/data/data/com.termux/files/usr/libexec/proot"
# Termux prefix used when applying its NDK sysroot patches (@TERMUX_PREFIX@ substitution).
TERMUX_PREFIX = "/data/data/com.termux/files/usr"
NDK_PATCHES_DIR = REPO_ROOT / "runtime_tools" / "ndk-patches"

# proot loader load addresses from src/arch.h.
LOADER64_ADDRESS = {
    "arm64-v8a": "0x2000000000",
    "x86_64": "0x600000000000",
}
LOADER32_ADDRESS = {
    "arm64-v8a": "0x20000000",
    "x86_64": "0xa0000000",
}
LOADER32_EXTRA_CFLAGS = {
    "arm64-v8a": [],
    "x86_64": ["-mregparm=3"],
}

ELF_CLASS_32 = 1
ELF_CLASS_64 = 2
ET_EXEC = 2
ET_DYN = 3
EM_386 = 3
EM_ARM = 40
EM_X86_64 = 62
EM_AARCH64 = 183
DT_NULL = 0
DT_NEEDED = 1
DT_STRTAB = 5
DT_SONAME = 14
SHT_DYNAMIC = 6
PT_INTERP = 3


class BuildError(RuntimeError):
    pass


# --------------------------------------------------------------------------------------------------
# Generic helpers
# --------------------------------------------------------------------------------------------------


def log(message: str) -> None:
    sys.stderr.write(f"[native-source] {message}\n")
    sys.stderr.flush()


def run(command: list[str], cwd: Path | None = None, env: dict[str, str] | None = None) -> None:
    printable = " ".join(str(part) for part in command)
    log(f"$ {printable}" + (f"  (cwd={cwd})" if cwd else ""))
    subprocess.run([str(part) for part in command], cwd=str(cwd) if cwd else None, env=env, check=True)


def hash_file(path: Path, algorithm: str) -> str:
    digest = sha256() if algorithm == "sha256" else sha1()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def download(url: str, destination: Path, attempts: int = 3) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    headers = {"User-Agent": "tariffiacode-native-source-build/1.0"}
    last_error: Exception | None = None
    for attempt in range(attempts):
        try:
            request = urllib.request.Request(url, headers=headers)
            with urllib.request.urlopen(request, timeout=300) as response, destination.open("wb") as out:
                shutil.copyfileobj(response, out, length=1024 * 1024)
            return
        except Exception as exc:  # pragma: no cover - live network path
            last_error = exc
            time.sleep(1.0 * (attempt + 1))
    raise BuildError(f"Failed to download {url}: {last_error}")


def verify_archive(path: Path, expected_sha256: str, expected_size: int | None = None) -> None:
    if expected_size is not None and path.stat().st_size != expected_size:
        raise BuildError(
            f"Size mismatch for {path.name}: expected {expected_size}, got {path.stat().st_size}"
        )
    actual = hash_file(path, "sha256")
    if actual != expected_sha256:
        raise BuildError(f"SHA-256 mismatch for {path.name}: expected {expected_sha256}, got {actual}")


def fetch_verified(url: str, destination: Path, expected_sha256: str, expected_size: int | None = None) -> Path:
    if destination.is_file():
        try:
            verify_archive(destination, expected_sha256, expected_size)
            log(f"Reusing verified archive {destination}")
            return destination
        except BuildError as exc:
            log(f"Discarding cached archive: {exc}")
            destination.unlink()
    log(f"Downloading {url}")
    partial = destination.with_suffix(destination.suffix + ".partial")
    partial.unlink(missing_ok=True)
    download(url, partial)
    verify_archive(partial, expected_sha256, expected_size)
    partial.replace(destination)
    return destination


def extract_archive(archive: Path, destination: Path, kind: str) -> Path:
    if destination.exists():
        shutil.rmtree(destination)
    destination.mkdir(parents=True, exist_ok=True)
    if kind == "zip":
        with zipfile.ZipFile(archive) as bundle:
            bundle.extractall(destination)
    elif kind == "tar.gz":
        with tarfile.open(archive, "r:gz") as bundle:
            try:
                bundle.extractall(destination, filter="data")
            except TypeError:  # Python < 3.11.4
                bundle.extractall(destination)
    else:
        raise BuildError(f"Unsupported archive kind: {kind}")
    entries = list(destination.iterdir())
    if len(entries) == 1 and entries[0].is_dir():
        return entries[0]
    return destination


# --------------------------------------------------------------------------------------------------
# ELF inspection (pure Python; used to verify the packaging/ABI contract without external tools)
# --------------------------------------------------------------------------------------------------


@dataclass
class ElfInfo:
    elf_class: int
    e_type: int
    machine: int
    interpreter: str | None = None
    needed: list[str] = field(default_factory=list)
    soname: str | None = None

    @property
    def class_name(self) -> str:
        return "ELF64" if self.elf_class == ELF_CLASS_64 else "ELF32"

    @property
    def type_name(self) -> str:
        return {ET_EXEC: "ET_EXEC", ET_DYN: "ET_DYN"}.get(self.e_type, str(self.e_type))

    @property
    def machine_name(self) -> str:
        return {
            EM_386: "i386",
            EM_ARM: "arm",
            EM_X86_64: "x86_64",
            EM_AARCH64: "aarch64",
        }.get(self.machine, str(self.machine))


def inspect_elf(path: Path) -> ElfInfo:
    data = path.read_bytes()
    if data[:4] != b"\x7fELF":
        raise BuildError(f"{path} is not an ELF file")
    elf_class = data[4]
    endian = "<" if data[5] == 1 else ">"
    if elf_class == ELF_CLASS_64:
        e_type, machine = struct.unpack_from(endian + "HH", data, 16)
        e_phoff = struct.unpack_from(endian + "Q", data, 32)[0]
        e_shoff = struct.unpack_from(endian + "Q", data, 40)[0]
        e_phentsize, e_phnum = struct.unpack_from(endian + "HH", data, 54)
        e_shentsize, e_shnum = struct.unpack_from(endian + "HH", data, 58)
    else:
        e_type, machine = struct.unpack_from(endian + "HH", data, 16)
        e_phoff = struct.unpack_from(endian + "I", data, 28)[0]
        e_shoff = struct.unpack_from(endian + "I", data, 32)[0]
        e_phentsize, e_phnum = struct.unpack_from(endian + "HH", data, 42)
        e_shentsize, e_shnum = struct.unpack_from(endian + "HH", data, 46)

    interpreter: str | None = None
    for index in range(e_phnum):
        offset = e_phoff + index * e_phentsize
        p_type = struct.unpack_from(endian + "I", data, offset)[0]
        if p_type != PT_INTERP:
            continue
        if elf_class == ELF_CLASS_64:
            p_offset = struct.unpack_from(endian + "Q", data, offset + 8)[0]
            p_filesz = struct.unpack_from(endian + "Q", data, offset + 32)[0]
        else:
            p_offset = struct.unpack_from(endian + "I", data, offset + 4)[0]
            p_filesz = struct.unpack_from(endian + "I", data, offset + 16)[0]
        interpreter = data[p_offset : p_offset + p_filesz].split(b"\0", 1)[0].decode("utf-8", "replace")
        break

    needed: list[str] = []
    soname: str | None = None
    sections: dict[int, tuple[int, int, int, int]] = {}
    for index in range(e_shnum):
        offset = e_shoff + index * e_shentsize
        sh_name = struct.unpack_from(endian + "I", data, offset)[0]
        sh_type = struct.unpack_from(endian + "I", data, offset + 4)[0]
        if elf_class == ELF_CLASS_64:
            sh_offset = struct.unpack_from(endian + "Q", data, offset + 24)[0]
            sh_size = struct.unpack_from(endian + "Q", data, offset + 32)[0]
            sh_link = struct.unpack_from(endian + "I", data, offset + 40)[0]
        else:
            sh_offset = struct.unpack_from(endian + "I", data, offset + 16)[0]
            sh_size = struct.unpack_from(endian + "I", data, offset + 20)[0]
            sh_link = struct.unpack_from(endian + "I", data, offset + 24)[0]
        sections[index] = (sh_type, sh_offset, sh_size, sh_link)

    entry_size = 16 if elf_class == ELF_CLASS_64 else 8
    tag_format = endian + ("Qq" if elf_class == ELF_CLASS_64 else "Ii")
    for sh_type, sh_offset, sh_size, sh_link in sections.values():
        if sh_type != SHT_DYNAMIC:
            continue
        strtab = sections.get(sh_link)
        if strtab is None or strtab[0] != 3:
            continue
        str_offset, str_size = strtab[1], strtab[2]
        strings = data[str_offset : str_offset + str_size]
        count = sh_size // entry_size
        for entry in range(count):
            tag, value = struct.unpack_from(tag_format, data, sh_offset + entry * entry_size)
            if tag == DT_NULL:
                break
            if tag in (DT_NEEDED, DT_SONAME):
                name = strings[value:].split(b"\0", 1)[0].decode("utf-8", "replace")
                if tag == DT_NEEDED:
                    needed.append(name)
                else:
                    soname = name
    return ElfInfo(
        elf_class=elf_class,
        e_type=e_type,
        machine=machine,
        interpreter=interpreter,
        needed=needed,
        soname=soname,
    )


# --------------------------------------------------------------------------------------------------
# NDK resolution
# --------------------------------------------------------------------------------------------------


@dataclass
class Ndk:
    root: Path
    host_tag: str
    version: str
    api_level: int

    @property
    def bin(self) -> Path:
        return self.root / "toolchains" / "llvm" / "prebuilt" / self.host_tag / "bin"

    def clang(self, triple: str) -> Path:
        candidate = self.bin / f"{triple}{self.api_level}-clang"
        if candidate.is_file():
            return candidate
        fallback = self.bin / f"{triple}-clang"
        if fallback.is_file():
            return fallback
        raise BuildError(f"NDK clang for {triple} not found under {self.bin}")

    def tool(self, name: str) -> Path:
        candidate = self.bin / name
        if not candidate.is_file():
            raise BuildError(f"NDK tool {name} not found under {self.bin}")
        return candidate


def ndk_host_tag(lock: dict) -> str:
    system = platform.system().lower()
    tag = lock["ndk"]["host_tags"].get(system)
    if tag is None:
        raise BuildError(f"Unsupported build host: {platform.system()}")
    return tag


def ndk_version_matches(root: Path, version: str) -> bool:
    source_properties = root / "source.properties"
    if not source_properties.is_file():
        return False
    for line in source_properties.read_text(encoding="utf-8", errors="ignore").splitlines():
        if line.strip().startswith("Pkg.Revision"):
            return line.split("=", 1)[1].strip().startswith(version)
    return False


def _parse_unified_diff(diff_text: str) -> list[tuple[int, list[str], list[str]]]:
    """Return (old_start_1_based, old_lines, new_lines) for each hunk of a unified diff."""
    hunks: list[tuple[int, list[str], list[str]]] = []
    lines = diff_text.split("\n")
    index = 0
    while index < len(lines):
        line = lines[index]
        match = re.match(r"^@@ -(\d+)(?:,\d+)? \+\d+(?:,\d+)? @@", line)
        if not match:
            index += 1
            continue
        old_start = int(match.group(1))
        index += 1
        old_lines: list[str] = []
        new_lines: list[str] = []
        while index < len(lines):
            current = lines[index]
            if current.startswith("@@") or current.startswith("diff ") or current.startswith("--- ") or current.startswith("+++ "):
                break
            if current.startswith("\\"):  # "\ No newline at end of file"
                index += 1
                continue
            if current.startswith("-"):
                old_lines.append(current[1:])
            elif current.startswith("+"):
                new_lines.append(current[1:])
            elif current.startswith(" "):
                old_lines.append(current[1:])
                new_lines.append(current[1:])
            elif current == "":
                old_lines.append("")
                new_lines.append("")
            else:
                break
            index += 1
        hunks.append((old_start, old_lines, new_lines))
    return hunks


def _find_block(content: list[str], block: list[str], expected: int) -> int | None:
    if not block:
        return None
    if 0 <= expected <= len(content) - len(block) and content[expected : expected + len(block)] == block:
        return expected
    for start in range(0, len(content) - len(block) + 1):
        if content[start : start + len(block)] == block:
            return start
    return None


def apply_unified_diff(target: Path, diff_text: str) -> bool:
    """Apply a unified diff to *target* in place. Returns False if already applied."""
    hunks = _parse_unified_diff(diff_text)
    if not hunks:
        raise BuildError(f"Patch for {target} contains no hunks")
    content = target.read_text(encoding="utf-8").split("\n")
    added = [line for _, _, new_lines in hunks for line in new_lines if line.strip()]
    if added and all(line in content for line in added):
        return False  # already applied
    offset = 0
    for old_start, old_lines, new_lines in hunks:
        index = _find_block(content, old_lines, old_start - 1 + offset)
        if index is None:
            raise BuildError(f"Failed to locate hunk @@ -{old_start} in {target}")
        content[index : index + len(old_lines)] = new_lines
        offset += len(new_lines) - len(old_lines)
    target.write_text("\n".join(content), encoding="utf-8")
    return True


def apply_sysroot_patches(ndk_root: Path, host_tag: str, lock: dict) -> None:
    """Reproducibly apply the pinned Termux NDK sysroot patches to the extracted NDK."""
    entries = lock["ndk"].get("sysroot_patches", [])
    if not entries:
        return
    sysroot = ndk_root / "toolchains" / "llvm" / "prebuilt" / host_tag / "sysroot"
    for entry in entries:
        patch_path = (REPO_ROOT / entry["path"]).resolve()
        if not patch_path.is_file():
            raise BuildError(f"Pinned NDK sysroot patch not found: {patch_path}")
        actual = hash_file(patch_path, "sha256")
        if actual != entry["sha256"]:
            raise BuildError(
                f"NDK sysroot patch SHA-256 mismatch for {entry['path']}: "
                f"expected {entry['sha256']}, got {actual}"
            )
        diff_text = patch_path.read_text(encoding="utf-8")
        diff_text = diff_text.replace("@TERMUX_PREFIX@", entry.get("termux_prefix", TERMUX_PREFIX))
        target_relative = _patch_target_relative(diff_text)
        if target_relative is None:
            raise BuildError(f"Unable to determine target for NDK patch {entry['path']}")
        target = sysroot / target_relative
        if not target.is_file():
            raise BuildError(f"NDK sysroot patch target not found: {target}")
        if apply_unified_diff(target, diff_text):
            log(f"Applied NDK sysroot patch {entry['path']} -> {target_relative}")
        else:
            log(f"NDK sysroot patch already applied: {entry['path']}")


def _patch_target_relative(diff_text: str) -> str | None:
    for line in diff_text.split("\n"):
        if line.startswith("+++ "):
            name = line[4:].split("\t", 1)[0].strip()
            if name in ("/dev/null", ""):
                return None
            for prefix in ("b/", "./", "a/"):
                if name.startswith(prefix):
                    name = name[len(prefix) :]
                    break
            return name
    return None


def resolve_ndk(args: argparse.Namespace, lock: dict, work_dir: Path) -> Ndk:
    host_tag = ndk_host_tag(lock)
    version = lock["ndk"]["version"]
    api_level = int(lock["ndk"]["api_level"])

    candidates: list[Path] = []
    if args.ndk_dir:
        candidates.append(Path(args.ndk_dir).expanduser().resolve())
    for key in ("ANDROID_NDK_HOME", "ANDROID_NDK_ROOT"):
        value = os.environ.get(key)
        if value:
            candidates.append(Path(value).expanduser().resolve())
    for key in ("ANDROID_SDK_ROOT", "ANDROID_HOME"):
        value = os.environ.get(key)
        if value:
            candidates.append(Path(value).expanduser().resolve() / "ndk" / version)

    for candidate in candidates:
        if candidate.is_dir():
            if ndk_version_matches(candidate, version):
                log(f"Using pinned NDK {version} at {candidate}")
                apply_sysroot_patches(candidate, host_tag, lock)
                return Ndk(root=candidate, host_tag=host_tag, version=version, api_level=api_level)
            log(f"Ignoring NDK at {candidate}: version does not match pinned {version}")

    if not args.download_ndk:
        raise BuildError(
            "Pinned Android NDK "
            f"{version} not found. Set ANDROID_NDK_HOME/ANDROID_NDK_ROOT or pass --ndk-dir, "
            "or pass --download-ndk to fetch and verify it."
        )

    archive_name = Path(lock["ndk"]["url"]).name
    archive = work_dir / "ndk" / archive_name
    fetch_verified(
        lock["ndk"]["url"],
        archive,
        expected_sha256=lock["ndk"]["sha256"],
        expected_size=lock["ndk"].get("size"),
    )
    extracted_root = work_dir / "ndk" / f"android-ndk-r{lock['ndk']['release']}"
    if not ndk_version_matches(extracted_root, version):
        if extracted_root.exists():
            shutil.rmtree(extracted_root)
        log(f"Extracting {archive_name}")
        with zipfile.ZipFile(archive) as bundle:
            bundle.extractall(work_dir / "ndk")
    if not ndk_version_matches(extracted_root, version):
        raise BuildError(f"Extracted NDK at {extracted_root} does not report version {version}")
    apply_sysroot_patches(extracted_root, host_tag, lock)
    return Ndk(root=extracted_root, host_tag=host_tag, version=version, api_level=api_level)


# --------------------------------------------------------------------------------------------------
# Source package builds
# --------------------------------------------------------------------------------------------------


def ensure_include(path: Path, include: str, anchor: str) -> None:
    """Idempotently insert ``include`` right after ``anchor`` in a pinned-source file."""
    text = path.read_text(encoding="utf-8")
    if include in text:
        return
    if anchor not in text:
        raise BuildError(f"{path.name} layout changed; cannot add {include}")
    path.write_text(text.replace(anchor, anchor + include + "\n", 1), encoding="utf-8")


def apply_libandroid_shmem_source_fix(source_dir: Path) -> None:
    """Add ``<fcntl.h>`` to ``shmem.c`` so ``open()``/``O_RDWR`` are declared.

    libandroid-shmem 0.7 calls ``open("/dev/ashmem", ...)`` on API < 26 but never includes
    ``<fcntl.h>``; clang rejects the resulting implicit declaration.
    """
    ensure_include(source_dir / "shmem.c", "#include <fcntl.h>", "#include <errno.h>\n")


def apply_proot_source_fix(source_dir: Path) -> None:
    """Add ``<string.h>`` to proot's ``ashmem_memfd.c`` so ``strcmp``/``memset`` are declared.

    The pinned termux/proot source uses both without including ``<string.h>``; clang rejects the
    resulting implicit declarations.
    """
    ensure_include(
        source_dir / "src" / "extension" / "ashmem_memfd" / "ashmem_memfd.c",
        "#include <string.h>",
        "#include <stdlib.h>\n",
    )


def build_libandroid_shmem(source_dir: Path, stage: Path, ndk: Ndk, triple: str) -> None:
    apply_libandroid_shmem_source_fix(source_dir)
    clang = ndk.clang(triple)
    env = os.environ.copy()
    env.update(
        {
            "CC": str(clang),
            "AR": str(ndk.tool("llvm-ar")),
            "RANLIB": str(ndk.tool("llvm-ranlib")),
        }
    )
    run(["make", "-j", "libandroid-shmem.so"], cwd=source_dir, env=env)
    library = source_dir / "libandroid-shmem.so"
    if not library.is_file():
        raise BuildError("libandroid-shmem.so was not produced")
    lib_dir = stage / "lib"
    lib_dir.mkdir(parents=True, exist_ok=True)
    shutil.copy2(library, lib_dir / "libandroid-shmem.so")
    include_dir = stage / "include" / "sys"
    include_dir.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source_dir / "shm.h", include_dir / "shm.h")


CROSS_ANSWERS = """\
Checking uname sysname type: "Linux"
Checking uname machine type: "dontcare"
Checking uname release type: "dontcare"
Checking uname version type: "dontcare"
Checking simple C program: OK
building library support: OK
Checking for large file support: OK
Checking for -D_FILE_OFFSET_BITS=64: OK
Checking for WORDS_BIGENDIAN: OK
Checking for C99 vsnprintf: OK
Checking for HAVE_SECURE_MKSTEMP: OK
rpath library support: OK
-Wl,--version-script support: FAIL
Checking correct behavior of strtoll: OK
Checking correct behavior of strptime: OK
Checking for HAVE_IFACE_GETIFADDRS: OK
Checking for HAVE_IFACE_IFCONF: OK
Checking for HAVE_IFACE_IFREQ: OK
Checking getconf LFS_CFLAGS: OK
Checking for large file support without additional flags: OK
Checking for working strptime: OK
Checking for HAVE_SHARED_MMAP: OK
Checking for HAVE_MREMAP: OK
Checking for HAVE_INCOHERENT_MMAP: OK
Checking getconf large file support flags work: OK
"""


def build_libtalloc(source_dir: Path, stage: Path, ndk: Ndk, triple: str) -> None:
    clang = ndk.clang(triple)
    env = os.environ.copy()
    env.update(
        {
            "CC": str(clang),
            "AR": str(ndk.tool("llvm-ar")),
            "RANLIB": str(ndk.tool("llvm-ranlib")),
            "STRIP": str(ndk.tool("llvm-strip")),
            "OBJCOPY": str(ndk.tool("llvm-objcopy")),
            "OBJDUMP": str(ndk.tool("llvm-objdump")),
            "PYTHON": sys.executable,
        }
    )
    (source_dir / "cross-answers.txt").write_text(CROSS_ANSWERS, encoding="utf-8")
    run(
        [
            "sh",
            "./configure",
            f"--prefix={stage}",
            "--disable-rpath",
            "--disable-python",
            "--cross-compile",
            "--cross-answers=cross-answers.txt",
        ],
        cwd=source_dir,
        env=env,
    )
    run(["make", "-j"], cwd=source_dir, env=env)
    run(["make", "install"], cwd=source_dir, env=env)

    lib_dir = stage / "lib"
    versioned = lib_dir / "libtalloc.so.2.4.3"
    if not versioned.is_file():
        candidates = sorted(lib_dir.glob("libtalloc.so.2.4.3*")) or sorted(lib_dir.glob("libtalloc.so*"))
        raise BuildError(f"libtalloc shared library not found in {lib_dir} (saw {candidates})")
    for link in ("libtalloc.so.2", "libtalloc.so"):
        link_path = lib_dir / link
        if not link_path.exists():
            link_path.symlink_to(versioned.name)


def rosegment_supported(clang: Path) -> bool:
    probe = "int main(void){return 0;}\n"
    try:
        result = subprocess.run(
            [str(clang), "-xc", "-", "-Wl,--rosegment", "-o", os.devnull],
            input=probe.encode("utf-8"),
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
    except OSError:
        return False
    return result.returncode == 0


def build_loader(
    source_dir: Path,
    clang: Path,
    output: Path,
    address: str,
    extra_cflags: list[str],
    cppflags: str,
    rosegment: bool,
) -> None:
    work = output.parent / (output.name + ".objects")
    work.mkdir(parents=True, exist_ok=True)
    loader_object = work / "loader.o"
    assembly_object = work / "assembly.o"
    common = [
        str(clang),
        *cppflags.split(),
        "-fPIC",
        "-ffreestanding",
        *extra_cflags,
    ]
    run([*common, "-c", "loader/loader.c", "-o", str(loader_object)], cwd=source_dir)
    run([*common, "-c", "loader/assembly.S", "-o", str(assembly_object)], cwd=source_dir)
    linker = f"-Wl,--build-id=none,-Ttext={address}"
    if rosegment:
        linker += ",--rosegment"
    linker += ",-z,noexecstack"
    run(
        [
            str(clang),
            "-o",
            str(output),
            str(loader_object),
            str(assembly_object),
            "-static",
            "-nostdlib",
            linker,
        ],
        cwd=source_dir,
    )


def build_proot(
    source_dir: Path,
    stage: Path,
    ndk: Ndk,
    triple: str,
    loader32_triple: str,
    abi: str,
    rosegment: bool,
) -> None:
    apply_proot_source_fix(source_dir)
    clang = ndk.clang(triple)
    strip = ndk.tool("llvm-strip")

    proot_src = source_dir / "src"
    loader64 = proot_src / "loader" / "loader"
    loader32 = proot_src / "loader" / "loader-m32"
    loader64.parent.mkdir(parents=True, exist_ok=True)
    # The loaders are freestanding and never reference VERSION, so only the shared arch flags apply.
    # -I. mirrors the Makefile's include path: loader.c includes "arch.h"/"compat.h" from src/.
    loader_cppflags = f"-DARG_MAX=131072 -I. -I{stage / 'include'}"
    build_loader(
        proot_src,
        clang,
        loader64,
        LOADER64_ADDRESS[abi],
        [],
        loader_cppflags,
        rosegment,
    )
    build_loader(
        proot_src,
        ndk.clang(loader32_triple),
        loader32,
        LOADER32_ADDRESS[abi],
        LOADER32_EXTRA_CFLAGS[abi],
        loader_cppflags,
        rosegment,
    )

    env = os.environ.copy()
    # Escaped quotes so the shell that expands the Makefile recipes hands clang a C string literal.
    env["CPPFLAGS"] = f"-DARG_MAX=131072 -DVERSION=\\\"{PROOT_VERSION}\\\" -I{stage / 'include'}"
    env["LDFLAGS"] = f"-L{stage / 'lib'}"
    run(
        [
            "make",
            "-C",
            "src",
            "-o",
            "loader/loader",
            "-o",
            "loader/loader-m32",
            "proot",
            f"CC={clang}",
            f"LD={clang}",
            f"STRIP={strip}",
            "PROOT_WITH_LIBANDROID_SHMEM=true",
            f"PROOT_UNBUNDLE_LOADER={PROOT_UNBUNDLE_LOADER}",
        ],
        cwd=source_dir,
        env=env,
    )

    proot_binary = source_dir / "src" / "proot"
    if not proot_binary.is_file():
        raise BuildError("proot binary was not produced")

    bin_dir = stage / "bin"
    bin_dir.mkdir(parents=True, exist_ok=True)
    shutil.copy2(proot_binary, bin_dir / "proot")
    loader_dir = stage / "libexec" / "proot"
    loader_dir.mkdir(parents=True, exist_ok=True)
    shutil.copy2(loader64, loader_dir / "loader")
    shutil.copy2(loader32, loader_dir / "loader32")


def prune_stage(stage: Path) -> None:
    for relative in ("include", "lib/pkgconfig", "share/doc", "share/info", "share/man", "share/LICENSES"):
        path = stage / relative
        if path.is_dir():
            shutil.rmtree(path, ignore_errors=True)


# --------------------------------------------------------------------------------------------------
# Verification
# --------------------------------------------------------------------------------------------------


def verify_prefix(prefix_dir: Path, abi: str, lock: dict) -> dict:
    abi_lock = lock["abis"][abi]
    expected_machine = int(abi_lock["elf_machine"])
    expected_loader32_machine = int(abi_lock["loader32_elf_machine"])

    proot = inspect_elf(prefix_dir / "bin" / "proot")
    loader = inspect_elf(prefix_dir / "libexec" / "proot" / "loader")
    loader32 = inspect_elf(prefix_dir / "libexec" / "proot" / "loader32")
    talloc = inspect_elf(prefix_dir / "lib" / "libtalloc.so.2.4.3")
    shmem = inspect_elf(prefix_dir / "lib" / "libandroid-shmem.so")

    errors: list[str] = []

    def expect(condition: bool, message: str) -> None:
        if not condition:
            errors.append(message)

    expect(proot.elf_class == ELF_CLASS_64, f"proot must be ELF64, got {proot.class_name}")
    expect(proot.e_type == ET_DYN, f"proot must be PIE (ET_DYN), got {proot.type_name}")
    expect(proot.machine == expected_machine, f"proot machine {proot.machine_name} != expected {abi}")
    expect(
        proot.interpreter == "/system/bin/linker64",
        f"proot interpreter must be /system/bin/linker64, got {proot.interpreter}",
    )
    expect("libtalloc.so.2" in proot.needed, f"proot must NEED libtalloc.so.2, got {proot.needed}")
    expect(
        "libandroid-shmem.so" in proot.needed,
        f"proot must NEED libandroid-shmem.so, got {proot.needed}",
    )

    expect(loader.elf_class == ELF_CLASS_64, f"loader must be ELF64, got {loader.class_name}")
    expect(loader.e_type == ET_EXEC, f"loader must be ET_EXEC, got {loader.type_name}")
    expect(loader.machine == expected_machine, f"loader machine {loader.machine_name} != expected {abi}")
    expect(loader.interpreter is None, f"loader must be static (no interpreter), got {loader.interpreter}")

    expect(loader32.elf_class == ELF_CLASS_32, f"loader32 must be ELF32, got {loader32.class_name}")
    expect(loader32.e_type == ET_EXEC, f"loader32 must be ET_EXEC, got {loader32.type_name}")
    expect(
        loader32.machine == expected_loader32_machine,
        f"loader32 machine {loader32.machine_name} != expected {expected_loader32_machine}",
    )
    expect(loader32.interpreter is None, f"loader32 must be static, got {loader32.interpreter}")

    expect(talloc.elf_class == ELF_CLASS_64, f"libtalloc must be ELF64, got {talloc.class_name}")
    expect(talloc.e_type == ET_DYN, f"libtalloc must be ET_DYN, got {talloc.type_name}")
    expect(talloc.machine == expected_machine, f"libtalloc machine {talloc.machine_name} != expected {abi}")
    expect(talloc.soname == "libtalloc.so.2", f"libtalloc SONAME must be libtalloc.so.2, got {talloc.soname}")

    expect(shmem.elf_class == ELF_CLASS_64, f"libandroid-shmem must be ELF64, got {shmem.class_name}")
    expect(shmem.e_type == ET_DYN, f"libandroid-shmem must be ET_DYN, got {shmem.type_name}")
    expect(shmem.machine == expected_machine, f"libandroid-shmem machine {shmem.machine_name} != expected {abi}")

    if errors:
        raise BuildError("Native ABI verification failed for " + abi + ":\n  - " + "\n  - ".join(errors))

    return {
        "proot": {
            "class": proot.class_name,
            "type": proot.type_name,
            "machine": proot.machine_name,
            "interpreter": proot.interpreter,
            "needed": proot.needed,
        },
        "loader": {"class": loader.class_name, "type": loader.type_name, "machine": loader.machine_name},
        "loader32": {
            "class": loader32.class_name,
            "type": loader32.type_name,
            "machine": loader32.machine_name,
        },
        "libtalloc": {"class": talloc.class_name, "type": talloc.type_name, "soname": talloc.soname},
        "libandroid-shmem": {"class": shmem.class_name, "type": shmem.type_name},
    }


def manifest_files(prefix_dir: Path) -> list[str]:
    return [
        path.relative_to(prefix_dir).as_posix()
        for path in sorted(prefix_dir.rglob("*"), key=lambda item: item.relative_to(prefix_dir).as_posix())
        if path.is_file()
    ]


# --------------------------------------------------------------------------------------------------
# Orchestration
# --------------------------------------------------------------------------------------------------


def build_abi(
    abi: str,
    lock: dict,
    output_dir: Path,
    work_dir: Path,
    ndk: Ndk,
    keep_work: bool,
) -> dict:
    log(f"=== Building {abi} ===")
    sources = lock["sources"]
    archive_dir = work_dir / "archives"
    source_root = work_dir / "src" / abi
    if source_root.exists():
        shutil.rmtree(source_root)
    source_root.mkdir(parents=True, exist_ok=True)

    extracted: dict[str, Path] = {}
    for key, entry in sources.items():
        archive = fetch_verified(
            entry["url"],
            archive_dir / f"{key}-{entry['version']}.{entry['archive']}",
            expected_sha256=entry["sha256"],
        )
        extracted[key] = extract_archive(archive, source_root / key, entry["archive"])
        log(f"{key} {entry['version']} verified and extracted to {extracted[key]}")

    stage = asset_prefix_dir(output_dir, abi)
    if stage.exists():
        shutil.rmtree(stage)
    stage.mkdir(parents=True, exist_ok=True)

    triple = lock["abis"][abi]["triple"]
    loader32_triple = lock["abis"][abi]["loader32_triple"]
    build_libandroid_shmem(extracted["libandroid-shmem"], stage, ndk, triple)
    build_libtalloc(extracted["libtalloc"], stage, ndk, triple)
    rosegment = rosegment_supported(ndk.clang(triple))
    build_proot(extracted["proot"], stage, ndk, triple, loader32_triple, abi, rosegment)
    prune_stage(stage)

    elf_report = verify_prefix(stage, abi, lock)
    manifest = {
        "asset_root": ANDROID_LINUX_ASSET_ROOT,
        "android_abi": abi,
        "termux_arch": ANDROID_TO_TERMUX_ARCH[abi],
        "build": {
            "system": "source",
            "ndk": {
                "release": lock["ndk"]["release"],
                "version": ndk.version,
                "url": lock["ndk"]["url"],
                "sha256": lock["ndk"]["sha256"],
                "api_level": ndk.api_level,
            },
            "sources": {
                name: {
                    "version": entry["version"],
                    "url": entry["url"],
                    "sha256": entry["sha256"],
                }
                for name, entry in sorted(sources.items())
            },
            "files": manifest_files(stage),
            "elf": elf_report,
        },
    }
    write_manifest(asset_manifest_path(output_dir, abi), manifest)
    for extra in ("home", "tmp"):
        (stage / extra).mkdir(parents=True, exist_ok=True)

    if not keep_work:
        shutil.rmtree(source_root, ignore_errors=True)
    log(f"=== {abi} done ===")
    return elf_report


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", help="Generated runtime assets directory (same layout as the .deb flow)")
    parser.add_argument("--lock-file", default=str(DEFAULT_LOCK_FILE), help="Pinned native source lock file")
    parser.add_argument("--ndk-dir", help="Path to an already-extracted pinned Android NDK")
    parser.add_argument("--download-ndk", action="store_true", help="Download and verify the pinned NDK if absent")
    parser.add_argument("--work-dir", help="Scratch directory for downloads/extraction (defaults under output dir)")
    parser.add_argument("--abis", nargs="+", help="Subset of ABIs to build (default: all in the lock)")
    parser.add_argument("--keep-work", action="store_true", help="Keep extracted sources after the build")
    parser.add_argument("--self-test", action="store_true", help="Run NDK-free validation of the lock and contract")
    parser.add_argument(
        "--verify-only",
        action="store_true",
        help="Verify an existing --output-dir prefix layout against the ABI/SONAME contract",
    )
    parser.add_argument("--report", help="Write a JSON build report to this path")
    return parser.parse_args(argv)


def self_test(lock_path: Path) -> None:
    lock = json.loads(lock_path.read_text(encoding="utf-8"))
    assert lock["version"] == 1, "lock version must be 1"
    assert lock["ndk"]["sha256"], "NDK sha256 must be pinned"
    assert lock["ndk"]["version"], "NDK version must be pinned"
    for name, entry in lock["sources"].items():
        digest = entry["sha256"]
        assert len(digest) == 64 and all(c in "0123456789abcdef" for c in digest), f"{name}: bad sha256"
        assert entry["url"].startswith("https://"), f"{name}: url must be https"
    assert set(lock["abis"]) == {"arm64-v8a", "x86_64"}, "unexpected ABI set"
    for abi, entry in lock["abis"].items():
        assert entry["triple"].endswith("-linux-android"), f"{abi}: bad triple"
        assert entry["loader32_triple"].endswith("-linux-android") or entry[
            "loader32_triple"
        ].startswith("armv7a-"), f"{abi}: bad loader32 triple"

    # The JNI names below are the contract consumed by prepare_android_runtime_native_libs.py.
    assert NATIVE_EXECUTABLES["bin/proot"] == "libopencode_android_proot.so"
    assert NATIVE_EXECUTABLES["libexec/proot/loader"] == "libopencode_android_proot_loader.so"
    assert NATIVE_EXECUTABLES["libexec/proot/loader32"] == "libopencode_android_proot_loader32.so"
    assert RUNTIME_LIBRARIES["libtalloc.so.2.4.3"] == "libtalloc.so"

    # Pinned Termux NDK sysroot patches must exist and be content-addressed.
    patches = lock["ndk"].get("sysroot_patches", [])
    assert patches, "NDK sysroot patches must be pinned"
    for entry in patches:
        patch_path = (REPO_ROOT / entry["path"]).resolve()
        assert patch_path.is_file(), f"missing NDK patch {entry['path']}"
        assert hash_file(patch_path, "sha256") == entry["sha256"], f"NDK patch hash mismatch {entry['path']}"
        parsed = _parse_unified_diff(patch_path.read_text(encoding="utf-8"))
        assert parsed, f"NDK patch {entry['path']} has no hunks"

    # Hash verification must accept the exact payload and reject a tampered one.
    scratch_archive = lock_path.parent / ".native-source-self-test.bin"
    payload = b"tariffiacode-native-source-self-test"
    scratch_archive.write_bytes(payload)
    try:
        verify_archive(scratch_archive, sha256(payload).hexdigest())
        try:
            verify_archive(scratch_archive, sha256(payload + b"!").hexdigest())
        except BuildError:
            pass
        else:
            raise SystemExit("self-test failed: verify_archive accepted a mismatched hash")
    finally:
        scratch_archive.unlink(missing_ok=True)

    # ELF parser sanity check on a tiny synthetic ELF64 header (no sections/programs).
    header = bytearray(64)
    header[0:4] = b"\x7fELF"
    header[4] = ELF_CLASS_64
    header[5] = 1
    header[6] = 1
    struct.pack_into("<HH", header, 16, ET_DYN, EM_AARCH64)
    struct.pack_into("<H", header, 52, 64)
    struct.pack_into("<H", header, 54, 0)
    struct.pack_into("<H", header, 60, 0)
    scratch = lock_path.parent / ".native-source-self-test.elf"
    scratch.write_bytes(bytes(header))
    try:
        info = inspect_elf(scratch)
        assert info.elf_class == ELF_CLASS_64 and info.machine == EM_AARCH64
        assert info.type_name == "ET_DYN"
    finally:
        scratch.unlink(missing_ok=True)

    sys.stdout.write("self-test: OK\n")


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    lock_path = Path(args.lock_file).expanduser().resolve()
    if not lock_path.is_file():
        raise BuildError(f"Lock file not found: {lock_path}")
    lock = json.loads(lock_path.read_text(encoding="utf-8"))

    if args.self_test:
        self_test(lock_path)
        return 0

    if not args.output_dir:
        raise BuildError("--output-dir is required (unless --self-test is used)")

    output_dir = Path(args.output_dir).expanduser().resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    work_dir = Path(args.work_dir).expanduser().resolve() if args.work_dir else output_dir / ".native-source-work"
    work_dir.mkdir(parents=True, exist_ok=True)

    abis = args.abis or list(lock["abis"].keys())
    for abi in abis:
        if abi not in lock["abis"]:
            raise BuildError(f"Unknown ABI: {abi}")

    if args.verify_only:
        report: dict[str, dict] = {}
        for abi in abis:
            prefix_dir = asset_prefix_dir(output_dir, abi)
            if not prefix_dir.is_dir():
                raise BuildError(f"Missing built prefix for {abi}: {prefix_dir}")
            report[abi] = verify_prefix(prefix_dir, abi, lock)
        encoded = json.dumps(report, indent=2, sort_keys=True) + "\n"
        if args.report:
            Path(args.report).expanduser().resolve().write_text(encoded, encoding="utf-8")
        sys.stdout.write(encoded)
        return 0

    ndk = resolve_ndk(args, lock, work_dir)
    report = {}
    for abi in abis:
        report[abi] = build_abi(abi, lock, output_dir, work_dir, ndk, args.keep_work)

    encoded = json.dumps(report, indent=2, sort_keys=True) + "\n"
    if args.report:
        Path(args.report).expanduser().resolve().write_text(encoded, encoding="utf-8")
    sys.stdout.write(encoded)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main(sys.argv[1:]))
    except BuildError as error:
        sys.stderr.write(f"error: {error}\n")
        raise SystemExit(1)
