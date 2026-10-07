package com.konprostart.tariffiacode.runtime.vps

import com.konprostart.tariffiacode.core.api.OpenCodeAgent
import com.konprostart.tariffiacode.core.api.OpenCodeEvent
import com.konprostart.tariffiacode.core.api.OpenCodeFileChange
import com.konprostart.tariffiacode.core.api.OpenCodeFileContent
import com.konprostart.tariffiacode.core.api.OpenCodeFileNode
import com.konprostart.tariffiacode.core.api.OpenCodeHealth
import com.konprostart.tariffiacode.core.api.OpenCodeMessage
import com.konprostart.tariffiacode.core.api.OpenCodePathInfo
import com.konprostart.tariffiacode.core.api.OpenCodeProject
import com.konprostart.tariffiacode.core.api.OpenCodeSearchMatch
import com.konprostart.tariffiacode.core.api.OpenCodeSession
import com.konprostart.tariffiacode.core.api.OpenCodeTodo
import com.konprostart.tariffiacode.core.api.OpenCodeVcsInfo
import com.konprostart.tariffiacode.core.api.PromptRequest
import com.konprostart.tariffiacode.core.api.ProviderAuthAuthorization
import com.konprostart.tariffiacode.core.api.ProviderAuthMethod
import com.konprostart.tariffiacode.core.api.ProviderCatalog
import com.konprostart.tariffiacode.core.api.QuestionRequest
import com.konprostart.tariffiacode.core.ssh.SshHostKey
import com.konprostart.tariffiacode.core.ssh.SshPortForward
import com.konprostart.tariffiacode.data.remote.RemoteProject
import com.konprostart.tariffiacode.data.remote.RemoteProjectResolver
import com.konprostart.tariffiacode.data.remote.RemoteProjectStore
import com.konprostart.tariffiacode.data.ssh.SshProfile
import com.konprostart.tariffiacode.data.ssh.SshProfileStore
import com.konprostart.tariffiacode.data.vps.VpsSelectionStore
import com.konprostart.tariffiacode.runtime.BackendKind
import com.konprostart.tariffiacode.runtime.PermissionResponse
import com.konprostart.tariffiacode.runtime.RuntimeCapabilities
import com.konprostart.tariffiacode.runtime.RuntimeState
import com.konprostart.tariffiacode.runtime.RuntimeTarget
import com.konprostart.tariffiacode.runtime.RuntimeType
import com.konprostart.tariffiacode.runtime.WorkspaceRef
import com.konprostart.tariffiacode.runtime.mergeSessionLists
import com.konprostart.tariffiacode.runtime.mergeWorkspaceRefs
import com.konprostart.tariffiacode.runtime.remote.RemoteOpenCodeBackend
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * A remote OpenCode runtime reached over SSH.
 *
 * It is an execution-target adapter around the existing [RemoteOpenCodeBackend]: it opens an SSH local
 * port forward ([VpsRuntimeConnector]) to the VPS's already-running OpenCode HTTP server, builds the same
 * loopback [com.konprostart.tariffiacode.data.connection.ConnectionProfile] shape the Android-local runtime
 * uses, and then delegates the entire OpenCode experience to [RemoteOpenCodeBackend] — no second OpenCode
 * client, no agent fork.
 *
 * `agent` is left null, exactly as [com.konprostart.tariffiacode.runtime.remote.RemoteRuntimeTarget] does,
 * so the existing UI treats it as OpenCode without any per-target special cases.
 *
 * Selection is single: one VPS entry that the owner points at a stored [SshProfile]. Persisting that
 * choice and the project mapping is a later step.
 */
