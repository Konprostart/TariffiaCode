package com.konprostart.tariffiacode.core.router

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which routing table the router runs, and whether the user knows.
 *
 * Adding a model should be data rather than a release, which only works if a file the user can reach
 * actually wins over the bundled copy. The cases that matter are the ones where it silently does not:
 * a malformed override, or one that leaves nothing servable. Both would otherwise produce "I changed
 * it and nothing happened", which is the worst possible failure for a routing table.
 */
class RouterConfigSourceTest {
    /**
     * A table the loader accepts: every tier present and servable.
     *
     * All three, not one - a table missing tiers is exactly the case that falls back, and a fixture
     * that quietly exercises the wrong path makes the test mean something other than it says.
     */
    private val usable =
        """
        {"chains":[
          {"tier":"haiku","models":[{"provider":"groq","model":"small","contextWindow":200000}]},
          {"tier":"sonnet","models":[{"provider":"deepseek","model":"deepseek-chat","contextWindow":200000}]},
          {"tier":"opus","models":[{"provider":"deepseek","model":"deepseek-reasoner","contextWindow":200000}]}
        ]}
        """.trimIndent()

    /** Well formed, but validation empties every chain. */
    private val everyChainEmpty =
        """
        {"chains":[
          {"tier":"haiku","models":[]},
          {"tier":"sonnet","models":[]},
          {"tier":"opus","models":[]}
        ]}
        """.trimIndent()

    @Test
    fun `a user table wins over the bundled one`() {
        val loaded = RouterConfigSource.resolve({ usable }, { throw AssertionError("bundled must not be read") })

        assertEquals(RouterConfigSource.Origin.USER_FILE, loaded.origin)
        assertEquals("deepseek-chat", loaded.table.config.chainFor(RouterTier.SONNET).single().model)
        assertNull("nothing was ignored, so nothing needs explaining", loaded.rejected)
    }

    @Test
    fun `no user table falls back to the bundled one, quietly`() {
        val loaded = RouterConfigSource.resolve({ null }, { usable })

        assertEquals(RouterConfigSource.Origin.BUNDLED, loaded.origin)
        assertTrue(loaded.isUsable)
        assertNull("the common case should not produce a warning about nothing", loaded.rejected)
    }

    @Test
    fun `a malformed user table falls back and says so`() {
        val loaded = RouterConfigSource.resolve({ "{ this is not json" }, { usable })

        assertEquals(RouterConfigSource.Origin.BUNDLED, loaded.origin)
        assertTrue(loaded.isUsable)
        assertNotNull("ignoring a file the user edited must never be silent", loaded.rejected)
    }

    @Test
    fun `a user table with nothing servable in it falls back and says so`() {
        // Every chain emptied by validation: this is the shape of "the user deleted too much".
        val loaded = RouterConfigSource.resolve({ everyChainEmpty }, { usable })

        assertEquals(RouterConfigSource.Origin.BUNDLED, loaded.origin)
        assertTrue(loaded.isUsable)
        assertTrue(
            "the reason has to name the empty chain, not just say something went wrong: ${loaded.rejected}",
            loaded.rejected?.contains("empty") == true,
        )
    }

    @Test
    fun `an unreadable user table falls back and says so`() {
        val loaded =
            RouterConfigSource.resolve(
                { throw java.io.IOException("EACCES") },
                { usable },
            )

        assertEquals(RouterConfigSource.Origin.BUNDLED, loaded.origin)
        assertTrue(loaded.isUsable)
        assertNotNull(loaded.rejected)
    }

    @Test
    fun `a broken bundled table leaves the router with no routing rather than a crash`() {
        val loaded = RouterConfigSource.resolve({ null }, { throw java.io.IOException("asset missing") })

        assertEquals(RouterConfigSource.Origin.BUNDLED, loaded.origin)
        assertEquals("nothing is better than something wrong", false, loaded.isUsable)
        assertTrue(loaded.table.rejected.isNotEmpty())
    }

    @Test
    fun `the user table lives where a file manager can reach it`() {
        val external = java.io.File("/sdcard/Android/data/com.example/files")
        val file = RouterConfigSource.userTableFile(external, "claude_tiers.json")

        assertNotNull(file)
        assertEquals("router/claude_tiers.json", file!!.path.removePrefix(external.path + "/"))
    }

    @Test
    fun `there is no user table location when the app has no external directory`() {
        assertNull(RouterConfigSource.userTableFile(null, "claude_tiers.json"))
    }
}
