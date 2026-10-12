package com.konprostart.tariffiacode.ui

import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.konprostart.tariffiacode.R
import com.konprostart.tariffiacode.runtime.LocalAgent
import com.konprostart.tariffiacode.runtime.RuntimeTarget

/**
 * The name shown for a runtime everywhere it is listed.
 *
 * Android-local targets used to be labelled by their device ("this Android"), which made OpenCode
 * and Claude Code indistinguishable in every picker. Local targets are now named by their agent and
 * marked as local; remote connections keep the name the user gave them.
 */
@Composable
fun runtimeTargetLabel(target: RuntimeTarget): String {
    val agent = target.agent ?: return target.displayName
    return stringResource(R.string.local_agent_on_device, stringResource(agent.displayNameRes))
}

/**
 * Icon standing in for a runtime.
 *
 * Every local runtime used to share the Android robot, which made the agents indistinguishable at a
 * glance. Local agents now carry their own product mark; remote connections keep a generic one
 * because they can be any OpenCode server.
 */
@DrawableRes
fun runtimeAgentIcon(agent: LocalAgent?): Int = agent?.iconRes ?: R.drawable.ic_runtime_remote
