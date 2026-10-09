# Third-Party Notices

TariffiaCode includes, depends on, or downloads and runs the third-party software listed below. **This
license inventory covers those third-party components only — it is separate from, and not covered
by, TariffiaCode's own [MIT License](LICENSE), which applies solely to this repository's Kotlin/Android
source code.** See [TRADEMARKS.md](TRADEMARKS.md) for trademark notices.

## Bundled native runtime components (PRoot / Termux-derived)

These binaries are built at build time from the pinned upstream source archives (verified by SHA-256)
using a pinned Android NDK, and packaged into the APK so the on-device Linux runtime can start. The
pinned inputs live in
[`runtime_tools/native_sources.lock.json`](runtime_tools/native_sources.lock.json) and the build is
driven by [`scripts/build_android_runtime_from_source.py`](scripts/build_android_runtime_from_source.py).
This is a fully self-contained, project-controlled build process and does not depend on the
`termux-packages` build framework; it replaces the former build-time `.deb` fetch, whose lock file
([`runtime_tools/termux_assets.lock.json`](runtime_tools/termux_assets.lock.json)) and mirrored
recipes are retained as historical provenance. TariffiaCode does not patch or modify these packages
beyond what the recipes perform. **Full license text for each of GPL-2.0, GPL-3.0 (which
LGPL-3.0 incorporates by reference), LGPL-3.0, and the BSD-3-Clause text used by
`libandroid-shmem` is bundled verbatim, unmodified, in [`THIRD_PARTY_LICENSES/`](THIRD_PARTY_LICENSES/)
in this repository and at `assets/legal/licenses/*.txt` inside the shipped APK** — not just linked.

