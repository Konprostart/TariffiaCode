package com.konprostart.tariffiacode.core.router

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The bundled routing table's two structural rules.
 *
 * Order within a chain is the failover order, so an entry at the end of a chain has nothing behind
 * it to fail over to. That is fine for a cheap last resort and is not fine for Anthropic, which
 * reports an exhausted credit balance as its own error rather than as a transport failure: with
 * Anthropic last, "Claude ran out of credits" is exactly the failure the router cannot answer, and
 * the turn ends where the chain was supposed to continue.
 *
 * Read from the asset file rather than a copy of it, so editing the table and running the tests
 * cannot disagree.
 */
class RouterBundledTableTest {
    @Test
    fun `anthropic is never the last entry of a chain`() {
        val result = RouterConfigTable.fromJson(bundledTable())

        assertTrue("the bundled table must parse into chains", result.config.chains.isNotEmpty())
        result.config.chains.forEach { chain ->
            val providers = chain.models.map { it.provider }
            assertTrue(
                "${chain.tier}: anthropic must have something behind it to fail over to, was $providers",
                providers.last() != "anthropic",
            )
        }
    }

    @Test
    fun `the bundled table validates without dropping anything`() {
        // A dropped entry shortens the chain that was validated, and then every other assertion here
        // passes on a chain that no longer matches the file - a tier quietly left with one provider is
        // exactly the failure the router cannot recover from, so it has to fail the build instead.
        val result = RouterConfigTable.fromJson(bundledTable())

        assertTrue("the bundled table must be usable as shipped: ${result.rejected}", result.isUsable)
        assertTrue("and nothing in it may be dropped: ${result.rejected}", result.rejected.isEmpty())
    }

    @Test
    fun `the first entry of every chain is one the router can actually reach`() {
        // The first entry is paid for on every turn, so it has to be one the user can reach: either a
        // vendor this build knows where to send a request, or one the user has addressed themselves.
        // A name nothing can resolve is a chain that pays a failure before it starts serving.
        val result = RouterConfigTable.fromJson(bundledTable())

        result.config.chains.forEach { chain ->
            val first = chain.models.first()
            assertTrue(
                "${chain.tier}: the first entry must be a provider the router knows how to address, was ${first.provider}",
                RouterEngine.defaultEndpoint(first.provider, "a-key") != null,
            )
        }
    }

    private fun bundledTable(): String = repositoryRoot().resolve("app/src/main/assets/$BUNDLED_TABLE").readText()

    private fun repositoryRoot(): File {
        val workingDirectory = System.getProperty("user.dir") ?: error("Test working directory is unavailable")
        var directory = File(workingDirectory)
        while (true) {
            if (directory.resolve(".release-version").isFile) return directory
            directory = directory.parentFile ?: error("Could not locate .release-version from $workingDirectory")
        }
    }

    private companion object {
        /** The one file this is about; the name the app ships and the harness defaults to. */
        const val BUNDLED_TABLE = "claude_tiers.json"
    }
}
