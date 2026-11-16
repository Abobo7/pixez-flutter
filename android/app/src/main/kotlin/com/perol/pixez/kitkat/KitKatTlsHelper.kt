package com.perol.pixez.kitkat

import android.os.Build
import android.util.Base64
import android.util.Log
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.TlsVersion
import org.conscrypt.Conscrypt
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.Provider
import java.security.Security
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Android 4.4.x (API 19) TLS 1.2 / 1.3 与现代根证书兼容助手。
 * 注册内置 Conscrypt (BoringSSL) 并向系统信任链补充现代公共根证书
 * (ISRG Root X1, DigiCert Global Root G2/G3, GlobalSign Root R3/R6)。
 */
object KitKatTlsHelper {

    private const val TAG = "KitKatTlsHelper"

    private const val ISRG_ROOT_X1_B64 =
        "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" +
        "os1AnX5iItreGCc="

    private const val DIGICERT_G2_B64 =
        "MIIDjjCCAnagAwIBAgIQAzrx5qcRqaC7KGSxHQn65TANBgkqhkiG9w0BAQsFADBhMQswCQYDVQQGEwJV" +
        "UzEVMBMGA1UEChMMRGlnaUNlcnQgSW5jMRkwFwYDVQQLExB3d3cuZGlnaWNlcnQuY29tMSAwHgYDVQQD" +
        "ExdEaWdpQ2VydCBHbG9iYWwgUm9vdCBHMjAeFw0xMzA4MDExMjAwMDBaFw0zODAxMTUxMjAwMDBaMGEx" +
        "CzAJBgNVBAYTAlVTMRUwEwYDVQQKEwxEaWdpQ2VydCBJbmMxGTAXBgNVBAsTEHd3dy5kaWdpY2VydC5j" +
        "b20xIDAeBgNVBAMTF0RpZ2lDZXJ0IEdsb2JhbCBSb290IEcyMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8A" +
        "MIIBCgKCAQEAuzfNNNx7a8myaJCtSnX/RrohCgiN9RlUyfuI2/Ou8jqJkTx65qsGGmvPrC3oXgkkRLpi" +
        "mn7Wo6h+4FR1IAWsULecYxpsMNzaHxmx1x7e/dfgy5SDN67sH0NO3Xss0r0upS/kqbitOtSZpLYl6Ztr" +
        "AGCSYP9PIUkY92eQq2EGnI/yuum06ZIya7XzV+hdG82MHauVBJVJ8zUtluNJbd134/tJS7SsVQepj5Wz" +
        "tCO7TG1F8PapspUwtP1MVYwnSlcUfIKdzXOS0xZKBgyMUNGPHgm+F6HmIcr9g+UQvIOlCsRnKPZzFBQ9" +
        "RnbDhxSJITRNrw9FDKZJobq7nMWxM4MphQIDAQABo0IwQDAPBgNVHRMBAf8EBTADAQH/MA4GA1UdDwEB" +
        "/wQEAwIBhjAdBgNVHQ4EFgQUTiJUIBiV5uNu5g/6+rkS7QYXjzkwDQYJKoZIhvcNAQELBQADggEBAGBn" +
        "KJRvDkhj6zHd6mcY1Yl9PMWLSn/pvtsrF9+wX3N3KjITOYFnQoQj8kVnNeyIv/iPsGEMNKSuIEyExtv4" +
        "NeF22d+mQrvHRAiGfzZ0JFrabA0UWTW98kndth/Jsw1HKj2ZL7tcu7XUIOGZX1NGFdtom/DzMNU+MeKN" +
        "hJ7jitralj41E6Vf8PlwUHBHQRFXGU7Aj64GxJUTFy8bJZ918rGOmaFvE7FBcf6IKshPECBV1/MUReXg" +
        "RPTqh5Uykw7+U0b6LJ3/iyK5S9kJRaTepLiaWN0bfVKfjllDiIGknibVb63dDcY3fe0Dkhvld1927jyN" +
        "xF1WW6LZZm6zNTflMrY="

