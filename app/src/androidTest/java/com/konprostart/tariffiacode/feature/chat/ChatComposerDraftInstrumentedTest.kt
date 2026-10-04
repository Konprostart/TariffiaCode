package com.konprostart.tariffiacode.feature.chat

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #355: the composer text must survive a configuration change. ChatHomeScreen holds its
 * composer state in `rememberSaveable`, so the in-progress message must still be there after the
 * activity is recreated (the same path a device rotation takes). A plain `remember` would lose it.
 */
@RunWith(AndroidJUnit4::class)
class ChatComposerDraftInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun composerTextSurvivesConfigurationChange() {
        val screenState = mutableStateOf(ChatUiState())

        composeRule.setContent {
            ChatHomeScreen(
                state = screenState.value,
                providers = emptyList(),
                agents = emptyList(),
                selectedProviderId = null,
                selectedModelId = null,
                selectedAgentId = null,
                runtimeTargets = emptyList(),
                selectedRuntimeId = null,
                onSelectRuntime = {},
                onSelectModel = { _, _ -> },
                onSelectAgent = {},
                onSelectQuestionAnswer = { _, _, _ -> },
                onSubmitQuestion = {},
                onSendMessage = {},
                onPermission = { _, _, _ -> },
                onAbort = {},
                onMic = {},
                onNewChat = {},
                onOpenLocalSetup = {},
                onOpenRemoteSetup = {},
                onOpenDrawer = {},
            )
        }

        val draft = "draft that must survive rotation"
        composeRule.onNodeWithTag("chat-message-input").performTextInput(draft)
        composeRule.onNodeWithTag("chat-message-input").assertTextContains(draft)

        // Recreate the hosting activity: this runs save/restore, exactly like a rotation.
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("chat-message-input").assertTextContains(draft)
    }
}
