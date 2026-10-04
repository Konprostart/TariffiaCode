package com.konprostart.tariffiacode.runtime.local

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RuntimeMetadataPortMigrationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
        }

    private fun metadata(port: Int) =
        LocalRuntimeMetadata(
            version = "1.18.5",
            port = port,
            installedAt = 123,
            runtimeVersion = "2026.07.25.1",
            abi = "arm64-v8a",
            components = setOf("opencode", "claude-code"),
            fullDevelopmentToolsInstalled = true,
            fullDebianDevelopmentToolsInstalled = true,
        )

    private fun write(port: Int): File =
        temporaryFolder.newFile("metadata.json").apply {
            writeText(json.encodeToString(LocalRuntimeMetadata.serializer(), metadata(port)))
        }

    private fun read(file: File): LocalRuntimeMetadata =
        json.decodeFromString(LocalRuntimeMetadata.serializer(), file.readText())

    @Test
    fun `migrates the port and keeps every other field`() {
        val file = write(4097)

        assertTrue(RuntimeMetadataPortMigration.reconcile(file, 4098))

        val migrated = read(file)
        assertEquals(4098, migrated.port)
        // Data-class equality proves every other field is byte-for-byte preserved.
        assertEquals(metadata(4098), migrated)
    }

    @Test
    fun `already on the manifest port is left untouched`() {
        val file = write(4098)
        val before = file.readText()

        assertFalse(RuntimeMetadataPortMigration.reconcile(file, 4098))

        assertEquals(before, file.readText())
    }

    @Test
    fun `missing metadata is a safe no-op`() {
        val file = File(temporaryFolder.root, "metadata.json")

        assertFalse(RuntimeMetadataPortMigration.reconcile(file, 4098))

        assertFalse(file.exists())
    }

    @Test
    fun `unreadable metadata is left intact`() {
        val file = temporaryFolder.newFile("metadata.json").apply { writeText("{ not valid json") }
        val before = file.readText()

        assertFalse(RuntimeMetadataPortMigration.reconcile(file, 4098))

        assertEquals(before, file.readText())
    }
}
