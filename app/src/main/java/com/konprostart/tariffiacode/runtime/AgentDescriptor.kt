package com.konprostart.tariffiacode.runtime

/**
 * How an agent's binaries get into the sandbox.
 *
 * The four installed agents use four mutually incompatible provisioning strategies, each with its
 * own release resolver and its own integrity check. Naming them here is what lets the installer be
 * split per strategy later instead of being one function that special-cases every agent.
 */
enum class AgentInstallMechanism {
    /** An upstream tarball, unpacked to a single binary (OpenCode). */
    DOWNLOADED_TARBALL,

    /** An Alpine package from the vendor's own apk repository (Claude Code). */
    APK,

    /** A tarball from the npm registry, with SRI verification (Codex). */
    NPM_TARBALL,

    /** A GitHub release tarball, installed into its own userland (Antigravity). */
    GITHUB_RELEASE_TARBALL,
}

/** Which Linux userland an agent is installed into. */
enum class AgentUserland {
    /**
     * Written into the Alpine staging rootfs before it is swapped into place.
     *
     * The swap is the whole reason this is worth naming: an installer for this userland has to be
     * able to finish before the environment is activated.
     */
    ALPINE_STAGING_ROOTFS,

    /**
     * A separately provisioned Debian rootfs, outside the Alpine swap.
     *
     * Antigravity needs glibc, which the Alpine userland does not provide. An agent on this
     * userland escapes the staging-swap ordering entirely, which is why the two are distinct rather
     * than a flag on one enum.
     */
    DEBIAN_ROOTFS,
}

/** How an MCP server entry is spelled in an agent's own config file. */
enum class AgentMcpEntryStyle {
    /** `{"type":"local","command":[…],"enabled":…,"timeout":…}` - OpenCode's shape. */
    LOCAL_WITH_TIMEOUT,

    /** `{"type":"stdio","command":…,"args":[]}` - Claude Code's shape. */
    STDIO,

    /** `{"command":…}` alone. */
    BARE_COMMAND,
}

/**
 * Where an agent reads its MCP servers from, if anywhere.
 *
 * A sealed type rather than a nullable path so that "this agent has no MCP config" is a value that
 * has to be chosen, not the result of forgetting a field: with a `null` default a new agent would
 * silently ship without its servers.
 */
sealed interface AgentMcpProvisioning {
    /** The agent has no MCP config file and gets no built-in servers. */
    data object None : AgentMcpProvisioning

    /**
     * The agent reads MCP servers from [path], a JSON object on the guest filesystem, under
     * [jsonKey], with each entry spelled [entryStyle].
     */
    data class AtConfigFile(
        val path: String,
        val jsonKey: String,
        val entryStyle: AgentMcpEntryStyle,
    ) : AgentMcpProvisioning
}

/**
 * Everything the app knows about one agent, declared in one place.
 *
 * This exists because the decisions below used to be made implicitly, scattered across a `when` or
 * an `if` per site, where a newly added agent inherited whatever the fallthrough happened to be:
 * `proprietary` came from "anything that is not OpenCode", the MCP connect toggle from "anything
 * not in a three-element set", and so on. Those defaults were wrong by accident rather than by
 * decision, and nothing failed when they were wrong.
 *
 * Declaring them here makes every one an explicit answer. Because [LocalAgent] takes a descriptor as
 * a required constructor argument and no field here has a default, adding an agent does not compile
 * until it has answered all of them - which is the entire point of the type.
 *
 * This is declaration only: it records what is already true and changes no behaviour. Acting on
 * `installMechanism` and `binaryPaths` is a later step; today the installers still hold those
 * constants, and a test pins the two together so they cannot drift.
 */
data class AgentDescriptor(
    /** Stable identifier, persisted in installed-component metadata and passed across process boundaries. */
    val id: String,
    /** Localised display name. */
    val displayNameRes: Int,
    /** Row/drawer icon, used where the name does not fit. */
    val iconRes: Int,
    /** Id of the [RuntimeTarget] this agent is reached through. */
    val targetId: String,
    /**
     * Whether this is a non-free third-party CLI.
     *
     * This decides whether the F-Droid flavour may download the agent at all, so it is declared
     * per agent rather than inferred from "is it the free one".
     */
    val proprietary: Boolean,
    /** How [binaryPaths] get onto the device. */
    val installMechanism: AgentInstallMechanism,
    /** Which Linux userland the agent is installed into. */
    val userland: AgentUserland,
    /**
     * Guest-absolute paths of the executables that make up the agent.
     *
     * Absolute on purpose: the same binary is referenced from a rootfs and from a device-side
     * staging directory, and those callers use different forms of the same path.
     */
    val binaryPaths: List<String>,
    /**
     * Guest path of the file the app writes its shared agent context into, if any.
     *
     * `null` means this agent has no such file and receives none; it is not a default to be filled
     * in later.
     */
    val instructionFile: String?,
    /** Where MCP servers are declared for this agent, if anywhere. */
    val mcp: AgentMcpProvisioning,
    /**
     * Whether the model picker should offer the provider catalogue.
     *
     * Only agents that speak OpenCode's own provider API can. The others publish a synthetic
     * one-provider catalogue and sign in on their own settings screens, so an "add a key" row here
     * could never connect them.
     */
    val runtimeHasProviderCatalog: Boolean,
    /**
     * Whether the MCP screen offers a connect/disconnect toggle for a server.
     *
     * Separate from [mcpSupportsOAuth] on purpose: they happen to agree for every agent today, but
     * they answer different questions and a future agent will need only one of them.
     */
    val mcpSupportsConnectToggle: Boolean,
    /** Whether MCP servers can authenticate through the agent's own OAuth flow. */
    val mcpSupportsOAuth: Boolean,
    /**
     * What this agent's runtime can do.
     *
     * The static floor. A target whose capabilities are genuinely dynamic - Claude Code's depend on
     * whether its permission bridge came up - starts from this and overrides what it must, rather
     * than assembling the set from scratch and letting it drift from what was declared here.
     */
    val capabilities: RuntimeCapabilities,
)
