# Security model: PRoot is not a sandbox

This document records what the local runtime's PRoot layer actually protects, what it does not, and
why a real fix needs a UID/namespace redesign rather than a code tweak. It exists so no code comment,
UI string or doc presents PRoot as a security boundary it is not.

## Summary

PRoot (`LocalRuntimeProcessLauncher`, `ClaudeSandboxLauncher`, `AntigravitySandboxLauncher`,
`CodexSandboxLauncher`) is a **user-space, `ptrace`-based path translator**. It rewrites the paths a
guest process passes to the kernel so that a directory tree looks like `/`, and it fakes a uid of 0
with `-0`. It does not change the process's real credentials, does not create namespaces, and does
not enforce access control.

The agent process runs as a **child of the app, with the app's real UID**. PRoot's `-0` is cosmetic:
every syscall still executes with the app's uid, so the kernel's access decisions are identical to
the app's own. PRoot "hides" files by translating names; it does not make them unreachable.

## What actually isolates (kernel / Android, not PRoot)

- **Android's per-app UID sandbox.** The agent cannot read other apps' private data or system files
  that require a different uid or root, because the kernel enforces the app's uid. This is the real
  containment for "can this agent touch another app or the system?".
- **Android Keystore + `EncryptedSharedPreferences`.** Secrets TariffiaCode stores
  (`SecureSettingsRepository`: GitHub token, SSH profiles/credentials, provider keys, connection
  passwords) are encrypted with an AES-256-GCM master key that never leaves the Keystore. Even if the
  agent reads the app's private files, it gets ciphertext and a Keystore-wrapped key it cannot unwrap.
- **App-level gates**, which are independent of PRoot:
  - AGT-1 `ScheduleBridge` refuses agent-originated schedule mutations.
  - AGT-2 `AdbConnectionManager` keeps the wireless-ADB link only after an explicit, default-off opt-in.
  - AGT-3 `RuntimeCredentialPolicy` keeps the GitHub token out of the agent sandbox by default.
  - AGT-4 `CodexSandboxPolicy` runs Codex under its own `workspace-write` sandbox by default.

## What PRoot does NOT isolate

- **Filesystem reachability.** PRoot only translates path names. Known classes of PRoot escape
  (e.g. reaching the real root through `/proc/self/root`, or a syscall the translator does not
  rewrite) are not blocked by anything in this app. The bind allow-list below describes what the
  sandbox is *asked* to expose, not a guarantee that nothing else is reachable.
- **The app's own private files.** If an escape path is found, the agent can read the app's private
  directory (same uid). Encrypted secrets stay protected by the Keystore (above); plaintext artifacts
  that live inside the rootfs - notably `root/.local/share/opencode/auth.json`, the provider keys
  OpenCode reads - are readable regardless, because they are inside the sandbox by design (AGT-3
  documents this as a residual).
- **Network.** PRoot creates no network namespace. The agent shares the device's network: full egress,
  LAN, and **the shared loopback interface**. Every app on the device shares `127.0.0.1`, so the local
  OpenCode server (`opencode serve --hostname 127.0.0.1 --port <manifest>`) is *network-reachable* by
  any other app. It is now protected by HTTP Basic auth: the launcher passes a random per-start
  `OPENCODE_SERVER_PASSWORD` (see `LocalRuntimeServerSecret`) and `LocalOpenCodeBackend` sends the same
  secret, so a request without it is rejected with 401. This is a real app-layer fix independent of
  PRoot; it does not change what the agent itself can reach.
- **Process visibility.** `/proc` is the host's `/proc` (`-b /proc`), so the agent can enumerate every
  process on the device and read world-readable `/proc/<pid>/*` entries. Signals are still limited by
  uid.
- **Device storage when granted.** With all-files access, `DeviceStorage` binds the real `/sdcard` and
  `/storage` into the sandbox read-write (`DeviceStorage.bindArguments()`); without it, it binds
  nothing. This is a user-visible, gated widening, not an isolation boundary.

## Concrete access paths

1. **Provider API keys - OPEN, no safe fix under the current architecture.**
   `LocalProviderCredentialStore.syncToRuntime()` writes plaintext provider keys into
   `root/.local/share/opencode/auth.json` inside the rootfs (mode `0600`). OpenCode 1.18.5 reads that
   file (`packages/opencode/src/auth/index.ts`, `Auth.all()`), so it must exist for model calls. The
   agent runs as the same uid, so it can read the file with ordinary file access - no PRoot escape
   needed.
   OpenCode also supports `OPENCODE_AUTH_CONTENT` (a JSON blob in the environment instead of the
   file), but that is **not** isolation and is deliberately not used:
   - the shell tool spawns commands with `{ ...process.env }` (`packages/opencode/src/tool/shell.ts`,
     `shellEnv`), so every command the agent runs inherits `OPENCODE_AUTH_CONTENT` and can read it;
   - `/proc/<opencode-pid>/environ` is readable by the same uid regardless;
   - it replaces the whole auth store, so server-managed OAuth tokens would be ignored (it would break
     OAuth sign-in).
   Provider env vars (`ANTHROPIC_API_KEY`, ...) have the same inheritance problem. Real isolation
   needs a separate uid or an out-of-sandbox credential proxy (see the future plan).
2. **Loopback OpenCode server - CLOSED.** The server now requires HTTP Basic auth with a random
   per-start password (`OPENCODE_SERVER_PASSWORD`); a co-installed app that connects to
   `127.0.0.1:<port>` without the secret gets 401 and cannot drive the agent/session API.
3. **App-private files via a PRoot escape.** The app's private directory is same-uid; a PRoot escape
   would expose its plaintext contents. Encrypted SharedPreferences remain Keystore-protected.

