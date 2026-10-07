package com.konprostart.tariffiacode.runtime.local

import com.konprostart.tariffiacode.runtime.LocalRuntimeStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The local runtime manager is built on the main thread (`Application.onCreate`, and by Koin during
 * composition), and its status computation used to run a blocking TCP port probe right there. The
 * probe is now deferred to a background scope; these tests pin that construction never waits on it.
 */
class LocalRuntimeStartupProbeTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun `constructing the manager does not probe the port synchronously`() =
        runTest {
            installMetadata(port = 4096)
            var probes = 0

            manager(portProbe = { probes++; false }, statusScope = this)

            assertEquals("construction must not run the blocking probe", 0, probes)

            advanceUntilIdle()

            assertTrue("the background refresh runs the probe", probes >= 1)
        }

    @Test
    fun `the deferred probe publishes the probed status`() =
        runTest {
            installMetadata(port = 4096)
            val manager = manager(portProbe = { true }, statusScope = this)

            assertTrue("the initial status must not have probed", manager.state.value is LocalRuntimeStatus.Stopped)

            advanceUntilIdle()

            assertTrue(manager.state.value is LocalRuntimeStatus.Ready)
        }

    @Test
    fun `constructing the target does not probe the port`() =
        runTest {
            installMetadata(port = 4096)
            var probes = 0
            val manager = manager(portProbe = { probes++; false }, statusScope = this)

            LocalRuntimeTarget(manager)

            assertEquals("neither the manager nor the target may probe at construction", 0, probes)
        }

    @Test
    fun `a cancelled status scope does not crash or probe`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).apply { cancel() }
        var probes = 0

        manager(portProbe = { probes++; false }, statusScope = scope)

        assertEquals(0, probes)
    }

    private fun manager(
        portProbe: (Int) -> Boolean,
        statusScope: CoroutineScope,
    ): LocalRuntimeManager =
        LocalRuntimeManager(
            runtimeDirectory = folder.root,
            abi = "arm64-v8a",
            portProbe = portProbe,
            statusScope = statusScope,
        )

    private fun installMetadata(port: Int) {
        File(folder.root, "metadata.json").writeText(
            """{"version":"1.2.3","port":$port,"installedAt":0,"components":["opencode"]}""",
        )
        File(folder.root, "environment/rootfs/usr/local/bin").mkdirs()
        File(folder.root, "environment/rootfs/usr/local/bin/opencode").writeText("")
    }
}
