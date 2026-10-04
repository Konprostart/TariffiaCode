package com.konprostart.tariffiacode.runtime.local

import kotlinx.serialization.json.Json
import java.io.File

/**
 * Realigns the persisted runtime port in `metadata.json` with the port declared by the bundled
 * runtime manifest, without rebuilding the runtime or touching any user data.
 *
 * A runtime installed by an earlier build keeps its port in `metadata.json`, and the runtime reads
 * that persisted value (not the manifest) when it launches `opencode serve` and when the client
 * connects. Android's loopback is shared across apps, so a stale port can collide with - or connect
 * to - another app's server. This rewrites only the `port` field and leaves every other field, the
 * rootfs, the workspace, sessions, config, and auth state untouched.
 *
 * Idempotent and best-effort: it does nothing when the runtime is not installed, when the metadata
 * is unreadable, or when the port already matches.
 */
internal object RuntimeMetadataPortMigration {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
        }

    /**
     * @return true when [metadataFile] was rewritten with [manifestPort]; false when there was
     *   nothing to do or the file could not be read/written. On any failure the original file is
     *   left intact.
     */
    fun reconcile(
        metadataFile: File,
        manifestPort: Int,
    ): Boolean {
        if (!metadataFile.isFile) return false
        val current =
            runCatching { json.decodeFromString<LocalRuntimeMetadata>(metadataFile.readText()) }
                .getOrNull() ?: return false
        if (current.port == manifestPort) return false
        val updated =
            json.encodeToString(
                LocalRuntimeMetadata.serializer(),
                current.copy(port = manifestPort),
            )
        val temp = File(metadataFile.parentFile, "${metadataFile.name}.migrate")
        val written =
            runCatching {
                temp.writeText(updated)
                replaceFileAtomically(temp, metadataFile)
            }.isSuccess
        temp.delete()
        return written
    }
}
