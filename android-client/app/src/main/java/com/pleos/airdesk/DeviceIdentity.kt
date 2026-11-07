package com.pleos.airdesk

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.math.BigInteger
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.Date
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.security.auth.x500.X500Principal

/** The alias stores a nonexportable key handle. Public certificate export only. */
class DeviceIdentity {
    private val alias = "airdesk-phase2-device-v1"
    private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    fun exists(): Boolean = store().containsAlias(alias)

    /** Called ONLY by the explicit UI identity creation button. */
    fun createOrReadPublicInfo(): String {
        if (!exists()) {
            val now = System.currentTimeMillis()
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
            generator.initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setCertificateSubject(X500Principal("CN=AirDesk Android Device"))
                .setCertificateSerialNumber(BigInteger(128, SecureRandom()).add(BigInteger.ONE))
                .setCertificateNotBefore(Date(now - 60000))
                .setCertificateNotAfter(Date(now + 365L * 24 * 60 * 60 * 1000))
                .build())
            generator.generateKeyPair()
        }
        val certificate = store().getCertificate(alias) as X509Certificate
        certificate.checkValidity()
        return JSONObject().put("protocol", "airdesk-device-v1")
            .put("deviceLabel", "AirDesk Android device")
            .put("clientCertificate", Base64.getEncoder().encodeToString(certificate.encoded))
            .put("clientFingerprint", fingerprint(certificate)).toString()
    }

    fun keyManager(): X509ExtendedKeyManager {
        require(exists()) { "먼저 이 기기의 공개 정보를 생성해 주세요." }
        val keyStore = store()
        val certificate = keyStore.getCertificate(alias) as X509Certificate
        certificate.checkValidity()
        val privateKey = keyStore.getKey(alias, null) as PrivateKey
        return object : X509ExtendedKeyManager() {
            private fun accepted(types: Array<out String>?) = types?.any { it == "EC" || it.startsWith("EC_") } == true
            override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? = if (accepted(keyType)) alias else null
            override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?): String? = if (accepted(keyType)) alias else null
            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = if (keyType == "EC") arrayOf(alias) else null
            override fun getCertificateChain(requestedAlias: String?): Array<X509Certificate>? = if (requestedAlias == alias) arrayOf(certificate) else null
            override fun getPrivateKey(requestedAlias: String?): PrivateKey? = if (requestedAlias == alias) privateKey else null
            override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
            override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
        }
    }
}
