package com.konprostart.tariffiacode.startup

import android.content.Context
import androidx.startup.Initializer
import com.konprostart.tariffiacode.AndCodeApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class CatalogReconcileInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        val app = context.applicationContext as AndCodeApplication
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            app.catalogRepository.state.collectLatest { catalog ->
                if (catalog.health != null) {
                    app.preferences.reconcile(catalog.providers, catalog.agents)
                }
            }
        }
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
