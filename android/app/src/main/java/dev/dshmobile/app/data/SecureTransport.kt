package dev.dshmobile.app.data

import okhttp3.CertificatePinner
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** Pins constrain already trusted certificates. Default OkHttp hostname verification is untouched. */
internal object SecureTransport {
    fun client(endpoint: HostEndpoint, proxy: RelayLoopbackProxy? = null): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .retryOnConnectionFailure(false) // A lost POST response must never trigger an automatic resend.
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS) // SSE heartbeat is <=20 s.
            .writeTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
        proxy?.let { builder.proxy(it.proxy).proxyAuthenticator(it.authenticator) }
        val host = endpoint.baseUrl.toHttpUrl().host
        endpoint.pinSha256?.let {
            builder.certificatePinner(CertificatePinner.Builder().add(host, it).build())
        }
        endpoint.certificatePem?.let { pem ->
            val anchor = certificate(pem)
            val roots = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null)
                setCertificateEntry("invitation-anchor", anchor)
            }
            val delegate = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).run {
                init(roots)
                trustManagers.filterIsInstance<X509TrustManager>().single()
            }
            // PKIX may not date-check a trust anchor (not a path certificate). Check BOTH explicitly.
            val trust = object : X509TrustManager {
                override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                    delegate.checkClientTrusted(chain, authType)
                }
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                    anchor.checkValidity()
                    chain.forEach { it.checkValidity() }
                    delegate.checkServerTrusted(chain, authType)
                }
            }
            val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trust), null) }
            builder.sslSocketFactory(ssl.socketFactory, trust)
        }
        return builder.build()
    }

    fun certificate(pem: String): X509Certificate {
        try {
            if (pem.toByteArray().size > 16 * 1024 || !PEM.matches(pem.trim())) throw MobileFailure("certificate_invalid")
            val input = pem.trim().byteInputStream()
            val certs = CertificateFactory.getInstance("X.509").generateCertificates(input)
            val cert = certs.singleOrNull() as? X509Certificate ?: throw MobileFailure("certificate_invalid")
            cert.checkValidity()
            return cert
        } catch (_: Exception) { throw MobileFailure("certificate_invalid") }
    }
    private val PEM = Regex("-----BEGIN CERTIFICATE-----\\s+[A-Za-z0-9+/=\\r\\n]+\\s+-----END CERTIFICATE-----")
}
