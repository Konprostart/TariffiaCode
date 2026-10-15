package com.konprostart.tariffiacode.feature.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.konprostart.tariffiacode.R

class QuickInputWidgetProvider : AppWidgetProvider() {
    /**
     * Both pending intents are fully specified - component, action, and no fill-in extras - so nothing
     * needs to be written into them by whoever fires them.
     *
     * They were built with FLAG_MUTABLE, which was the only such flag in the app and bought nothing:
     * a mutable PendingIntent whose holder can replace its extras lets whoever holds it inject text
     * that QuickInputActivity forwards straight into a live agent session as if the user had typed
     * it. The intent here already carries everything the activity needs, so it is immutable.
     */
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        for (appWidgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId)
        }
    }

    companion object {
        const val ACTION_SEND = "com.konprostart.tariffiacode.widget.SEND"
        const val ACTION_MIC = "com.konprostart.tariffiacode.widget.MIC"
        const val EXTRA_TEXT = "widget_text"

        fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_quick_input)

            val sendIntent =
                Intent(context, QuickInputActivity::class.java).apply {
                    action = ACTION_SEND
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
            val sendPendingIntent =
                PendingIntent.getActivity(
                    context,
                    appWidgetId,
                    sendIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )

            val micIntent =
                Intent(context, QuickInputActivity::class.java).apply {
                    action = ACTION_MIC
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
            val micPendingIntent =
                PendingIntent.getActivity(
                    context,
                    appWidgetId + 1000,
                    micIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )

            views.setOnClickPendingIntent(R.id.widget_send_button, sendPendingIntent)
            views.setOnClickPendingIntent(R.id.widget_mic_button, micPendingIntent)
            views.setOnClickPendingIntent(R.id.widget_input, sendPendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
