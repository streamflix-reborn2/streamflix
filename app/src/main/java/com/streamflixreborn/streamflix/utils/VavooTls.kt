package com.streamflixreborn.streamflix.utils

import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Sonderbehandlung ausschließlich für Vavoo Live.
 *
 * Hintergrund:
 * Einige aktuelle Vavoo-CDN-Streams sind technisch erreichbar,
 * besitzen aber ein abgelaufenes TLS-Zertifikat.
 *
 * Die Ausnahme wird zusätzlich auf exakt den aufgelösten
 * Stream-Host begrenzt. Andere Provider verwenden diesen
 * Client niemals.
 */
object VavooTls {

    private val trustManager = object : X509TrustManager {
        override fun checkClientTrusted(
            chain: Array<out X509Certificate>?,
            authType: String?
        ) = Unit

        override fun checkServerTrusted(
            chain: Array<out X509Certificate>?,
            authType: String?
        ) = Unit

        override fun getAcceptedIssuers(): Array<X509Certificate> =
            emptyArray()
    }

    private val sslSocketFactory by lazy {
        SSLContext
            .getInstance("TLS")
            .apply {
                init(
                    null,
                    arrayOf<TrustManager>(trustManager),
                    SecureRandom()
                )
            }
            .socketFactory
    }

    fun relaxForHost(
        builder: OkHttpClient.Builder,
        allowedHost: String
    ): OkHttpClient.Builder {

        val normalHostnameVerifier =
            HttpsURLConnection.getDefaultHostnameVerifier()

        return builder
            .sslSocketFactory(
                sslSocketFactory,
                trustManager
            )
            .hostnameVerifier { hostname, session ->
                hostname.equals(
                    allowedHost,
                    ignoreCase = true
                ) &&
                    normalHostnameVerifier.verify(
                        hostname,
                        session
                    )
            }
    }
}
