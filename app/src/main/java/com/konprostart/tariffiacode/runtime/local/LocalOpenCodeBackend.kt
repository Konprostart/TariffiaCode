package com.konprostart.tariffiacode.runtime.local

import com.konprostart.tariffiacode.core.api.ConfiguredProvider
import com.konprostart.tariffiacode.core.api.McpAuthRemoval
import com.konprostart.tariffiacode.core.api.McpAuthStart
import com.konprostart.tariffiacode.core.api.McpAuthStatus
import com.konprostart.tariffiacode.core.api.McpServer
import com.konprostart.tariffiacode.core.api.OpenCodeAgent
import com.konprostart.tariffiacode.core.api.OpenCodeCommand
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
import com.konprostart.tariffiacode.core.api.OpenCodeSkill
import com.konprostart.tariffiacode.core.api.OpenCodeTodo
import com.konprostart.tariffiacode.core.api.OpenCodeVcsInfo
import com.konprostart.tariffiacode.core.api.PromptRequest
import com.konprostart.tariffiacode.core.api.ProviderAuthAuthorization
import com.konprostart.tariffiacode.core.api.ProviderAuthMethod
import com.konprostart.tariffiacode.core.api.ProviderCatalog
import com.konprostart.tariffiacode.core.api.QuestionRequest
import com.konprostart.tariffiacode.data.connection.ConnectionProfile
import com.konprostart.tariffiacode.runtime.BackendKind
import com.konprostart.tariffiacode.runtime.OpenCodeBackend
import com.konprostart.tariffiacode.runtime.PermissionResponse
import com.konprostart.tariffiacode.runtime.remote.RemoteOpenCodeBackend
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

