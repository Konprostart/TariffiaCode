package com.konprostart.tariffiacode.di

import android.os.Build
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
import com.konprostart.tariffiacode.runtime.local.AndroidLocalRuntimeMessages
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
import com.konprostart.tariffiacode.runtime.local.LocalRuntimeUpdater
import com.konprostart.tariffiacode.runtime.local.RuntimeCredentialPolicy
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
                githubToken = { RuntimeCredentialPolicy.runtimeGitHubToken(settings.githubToken, settings.shareGitHubTokenWithRuntime) },
                beforeStart = { installed ->
                    runCatching { providerCredentials.syncToRuntime(installed.rootfs) }
                    runCatching { customProviders.syncToRuntime(installed.rootfs) }
                    runCatching {
                        val runtimeToken =
                            RuntimeCredentialPolicy.runtimeGitHubToken(
                                settings.githubToken,
                                settings.shareGitHubTokenWithRuntime,
                            )
                        GitCredentialHelper(installed.rootfs) { runtimeToken }.let { helper ->
                            if (runtimeToken.isNullOrBlank()) helper.remove() else helper.install()
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

        // RuntimeRegistry is deliberately NOT registered here. The application builds the only one
        // that exists, in TariffiaCodeApplication.onCreate, because its targets need the agents and
        // the VPS connection that are constructed there and cannot be resolved from this module.
        //
        // There used to be a second definition here, holding a different and smaller set of targets
        // than the one actually used - no Claude Code, no VPS - and nothing ever resolved it. That
        // is worse than dead code: it reads as the place to register an agent, so adding one here
        // would have changed nothing at all, silently. If you need the registry, take
        // TariffiaCodeApplication.runtimeRegistry, which is what the UI and every view model use.

        // Five definitions below still ask Koin for a RuntimeRegistry through an untyped `get()` -
        // the two repositories and three view models. Nothing resolves them (the UI hand-builds
        // those objects from the application's own instances), and before this definition was
        // removed they would have quietly produced a registry missing Claude Code and the VPS
        // target. They now throw instead, which is the safer failure but still means any future
        // `by viewModel()` on one of them would break at runtime rather than at build time. They
        // are dead and are the next thing to remove.

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
