package com.konprostart.tariffiacode.runtime.local

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
import com.konprostart.tariffiacode.runtime.BackendKind
import com.konprostart.tariffiacode.runtime.LocalAgent
import com.konprostart.tariffiacode.runtime.LocalRuntimeStatus
import com.konprostart.tariffiacode.runtime.PermissionResponse
import com.konprostart.tariffiacode.runtime.RuntimeCapabilities
import com.konprostart.tariffiacode.runtime.RuntimeState
import com.konprostart.tariffiacode.runtime.RuntimeTarget
import com.konprostart.tariffiacode.runtime.RuntimeType
import com.konprostart.tariffiacode.runtime.WorkspaceRef
import com.konprostart.tariffiacode.runtime.mergeSessionLists
import com.konprostart.tariffiacode.runtime.mergeWorkspaceRefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LocalRuntimeTarget(
    private val runtimeManager: LocalRuntimeManager,
    private val backend: LocalOpenCodeBackend = LocalOpenCodeBackend(runtimeManager),
    private val messages: LocalRuntimeMessages = LocalRuntimeMessages,
) : RuntimeTarget {
    override val id: String = LocalAgent.OPEN_CODE.targetId
    override val displayName: String = "OpenCode"
    override val agent: LocalAgent = LocalAgent.OPEN_CODE
    override val type: RuntimeType = RuntimeType.LOCAL
    override val kind: BackendKind = BackendKind.LOCAL
    override val capabilities =
        RuntimeCapabilities(
            permissions = true,
            providerModelList = true,
            abortsBeforeInterrupt = true,
            editMessages = true,
            diffCapable = true,
        )

    // The cached flow value, not status(): this runs during composition (main thread) and status()
    // probes the port, which is a blocking socket connect.
    private val mutableState = MutableStateFlow(mapStatus(runtimeManager.state.value))
    override val state: StateFlow<RuntimeState> = mutableState.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        scope.launch {
            runtimeManager.state.collect { status ->
                if (status !is LocalRuntimeStatus.Ready) {
                    backend.invalidate()
                }
                mutableState.value = mapStatus(status)
            }
        }
    }

    fun refreshLocalState(): RuntimeState =
        mapStatus(runtimeManager.state.value).also {
            mutableState.value = it
        }

    override suspend fun connect(): Result<OpenCodeHealth> {
        // connect() is polled by the connection-quality monitor on the caller's dispatcher, which is
        // the main thread for an open chat; status() probes the port, so run it off that thread.
        val localStatus = withContext(Dispatchers.IO) { runtimeManager.status() }
        if (localStatus !is LocalRuntimeStatus.Ready) {
            val state = mapStatus(localStatus)
            mutableState.value = state
            return Result.failure(IllegalStateException(state.describe()))
        }

        // Keep an already connected runtime connected while re-checking health, otherwise every
        // catalog refresh restarts the event stream and drops in-flight replies.
        if (mutableState.value !is RuntimeState.Connected) {
            mutableState.value = RuntimeState.Connecting
        }
        return runCatching { backend.health() }
            .onSuccess { health ->
                mutableState.value =
                    if (health.healthy) {
                        RuntimeState.Connected(health.version)
                    } else {
                        RuntimeState.Failed(messages.localRuntimeUnhealthy)
                    }
            }
            .onFailure { error ->
                mutableState.value = RuntimeState.Failed(error.message?.takeIf(String::isNotBlank) ?: messages.localRuntimeConnectionFailed)
            }
    }

    override fun disconnect() {
        mutableState.value = mapStatus(runtimeManager.state.value)
    }

    override suspend fun listWorkspaces(): List<WorkspaceRef> {
        val current = runCatching { backend.pathInfo().directory }.getOrNull()
        val sessions = backend.listSessions()
        val projects = runCatching { backend.listProjects() }.getOrDefault(emptyList())
        return mergeWorkspaceRefs(current, sessions, projects)
    }

    override suspend fun health(): OpenCodeHealth = backend.health()

    override suspend fun listSessions(directory: String?): List<OpenCodeSession> {
        if (directory != null) return backend.listSessions(directory)
        val projects = runCatching { backend.listProjects() }.getOrDefault(emptyList())
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
                runCatching { backend.listSessions(scopedDirectory) }.getOrDefault(emptyList())
            }
        return mergeSessionLists(sessionLists)
    }

    override suspend fun createSession(
        title: String?,
        directory: String?,
    ): OpenCodeSession = backend.createSession(title, directory)

    override suspend fun session(sessionId: String): OpenCodeSession = backend.session(sessionId)

    override suspend fun listMessages(sessionId: String): List<OpenCodeMessage> = backend.listMessages(sessionId)

    override suspend fun deleteMessage(
        sessionId: String,
        messageId: String,
    ): Boolean = backend.deleteMessage(sessionId, messageId)

    override suspend fun listProviders(): ProviderCatalog = backend.listProviders()

    override suspend fun listAgents(): List<OpenCodeAgent> = backend.listAgents()

    override suspend fun providerAuthMethods(): Map<String, List<ProviderAuthMethod>> = backend.providerAuthMethods()

    override suspend fun authorizeProvider(
        providerId: String,
        methodIndex: Int,
        inputs: Map<String, String>,
    ): ProviderAuthAuthorization = backend.authorizeProvider(providerId, methodIndex, inputs)

    override suspend fun setProviderApiKey(
        providerId: String,
        apiKey: String,
        metadata: Map<String, String>,
    ): Boolean = backend.setProviderApiKey(providerId, apiKey, metadata)

    override suspend fun removeProviderAuth(providerId: String): Boolean = backend.removeProviderAuth(providerId)

    override suspend fun completeProviderOAuth(
        providerId: String,
        methodIndex: Int,
        code: String?,
    ): Boolean = backend.completeProviderOAuth(providerId, methodIndex, code)

    override suspend fun listProjects(directory: String?): List<OpenCodeProject> = backend.listProjects(directory)

    override suspend fun currentProject(directory: String?): OpenCodeProject = backend.currentProject(directory)

    override suspend fun pathInfo(directory: String?): OpenCodePathInfo = backend.pathInfo(directory)

    override suspend fun listFiles(
        directory: String,
        path: String,
    ): List<OpenCodeFileNode> = backend.listFiles(directory, path)

    override suspend fun readFile(
        directory: String,
        path: String,
    ): OpenCodeFileContent = backend.readFile(directory, path)

    override suspend fun fileStatus(directory: String): List<OpenCodeFileChange> = backend.fileStatus(directory)

    override suspend fun searchText(
        directory: String,
        pattern: String,
    ): List<OpenCodeSearchMatch> = backend.searchText(directory, pattern)

    override suspend fun findFiles(
        directory: String,
        query: String,
        includeDirectories: Boolean?,
        type: String?,
        limit: Int?,
    ): List<String> = backend.findFiles(directory, query, includeDirectories, type, limit)

    override suspend fun vcsInfo(directory: String): OpenCodeVcsInfo = backend.vcsInfo(directory)

    override suspend fun vcsStatus(directory: String): List<OpenCodeFileChange> = backend.vcsStatus(directory)

    override suspend fun vcsDiff(
        directory: String,
        mode: String,
        context: Int?,
    ): List<OpenCodeFileChange> = backend.vcsDiff(directory, mode, context)

    override suspend fun sessionDiff(
        sessionId: String,
        directory: String?,
        messageId: String?,
    ): List<OpenCodeFileChange> = backend.sessionDiff(sessionId, directory, messageId)

    override suspend fun sessionTodo(
        sessionId: String,
        directory: String?,
    ): List<OpenCodeTodo> = backend.sessionTodo(sessionId, directory)

    override suspend fun sendMessage(
        sessionId: String,
        request: PromptRequest,
    ) = backend.sendMessage(sessionId, request)

    override suspend fun abortSession(sessionId: String): Boolean = backend.abortSession(sessionId)

    override suspend fun renameSession(
        sessionId: String,
        title: String,
    ): OpenCodeSession = backend.renameSession(sessionId, title)

    override suspend fun deleteSession(sessionId: String): Boolean = backend.deleteSession(sessionId)

    override suspend fun respondToPermission(
        sessionId: String,
        permissionId: String,
        response: PermissionResponse,
        remember: Boolean,
    ): Boolean = backend.respondToPermission(sessionId, permissionId, response, remember)

    override suspend fun archiveSession(sessionId: String): OpenCodeSession = backend.archiveSession(sessionId)

    override suspend fun mcpServers(): List<com.konprostart.tariffiacode.core.api.McpServer> = backend.mcpServers()

    override suspend fun addMcpServer(body: kotlinx.serialization.json.JsonObject): com.konprostart.tariffiacode.core.api.McpServer =
        backend.addMcpServer(body)

    override suspend fun connectMcpServer(name: String): Boolean = backend.connectMcpServer(name)

    override suspend fun disconnectMcpServer(name: String): Boolean = backend.disconnectMcpServer(name)

    override suspend fun removeMcpAuth(name: String): com.konprostart.tariffiacode.core.api.McpAuthRemoval = backend.removeMcpAuth(name)

    override suspend fun mcpAuth(name: String): com.konprostart.tariffiacode.core.api.McpAuthStart = backend.mcpAuth(name)

    override suspend fun mcpAuthCallback(
        name: String,
        code: String,
    ): com.konprostart.tariffiacode.core.api.McpAuthStatus = backend.mcpAuthCallback(name, code)

    override suspend fun config(): kotlinx.serialization.json.JsonElement = backend.config()

    override suspend fun updateConfig(patch: kotlinx.serialization.json.JsonObject): kotlinx.serialization.json.JsonElement =
        backend.updateConfig(patch)

    override suspend fun configProviders(): List<com.konprostart.tariffiacode.core.api.ConfiguredProvider> = backend.configProviders()

    override suspend fun commands(): List<com.konprostart.tariffiacode.core.api.OpenCodeCommand> = backend.commands()

    override suspend fun skills(): List<com.konprostart.tariffiacode.core.api.OpenCodeSkill> = backend.skills()

    override suspend fun executeCommand(
        sessionId: String,
        command: String,
        arguments: String,
    ) = backend.executeCommand(sessionId, command, arguments)

    override suspend fun summarizeSession(
        sessionId: String,
        providerId: String,
        modelId: String,
    ): Boolean = backend.summarizeSession(sessionId, providerId, modelId)

    override suspend fun answerQuestion(
        requestId: String,
        answers: List<List<String>>,
        directory: String?,
    ): Boolean = backend.answerQuestion(requestId, answers, directory)

    override suspend fun rejectQuestion(
        requestId: String,
        directory: String?,
    ): Boolean = backend.rejectQuestion(requestId, directory)

    override suspend fun pendingQuestions(directory: String?): List<QuestionRequest> = backend.pendingQuestions(directory)

    override fun events(): Flow<OpenCodeEvent> = backend.events()

    fun destroy() {
        scope.cancel()
    }

    private fun mapStatus(status: LocalRuntimeStatus): RuntimeState =
        when (status) {
            LocalRuntimeStatus.NotInstalled -> RuntimeState.Unavailable(messages.notInstalled)
            is LocalRuntimeStatus.UnsupportedAbi -> RuntimeState.Unavailable(messages.unsupportedAbi(status.abi))
            is LocalRuntimeStatus.Installing -> RuntimeState.Connecting
            is LocalRuntimeStatus.Starting -> RuntimeState.Connecting
            is LocalRuntimeStatus.Updating -> RuntimeState.Connecting
            is LocalRuntimeStatus.Stopped -> RuntimeState.Disconnected
            is LocalRuntimeStatus.Broken -> RuntimeState.Failed(status.reason)
            is LocalRuntimeStatus.Ready -> RuntimeState.Connected(status.version)
        }

    private fun RuntimeState.describe(): String =
        when (this) {
            RuntimeState.Disconnected -> messages.runtimeStopped
            RuntimeState.Connecting -> messages.runtimeConnecting
            is RuntimeState.Connected -> "OpenCode $version"
            is RuntimeState.Unavailable -> reason
            is RuntimeState.Failed -> message
        }
}