| Package | Version | License (verified against upstream source, not guessed) | Copyright | Corresponding source (content-addressed) |
|---|---|---|---|---|
| `proot` | 5.1.107.92 | [GPL-2.0](THIRD_PARTY_LICENSES/GPL-2.0.txt), per [`TERMUX_PKG_LICENSE`](runtime_tools/termux-packaging-recipes/proot.build.sh) | The PRoot contributors | Upstream source archive `v5.1.107.92.zip`, SHA-256 `29385d1ddb619a9c4449ab512bfd55032034b22f724ddf98fc95ff300ea32135` (from [github.com/termux/proot](https://github.com/termux/proot/archive/v5.1.107.92.zip), tagged release, not `master`). Recipe pinned to `termux/termux-packages@08b49b3ce00b1e14a3a0365200f30e50f8dfafe1`. |
| `libandroid-shmem` | 0.7 | [BSD-3-Clause](THIRD_PARTY_LICENSES/BSD-3-Clause-libandroid-shmem.txt), per [`TERMUX_PKG_LICENSE`](runtime_tools/termux-packaging-recipes/libandroid-shmem.build.sh) and the project's own `LICENSE` file | Copyright (c) 2013 Sergii Pylypenko; Copyright (c) 2017 Fredrik Fornwall | Upstream source archive `v0.7.tar.gz`, SHA-256 `1e5ff8459bc0a8c229dd8a94b27d119987e09ef3414331c2b5ebfff20b98e867` (from [github.com/termux/libandroid-shmem](https://github.com/termux/libandroid-shmem/archive/refs/tags/v0.7.tar.gz), tagged release, not `master`). Recipe pinned to `termux/termux-packages@b25e257208da6d2e8b558b8a2b51762158a2e806`. |
| `libtalloc` | 2.4.3 | [LGPL-3.0-or-later](THIRD_PARTY_LICENSES/LGPL-3.0.txt) for the actual runtime library. Termux's own packaging metadata tags the *package* `GPL-3.0` (a coarser, package-level tag), but the shared library source itself (`talloc.c`/`talloc.h`, the only files that become `libtalloc.a`/`libtalloc.so`) carries its own header: *"the following LGPL license applies to the talloc library. This does NOT imply that all of Samba is released under the LGPL"* — version 3 or later. The library license is LGPL-3.0-or-later; for the historical `.deb`-derived binaries the corresponding-source status is `REQUIRES_LICENSE_REVIEW`, while current releases provide corresponding source via the self-contained source build (see below). | Copyright (C) Andrew Tridgell 2004; Copyright (C) Stefan Metzmacher 2006 | Upstream source archive `talloc-2.4.3.tar.gz`, SHA-256 `dc46c40b9f46bb34dd97fe41f548b0e8b247b77a918576733c528e83abd854dd` (from [samba.org/ftp/talloc](https://www.samba.org/ftp/talloc/talloc-2.4.3.tar.gz), versioned release path, not a mutable branch). Recipe pinned to `termux/termux-packages@fbc049451e7fc59cdf510732aad49bd45590b0bb`. |

**Packaging recipes, mirrored (not just linked):** the exact `TERMUX_PKG_*` build recipe used for
each package above — the thing that actually produces the `.deb` pinned by hash in
`termux_assets.lock.json` — is copied verbatim into
[`runtime_tools/termux-packaging-recipes/`](runtime_tools/termux-packaging-recipes/) in this
repository, each pinned to the exact `termux/termux-packages` commit it was retrieved from:
`proot` at `08b49b3ce00b1e14a3a0365200f30e50f8dfafe1` (retrieved 2026-08-24), `libandroid-shmem` at
`b25e257208da6d2e8b558b8a2b51762158a2e806` and `libtalloc` at
`fbc049451e7fc59cdf510732aad49bd45590b0bb` (both retrieved 2026-08-02). This exists specifically so
the corresponding-source reference does not depend on the upstream `termux-packages` repository's
mutable `master` branch continuing to show the same content in the future; the recipe as it existed
at the time these exact binaries were built is preserved here.

**Source availability — self-contained source build:**

- *Provided:* the exact, versioned, content-addressed upstream source archive for each package
  (verifiable by the SHA-256 shown, independent of any branch or tag being later force-moved); the
  exact `TERMUX_PKG_*` recipe file for each package, pinned to the `termux/termux-packages` commit it
  was retrieved from (see above); the pinned Android NDK (`r29`, SHA-256 pinned in
  `native_sources.lock.json`); and the self-contained build script
  [`scripts/build_android_runtime_from_source.py`](scripts/build_android_runtime_from_source.py)
  that reproduces all three binaries from those inputs without the `termux-packages` build framework.
- *Historical provenance:* the former build-time `.deb` fetch and its lock file
  ([`runtime_tools/termux_assets.lock.json`](runtime_tools/termux_assets.lock.json)) and mirrored
  recipes are retained as provenance. The `.deb` SHA-256 pins describe releases built before the
  self-contained source build landed; they are no longer the packaged artifacts.

**Current releases provide the corresponding source for `proot`, `libandroid-shmem`, and `libtalloc`
through option (b): these three packages are rebuilt from source under this project's own pinned,
fully self-contained build process (inputs pinned in `native_sources.lock.json`). The historical
`.deb`-derived binaries remain flagged `REQUIRES_LICENSE_REVIEW`, and for releases that shipped
those binaries the following GPLv2 §3(b)-style written offer still stands for those three packages
specifically: **on request (open an issue at
[Konprostart/TariffiaCode](https://github.com/Konprostart/TariffiaCode/issues)), for at least three
years from the release you obtained, this project will provide, at no more than the cost of
physically performing the distribution, a complete machine-readable copy of the corresponding source
it is able to identify or reconstruct for the pinned `proot`, `libandroid-shmem`, and `libtalloc`
binaries in that release.**

## Coding agent binaries downloaded and run on-device

Not bundled in the APK — downloaded from the official distribution channel at first setup/update
and verified by checksum before use. See
[docs/AUTHENTICATION_AND_DATA_FLOW.md](docs/AUTHENTICATION_AND_DATA_FLOW.md) for how each is
installed and run.

| Component | Source | License | Notes |
|---|---|---|---|
| OpenCode | `github.com/anomalyco/opencode` releases (musl Linux binary; URL/version pinned in [`app/src/main/assets/local-runtime-manifest.json`](app/src/main/assets/local-runtime-manifest.json)) | See [anomalyco/opencode](https://github.com/anomalyco/opencode) for the current license | TariffiaCode integrates OpenCode as an independent third-party coding agent runtime; not affiliated with the OpenCode project |
| Claude Code | `downloads.claude.ai/claude-code/apk/latest` (Anthropic's official Alpine package repository, signature-verified) | Proprietary; governed by Anthropic's own Claude Code terms | Official CLI, unmodified; TariffiaCode does not fork or re-host it |
| Google Antigravity CLI | `github.com/google-antigravity/antigravity-cli` releases (pinned in `AntigravityManifest.kt`, currently 1.1.7) | Proprietary; governed by Google Antigravity's own terms | Official CLI, unmodified; TariffiaCode does not fork or re-host it |

## Base Linux root filesystems

| Component | Source | License | Notes |
|---|---|---|---|
| Alpine Linux minirootfs | `dl-cdn.alpinelinux.org` (version pinned in [`local-runtime-manifest.json`](app/src/main/assets/local-runtime-manifest.json), currently 3.24.1) | Each Alpine package keeps its own upstream license (mix of MIT, BSD, GPL, and others); see [alpinelinux.org](https://alpinelinux.org/) and each package's `APKINDEX`/`.PKGINFO` metadata | Used as the rootfs for OpenCode and Claude Code |
| Debian Bookworm rootfs (bootstrap + packages) | Official Debian mirrors (`deb.debian.org`, `security.debian.org`), fetched via `apt` at setup time — see `DebianRootfsInstaller.kt` | Each Debian package keeps its own upstream license (mix of GPL, LGPL, MIT, BSD, and others); see [debian.org/legal](https://www.debian.org/legal/) | Used only for the Antigravity CLI, which requires glibc |

## Gradle / Android dependencies

The `releaseRuntimeClasspath` Gradle configuration — everything actually resolved into a release
build, direct **and** transitive — has ~190 distinct artifacts as of this writing. Rather than a
hand-maintained table (which drifted from reality before - both listing things that were not actually
resolved and omitting things that were), the authoritative, generated list is committed at
[`THIRD_PARTY_LICENSES/release-dependencies-releaseRuntimeClasspath.txt`](THIRD_PARTY_LICENSES/release-dependencies-releaseRuntimeClasspath.txt).
Regenerate it with:

```bash
./scripts/generate_dependency_report.sh
```

and diff the result before releasing if `app/build.gradle.kts` changed. The table below is a
**curated summary of the most relevant/highest-profile entries** from that generated list, not a
claim that it is exhaustive — consult the generated file for the complete set.

| Dependency | Version | License |
|---|---|---|
| AndroidX Core, Lifecycle, Activity Compose, Navigation Compose, Compose BOM, Security Crypto, DocumentFile, Room, Startup, Profileinstaller, DataStore | `core-ktx:1.15.0`, `lifecycle:2.8.7`, `activity-compose:1.9.3`, `navigation-compose:2.8.5`, `compose-bom:2024.12.01`, `security-crypto:1.1.0-alpha06`, `documentfile:1.0.1`, `room:2.6.1`, `datastore:1.1.7` | Apache License 2.0 — [developer.android.com/jetpack](https://developer.android.com/jetpack) |
| OkHttp / OkHttp-SSE / Okio | `okhttp:4.12.0`, `okio:3.4.0`/`3.6.0` | Apache License 2.0 — [square.github.io/okhttp](https://square.github.io/okhttp/) |
| kotlinx.serialization (JSON) | `1.7.3` | Apache License 2.0 — [github.com/Kotlin/kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) |
| ZXing (`com.journeyapps:zxing-android-embedded`, `com.google.zxing:core`) | `zxing-android-embedded:4.3.0`, `core:3.4.1` | Apache License 2.0 — [github.com/journeyapps/zxing-android-embedded](https://github.com/journeyapps/zxing-android-embedded), [github.com/zxing/zxing](https://github.com/zxing/zxing) |
| Apache Commons Compress, Commons Codec, Commons IO, Commons Lang3 | `commons-compress:1.27.1`, `commons-codec:1.17.1`, `commons-io:2.16.1`, `commons-lang3:3.16.0` | Apache License 2.0 — [commons.apache.org](https://commons.apache.org/) |
| **`org.tukaani:xz`** (used by `commons-compress` for `.xz` archive support) | `1.9` | Public-domain-style permissive license ("Permission to use, copy, modify, and/or distribute this software for any purpose with or without fee is hereby granted"), per the project's own `COPYING` file — [github.com/tukaani-project/xz-java](https://github.com/tukaani-project/xz-java) |
| Kotlin stdlib / Kotlin Gradle plugins | `2.0.21` | Apache License 2.0 — [kotlinlang.org](https://kotlinlang.org/) |
| kotlinx.coroutines (Android, core) | `1.9.0` | Apache License 2.0 — [github.com/Kotlin/kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) |
| Koin (`koin-android`, `koin-androidx-compose`, `koin-core`) | `4.0.1` | Apache License 2.0 — [insert-koin.io](https://insert-koin.io/) |
| **Google Tink** (`com.google.crypto.tink:tink-android`, pulled in by `androidx.security:security-crypto` for `EncryptedSharedPreferences`) | `1.8.0` | Apache License 2.0 — [github.com/tink-crypto/tink-java](https://github.com/tink-crypto/tink-java) |
| **`com.alphacephei:vosk-android`** (wake-word speech recognition; bundles Kaldi) | `0.3.75` | Apache License 2.0 — [github.com/alphacep/vosk-api](https://github.com/alphacep/vosk-api) |
| AndroidX Test / Espresso, JUnit 4, MockWebServer, `kotlinx-coroutines-test` (test only, not shipped in a release APK) | various | Apache License 2.0 (AndroidX Test/Espresso, MockWebServer, coroutines-test) / Eclipse Public License 1.0 (JUnit 4 — [junit.org/junit4](https://junit.org/junit4/)) |

Apache License 2.0's full text is bundled at
[`THIRD_PARTY_LICENSES/Apache-2.0.txt`](THIRD_PARTY_LICENSES/Apache-2.0.txt) and
`assets/legal/licenses/Apache-2.0.txt` inside the APK, covering the dependencies above.

**NOTICE files are not preserved in the built APK and must be aggregated separately.** An earlier
version of this document claimed that Gradle keeps each dependency's `META-INF/NOTICE` file intact
in the final package; that was checked against the actual `tariffiacode-debug` CI artifact and found to
be false. AGP's resource merging deduplicates files that collide under `META-INF/NOTICE*` across
dependencies by keeping a single, arbitrarily-chosen copy (a "pick first" rule, not per-artifact
preservation) — inspecting the built APK showed only `okhttp3/internal/publicsuffix/NOTICE`
survived; the Apache Commons artifacts' own `NOTICE` files did not make it into the APK at all.

The actual NOTICE text for every `releaseRuntimeClasspath` artifact that ships one is aggregated,
straight out of the dependency archives (not the built APK), at
[`THIRD_PARTY_LICENSES/NOTICE-aggregate.txt`](THIRD_PARTY_LICENSES/NOTICE-aggregate.txt) and
`assets/legal/notice_aggregate.md` inside the APK, reachable from the in-app Legal screen. As of
this writing that is `commons-codec`, `commons-io`, `commons-compress`, and `commons-lang3` — all
four are the standard "Copyright The Apache Software Foundation" boilerplate NOTICE, not a notice
of any modification. Regenerate it after any dependency change with:

```bash
./scripts/generate_notice_aggregate.sh
```

and re-copy the result into `app/src/main/assets/legal/notice_aggregate.md` before releasing (a
test enforces the two stay byte-for-byte identical). An artifact absent from the aggregate is not
a claim that it ships no NOTICE file — only that none of the standard `NOTICE`/`NOTICE.txt` entry
names were found at the top level of its jar/aar or its nested `classes.jar`.

## Wake-word speech model (downloaded at runtime, not bundled)

The on-device wake word is recognised with [Vosk](https://github.com/alphacep/vosk-api)
(`com.alphacephei:vosk-android`), which is **Apache License 2.0** — full text bundled at
[`THIRD_PARTY_LICENSES/Apache-2.0.txt`](THIRD_PARTY_LICENSES/Apache-2.0.txt). Vosk in turn builds on
[Kaldi](https://github.com/kaldi-asr/kaldi), also Apache-2.0.

The speech model Vosk loads is **not part of the APK**. It is downloaded from
[alphacephei.com/vosk/models](https://alphacephei.com/vosk/models) the first time the wake word is
switched on, into the app's private storage, and can be removed again from voice settings. The
models offered are:

| Model | Download | License |
|---|---|---|
| `vosk-model-small-en-us-0.15` (English, ~40 MB) | [alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip](https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip) | Apache License 2.0, per the model list published by the Vosk project |
| `vosk-model-small-ja-0.22` (Japanese, ~48 MB) | [alphacephei.com/vosk/models/vosk-model-small-ja-0.22.zip](https://alphacephei.com/vosk/models/vosk-model-small-ja-0.22.zip) | Apache License 2.0, per the model list published by the Vosk project |

No hash is pinned for these files because they are fetched from the vendor at runtime rather than
redistributed by this project; the archive is validated structurally (it must unpack to the
expected model directory) before it is used, and is discarded if it does not.

Because nothing is redistributed and both the library and the models are Apache-2.0, the wake-word
feature carries no `REQUIRES_LICENSE_REVIEW` flag. Downloading is subject to whatever terms the
Vosk project applies to its own hosting.

**This replaces the previous openWakeWord implementation**, whose three bundled `.tflite` model
files were licensed CC BY-NC-SA 4.0 (Attribution-**NonCommercial**-ShareAlike) and were flagged
`REQUIRES_LICENSE_REVIEW` here, because this project could not determine which downstream uses
would qualify as NonCommercial. Those files and the `org.tensorflow:tensorflow-lite` dependency
that ran them have been removed, so that question no longer arises.

## Generating a full SBOM

For a machine-readable inventory of every resolved Gradle dependency (including transitive ones not
itemized above), see [`scripts/generate_dependency_report.sh`](scripts/generate_dependency_report.sh)
and its committed output at
[`THIRD_PARTY_LICENSES/release-dependencies-releaseRuntimeClasspath.txt`](THIRD_PARTY_LICENSES/release-dependencies-releaseRuntimeClasspath.txt).

Optional: use [Syft](https://github.com/anchore/syft) or the Gradle CycloneDX plugin in CI for
SPDX/CycloneDX output.

## In-app access

This file, along with [PRIVACY.md](PRIVACY.md), [TERMS.md](TERMS.md),
[THIRD_PARTY_SERVICES.md](THIRD_PARTY_SERVICES.md), [TRADEMARKS.md](TRADEMARKS.md), and
[docs/AUTHENTICATION_AND_DATA_FLOW.md](docs/AUTHENTICATION_AND_DATA_FLOW.md), is bundled into the
APK as an asset and reachable offline from **Settings → Legal & Privacy → Open-source Licenses**.
The full GPL-2.0, GPL-3.0, LGPL-3.0, and BSD-3-Clause license texts referenced above are bundled at
`assets/legal/licenses/*.txt` inside the same APK (extractable with e.g. `unzip -p app.apk
assets/legal/licenses/GPL-2.0.txt`), and at [`THIRD_PARTY_LICENSES/`](THIRD_PARTY_LICENSES/) in this
source repository.
