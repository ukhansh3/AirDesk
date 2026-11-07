package com.pleos.airdesk

import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64

data class HostBootstrap(
    val host: String,
    val port: Int,
    val certificate: X509Certificate,
    val fingerprint: String,
    val pairingId: String,
    val expiresAtMs: Long,
) {
    val url: String get() = "wss://$host:$port"
    fun canClaim(nowEpochMs: Long) = nowEpochMs < expiresAtMs

    companion object {
        fun parse(text: String): HostBootstrap {
            require(text.toByteArray().size <= 32768) { "공개 연결 정보가 너무 큽니다." }
            val json = StrictJson.objectValue(text, 32768)
            require(json.exactKeys("protocol", "host", "port", "serverCertificate", "serverFingerprint", "pairingId", "expiresAtMs")) { "지원하지 않는 연결 정보 필드입니다." }
            require(json.opt("protocol") == "airdesk-bootstrap-v1")
            require(listOf("serverCertificate", "serverFingerprint", "pairingId").all { json.opt(it) is String })
            require(json.opt("host") == "10.0.2.2" && json.integer("port") == 8766L) { "현재는 같은 PC의 에뮬레이터 연결만 지원합니다." }
            val der = Base64.getDecoder().decode(json.getString("serverCertificate"))
            val certificate = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate
            certificate.checkValidity()
            require(certificate.encoded.contentEquals(der)) { "인증서 형식이 올바르지 않습니다." }
            val fingerprint = fingerprint(certificate)
            require(json.opt("serverFingerprint") is String && json.getString("serverFingerprint").matches(Regex("^[A-Fa-f0-9]{64}$")))
            require(MessageDigest.isEqual(fingerprint.toByteArray(), json.getString("serverFingerprint").uppercase().toByteArray())) { "인증서 지문이 일치하지 않습니다." }
            val pairingId = json.getString("pairingId")
            require(pairingId.matches(Regex("^[A-Za-z0-9._-]{1,128}$")))
            val expiry = json.integer("expiresAtMs") ?: error("만료 시각 형식이 올바르지 않습니다.")
            require(expiry in 0..9007199254740991L)
            return HostBootstrap("10.0.2.2", 8766, certificate, fingerprint, pairingId, expiry)
        }
    }
}

internal fun fingerprint(certificate: X509Certificate): String = MessageDigest.getInstance("SHA-256")
    .digest(certificate.encoded).joinToString("") { "%02X".format(it.toInt() and 255) }
