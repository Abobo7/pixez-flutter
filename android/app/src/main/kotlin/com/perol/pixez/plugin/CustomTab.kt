package com.perol.pixez.plugin

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.browser.customtabs.CustomTabsIntent
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

object CustomTab {
    private const val CUSTOM_TAB_CHANNEL = "com.perol.dev/custom_tab"

    private val KITKAT_MODERN_BROWSERS = listOf(
        "com.mipad.geckobrowser",
        "org.mozilla.fenix",
        "io.github.forkmaintainers.iceraven",
        "org.mozilla.firefox",
        "org.mozilla.fennec_aurora",
        "com.kiwibrowser.browser.classic",
        "com.kiwibrowser.browser",
        "org.cromite.cromite",
        "mark.via",
        "mark.via.gp",
        "com.xbrowser.play",
        "com.UCMobile",
        "com.UCMobile.intl",
        "com.android.chrome"
    )

    fun bindChannel(context: Context, flutterEngine: FlutterEngine) {
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CUSTOM_TAB_CHANNEL)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "launch" -> {
                        val url = call.argument<String>("url")
                        url?.let { context.launchUrl(it) }
                    }
                }
                result.success(null)
            }
    }

    private fun Context.launchUrl(url: String) {
        val uri = Uri.parse(url)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            val pm = packageManager
            for (pkg in KITKAT_MODERN_BROWSERS) {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                        setPackage(pkg)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    if (intent.resolveActivity(pm) != null) {
                        startActivity(intent)
                        return
                    }
                } catch (_: Throwable) {
                }
            }
            // Dynamic scan for any other installed Gecko/Firefox/Chromium fork before system WebView browser
            try {
                val baseIntent = Intent(Intent.ACTION_VIEW, uri)
                val candidates = pm.queryIntentActivities(baseIntent, 0)
                val preferred = candidates.firstOrNull { info ->
                    val pkg = info.activityInfo?.packageName?.lowercase() ?: ""
                    pkg.contains("gecko") || pkg.contains("firefox") || pkg.contains("kiwi") || pkg.contains("via")
                }
                if (preferred?.activityInfo?.packageName != null) {
                    val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                        setPackage(preferred.activityInfo.packageName)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                    return
                }
            } catch (_: Throwable) {
            }
            val fallback = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(fallback)
            return
        }
        try {
            CustomTabsIntent.Builder().build().launchUrl(this, uri)
        } catch (_: Throwable) {
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        }
    }
}