class LocalOpenCodeBackend(
    private val portProvider: () -> Int?,
    private val backendFactory: (ConnectionProfile) -> RemoteOpenCodeBackend = { profile ->
        RemoteOpenCodeBackend(profile)
    },
    /**
     * The Basic-auth password the local server was started with (see [LocalRuntimeServerSecret]).
     * Null means no credentials are sent, which is only correct for a server started before loopback
     * auth existed.
     */
    private val passwordProvider: () -> String? = { null },
) : OpenCodeBackend {
    constructor(runtimeManager: LocalRuntimeManager) : this(
        portProvider = runtimeManager::installedPort,
        passwordProvider = runtimeManager::serverPassword,
    )

    override val id: String = "local-android"
    override val displayName: String = "Android local"
    override val kind: BackendKind = BackendKind.LOCAL

    @Volatile
    private var cached: CachedDelegate? = null
    private val lock = Any()

    internal fun delegate(): RemoteOpenCodeBackend {
        val port =
            portProvider()
                ?: error("Android local OpenCode runtime is not installed")
        val password = passwordProvider()?.takeIf { it.isNotBlank() }
        cached?.takeIf { it.port == port && it.password == password }?.let { return it.backend }

        synchronized(lock) {
            cached?.takeIf { it.port == port && it.password == password }?.let { return it.backend }
            val backend =
                backendFactory(
                    ConnectionProfile(
                        id = id,
                        name = displayName,
                        baseUrl = "http://127.0.0.1:$port/",
                        username = LocalRuntimeServerSecret.USERNAME,
                        password = password,
                        allowInsecureLan = true,
                    ),
                )
            cached = CachedDelegate(port, password, backend)
            return backend
        }
    }

    fun invalidate() {
        cached = null
    }

    override suspend fun health(): OpenCodeHealth = delegate().health()

    override suspend fun listSessions(directory: String?): List<OpenCodeSession> = delegate().listSessions(directory)

    override suspend fun session(sessionId: String): OpenCodeSession = delegate().session(sessionId)

    override suspend fun createSession(
        title: String?,
        directory: String?,
    ): OpenCodeSession = delegate().createSession(title, directory)

    override suspend fun listMessages(sessionId: String): List<OpenCodeMessage> = delegate().listMessages(sessionId)

    override suspend fun deleteMessage(
        sessionId: String,
        messageId: String,
    ): Boolean = delegate().deleteMessage(sessionId, messageId)

    override suspend fun listProviders(): ProviderCatalog = delegate().listProviders()

    override suspend fun listAgents(): List<OpenCodeAgent> = delegate().listAgents()

    override suspend fun providerAuthMethods(): Map<String, List<ProviderAuthMethod>> = delegate().providerAuthMethods()

    override suspend fun authorizeProvider(
        providerId: String,
        methodIndex: Int,
        inputs: Map<String, String>,
    ): ProviderAuthAuthorization = delegate().authorizeProvider(providerId, methodIndex, inputs)

    override suspend fun setProviderApiKey(
        providerId: String,
        apiKey: String,
        metadata: Map<String, String>,
    ): Boolean = delegate().setProviderApiKey(providerId, apiKey, metadata)

    override suspend fun removeProviderAuth(providerId: String): Boolean = delegate().removeProviderAuth(providerId)

    override suspend fun completeProviderOAuth(
        providerId: String,
        methodIndex: Int,
        code: String?,
    ): Boolean = delegate().completeProviderOAuth(providerId, methodIndex, code)

    override suspend fun listProjects(directory: String?): List<OpenCodeProject> = delegate().listProjects(directory)

    override suspend fun currentProject(directory: String?): OpenCodeProject = delegate().currentProject(directory)

    override suspend fun pathInfo(directory: String?): OpenCodePathInfo = delegate().pathInfo(directory)

    override suspend fun listFiles(
        directory: String,
        path: String,
    ): List<OpenCodeFileNode> = delegate().listFiles(directory, path)

    override suspend fun readFile(
        directory: String,
        path: String,
    ): OpenCodeFileContent = delegate().readFile(directory, path)

    override suspend fun fileStatus(directory: String): List<OpenCodeFileChange> = delegate().fileStatus(directory)

    override suspend fun searchText(
        directory: String,
        pattern: String,
    ): List<OpenCodeSearchMatch> = delegate().searchText(directory, pattern)

    override suspend fun findFiles(
        directory: String,
        query: String,
        includeDirectories: Boolean?,
        type: String?,
        limit: Int?,
    ): List<String> = delegate().findFiles(directory, query, includeDirectories, type, limit)

    override suspend fun vcsInfo(directory: String): OpenCodeVcsInfo = delegate().vcsInfo(directory)

    override suspend fun vcsStatus(directory: String): List<OpenCodeFileChange> = delegate().vcsStatus(directory)

    override suspend fun vcsDiff(
        directory: String,
        mode: String,
        context: Int?,
    ): List<OpenCodeFileChange> = delegate().vcsDiff(directory, mode, context)

    override suspend fun sessionDiff(
        sessionId: String,
        directory: String?,
        messageId: String?,
    ): List<OpenCodeFileChange> = delegate().sessionDiff(sessionId, directory, messageId)

    override suspend fun sessionTodo(
        sessionId: String,
        directory: String?,
    ): List<OpenCodeTodo> = delegate().sessionTodo(sessionId, directory)

    override suspend fun sendMessage(
        sessionId: String,
        request: PromptRequest,
    ) = delegate().sendMessage(sessionId, request)

    override suspend fun summarizeSession(
        sessionId: String,
        providerId: String,
        modelId: String,
    ): Boolean = delegate().summarizeSession(sessionId, providerId, modelId)

    override suspend fun abortSession(sessionId: String): Boolean = delegate().abortSession(sessionId)

    override suspend fun renameSession(
        sessionId: String,
        title: String,
    ): OpenCodeSession = delegate().renameSession(sessionId, title)

    override suspend fun deleteSession(sessionId: String): Boolean = delegate().deleteSession(sessionId)

    override suspend fun respondToPermission(
        sessionId: String,
        permissionId: String,
        response: PermissionResponse,
        remember: Boolean,
    ): Boolean = delegate().respondToPermission(sessionId, permissionId, response, remember)

    override suspend fun answerQuestion(
        requestId: String,
        answers: List<List<String>>,
        directory: String?,
    ): Boolean = delegate().answerQuestion(requestId, answers, directory)

    override suspend fun rejectQuestion(
        requestId: String,
        directory: String?,
    ): Boolean = delegate().rejectQuestion(requestId, directory)

    override suspend fun pendingQuestions(directory: String?): List<QuestionRequest> = delegate().pendingQuestions(directory)

    override suspend fun archiveSession(sessionId: String): OpenCodeSession = delegate().archiveSession(sessionId)

    override suspend fun mcpServers(): List<McpServer> = delegate().mcpServers()

    override suspend fun addMcpServer(body: JsonObject): McpServer = delegate().addMcpServer(body)

    override suspend fun connectMcpServer(name: String): Boolean = delegate().connectMcpServer(name)

    override suspend fun disconnectMcpServer(name: String): Boolean = delegate().disconnectMcpServer(name)

    override suspend fun removeMcpAuth(name: String): McpAuthRemoval = delegate().removeMcpAuth(name)

    override suspend fun mcpAuth(name: String): McpAuthStart = delegate().mcpAuth(name)

    override suspend fun mcpAuthCallback(
        name: String,
        code: String,
    ): McpAuthStatus = delegate().mcpAuthCallback(name, code)

    override suspend fun config(): JsonElement = delegate().config()

    override suspend fun updateConfig(patch: JsonObject): JsonElement = delegate().updateConfig(patch)

    override suspend fun configProviders(): List<ConfiguredProvider> = delegate().configProviders()

    override suspend fun commands(): List<OpenCodeCommand> = delegate().commands()

    override suspend fun skills(): List<OpenCodeSkill> = delegate().skills()

    override suspend fun executeCommand(
        sessionId: String,
        command: String,
        arguments: String,
    ) = delegate().executeCommand(sessionId, command, arguments)

    override fun events(): Flow<OpenCodeEvent> = delegate().events()

    private data class CachedDelegate(
        val port: Int,
        val password: String?,
        val backend: RemoteOpenCodeBackend,
    )
}
