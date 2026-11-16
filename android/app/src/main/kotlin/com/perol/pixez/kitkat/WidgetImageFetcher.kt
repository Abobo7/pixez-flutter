package com.perol.pixez.kitkat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import com.perol.pixez.kitkat.KitKatTlsHelper
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object WidgetImageFetcher {

    private val client: OkHttpClient by lazy {
        KitKatTlsHelper.createOkHttpClientBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    fun fetchRoundedBitmap(
        url: String,
        hostHeader: String?,
        targetSizePx: Int,
        cornerRadiusPx: Float
    ): Bitmap? {
        val reqBuilder = Request.Builder()
            .url(url)
            .header("Referer", "https://app-api.pixiv.net/")
            .header("User-Agent", "PixivIOSApp/5.8.0")
        if (!hostHeader.isNullOrEmpty()) {
            reqBuilder.header("Host", hostHeader)
        }
        val bytes = client.newCall(reqBuilder.build()).execute().use { resp ->
            if (!resp.isSuccessful) return null
            resp.body()?.bytes()
        } ?: return null

        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOpts)
        if (boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0) return null

        var sampleSize = 1
        while (boundsOpts.outWidth / (sampleSize * 2) >= targetSizePx &&
            boundsOpts.outHeight / (sampleSize * 2) >= targetSizePx
        ) {
            sampleSize *= 2
        }
        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts) ?: return null
        if (cornerRadiusPx <= 0f) return raw

        val out = Bitmap.createBitmap(raw.width, raw.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = BitmapShader(raw, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        canvas.drawRoundRect(
            RectF(0f, 0f, raw.width.toFloat(), raw.height.toFloat()),
            cornerRadiusPx,
            cornerRadiusPx,
            paint
        )
        if (raw != out) raw.recycle()
        return out
    }
}