class VpsRuntimeTarget(
    private val connector: VpsRuntimeConnector,
    private val remoteHost: String = VpsRuntimeConnector.DEFAULT_REMOTE_HOST,
    private val remotePort: Int = VpsRuntimeConnector.DEFAULT_REMOTE_PORT,
    override val id: String = DEFAULT_ID,
    /** Resolves the persisted selected-profile id back to a profile on startup. */
    private val profiles: SshProfileStore? = null,
    /** Resolves the persisted applied-mapping id back to a Remote Project on startup. */
    private val projects: RemoteProjectStore? = null,
    /** Persists the selected profile / applied mapping so they survive restarts. */
    private val selection: VpsSelectionStore = VpsSelectionStore(),
) : RuntimeTarget,
    VpsConnectionController {
    override val displayName: String = "OpenCode VPS"
    override val type: RuntimeType = RuntimeType.REMOTE
    override val kind: BackendKind = BackendKind.REMOTE
    override val capabilities =
        RuntimeCapabilities(
            permissions = true,
            providerModelList = true,
            abortsBeforeInterrupt = true,
            editMessages = true,
            diffCapable = true,
        )

    private val mutableState = MutableStateFlow<RuntimeState>(RuntimeState.Unavailable("No SSH connection selected"))
    override val state: StateFlow<RuntimeState> = mutableState.asStateFlow()

    private val mutableSelectedProfile = MutableStateFlow<SshProfile?>(null)

    /** The SSH connection currently selected for this target, if any. */
    val selectedProfile: StateFlow<SshProfile?> = mutableSelectedProfile.asStateFlow()

    private val mutableSelectedRemoteProject = MutableStateFlow<RemoteProject?>(null)

    /** The Remote Project mapping currently applied to this runtime, if any. */
    override val selectedRemoteProject: StateFlow<RemoteProject?> = mutableSelectedRemoteProject.asStateFlow()

    private val mutablePendingHostKey = MutableStateFlow<SshHostKey?>(null)

    /** A host key awaiting explicit user trust, surfaced when the connection was refused for it. */
    override val pendingHostKey: StateFlow<SshHostKey?> = mutablePendingHostKey.asStateFlow()

    @Volatile
    private var backend: RemoteOpenCodeBackend? = null

    @Volatile
    private var forward: SshPortForward? = null

    /** True while the SSH forward backing this runtime is open. Exposed for tests/diagnostics. */
    override val isForwardOpen: Boolean
        get() = forward?.isOpen == true

    init {
        // Restore the persisted selection before anything can read it, so reopening the app or coming
        // back from process death re-points the VPS runtime at the same server and folder. A
        // profile/mapping deleted since is cleared rather than resurrected.
        selection.selectedProfileId()?.let { id ->
            val restored = profiles?.profile(id)
            if (restored != null) selectProfile(restored) else selection.selectProfile(null)
        }
        selection.selectedProjectId()?.let { id ->
            val restored = projects?.project(id)
            if (restored != null) selectRemoteProject(restored) else selection.selectProject(null)
        }
    }

    /** Choose which stored SSH connection this target uses. Does not connect. */
    fun selectProfile(profile: SshProfile?) {
        mutableSelectedProfile.value = profile
        // Persist the choice so it survives a restart / process death.
        selection.selectProfile(profile?.id)
        if (!isConnected()) {
            mutableState.value =
                if (profile == null) {
                    RuntimeState.Unavailable("No SSH connection selected")
                } else {
                    RuntimeState.Disconnected
                }
        }
    }

    /**
     * Apply (or clear, with null) the Remote Project mapping whose remote path this runtime should use as
     * its OpenCode workspace. Only the mapping's path is used; its SSH profile must match the selected
     * profile at resolution time, see [RemoteProjectResolver].
     */
    override fun selectRemoteProject(project: RemoteProject?) {
        mutableSelectedRemoteProject.value = project
        // Persist the mapping so it survives a restart / process death.
        selection.selectProject(project?.id)
    }

    /** Persist a host key the user confirmed, so the next connect trusts it. Returns the new profile. */
    fun trustHostKey(
        profile: SshProfile,
        hostKey: SshHostKey,
    ): SshProfile {
        mutablePendingHostKey.value = null
        return profile.trusting(hostKey.sha256Fingerprint)
    }

    /** The UI confirms the pending key: trust it for the selected profile, returning it to persist. */
    override fun trustHostKey(hostKey: SshHostKey): SshProfile? {
        val profile = mutableSelectedProfile.value ?: return null
        return trustHostKey(profile, hostKey)
    }

    /** The UI declined the pending key: drop it without trusting. */
    override fun dismissHostKey() {
        mutablePendingHostKey.value = null
    }

    override suspend fun connect(): Result<OpenCodeHealth> {
        val profile =
            mutableSelectedProfile.value
                ?: return fail(RuntimeState.Unavailable("No SSH connection selected"), "No SSH connection selected")

        // Re-checking an already connected runtime must not tear down the forward / event stream.
        if (isConnected()) return healthResult()

        mutableState.value = RuntimeState.Connecting
        return when (val outcome = connector.connect(profile, remoteHost = remoteHost, remotePort = remotePort)) {
            is VpsConnectOutcome.Connected -> {
                forward = outcome.forward
                backend = RemoteOpenCodeBackend(outcome.profile)
                mutablePendingHostKey.value = null
                val result = healthResult()
                // A forward that came up but whose OpenCode is unhealthy is still a failed connection:
                // release it so the target is left in a clean, non-connected state.
                if (mutableState.value !is RuntimeState.Connected) closeResources()
                result
            }
            is VpsConnectOutcome.NeedsHostKeyTrust -> {
                mutablePendingHostKey.value = outcome.hostKey
                fail(
                    RuntimeState.Failed("SSH host key is not trusted"),
                    "SSH host key is not trusted",
                )
            }
            is VpsConnectOutcome.HostKeyMismatch -> {
                mutablePendingHostKey.value = null
                fail(
                    RuntimeState.Failed("SSH host key mismatch"),
                    "SSH host key mismatch: expected ${outcome.expectedFingerprint}",
                )
            }
            VpsConnectOutcome.MissingCredential -> {
                mutablePendingHostKey.value = null
                fail(RuntimeState.Failed("No stored SSH credential"), "No stored SSH credential")
            }
            is VpsConnectOutcome.Failed -> {
                mutablePendingHostKey.value = null
                fail(RuntimeState.Failed(outcome.message), outcome.message)
            }
        }
    }

    /** Fresh connection: drop any existing forward/session, then connect again. */
    override suspend fun reconnect(): Result<OpenCodeHealth> {
        disconnect()
        return connect()
    }

    override fun disconnect() {
        closeResources()
        mutablePendingHostKey.value = null
        mutableState.value =
            if (mutableSelectedProfile.value == null) {
                RuntimeState.Unavailable("No SSH connection selected")
            } else {
                RuntimeState.Disconnected
            }
    }

    /** Close the SSH forward/session and drop the backend. Safe to call repeatedly. Does not touch state. */
    private fun closeResources() {
        runCatching { forward?.close() }
        forward = null
        backend = null
    }

    override suspend fun listWorkspaces(): List<WorkspaceRef> {
        val current = runCatching { requireBackend().pathInfo().directory }.getOrNull()
        val sessions = requireBackend().listSessions()
        val projects = runCatching { requireBackend().listProjects() }.getOrDefault(emptyList())
        val reported = mergeWorkspaceRefs(current, sessions, projects)
        // Surface the mapped remote path as a workspace even before OpenCode has seen it, so the user can
        // pick the project they mapped and have it become the session directory.
        val mapped =
            effectiveDirectory(null)?.let { path ->
                WorkspaceRef(id = path, name = mappingLabel() ?: path, path = path)
            }
        return (listOfNotNull(mapped) + reported).distinctBy { it.path }
    }

    override suspend fun health(): OpenCodeHealth = requireBackend().health()

    override suspend fun listSessions(directory: String?): List<OpenCodeSession> {
        effectiveDirectory(directory)?.let { return requireBackend().listSessions(it) }
        val projects = runCatching { requireBackend().listProjects() }.getOrDefault(emptyList())
        val directories =
            buildList<String?> {
                add(null)
                projects
                    .asSequence()
                    .filterNot { it.id == "global" }
                    .map { it.worktree }
                    .filter { it.isNotBlank() && it != "/" }
                    .forEach(::add)
            }.distinct()
        val sessionLists =
            directories.map { scopedDirectory ->
                runCatching { requireBackend().listSessions(scopedDirectory) }.getOrDefault(emptyList())
            }
        return mergeSessionLists(sessionLists)
    }

    override suspend fun createSession(
        title: String?,
        directory: String?,
    ): OpenCodeSession = requireBackend().createSession(title, effectiveDirectory(directory))

    override suspend fun session(sessionId: String): OpenCodeSession = requireBackend().session(sessionId)

    override suspend fun listMessages(sessionId: String): List<OpenCodeMessage> = requireBackend().listMessages(sessionId)

    override suspend fun deleteMessage(
        sessionId: String,
        messageId: String,
    ): Boolean = requireBackend().deleteMessage(sessionId, messageId)

    override suspend fun listProviders(): ProviderCatalog = requireBackend().listProviders()

    override suspend fun listAgents(): List<OpenCodeAgent> = requireBackend().listAgents()

    override suspend fun providerAuthMethods(): Map<String, List<ProviderAuthMethod>> = requireBackend().providerAuthMethods()

    override suspend fun authorizeProvider(
        providerId: String,
        methodIndex: Int,
        inputs: Map<String, String>,
    ): ProviderAuthAuthorization = requireBackend().authorizeProvider(providerId, methodIndex, inputs)

    override suspend fun setProviderApiKey(
        providerId: String,
        apiKey: String,
        metadata: Map<String, String>,
    ): Boolean = requireBackend().setProviderApiKey(providerId, apiKey, metadata)

    override suspend fun removeProviderAuth(providerId: String): Boolean = requireBackend().removeProviderAuth(providerId)

    override suspend fun completeProviderOAuth(
        providerId: String,
        methodIndex: Int,
        code: String?,
    ): Boolean = requireBackend().completeProviderOAuth(providerId, methodIndex, code)

    override suspend fun listProjects(directory: String?): List<OpenCodeProject> = requireBackend().listProjects(directory)

    override suspend fun currentProject(directory: String?): OpenCodeProject = requireBackend().currentProject(directory)

    override suspend fun pathInfo(directory: String?): OpenCodePathInfo = requireBackend().pathInfo(directory)

    override suspend fun listFiles(
        directory: String,
        path: String,
    ): List<OpenCodeFileNode> = requireBackend().listFiles(directory, path)

    override suspend fun readFile(
        directory: String,
        path: String,
    ): OpenCodeFileContent = requireBackend().readFile(directory, path)

    override suspend fun fileStatus(directory: String): List<OpenCodeFileChange> = requireBackend().fileStatus(directory)

    override suspend fun searchText(
        directory: String,
        pattern: String,
    ): List<OpenCodeSearchMatch> = requireBackend().searchText(directory, pattern)

    override suspend fun findFiles(
        directory: String,
        query: String,
        includeDirectories: Boolean?,
        type: String?,
        limit: Int?,
    ): List<String> = requireBackend().findFiles(directory, query, includeDirectories, type, limit)

    override suspend fun vcsInfo(directory: String): OpenCodeVcsInfo = requireBackend().vcsInfo(directory)

    override suspend fun vcsStatus(directory: String): List<OpenCodeFileChange> = requireBackend().vcsStatus(directory)

    override suspend fun vcsDiff(
        directory: String,
        mode: String,
        context: Int?,
    ): List<OpenCodeFileChange> = requireBackend().vcsDiff(directory, mode, context)

    override suspend fun sessionDiff(
        sessionId: String,
        directory: String?,
        messageId: String?,
    ): List<OpenCodeFileChange> = requireBackend().sessionDiff(sessionId, directory, messageId)

    override suspend fun sessionTodo(
        sessionId: String,
        directory: String?,
    ): List<OpenCodeTodo> = requireBackend().sessionTodo(sessionId, directory)

    override suspend fun sendMessage(
        sessionId: String,
        request: PromptRequest,
    ) = requireBackend().sendMessage(sessionId, request)

    override suspend fun abortSession(sessionId: String): Boolean = requireBackend().abortSession(sessionId)

    override suspend fun renameSession(
        sessionId: String,
        title: String,
    ): OpenCodeSession = requireBackend().renameSession(sessionId, title)

    override suspend fun deleteSession(sessionId: String): Boolean = requireBackend().deleteSession(sessionId)

    override suspend fun respondToPermission(
        sessionId: String,
        permissionId: String,
        response: PermissionResponse,
        remember: Boolean,
    ): Boolean = requireBackend().respondToPermission(sessionId, permissionId, response, remember)

    override suspend fun archiveSession(sessionId: String): OpenCodeSession = requireBackend().archiveSession(sessionId)

    override suspend fun mcpServers(): List<com.konprostart.tariffiacode.core.api.McpServer> = requireBackend().mcpServers()

    override suspend fun addMcpServer(body: JsonObject): com.konprostart.tariffiacode.core.api.McpServer =
        requireBackend().addMcpServer(body)

    override suspend fun connectMcpServer(name: String): Boolean = requireBackend().connectMcpServer(name)

    override suspend fun disconnectMcpServer(name: String): Boolean = requireBackend().disconnectMcpServer(name)

    override suspend fun removeMcpAuth(name: String): com.konprostart.tariffiacode.core.api.McpAuthRemoval =
        requireBackend().removeMcpAuth(name)

    override suspend fun mcpAuth(name: String): com.konprostart.tariffiacode.core.api.McpAuthStart = requireBackend().mcpAuth(name)

    override suspend fun mcpAuthCallback(
        name: String,
        code: String,
    ): com.konprostart.tariffiacode.core.api.McpAuthStatus = requireBackend().mcpAuthCallback(name, code)

    override suspend fun config(): JsonElement = requireBackend().config()

    override suspend fun updateConfig(patch: JsonObject): JsonElement = requireBackend().updateConfig(patch)

    override suspend fun configProviders(): List<com.konprostart.tariffiacode.core.api.ConfiguredProvider> =
        requireBackend().configProviders()

    override suspend fun commands(): List<com.konprostart.tariffiacode.core.api.OpenCodeCommand> = requireBackend().commands()

    override suspend fun skills(): List<com.konprostart.tariffiacode.core.api.OpenCodeSkill> = requireBackend().skills()

    override suspend fun executeCommand(
        sessionId: String,
        command: String,
        arguments: String,
    ) = requireBackend().executeCommand(sessionId, command, arguments)

    override suspend fun summarizeSession(
        sessionId: String,
        providerId: String,
        modelId: String,
    ): Boolean = requireBackend().summarizeSession(sessionId, providerId, modelId)

    override suspend fun answerQuestion(
        requestId: String,
        answers: List<List<String>>,
        directory: String?,
    ): Boolean = requireBackend().answerQuestion(requestId, answers, directory)

    override suspend fun rejectQuestion(
        requestId: String,
        directory: String?,
    ): Boolean = requireBackend().rejectQuestion(requestId, directory)

    override suspend fun pendingQuestions(directory: String?): List<QuestionRequest> = requireBackend().pendingQuestions(directory)

    override fun events(): Flow<OpenCodeEvent> = requireBackend().events()

    private fun isConnected(): Boolean = backend != null && forward?.isOpen == true

    private suspend fun healthResult(): Result<OpenCodeHealth> =
        runCatching { requireBackend().health() }
            .onSuccess { health ->
                mutableState.value =
                    if (health.healthy) {
                        RuntimeState.Connected(health.version)
                    } else {
                        RuntimeState.Failed("OpenCode on the VPS reported an unhealthy server")
                    }
            }
            .onFailure { error ->
                mutableState.value = RuntimeState.Failed(error.message?.takeIf(String::isNotBlank) ?: "OpenCode on the VPS is unreachable")
            }

    private fun fail(
        state: RuntimeState,
        message: String,
    ): Result<OpenCodeHealth> {
        mutableState.value = state
        return Result.failure(IllegalStateException(message))
    }

    private fun requireBackend(): RemoteOpenCodeBackend = backend ?: error("VPS runtime is not connected")

    /**
     * The OpenCode directory to use: an explicit request wins, otherwise the selected Remote Project's
     * path when its SSH profile matches the one this runtime is connected with.
     */
    private fun effectiveDirectory(requested: String?): String? =
        RemoteProjectResolver.resolveDirectory(
            mapping = mutableSelectedRemoteProject.value,
            selectedSshProfileId = mutableSelectedProfile.value?.id,
            requestedDirectory = requested,
        )

    private fun mappingLabel(): String? = mutableSelectedRemoteProject.value?.label

    companion object {
        const val DEFAULT_ID = "vps"
    }
}
