package com.konprostart.tariffiacode.di

import android.os.Build
import com.konprostart.tariffiacode.TariffiaCodeApplication
import com.konprostart.tariffiacode.core.api.GitHubApiClient
import com.konprostart.tariffiacode.core.notification.RuntimeNotificationHelper
import com.konprostart.tariffiacode.data.connection.SecureSettingsRepository
import com.konprostart.tariffiacode.data.repository.AndroidRuntimeActivityMessages
import com.konprostart.tariffiacode.data.repository.AndroidRuntimeCatalogMessages
import com.konprostart.tariffiacode.data.repository.PullRequestStatusRepository
import com.konprostart.tariffiacode.data.repository.RuntimeActivityRepository
import com.konprostart.tariffiacode.data.repository.RuntimeCatalogRepository
import com.konprostart.tariffiacode.data.settings.AppPreferencesRepository
import com.konprostart.tariffiacode.data.settings.DraftRepository
import com.konprostart.tariffiacode.feature.wakeword.VoskModelStore
import com.konprostart.tariffiacode.runtime.RuntimeRegistry
import com.konprostart.tariffiacode.runtime.local.AndroidLocalRuntimeMessages
import com.konprostart.tariffiacode.runtime.local.AntigravityRuntime
import com.konprostart.tariffiacode.runtime.local.AntigravityTarget
import com.konprostart.tariffiacode.runtime.local.CustomProviderStore
import com.konprostart.tariffiacode.runtime.local.DefaultLocalRuntimeUpdateEngine
import com.konprostart.tariffiacode.runtime.local.GitCredentialHelper
import com.konprostart.tariffiacode.runtime.local.LocalProviderCredentialStore
import com.konprostart.tariffiacode.runtime.local.LocalRuntimeAccessCoordinator
import com.konprostart.tariffiacode.runtime.local.LocalRuntimeCommandRunner
import com.konprostart.tariffiacode.runtime.local.LocalRuntimeInstaller
import com.konprostart.tariffiacode.runtime.local.LocalRuntimeManager
import com.konprostart.tariffiacode.runtime.local.LocalRuntimeMessages
import com.konprostart.tariffiacode.runtime.local.LocalRuntimeProcessLauncher
import com.konprostart.tariffiacode.runtime.local.LocalRuntimeReleaseClient
import com.konprostart.tariffiacode.runtime.local.LocalRuntimeServiceController
import com.konprostart.tariffiacode.runtime.local.LocalRuntimeTarget
import com.konprostart.tariffiacode.runtime.local.LocalRuntimeUpdater
import com.konprostart.tariffiacode.runtime.local.VerifiedRuntimeDownloader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
import java.io.File

