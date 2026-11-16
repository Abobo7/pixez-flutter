package com.perol.pixez

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.multidex.MultiDex
import com.perol.pixez.kitkat.KitKatTlsHelper

class PixEz : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            MultiDex.install(this)
        }
        try {
            System.loadLibrary("jnicompat19")
        } catch (_: Throwable) {
        }
    }

    override fun onCreate() {
        super.onCreate()
        KitKatTlsHelper.install()
        CrashHandler.instance.init(applicationContext)
    }
}
