package com.konprostart.tariffiacode.feature.assistant

import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

object AssistantStatus {
    fun isActive(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(RoleManager::class.java)
            if (roleManager?.isRoleAvailable(RoleManager.ROLE_ASSISTANT) == true) {
                return roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)
            }
        }

        val configuredService =
            Settings.Secure.getString(
                context.contentResolver,
                VOICE_INTERACTION_SERVICE_SETTING,
            )
        val expected = ComponentName(context, TariffiaCodeVoiceInteractionService::class.java)
        return isConfiguredService(configuredService, expected)
    }

    /**
     * Intent that asks the OS to make TariffiaCode the default digital assistant, or null when the
     * platform has no assistant role (Android 10+) or the role is unavailable on this device.
     *
     * Requesting the role is what routes the system's assistant/AI button to TariffiaCode instead
     * of whichever assistant app currently holds it (for example, the ChatGPT app). The platform
     * shows its own confirmation dialog, so nothing changes until the user accepts it.
     */
    fun assistantRoleRequestIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val roleManager = context.getSystemService(RoleManager::class.java) ?: return null
        val roleAvailable = roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT)
        if (!canRequestAssistantRole(Build.VERSION.SDK_INT, roleAvailable)) return null
        return roleManager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)
    }

    internal fun canRequestAssistantRole(
        sdkInt: Int,
        roleAvailable: Boolean,
    ): Boolean = sdkInt >= ASSISTANT_ROLE_API && roleAvailable

    internal fun isConfiguredService(
        configuredService: String?,
        expected: ComponentName,
    ): Boolean =
        matchesConfiguredService(
            configuredService,
            expected.flattenToString(),
            expected.flattenToShortString(),
        )

    internal fun matchesConfiguredService(
        configuredService: String?,
        flattenedName: String,
        shortName: String,
    ): Boolean = configuredService == flattenedName || configuredService == shortName

    private const val VOICE_INTERACTION_SERVICE_SETTING = "voice_interaction_service"

    // Build.VERSION_CODES.Q: the release that introduced RoleManager.ROLE_ASSISTANT.
    private const val ASSISTANT_ROLE_API = 29
}
