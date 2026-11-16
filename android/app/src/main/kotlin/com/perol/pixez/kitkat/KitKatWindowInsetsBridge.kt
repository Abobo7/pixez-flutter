package com.perol.pixez.kitkat

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowManager
import io.flutter.embedding.android.FlutterView
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.lang.ref.WeakReference

object KitKatWindowInsetsBridge {
    private const val UI_CHANNEL = "com.perol.dev/kitkat_ui"
    private const val SHARED_PREFS_NAME = "FlutterSharedPreferences"

    @Volatile
    private var darkStatusBarIconsOverride: Boolean? = null
    private var activeWindowRef: WeakReference<Window>? = null
    private var activeSyncCallback: (() -> Unit)? = null

    fun install(activity: Activity, flutterEngine: FlutterEngine?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP || flutterEngine == null) {
            return
        }
        val window = activity.window ?: return
        activeWindowRef = WeakReference(window)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, UI_CHANNEL)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "setStatusBarDarkIcons" -> {
                        val darkIcons = call.argument<Boolean>("darkIcons")
                        if (darkIcons != null && darkStatusBarIconsOverride != darkIcons) {
                            darkStatusBarIconsOverride = darkIcons
                            activeWindowRef?.get()?.let { applyTranslucentStatusBar(it) }
                        }
                        activeSyncCallback?.invoke()
                        result.success(null)
                    }

                    else -> result.notImplemented()
                }
            }

        applyTranslucentStatusBar(window)

        val decorView = window.decorView
        var flutterViewRef: FlutterView? = findFlutterView(decorView)

        val syncInsets = fun() {
            val statusBarHeight = getStatusBarHeight(activity)
            val fv = flutterViewRef ?: findFlutterView(decorView)?.also { flutterViewRef = it } ?: return
            val vm = fv.viewportMetrics

            val winFlags = window.attributes?.flags ?: 0
            @Suppress("DEPRECATION")
            val sysUiVis = decorView.systemUiVisibility
            @Suppress("DEPRECATION")
            val statusBarHidden =
                (winFlags and WindowManager.LayoutParams.FLAG_FULLSCREEN) != 0 ||
                    (sysUiVis and View.SYSTEM_UI_FLAG_FULLSCREEN) != 0

            val loc = IntArray(2)
            fv.getLocationOnScreen(loc)
            val visibleFrame = Rect()
            decorView.getWindowVisibleDisplayFrame(visibleFrame)

            val topPadding = when {
                statusBarHidden -> 0
                fv.height == 0 -> statusBarHeight
                else -> {
                    val effectiveStatusBottom = maxOf(visibleFrame.top, statusBarHeight)
                    maxOf(0, effectiveStatusBottom - maxOf(0, loc[1]))
                }
            }

            var bottomPadding = 0
            var bottomInset = 0
            if (fv.height > 0 && visibleFrame.bottom > 0) {
                val fvBottom = loc[1] + fv.height
                val bottomOverlap = maxOf(0, fvBottom - visibleFrame.bottom)
                val imeThreshold = (100f * activity.resources.displayMetrics.density).toInt()
                if (bottomOverlap > imeThreshold) {
                    bottomInset = bottomOverlap
                } else {
                    bottomPadding = bottomOverlap
                }
            }

            val changed = vm.viewPaddingTop != topPadding ||
                vm.viewPaddingBottom != bottomPadding ||
                vm.viewInsetBottom != bottomInset

            vm.viewPaddingTop = topPadding
            vm.viewPaddingBottom = bottomPadding
            vm.viewInsetBottom = bottomInset

            if (changed && vm.width > 0 && vm.height > 0 && vm.devicePixelRatio > 0f) {
                flutterEngine.renderer.setViewportMetrics(vm)
            }
        }

        activeSyncCallback = syncInsets
        // Pre-seed before FlutterView's initial onSizeChanged
        syncInsets()

        val layoutListener = ViewTreeObserver.OnGlobalLayoutListener {
            syncInsets()
        }
        decorView.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
        decorView.post { syncInsets() }
    }

    fun onResume() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) return
        activeWindowRef?.get()?.let { applyTranslucentStatusBar(it) }
        activeSyncCallback?.invoke()
    }

    @JvmStatic
    fun onSetStatusBarColor(window: Window, color: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) return
        if (color == Color.TRANSPARENT || Color.alpha(color) < 255) {
            applyTranslucentStatusBar(window)
        }
        activeSyncCallback?.invoke()
    }

    @Suppress("DEPRECATION")
    fun applyTranslucentStatusBar(window: Window) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT ||
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
        ) {
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
        val useDarkIcons = resolveDarkStatusBarIcons(window.context)
        applyMiuiStatusBarFlags(window, useDarkIcons)
    }

    private fun resolveDarkStatusBarIcons(context: Context): Boolean {
        darkStatusBarIconsOverride?.let { return it }
        try {
            val prefs = context.getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE)
            when (prefs.getLong("flutter.theme_mode", 0L).toInt()) {
                1 -> return true
                2 -> return false
            }
        } catch (_: Throwable) {
        }
        val nightMask = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return nightMask != Configuration.UI_MODE_NIGHT_YES
    }

    @SuppressLint("PrivateApi")
    private fun applyMiuiStatusBarFlags(window: Window, darkIcons: Boolean) {
        try {
            val layoutParamsClass = Class.forName("android.view.MiuiWindowManager\$LayoutParams")
            val transparentBit = layoutParamsClass
                .getField("EXTRA_FLAG_STATUS_BAR_TRANSPARENT")
                .getInt(layoutParamsClass)
            val darkModeBit = layoutParamsClass
                .getField("EXTRA_FLAG_STATUS_BAR_DARK_MODE")
                .getInt(layoutParamsClass)
            val setExtraFlags = window.javaClass.getMethod(
                "setExtraFlags",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            val mask = transparentBit or darkModeBit
            val value = if (darkIcons) (transparentBit or darkModeBit) else transparentBit
            setExtraFlags.invoke(window, value, mask)
        } catch (_: Throwable) {
        }
    }

    @SuppressLint("InternalInsetResource")
    private fun getStatusBarHeight(context: Context): Int {
        val resId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        if (resId > 0) {
            val px = context.resources.getDimensionPixelSize(resId)
            if (px > 0) return px
        }
        return (25f * context.resources.displayMetrics.density).toInt()
    }

    private fun findFlutterView(view: View?): FlutterView? {
        if (view == null) return null
        if (view is FlutterView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val found = findFlutterView(view.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }
}