val appModule =
    module {

        single<File> { File(androidContext().filesDir, "runtime") }

        single { CoroutineScope(SupervisorJob() + Dispatchers.IO) }

        single { SecureSettingsRepository(androidContext()) }

        single { AppPreferencesRepository(get()) }

        single { DraftRepository(androidContext()) }

        single { RuntimeNotificationHelper(androidContext()) }

        single { AndroidRuntimeActivityMessages(androidContext()) }

        single { AndroidRuntimeCatalogMessages(androidContext()) }

        single { LocalProviderCredentialStore(get()) }

        single { CustomProviderStore(get()) }

        single { VoskModelStore(androidContext(), get(), get()) }

        single { OkHttpClient() }

        single { LocalRuntimeAccessCoordinator() }

        single<LocalRuntimeMessages> { AndroidLocalRuntimeMessages(androidContext()) }

        single {
            val runtimeDirectory: File = get()
            val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
            LocalRuntimeInstaller(
                context = androidContext(),
                runtimeDirectory = runtimeDirectory,
                abi = abi,
                accessCoordinator = get(),
            )
        }

        single {
            val settings: SecureSettingsRepository = get()
            val providerCredentials: LocalProviderCredentialStore = get()
            val customProviders: CustomProviderStore = get()
            val runtimeDirectory: File = get()
            LocalRuntimeProcessLauncher(
                runtimeDirectory = runtimeDirectory,
                portProbe = LocalRuntimeManager::defaultPortProbe,
                githubToken = { settings.githubToken },
                beforeStart = { installed ->
                    runCatching { providerCredentials.syncToRuntime(installed.rootfs) }
                    runCatching { customProviders.syncToRuntime(installed.rootfs) }
                    runCatching {
                        GitCredentialHelper(installed.rootfs) { settings.githubToken }.let { helper ->
                            if (settings.githubToken.isNullOrBlank()) helper.remove() else helper.install()
                        }
                    }
                },
            )
        }

        single {
            val runtimeDirectory: File = get()
            val installer: LocalRuntimeInstaller = get()
            LocalRuntimeCommandRunner(
                runtimeDirectory = runtimeDirectory,
                installedRuntimeProvider = installer::installedRuntime,
                accessCoordinator = get(),
                messages = get(),
            )
        }

        single {
            val runtimeDirectory: File = get()
            val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
            val httpClient: OkHttpClient = get()
            val commandRunner: LocalRuntimeCommandRunner = get()
            val verifiedDownloader = VerifiedRuntimeDownloader(httpClient)
            val updater =
                LocalRuntimeUpdater(
                    runtimeDirectory = runtimeDirectory,
                    abi = abi,
                    downloadAsset = { asset, destination, progress ->
                        verifiedDownloader.download(
                            url = asset.url,
                            destination = destination,
                            expectedSha256 = asset.sha256,
                            expectedSizeBytes = asset.sizeBytes,
                            onProgress = progress,
                        )
                    },
                    candidateVersionProvider = { candidate ->
                        val result =
                            commandRunner.runShell(
                                commandText = "/usr/local/bin/${candidate.name} --version",
                                timeoutSeconds = 30L,
                            )
                        require(result.exitCode == 0) {
                            "OpenCode update candidate validation failed: ${result.output}"
                        }
                        result.output.lineSequence().firstOrNull(String::isNotBlank)
                            ?: error("OpenCode update candidate returned no version")
                    },
                    accessCoordinator = get(),
                    messages = get(),
                )
            val updateEngine =
                DefaultLocalRuntimeUpdateEngine(
                    releaseClient = LocalRuntimeReleaseClient(httpClient),
                    updater = updater,
                )
            LocalRuntimeManager(
                runtimeDirectory = runtimeDirectory,
                abi = abi,
                installer = get(),
                processLauncher = get(),
                updateEngine = updateEngine,
                messages = get(),
                reconcilePersistedPort = { get<LocalRuntimeInstaller>().reconcilePersistedPortWithManifest() },
            )
        }

        single { LocalRuntimeServiceController(androidContext()) }

        single {
            RuntimeRegistry(
                store = get(),
                localTarget = LocalRuntimeTarget(get(), messages = get()),
                additionalTargets =
                    listOf(
                        AntigravityTarget(
                            AntigravityRuntime(get(), (get<LocalRuntimeInstaller>())::installedRuntime),
                        ),
                        // The application's own instance, not a second CodexTarget: Codex runs one
                        // long-lived app-server whose approvals and threads a second runtime would
                        // not see. Resolved lazily, after TariffiaCodeApplication.onCreate has built it.
                        (androidContext().applicationContext as TariffiaCodeApplication).codexTarget,
                    ),
            )
        }

        single {
            val settings: SecureSettingsRepository = get()
            GitHubApiClient(token = { settings.githubToken }, client = get())
        }

        single {
            PullRequestStatusRepository(api = get(), scope = get())
        }

        single {
            RuntimeCatalogRepository(get(), get(), messages = get<AndroidRuntimeCatalogMessages>())
        }

        single {
            val notifications: RuntimeNotificationHelper = get()
            RuntimeActivityRepository(
                registry = get(),
                scope = get(),
                onPermissionAsked = { request, title, runtimeId ->
                    notifications.notifyPermission(request, title, runtimeId)
                },
                onSessionIdle = { sessionId, title, runtimeId ->
                    notifications.notifySessionComplete(sessionId, title, runtimeId)
                },
                onSessionError = { sessionId, message, runtimeId ->
                    notifications.notifySessionError(sessionId, message, runtimeId)
                },
                onQuestionAsked = { request, title, runtimeId ->
                    notifications.notifyQuestion(request, title, runtimeId)
                },
                messages = get<AndroidRuntimeActivityMessages>(),
            )
        }
    }
