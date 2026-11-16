package com.perol.pixez.kitkat

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.SurfaceTexture
import android.os.Build
import android.os.Handler
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import java.io.File
import java.util.Collections
import java.util.Locale
import java.util.concurrent.Executor

object KitKatRuntimeHooks {

    @JvmStatic
    fun getDataDir(context: Context): File {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.dataDir
        } else {
            val path = context.applicationInfo?.dataDir
            if (!path.isNullOrEmpty()) File(path) else context.filesDir.parentFile ?: context.filesDir
        }
    }

    @JvmStatic
    fun getCodeCacheDir(context: Context): File {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            context.codeCacheDir
        } else {
            File(getDataDir(context), "code_cache").apply { if (!exists()) mkdirs() }
        }
    }

    @JvmStatic
    fun getNoBackupFilesDir(context: Context): File {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            context.noBackupFilesDir
        } else {
            File(getDataDir(context), "no_backup").apply { if (!exists()) mkdirs() }
        }
    }

    @JvmStatic
    fun getExternalMediaDirs(context: Context): Array<File?> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            context.externalMediaDirs
        } else {
            ContextCompat.getExternalFilesDirs(context, null)
        }
    }

    @JvmStatic
    @Suppress("DEPRECATION")
    fun getSupportedAbis(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Build.SUPPORTED_ABIS
        } else {
            val out = ArrayList<String>(2)
            if (!Build.CPU_ABI.isNullOrEmpty()) out.add(Build.CPU_ABI)
            if (!Build.CPU_ABI2.isNullOrEmpty() && Build.CPU_ABI2 != "unknown") out.add(Build.CPU_ABI2)
            if (out.isEmpty()) out.add("armeabi-v7a")
            out.toTypedArray()
        }
    }

    @JvmStatic
    fun getSplitSourceDirs(appInfo: ApplicationInfo): Array<String>? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) appInfo.splitSourceDirs else null
    }

    @JvmStatic
    fun getScript(locale: Locale): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            return locale.script ?: ""
        }
        val raw = locale.toString()
        return when {
            raw.contains("Hant", ignoreCase = true) -> "Hant"
            raw.contains("Hans", ignoreCase = true) -> "Hans"
            locale.language == "zh" && locale.country in setOf("TW", "HK", "MO") -> "Hant"
            locale.language == "zh" -> "Hans"
            else -> ""
        }
    }

    @JvmStatic
    fun toLanguageTag(locale: Locale): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            return locale.toLanguageTag()
        }
        return buildString {
            append(locale.language)
            val s = getScript(locale)
            if (s.isNotEmpty()) append('-').append(s)
            if (locale.country.isNotEmpty()) append('-').append(locale.country)
        }
    }

    @JvmStatic
    fun forLanguageTag(tag: String?): Locale {
        if (tag.isNullOrBlank()) return Locale.getDefault()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Locale.forLanguageTag(tag)
        } else {
            localeFromString(tag)
        }
    }

    @JvmStatic
    fun localeFromString(raw: String?): Locale {
        if (raw.isNullOrBlank()) return Locale.getDefault()
        val normalized = raw.replace('_', '-')
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            return Locale.forLanguageTag(normalized)
        }
        val tokens = normalized.split('-')
        val lang = tokens.getOrNull(0).orEmpty()
        var idx = 1
        var script = ""
        if (tokens.size > idx && tokens[idx].length == 4) {
            script = tokens[idx]
            idx++
        }
        var region = tokens.getOrNull(idx).orEmpty()
        if (region.isEmpty() && lang == "zh") {
            region = if (script.equals("Hant", ignoreCase = true)) "TW" else "CN"
        }
        return when {
            region.isNotEmpty() -> Locale(lang, region)
            lang.isNotEmpty() -> Locale(lang)
            else -> Locale.getDefault()
        }
    }

    @JvmStatic
    fun computePlatformResolvedLocale(items: Array<String>?): Array<String> {
        val sys = Locale.getDefault()
        val sysLang = sys.language.orEmpty().ifEmpty { "zh" }
        val sysRegion = sys.country.orEmpty()
        val sysScript = getScript(sys)
        if (items.isNullOrEmpty()) return arrayOf(sysLang, sysRegion, sysScript)
        var fallbackLang: Array<String>? = null
        var k = 0
        while (k + 2 < items.size) {
            val l = items[k] ?: ""
            val r = items[k + 1] ?: ""
            val s = items[k + 2] ?: ""
            if (l == sysLang && r == sysRegion && (s.isEmpty() || s == sysScript)) {
                return arrayOf(l, r, s)
            }
            if (l == sysLang && fallbackLang == null) {
                fallbackLang = arrayOf(l, r, s)
            }
            k += 3
        }
        return fallbackLang ?: arrayOf(
            items.getOrElse(0) { sysLang },
            items.getOrElse(1) { sysRegion },
            items.getOrElse(2) { sysScript }
        )
    }

    @JvmStatic
    @Suppress("DEPRECATION")
    fun getConfigurationLocales(config: Configuration): List<Locale> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val ll = config.locales
            return List(ll.size()) { ll.get(it) }
        }
        return Collections.singletonList(config.locale ?: Locale.getDefault())
    }

    @JvmStatic
    fun resolveNativeLocale(context: Context, candidates: List<Locale>?): Locale? {
        if (candidates.isNullOrEmpty()) return null
        val active = getConfigurationLocales(context.resources.configuration)
        for (u in active) {
            candidates.firstOrNull { it == u }?.let { return it }
            candidates.firstOrNull { it.language == u.language && it.country == u.country && u.country.isNotEmpty() }?.let { return it }
            candidates.firstOrNull { it.language == u.language }?.let { return it }
        }
        return candidates.first()
    }

    @JvmStatic
    fun requestUnbufferedDispatch(view: View, event: MotionEvent?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && event != null) {
            view.requestUnbufferedDispatch(event)
        }
    }

    @JvmStatic
    fun setStatusBarColor(window: Window, color: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.statusBarColor = color
        } else {
            KitKatWindowInsetsBridge.onSetStatusBarColor(window, color)
        }
    }

    @JvmStatic
    fun setNavigationBarColor(window: Window, color: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) window.navigationBarColor = color
    }

    @JvmStatic
    fun getStatusBarColor(window: Window): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) window.statusBarColor else 0
    }

    @JvmStatic
    fun getNavigationBarColor(window: Window): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) window.navigationBarColor else 0
    }

    @JvmStatic
    fun setOnFrameAvailableListener(
        st: SurfaceTexture,
        listener: SurfaceTexture.OnFrameAvailableListener?,
        handler: Handler?
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            st.setOnFrameAvailableListener(listener, handler)
        } else {
            st.setOnFrameAvailableListener(listener)
        }
    }

    @JvmStatic
    fun <T> getSystemService(context: Context, cls: Class<T>): T? {
        return ContextCompat.getSystemService(context, cls)
    }

    @JvmStatic
    fun checkSelfPermission(context: Context, permission: String): Int {
        return ContextCompat.checkSelfPermission(context, permission)
    }

    @JvmStatic
    fun getMainExecutor(context: Context): Executor {
        return ContextCompat.getMainExecutor(context)
    }

    @JvmStatic
    fun getColor(res: Resources, id: Int, theme: Resources.Theme?): Int {
        return ResourcesCompat.getColor(res, id, theme)
    }

    @JvmStatic
    fun getLayoutInDisplayCutoutMode(lp: WindowManager.LayoutParams): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) lp.layoutInDisplayCutoutMode else 0
    }

    @JvmStatic
    fun setAccessibilityPaneTitle(view: View, title: CharSequence?) {
        ViewCompat.setAccessibilityPaneTitle(view, title)
    }

    @JvmStatic
    fun newActionHolder(): Array<Any?> = arrayOfNulls(2)

    @JvmStatic
    fun initActionHolder(holder: Array<Any?>, id: Int, label: CharSequence?) {
        holder[0] = id
        holder[1] = label
    }

    @JvmStatic
    @Suppress("DEPRECATION")
    @SuppressLint("NewApi")
    fun addAccessibilityActionSafe(node: AccessibilityNodeInfo, actionOrHolder: Any?) {
        if (actionOrHolder is Array<*>) {
            val id = (actionOrHolder.getOrNull(0) as? Int) ?: return
            val label = actionOrHolder.getOrNull(1) as? CharSequence
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                node.addAction(AccessibilityNodeInfo.AccessibilityAction(id, label))
            } else {
                node.addAction(id)
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && actionOrHolder != null) {
            node.addAction(actionOrHolder as AccessibilityNodeInfo.AccessibilityAction)
        }
    }

    @JvmStatic
    fun setImportantForAccessibility(node: AccessibilityNodeInfo, important: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            node.isImportantForAccessibility = important
        }
    }

    @JvmStatic
    fun setTraversalAfter(node: AccessibilityNodeInfo, view: View?, id: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            node.setTraversalAfter(view, id)
        }
    }

    @JvmStatic
    fun setMaxTextLength(node: AccessibilityNodeInfo, max: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            node.maxTextLength = max
        }
    }

    @JvmStatic
    @SuppressLint("NewApi")
    fun updateCursorAnchorInfoSafe(imm: InputMethodManager, view: View, info: Any?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && info != null) {
            imm.updateCursorAnchorInfo(view, info as android.view.inputmethod.CursorAnchorInfo)
        }
    }

    @JvmStatic
    fun closeInputConnectionSafe(conn: android.view.inputmethod.BaseInputConnection) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            conn.closeConnection()
        } else {
            conn.finishComposingText()
        }
    }
}