    private const val DIGICERT_G3_B64 =
        "MIICPzCCAcWgAwIBAgIQBVVWvPJepDU1w6QP1atFcjAKBggqhkjOPQQDAzBhMQswCQYDVQQGEwJVUzEV" +
        "MBMGA1UEChMMRGlnaUNlcnQgSW5jMRkwFwYDVQQLExB3d3cuZGlnaWNlcnQuY29tMSAwHgYDVQQDExdE" +
        "aWdpQ2VydCBHbG9iYWwgUm9vdCBHMzAeFw0xMzA4MDExMjAwMDBaFw0zODAxMTUxMjAwMDBaMGExCzAJ" +
        "BgNVBAYTAlVTMRUwEwYDVQQKEwxEaWdpQ2VydCBJbmMxGTAXBgNVBAsTEHd3dy5kaWdpY2VydC5jb20x" +
        "IDAeBgNVBAMTF0RpZ2lDZXJ0IEdsb2JhbCBSb290IEczMHYwEAYHKoZIzj0CAQYFK4EEACIDYgAE3afZ" +
        "u4q4C/sLfyHS8L6+c/MzXRq8NOrexpu80JX28MzQC7phW1FGfp4tn+6OYwwX7Adw9c+ELkCDnOg/QW07" +
        "rdOkFFk2eJ0DQ+4QE2xy3q6Ip6FrtUPOZ9wj/wMco+I+o0IwQDAPBgNVHRMBAf8EBTADAQH/MA4GA1Ud" +
        "DwEB/wQEAwIBhjAdBgNVHQ4EFgQUs9tIpPmhxdiuNkHMEWNpYim8S8YwCgYIKoZIzj0EAwMDaAAwZQIx" +
        "AK288mw/EkrRLTnDCgmXc/SINoyIJ7vmiI1Qhadj+Z4y3maTD/HMsQmP3Wyr+mt/oAIwOWZbwmSNuJ5Q" +
        "3KjVSaLtx9zRSX8XAbjIho9OjIgrqJqpisXRAL34VOKa5Vt8sycX"

    private const val GLOBALSIGN_R3_B64 =
        "MIIDXzCCAkegAwIBAgILBAAAAAABIVhTCKIwDQYJKoZIhvcNAQELBQAwTDEgMB4GA1UECxMXR2xvYmFs" +
        "U2lnbiBSb290IENBIC0gUjMxEzARBgNVBAoTCkdsb2JhbFNpZ24xEzARBgNVBAMTCkdsb2JhbFNpZ24w" +
        "HhcNMDkwMzE4MTAwMDAwWhcNMjkwMzE4MTAwMDAwWjBMMSAwHgYDVQQLExdHbG9iYWxTaWduIFJvb3Qg" +
        "Q0EgLSBSMzETMBEGA1UEChMKR2xvYmFsU2lnbjETMBEGA1UEAxMKR2xvYmFsU2lnbjCCASIwDQYJKoZI" +
        "hvcNAQEBBQADggEPADCCAQoCggEBAMwldpB5BngiFvXAg7aEyiie/QV2EcWtiHL8RgJDx7KKnQRfJMsu" +
        "S+FggkbhUqsMgUdwbN1k0ev1LKMPgj0MK66X17YUhhB5uzsTgHeMCOFJ0mpiLx9e+pZo34knlTifBtc+" +
        "ycsmWQ1z3rDI6SYOgxXG71uL0gRgykmmKPZpO/bLyCiR5Z2KYVc3rHQU3HTgOu5yLy6c+9C7v/U9AOEG" +
        "M+iCK65TpjoWc4zdQQ4gOsC0p6Hpsk+QLjJg6VfLuQSSaGjlOCZgdbKfd/+RFO+uIEn8rUAVSNECMWEZ" +
        "XriX7613t2Saer9fwRPvm2L7DWzgVGkWqQPabumDk3F2xmmFghcCAwEAAaNCMEAwDgYDVR0PAQH/BAQD" +
        "AgEGMA8GA1UdEwEB/wQFMAMBAf8wHQYDVR0OBBYEFI/wS3+oLkUkrk1Q+mOai97i3Ru8MA0GCSqGSIb3" +
        "DQEBCwUAA4IBAQBLQNvAUKr+yAzv95ZURUm7lgAJQayzE4aGKAczymvmdLm6AC2upArT9fHxD4q/c2dK" +
        "g8dEe3jgr25sbwMpjjM5RcOO5LlXbKr8EpbsU8Yt5CRsuZRj+9xTaGdWPoO4zzUhw8lo/s7awlOqzJCK" +
        "6fBdRoyV3XpYKBovHd7NADdBj+1EbddTKJd+82cEHhXXipa0095MJ6RMG3NzdvQXmcIfeg7jLQitChws" +
        "/zyrVQ4PkX4268NXSb7hLi18YIvDQVETI53O9zJrlAGomecsMx86OyXShkDOOyyGeMlhLxS67ttVb9+E" +
        "7gUJTb0o2HLO02JQZR7rkpeDMdmztcpHWD9f"

