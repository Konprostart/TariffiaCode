package com.konprostart.tariffiacode.di

import com.konprostart.tariffiacode.TariffiaCodeApplication
import com.konprostart.tariffiacode.feature.activity.ActivityViewModel
import com.konprostart.tariffiacode.feature.chat.ChatViewModel
import com.konprostart.tariffiacode.feature.settings.SettingsViewModel
import com.konprostart.tariffiacode.feature.workspace.WorkspaceViewModel
import kotlinx.coroutines.flow.first
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import java.io.File

val viewModelModule =
    module {

        // Every definition below asks Koin for a RuntimeRegistry through an untyped `get()`. The
        // registry is not a Koin bean - the application builds the only one, in onCreate - so
        // resolving any of these throws rather than quietly returning a registry with the wrong
        // targets. None is resolved today: the UI constructs these view models explicitly from the
        // application's own instances. Remove this module rather than adding a bean to make it work.

        viewModel {
            val app = androidContext().applicationContext as TariffiaCodeApplication
            ChatViewModel(
                draftRepo = get(),
                pullRequestStatuses = get(),
                // Kept in step with the hand-rolled factory in ui/TariffiaCodeApp.kt: both construction
                // paths have to park the connection probe and stall watchdog while the app is
                // backgrounded, or whichever one the screen happens to use decides whether those
                // 30-second loops keep polling out of sight.
                awaitForeground = { app.appForeground.foreground.first { visible -> visible } },
                videoNotSupportedMessage = androidContext().getString(com.konprostart.tariffiacode.R.string.error_video_not_supported),
            )
        }

        viewModel {
            WorkspaceViewModel(
                registry = get(),
                catalog = get(),
                localRuntimeManager = get(),
                localRuntimeController = get(),
                settings = get(),
                workspaceHostDir = File(androidContext().filesDir, "runtime/workspace"),
                incompleteConnectionMessage = androidContext().getString(com.konprostart.tariffiacode.R.string.connection_info_incomplete),
            )
        }

        viewModel {
            SettingsViewModel(
                catalog = get(),
                preferences = get(),
                credentials = get(),
                customProviders = get(),
                settings = get(),
                registry = get(),
                voskModels = get(),
                providerDisconnectRejectedMessage =
                    androidContext().getString(com.konprostart.tariffiacode.R.string.provider_disconnect_rejected),
                customProviderInvalidMessage =
                    androidContext().getString(com.konprostart.tariffiacode.R.string.provider_custom_invalid),
            )
        }

        viewModel {
            ActivityViewModel(
                catalog = get(),
                activity = get(),
            )
        }
    }
