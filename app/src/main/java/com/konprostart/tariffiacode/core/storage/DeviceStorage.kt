package com.konprostart.tariffiacode.core.storage

import java.io.File

/**
 * Where the phone's own files sit, on the host and inside the PRoot sandbox.
 *
 * The sandbox used to see two directories, both app-private: the Linux rootfs and the `/workspace`
 * mount. Everything the user actually keeps on the device — a repository cloned from another
 * terminal app into `Download`, notes under `Documents`, a folder synced from a PC — was invisible.
 * The only way in was the SAF importer, which *copies* a tree into app storage, so the agent then
 * worked on a detached duplicate while the original sat untouched.
 *
 * The folder picker walks the device's own files, a workspace can point straight at one, and the
 * agent reads and writes the real files. Reaching them at all needs the user to grant all-files
 * access, which is why every accessor here answers null until [install] is handed a provider that
 * says the permission is held.
 *
 * The sandbox is NOT handed all of shared storage. [bindArguments] binds only the folders the user
 * registered as projects ([installProjectPaths]), so a prompt-injected agent cannot read or write
 * the rest of `/sdcard`/`/storage`. The app's own browser still sees everything the permission
 * grants; only the agent's view is narrowed.
 */
object DeviceStorage {
    /** Guest path for the primary shared volume, matching the alias Android itself uses. */
    const val GUEST_SHARED_ROOT = "/sdcard"

    /**
     * Guest path for the volume list, so an SD card or a USB drive is reachable too. Bound at the
     * host's own path: a secondary volume is named by a per-device id (`/storage/1A2B-3C4D`) that
     * only means anything spelled exactly as the platform spells it.
     */
    const val GUEST_VOLUMES_ROOT = "/storage"

    /** The host directories the sandbox may reach; null means "not granted, or not present". */
    data class Mounts(
        val sharedStorage: File? = null,
        val volumes: File? = null,
    ) {
        val isEmpty: Boolean
            get() = sharedStorage == null && volumes == null

        companion object {
            val None = Mounts()
        }
    }

    /**
     * Read through a provider rather than stored, because the answer changes without the process
     * restarting: the user grants all-files access in system settings and comes straight back.
     *
     * A process-wide hook is what the callers need. PRoot invocations are assembled by objects
     * ([com.konprostart.tariffiacode.runtime.local.ClaudeSandboxLauncher] and its siblings) reached
     * from a dozen call sites, none of which hold a `Context` to ask the permission about.
     */
    @Volatile
    private var provider: () -> Mounts = { Mounts.None }

    /**
     * The specific device-storage folders the user registered as projects. Read at sandbox start,
     * this is what the sandbox may bind - never the whole `/sdcard`/`/storage` tree. The app itself
     * still browses all granted storage (that is what the folder picker uses); only the agent's view
     * is narrowed to the folders the user actually pointed it at.
     */
    @Volatile
    private var projectPathsProvider: () -> List<String> = { emptyList() }

    fun install(provider: () -> Mounts) {
        this.provider = provider
    }

    fun installProjectPaths(provider: () -> List<String>) {
        this.projectPathsProvider = provider
    }

    fun mounts(): Mounts = provider()

    /**
     * PRoot `-b` arguments for the sandbox: one bind per registered project folder, and nothing else.
     * Binding a storage root itself is refused, so a project cannot re-open broad access.
     */
    fun bindArguments(): List<String> = bindArguments(mounts(), projectPathsProvider())

    fun bindArguments(
        mounts: Mounts,
        projectPaths: List<String>,
    ): List<String> =
        buildList {
            projectPaths
                .map(String::trim)
                .filter(String::isNotEmpty)
                .distinct()
                .forEach { guest ->
                    if (guest == GUEST_SHARED_ROOT || guest == GUEST_VOLUMES_ROOT) return@forEach
                    val host = hostDirectory(mounts, guest) ?: return@forEach
                    add("-b")
                    add("${host.absolutePath}:$guest")
                }
        }

    /** The guest roots to offer as browsable entries, ordered as they should be shown. */
    fun guestRoots(mounts: Mounts): List<String> =
        buildList {
            mounts.sharedStorage?.let { add(GUEST_SHARED_ROOT) }
            mounts.volumes?.let { add(GUEST_VOLUMES_ROOT) }
        }

    /** True when [path] names device storage rather than something inside the Linux rootfs. */
    fun isDeviceStoragePath(path: String): Boolean = guestRootOf(path) != null

    /**
     * The host directory behind a device-storage guest path, or null when the path is not device
     * storage, the permission is not held, or the path climbs back out of the mount.
     */
    fun hostDirectory(
        mounts: Mounts,
        path: String,
    ): File? {
        val root = guestRootOf(path) ?: return null
        val host =
            when (root) {
                GUEST_SHARED_ROOT -> mounts.sharedStorage
                else -> mounts.volumes
            } ?: return null
        val relative = path.removePrefix(root).trim('/')
        val base = host.absoluteFile.normalize()
        if (relative.isEmpty()) return base
        val resolved = File(base, relative).absoluteFile.normalize()
        return resolved.takeIf { it.path.startsWith(base.path + File.separator) }
    }

    private fun guestRootOf(path: String): String? =
        listOf(GUEST_SHARED_ROOT, GUEST_VOLUMES_ROOT)
            .firstOrNull { path == it || path.startsWith("$it/") }
}