    private const val GLOBALSIGN_R6_B64 =
        "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"

    @Volatile
    private var installed = false

    @Volatile
    private var sslSocketFactory: SSLSocketFactory? = null

    @Volatile
    private var trustManager: X509TrustManager? = null

    val defaultOkHttpClient: OkHttpClient by lazy {
        createOkHttpClientBuilder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    @Synchronized
    fun install() {
        if (installed) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            try {
                val tm = buildCompositeTrustManager()
                val provider = getOrCreateConscryptProvider()
                val sslContext = SSLContext.getInstance("TLS", provider)
                sslContext.init(null, arrayOf<TrustManager>(tm), null)
                val factory = sslContext.socketFactory
                sslSocketFactory = factory
                trustManager = tm
                HttpsURLConnection.setDefaultSSLSocketFactory(factory)
                Log.i(TAG, "Installed Conscrypt TLS 1.2/1.3 & modern Root CAs on API ${Build.VERSION.SDK_INT}")
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to install Conscrypt TLS provider", t)
            }
        }
        installed = true
    }

    fun createOkHttpClientBuilder(): OkHttpClient.Builder {
        install()
        val builder = OkHttpClient.Builder()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            val factory = sslSocketFactory
            val tm = trustManager
            if (factory != null && tm != null) {
                builder.sslSocketFactory(factory, tm)
                val modernTls = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                    .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2, TlsVersion.TLS_1_1, TlsVersion.TLS_1_0)
                    .allEnabledCipherSuites()
                    .build()
                builder.connectionSpecs(listOf(modernTls, ConnectionSpec.CLEARTEXT))
            }
        }
        return builder
    }

    private fun getOrCreateConscryptProvider(): Provider {
        Security.getProvider("Conscrypt")?.let { return it }
        val created = Conscrypt.newProviderBuilder()
            .provideTrustManager(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            .build()
        Security.insertProviderAt(created, 1)
        return created
    }

    private fun buildCompositeTrustManager(): X509TrustManager {
        val delegates = ArrayList<X509TrustManager>()
        try {
            val sysFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            sysFactory.init(null as KeyStore?)
            sysFactory.trustManagers.filterIsInstance<X509TrustManager>().forEach { delegates.add(it) }
        } catch (t: Throwable) {
            Log.w(TAG, "System TrustManager init failed", t)
        }

        try {
            val extraStore = KeyStore.getInstance(KeyStore.getDefaultType())
            extraStore.load(null, null)
            val cf = CertificateFactory.getInstance("X.509")
            val roots = arrayOf(ISRG_ROOT_X1_B64, DIGICERT_G2_B64, DIGICERT_G3_B64, GLOBALSIGN_R3_B64, GLOBALSIGN_R6_B64)
            roots.forEachIndexed { idx, b64 ->
                val der = Base64.decode(b64, Base64.DEFAULT)
                val cert = cf.generateCertificate(ByteArrayInputStream(der)) as X509Certificate
                extraStore.setCertificateEntry("kitkat_root_$idx", cert)
            }
            val extraFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            extraFactory.init(extraStore)
            extraFactory.trustManagers.filterIsInstance<X509TrustManager>().forEach { delegates.add(it) }
        } catch (t: Throwable) {
            Log.w(TAG, "Extra Root CA store init failed", t)
        }

        return CompositeX509TrustManager(delegates)
    }

    private class CompositeX509TrustManager(
        private val delegates: List<X509TrustManager>
    ) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            var lastError: CertificateException? = null
            for (tm in delegates) {
                try {
                    tm.checkClientTrusted(chain, authType)
                    return
                } catch (e: CertificateException) {
                    lastError = e
                }
            }
            if (lastError != null) throw lastError
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            var lastError: CertificateException? = null
            for (tm in delegates) {
                try {
                    tm.checkServerTrusted(chain, authType)
                    return
                } catch (e: CertificateException) {
                    lastError = e
                }
            }
            if (lastError != null) throw lastError
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> {
            return delegates.flatMap { it.acceptedIssuers.toList() }.toTypedArray()
        }
    }
}
