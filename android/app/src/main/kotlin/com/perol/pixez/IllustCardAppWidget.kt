package com.perol.pixez

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import com.perol.pixez.glance.GlanceDBManager
import com.perol.pixez.glance.GlanceIllust
import com.perol.pixez.kitkat.WidgetImageFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class IllustCardAppWidget : AppWidgetProvider() {

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
                updateWidget(context, appWidgetManager, appWidgetId, illust, host)
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
    appWidgetManager: AppWidgetManager,
    appWidgetId: Int,
    illust: GlanceIllust,
    host: String?
) {
    val url = illust.pictureUrl
    val iId = illust.illustId
    val views = RemoteViews(context.packageName, R.layout.illust_app_widget)
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
                Log.e("Card app widget", "url error: $trueUrl", e)
            }.getOrNull()
        }
        if (bitmap != null) {
            views.setImageViewBitmap(R.id.appwidget_image, bitmap)
            val intent = Intent(context, MainActivity::class.java).apply {
                putExtra("iid", iId)
            }
            val pendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.getActivity(
                    context,
                    url.hashCode() + views.hashCode(),
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            } else {
                PendingIntent.getActivity(
                    context,
                    url.hashCode() + views.hashCode(),
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT
                )
            }
            views.setTextViewText(R.id.appwidget_title, illust.title)
            views.setTextViewText(R.id.appwidget_subtitle, illust.userName)
            views.setOnClickPendingIntent(R.id.appwidget_normal_container, pendingIntent)
            views.setViewVisibility(R.id.appwidget_warning_container, View.GONE)
            views.setImageViewResource(R.id.appwidget_app_icon, R.mipmap.ic_launcher_foreground)
            views.setInt(R.id.appwidget_app_icon, "setColorFilter", Color.parseColor("#2196F3"))
            appWidgetManager.updateAppWidget(appWidgetId, views)
        } else {
            views.setViewVisibility(R.id.appwidget_warning_title, View.VISIBLE)
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
