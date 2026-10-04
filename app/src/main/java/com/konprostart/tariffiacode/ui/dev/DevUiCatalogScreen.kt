package com.konprostart.tariffiacode.ui.dev

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.konprostart.tariffiacode.feature.settings.AgentMetricRow
import com.konprostart.tariffiacode.feature.settings.SettingsDivider
import com.konprostart.tariffiacode.feature.settings.SettingsRow
import com.konprostart.tariffiacode.feature.settings.SettingsSection
import com.konprostart.tariffiacode.ui.components.SectionCard
import com.konprostart.tariffiacode.ui.components.StatusChip
import com.konprostart.tariffiacode.ui.theme.TariffiaCodeTheme

/**
 * Debug-only catalog that renders a few REAL TariffiaCode composables, so UI work can be reviewed
 * on-device without Android Studio. The screen is registered in the NavHost only when
 * [com.konprostart.tariffiacode.BuildConfig.DEBUG] is true, so it is unreachable in release builds.
 *
 * Deliberately small: it reuses existing theme/components rather than introducing any preview
 * framework, and renders representative pieces (a card with metrics, status chips, settings rows).
 * Text is hard-coded English on purpose: this is a developer-only screen and adding a translated
 * string resource would force every locale to carry a debug string (the i18n check enforces parity).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevUiCatalogScreen(onBack: () -> Unit) {
    TariffiaCodeTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("UI Catalog (debug)") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                SectionCard {
                    Text("SectionCard + AgentMetricRow + StatusChip", style = MaterialTheme.typography.titleMedium)
                    AgentMetricRow("Example metric", "42")
                    AgentMetricRow("Another metric", "enabled")
                    StatusChip(text = "active", active = true)
                    StatusChip(text = "inactive", active = false)
                }

                SettingsSection(title = "Settings rows") {
                    SettingsRow(icon = Icons.Default.Star, title = "Example row", onClick = {})
                    SettingsDivider()
                    SettingsRow(icon = Icons.Default.BugReport, title = "Row with value", value = "value", onClick = {})
                }
            }
        }
    }
}
