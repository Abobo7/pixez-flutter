package com.perol.pixez

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import com.perol.pixez.glance.GlanceDBManager
import com.perol.pixez.kitkat.WidgetImageFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SquareAppWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            val sharedPreferences =
                context.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
            val glanceDBManager = GlanceDBManager()
            val illust = selectAppWidgetIllust(context, glanceDBManager)
            if (illust != null) {
                val host = sharedPreferences.getString("flutter.picture_source", null)
                updateWidget(context, illust.pictureUrl, appWidgetId, illust.illustId, host)
            } else {
                val views = RemoteViews(context.packageName, R.layout.card_app_widget)
                val manager = AppWidgetManager.getInstance(context)
                views.setViewVisibility(R.id.appwidget_warning_title, View.VISIBLE)
                manager.updateAppWidget(appWidgetId, views)
            }
        }
    }

    override fun onEnabled(context: Context) {}

    override fun onDisabled(context: Context) {}
}

@SuppressLint("UnspecifiedImmutableFlag")
private fun updateWidget(
    context: Context,
    url: String,
    appWidgetId: Int,
    iId: Long?,
    host: String?
) {
    val views = RemoteViews(context.packageName, R.layout.card_app_widget)
    val manager = AppWidgetManager.getInstance(context)
    val trueUrl = if (host != null) url.replace("i.pximg.net", host) else url
    val scale = context.resources.displayMetrics.density
    val radius = 16.0f * scale + 0.5f

    MainScope().launch {
        val bitmap = withContext(Dispatchers.IO) {
            runCatching {
                WidgetImageFetcher.fetchRoundedBitmap(
                    url = trueUrl,
                    hostHeader = Uri.parse(trueUrl).host,
                    targetSizePx = 540,
                    cornerRadiusPx = radius
                )
            }.onFailure { e ->
                io.flutter.Log.e("Card app widget", "url error: $trueUrl", e)
            }.getOrNull()
        }
        if (bitmap != null) {
            try {
                views.setImageViewBitmap(R.id.appwidget_image, bitmap)
                val intent = Intent(context, MainActivity::class.java).apply {
                    putExtra("iid", iId)
                }
                val pendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    PendingIntent.getActivity(
                        context,
                        url.hashCode(),
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                } else {
                    PendingIntent.getActivity(
                        context,
                        url.hashCode(),
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT
                    )
                }
                views.setOnClickPendingIntent(R.id.appwidget_image, pendingIntent)
                views.setViewVisibility(R.id.appwidget_warning_container, View.GONE)
                manager.updateAppWidget(appWidgetId, views)
            } catch (throwable: Throwable) {
                io.flutter.Log.d("Card app widget", throwable.toString())
            }
        } else {
            views.setViewVisibility(R.id.appwidget_warning_title, View.VISIBLE)
            manager.updateAppWidget(appWidgetId, views)
        }
    }
}