## Why there is no small fix

- PRoot has no option that turns it into an access-control boundary; its whole design is name
  translation. Removing binds (`/dev`, `/proc`, `/sys`, `/system`) breaks the toolchain (no `/proc`,
  no Android `am`/`pm`/`input`) without adding isolation, because the app already has that access.
- The app cannot `setuid`/`setgid` to a distinct agent uid, and cannot create user/PID/mount/network
  namespaces (`CLONE_NEWUSER` is restricted for unprivileged Android processes), so it cannot build a
  real sandbox around the child.
- Adding a fake check (e.g. a path denylist inside PRoot) would be security theater: it would not
  change the kernel's access decisions.

## Minimal future plan (redesign, not a tweak)

1. ~~**Authenticate the local OpenCode server.**~~ **Done.** OpenCode supports
   `OPENCODE_SERVER_PASSWORD`/`OPENCODE_SERVER_USERNAME` natively (no proxy needed);
   `LocalRuntimeServerSecret` generates a random per-start secret, `LocalRuntimeProcessLauncher`
   passes it to `opencode serve`, and `LocalOpenCodeBackend` sends it via `profile.password`. Access
   path 2 is closed.
2. **Isolate provider keys (needs an architecture change).** Per-agent files do not help: every
   agent runs as the same uid, so a file readable by the OpenCode process is readable by any agent
   command. The only real options are (a) an app-side, out-of-sandbox credential proxy that holds the
   keys and injects provider auth, with OpenCode pointed at the proxy base URL (provider-specific and
   non-trivial, and it must itself be authenticated on loopback), or (b) running the agent under a
   distinct uid, which an unprivileged Android app cannot do.
3. **Real OS isolation (longer term).** A genuine sandbox requires either a distinct uid per agent
   (not possible for an unprivileged app) or a helper with namespace privileges (e.g. a `proot`
   replacement based on `unshare`/`bwrap` where the platform permits it), plus a private procfs and a
   network namespace with an explicit allow-list. Until then, treat the local agent as running with
   the app's own privileges.

## Update integrity

The in-app updater verifies the downloaded APK before it can be installed. The expected hash is not
hardcoded: it comes from GitHub's own release-asset `digest` (`sha256:<hex>`), fetched over the same
HTTPS Releases API call that yields the download URL (`AppUpdateReleaseClient`). If a release asset
has no usable SHA-256 digest, the update is refused rather than offered unverified. The downloader
(`OkHttpAppUpdateApkDownloader`) streams the APK, recomputes its SHA-256, and on any mismatch,
truncation, or network failure deletes the file and throws, so a partial or tampered APK never reaches
the system installer. Android's package installer still enforces the update signing key as a second,
independent provenance check.

The transport is HTTPS-only and GitHub-host-only. The app's global network security config permits
cleartext because remote OpenCode runtimes are reached over plain HTTP on LAN/loopback addresses that
Android's config cannot express as rules, so the update flow cannot rely on that config.
`AppUpdateHttp` provides a metadata client restricted to `api.github.com` and an asset client
restricted to `github.com` plus GitHub's asset CDN `release-assets.githubusercontent.com` (the only
redirect target the real `github.com/.../releases/download/...` flow uses, verified against the live
API). A network interceptor rejects any request - including a redirect hop - that is not HTTPS on an
allowed host, so a redirect cannot move the update to another server. `AppUpdateReleaseClient` also
refuses a non-`github.com` asset URL at check time, and `requireGitHubApiEndpoint` pins the metadata
endpoint. No certificate pinning is used.

## Remote command exit status

Remote git commands run over an SSH **exec** channel (`MinaSshExecClient`), not the interactive PTY
shell. The exit code is read from the SSH `exit-status` message (`ChannelExec.getExitStatus`), a
control-channel value delivered separately from stdout/stderr. The previous approach appended a fixed
`__TC_EXIT__$?` marker to the script and parsed the first marker out of the merged PTY output, so a
command (or a file it printed) containing `__TC_EXIT__0` could forge success; that marker scheme is
gone. The interactive PTY terminal (`MinaSshShellClient`) is unchanged and still used for the Remote
Terminal.

## Device storage and the agent sandbox

The app declares `MANAGE_EXTERNAL_STORAGE` so a project folder the user points at can be opened
**in place**, as ordinary POSIX paths, by a Linux agent process. Android's Storage Access Framework
cannot replace it here: SAF hands back a `content://` tree URI, not a mountable directory, so a
native process cannot `chdir`/`open` through it - the previous SAF importer had to copy a tree into
app storage, and the agent then worked on a detached duplicate. The permission is requested only from
the workspace screen and is never held without an explicit user grant.

What is deliberately **not** done: binding the whole of `/sdcard` and `/storage` into the sandbox.
`DeviceStorage.bindArguments` binds only the folders the user registered as projects
(`DeviceStorage.installProjectPaths { settings.projectPaths }`), resolved to their host directories,
and refuses to bind a storage root itself. So a prompt-injected agent can read and write the project
folders the user chose, but not the rest of shared storage (photos, Downloads, other apps' files). The
app's own folder picker still browses all granted storage, because that is the trusted UI the user
uses to choose a project; only the agent's view is narrowed. Registering a device-storage project
restarts the local runtime so the new bind takes effect.

## Regression coverage

`SandboxMountBoundaryTest` pins the provable part: each launcher only asks PRoot to bind a fixed
allow-list of host paths (system dirs plus app-private workspace/bridge), and device storage is bound
only when the user has granted access. It does **not** claim PRoot enforces that allow-list at
runtime - that is exactly the limitation this document records.
