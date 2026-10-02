package com.konprostart.tariffiacode.runtime.local

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalRuntimeInstallerMcpTest {
    @Test
    fun `upsertMcpServer removes legacy browser key and keeps user servers`() {
        val root =
            JSONObject(
                """
                {
                  "mcpServers": {
                    "and-code-browser": { "command": "/usr/local/bin/andcode-browser-mcp.py" },
                    "my-own-server": { "command": "/home/user/mine" }
                  },
                  "other": { "keep": true }
                }
                """.trimIndent(),
            )

        LocalRuntimeInstaller.upsertMcpServer(
            root = root,
            containerKey = "mcpServers",
            newName = "tariffiacode-browser",
            entry = JSONObject().put("command", "/usr/local/bin/tariffiacode-browser-mcp.py"),
            legacyName = "and-code-browser",
        )

        val servers = root.getJSONObject("mcpServers")
        assertFalse("legacy browser entry must be removed", servers.has("and-code-browser"))
        assertTrue("current browser entry must be present", servers.has("tariffiacode-browser"))
        assertTrue("user server must be preserved", servers.has("my-own-server"))
        assertTrue(root.getJSONObject("other").getBoolean("keep"))
    }

    @Test
    fun `upsertMcpServer removes legacy schedule key from opencode mcp container`() {
        val root =
            JSONObject(
                """
                {
                  "mcp": {
                    "and-code-schedule": { "command": ["/usr/local/bin/andcode-schedule-mcp.py"] },
                    "keep-me": { "enabled": true }
                  }
                }
                """.trimIndent(),
            )

        LocalRuntimeInstaller.upsertMcpServer(
            root = root,
            containerKey = "mcp",
            newName = "tariffiacode-schedule",
            entry = JSONObject().put("enabled", true),
            legacyName = "and-code-schedule",
        )

        val mcp = root.getJSONObject("mcp")
        assertFalse("legacy schedule entry must be removed", mcp.has("and-code-schedule"))
        assertTrue("current schedule entry must be present", mcp.has("tariffiacode-schedule"))
        assertTrue("unrelated server must be preserved", mcp.has("keep-me"))
    }

    @Test
    fun `upsertMcpServer creates the container when it is absent`() {
        val root = JSONObject()

        LocalRuntimeInstaller.upsertMcpServer(
            root = root,
            containerKey = "mcpServers",
            newName = "tariffiacode-browser",
            entry = JSONObject().put("command", "/usr/local/bin/tariffiacode-browser-mcp.py"),
            legacyName = "and-code-browser",
        )

        assertEquals(
            "/usr/local/bin/tariffiacode-browser-mcp.py",
            root.getJSONObject("mcpServers").getJSONObject("tariffiacode-browser").getString("command"),
        )
    }
}
