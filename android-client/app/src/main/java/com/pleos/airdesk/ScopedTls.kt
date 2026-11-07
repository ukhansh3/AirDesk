package com.pleos.airdesk

import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.TlsVersion
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** Exact DER enrollment pin + platform chain validation, scoped to this service.
 * The default OkHttp hostname verifier is deliberately left intact. */
internal fun secureHttp(host: HostBootstrap, identity: DeviceIdentity): OkHttpClient {
    val anchors = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
        load(null, null); setCertificateEntry("enrolled-airdesk-host", host.certificate)
    }
    val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(anchors) }
    val delegate = factory.trustManagers.filterIsInstance<X509TrustManager>().single()
    val trust = object : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            throw CertificateException("Client validation is not supported by this client trust store")
        }
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            if (chain.isNullOrEmpty() || authType.isNullOrBlank()) throw CertificateException("Missing host identity")
            host.certificate.checkValidity()
            chain[0].checkValidity()
            if (fingerprint(chain[0]) != host.fingerprint) throw CertificateException("Host enrollment pin mismatch")
            delegate.checkServerTrusted(chain, authType)
        }
    }
    val context = SSLContext.getInstance("TLS").apply {
        init(arrayOf(identity.keyManager()), arrayOf(trust), SecureRandom())
    }
    return OkHttpClient.Builder()
        .sslSocketFactory(context.socketFactory, trust)
        .connectionSpecs(listOf(ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
            .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2).build()))
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(10, TimeUnit.SECONDS)
        .build()
}
