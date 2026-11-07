package com.pleos.airdesk

import org.junit.Assert.assertTrue
import org.junit.Test

class TrustBootstrapTest {
    @Test fun rejectUnknownFieldsHostPortAndWrongTypesBeforeTrustEnrollment() {
        val invalid = listOf(
            """{"protocol":"airdesk-bootstrap-v1","host":"evil.example","port":8766,"serverCertificate":"","serverFingerprint":"${"A".repeat(64)}","pairingId":"offer","expiresAtMs":1000}""",
            """{"protocol":"airdesk-bootstrap-v1","host":"10.0.2.2","port":"8766","serverCertificate":"","serverFingerprint":"${"A".repeat(64)}","pairingId":"offer","expiresAtMs":1000}""",
            """{"protocol":"airdesk-bootstrap-v1","host":"10.0.2.2","port":8766,"serverCertificate":123,"serverFingerprint":"${"A".repeat(64)}","pairingId":"offer","expiresAtMs":1000}""",
            """{"protocol":"airdesk-bootstrap-v1","privateKey":"must-not-import"}""",
        )
        invalid.forEach { assertTrue(runCatching { HostBootstrap.parse(it) }.isFailure) }
    }
}
