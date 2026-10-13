package com.konprostart.tariffiacode.runtime.local

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * The guest capabilities the app writes into a rootfs regardless of which agents are installed:
 * the helper scripts, and the browser and schedule MCP servers registered for every agent that
 * understands them.
 *
 * These live apart from [LocalRuntimeInstaller] because they are not part of one agent's install -
 * they are written into whichever rootfs is being prepared, including an agent's userland of its own
 * and the re-seed path for runtimes installed before the provisioning existed.
 */
internal class GuestCapabilities(
    private val context: Context,
) {
    /** The Android-vision helper scripts and both MCP servers, into the rootfs's own bin. */
    fun installHelperScripts(rootfs: File) {
        val binDir = File(rootfs, "usr/local/bin").apply { mkdirs() }
        File(binDir, "android-ui").delete()
        listOf(
            "android-vision.sh" to "android-vision",
            "android-screenshot.sh" to "android-screenshot",
            "android-instrument.sh" to "android-instrument",
            "android-app.sh" to "android-app",
            BROWSER_MCP_SCRIPT_NAME to BROWSER_MCP_SCRIPT_NAME,
            SCHEDULE_MCP_SCRIPT_NAME to SCHEDULE_MCP_SCRIPT_NAME,
        ).forEach {
                (assetName, scriptName) ->
            val scriptFile = File(binDir, scriptName)
            context.assets.open("scripts/$assetName").use { input ->
                scriptFile.outputStream().use { output -> input.copyTo(output) }
            }
            scriptFile.setExecutable(true, false)
        }
        // Remove pre-rename MCP binaries so an upgraded guest does not keep runnable legacy servers.
        File(binDir, LEGACY_BROWSER_MCP_BIN_NAME).delete()
        File(binDir, LEGACY_SCHEDULE_MCP_BIN_NAME).delete()
    }

    /** Registers the guest-browser MCP server with every agent that understands it. */
    fun provisionBrowserMcp(rootfs: File) {
        mergeJsonConfig(File(rootfs, "root/.config/opencode/opencode.json")) { root ->
            upsertMcpServer(root, "mcp", BROWSER_MCP_NAME, browserMcpEntry("opencode"), LEGACY_BROWSER_MCP_NAME)
        }
        mergeJsonConfig(File(rootfs, "root/.claude.json")) { root ->
            upsertMcpServer(root, "mcpServers", BROWSER_MCP_NAME, browserMcpEntry("claude"), LEGACY_BROWSER_MCP_NAME)
        }
        mergeJsonConfig(File(rootfs, "root/.gemini/config/mcp_config.json")) { root ->
            upsertMcpServer(root, "mcpServers", BROWSER_MCP_NAME, browserMcpEntry("antigravity"), LEGACY_BROWSER_MCP_NAME)
        }
    }

    /** Registers the guest-schedule MCP server with every agent, so they all expose the same schedule_* tools. */
    fun provisionScheduleMcp(rootfs: File) {
        mergeJsonConfig(File(rootfs, "root/.config/opencode/opencode.json")) { root ->
            upsertMcpServer(root, "mcp", SCHEDULE_MCP_NAME, scheduleMcpEntry("opencode"), LEGACY_SCHEDULE_MCP_NAME)
        }
        mergeJsonConfig(File(rootfs, "root/.claude.json")) { root ->
            upsertMcpServer(root, "mcpServers", SCHEDULE_MCP_NAME, scheduleMcpEntry("claude"), LEGACY_SCHEDULE_MCP_NAME)
        }
        mergeJsonConfig(File(rootfs, "root/.gemini/config/mcp_config.json")) { root ->
            upsertMcpServer(root, "mcpServers", SCHEDULE_MCP_NAME, scheduleMcpEntry("antigravity"), LEGACY_SCHEDULE_MCP_NAME)
        }
    }

    /**
     * Installs the Claude Code PermissionRequest hook into a rootfs.
     *
     * Claude's, but written at the rootfs level rather than as part of Claude's own install: a
     * runtime installed before this provisioning existed still needs it on re-seed, and duplicating
     * the script read here would let the two copies drift.
     */
    fun installClaudePermissionHook(rootfs: File) {
        if (!rootfs.isDirectory) return
        runCatching {
            val script =
                context.assets.open("scripts/tariffiacode-claude-permission-hook.sh").bufferedReader().use { it.readText() }
            ClaudePermissionHooks.installInto(rootfs, script)
        }
    }

    /**
     * Copies the Alpine trust store into a rootfs built from a different userland.
     *
     * The Debian rootfs ships without it, and without it every HTTPS call the agent makes - its
     * release check included - would fail on a certificate it cannot verify.
     */
    fun copyCaCertificates(
        alpineRootfs: File,
        otherRootfs: File,
    ) {
        val source = File(alpineRootfs, "etc/ssl/certs/ca-certificates.crt")
        if (!source.isFile) return
        val destination = File(otherRootfs, "etc/ssl/certs/ca-certificates.crt")
        destination.parentFile?.mkdirs()
        source.copyTo(destination, overwrite = true)
    }

    private fun browserMcpEntry(agent: String): JSONObject =
        when (agent) {
            "claude" ->
                JSONObject()
                    .put("type", "stdio")
                    .put("command", BROWSER_MCP_BIN)
                    .put("args", org.json.JSONArray())
            "antigravity" -> JSONObject().put("command", BROWSER_MCP_BIN)
            else ->
                JSONObject()
                    .put("type", "local")
                    .put("command", org.json.JSONArray(listOf(BROWSER_MCP_BIN)))
                    .put("enabled", true)
                    .put("timeout", BROWSER_MCP_TIMEOUT_MILLIS)
        }

    private fun scheduleMcpEntry(agent: String): JSONObject =
        when (agent) {
            "claude" ->
                JSONObject()
                    .put("type", "stdio")
                    .put("command", SCHEDULE_MCP_BIN)
                    .put("args", org.json.JSONArray())
            "antigravity" -> JSONObject().put("command", SCHEDULE_MCP_BIN)
            else ->
                JSONObject()
                    .put("type", "local")
                    .put("command", org.json.JSONArray(listOf(SCHEDULE_MCP_BIN)))
                    .put("enabled", true)
                    .put("timeout", SCHEDULE_MCP_TIMEOUT_MILLIS)
        }

    private fun mergeJsonConfig(
        file: File,
        mutate: (JSONObject) -> Unit,
    ) {
        file.parentFile?.mkdirs()
        val root =
            if (file.isFile) {
                runCatching { JSONObject(file.readText()) }.getOrNull() ?: JSONObject()
            } else {
                JSONObject()
            }
        val before = root.toString()
        mutate(root)
        if (root.toString() != before) {
            file.writeText(root.toString(2) + "\n")
        }
    }

    internal companion object {
        const val BROWSER_MCP_NAME = "tariffiacode-browser"
        const val LEGACY_BROWSER_MCP_NAME = "and-code-browser"
        const val BROWSER_MCP_BIN = "/usr/local/bin/tariffiacode-browser-mcp.py"
        const val BROWSER_MCP_SCRIPT_NAME = "tariffiacode-browser-mcp.py"
        const val LEGACY_BROWSER_MCP_BIN_NAME = "andcode-browser-mcp.py"
        const val BROWSER_MCP_TIMEOUT_MILLIS = 30000
        const val SCHEDULE_MCP_NAME = "tariffiacode-schedule"
        const val LEGACY_SCHEDULE_MCP_NAME = "and-code-schedule"
        const val SCHEDULE_MCP_BIN = "/usr/local/bin/tariffiacode-schedule-mcp.py"
        const val SCHEDULE_MCP_SCRIPT_NAME = "tariffiacode-schedule-mcp.py"
        const val LEGACY_SCHEDULE_MCP_BIN_NAME = "andcode-schedule-mcp.py"
        const val SCHEDULE_MCP_TIMEOUT_MILLIS = 120000

        /**
         * Registers [newName] under [containerKey] in [root], dropping the pre-rename [legacyName]
         * if present. Every other server in the container, and the rest of [root], is left as-is.
         */
        internal fun upsertMcpServer(
            root: JSONObject,
            containerKey: String,
            newName: String,
            entry: JSONObject,
            legacyName: String,
        ) {
            val servers = root.optJSONObject(containerKey) ?: JSONObject()
            servers.remove(legacyName)
            servers.put(newName, entry)
            root.put(containerKey, servers)
        }
    }
}
