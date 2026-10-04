package com.konprostart.tariffiacode.runtime.local

import com.konprostart.tariffiacode.runtime.LocalRuntimeStatus
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LocalRuntimeManagerPortMigrationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
        }

    /**
     * The persisted port must be realigned before the start path reads it, so an existing 4097
     * runtime can never be launched or connected to. [LocalRuntimeOperations.start] reads the port
     * it would have used; that value must already be 4098.
     */
    @Test
    fun `port is realigned before the runtime start reads it`() =
        runTest {
            val runtime = temporaryFolder.newFolder("runtime")
            runtime.resolve("metadata.json").writeText(
                json.encodeToString(
                    LocalRuntimeMetadata.serializer(),
                    LocalRuntimeMetadata(
                        version = "1.18.5",
                        port = 4097,
                        installedAt = 1,
                        runtimeVersion = "test",
                        abi = "arm64-v8a",
                    ),
                ),
            )

            var observedPort: Int? = null
            val operations =
                object : LocalRuntimeOperations {
                    override fun currentMetadata(): LocalRuntimeMetadata? = read(runtime)

                    override suspend fun stop() = Unit

                    override suspend fun start(): LocalRuntimeStatus.Ready {
                        observedPort = requireNotNull(read(runtime)).port
                        return LocalRuntimeStatus.Ready("1.18.5", observedPort!!)
                    }
                }

            val manager =
                LocalRuntimeManager(
                    runtimeDirectory = runtime,
                    abi = "arm64-v8a",
                    installer = null,
                    processLauncher = null,
                    runtimeOperations = operations,
                    reconcilePersistedPort = {
                        RuntimeMetadataPortMigration.reconcile(runtime.resolve("metadata.json"), 4098)
                    },
                )

            val result = manager.start()

            assertTrue(result.isSuccess)
            assertEquals(4098, observedPort)
            assertEquals(4098, requireNotNull(read(runtime)).port)
        }

    private fun read(runtime: File): LocalRuntimeMetadata? =
        runCatching {
            json.decodeFromString<LocalRuntimeMetadata>(runtime.resolve("metadata.json").readText())
        }.getOrNull()
}
