package com.m3u.data.telemetry

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CredentialRedactorTest {
    @Test
    fun `redact strips xtream paths query credentials userinfo hosts and ips`() {
        val raw = """
            Failed opening https://alice:s3cr3tPass@iptv.example.com:8443/live/bobUser/topSecret99/12345.ts?username=u1&password=p1&token=tok_abc
            Connected to 203.0.113.42:8080 with Authorization: Bearer eyJhbGciOiJSUzI1NiJ9.payload.sig
            Device Model: Pixel 9 Pro API 35 HTTP=502
        """.trimIndent()

        val redacted = CredentialRedactor.redact(raw)

        assertFalse(redacted.contains("iptv.example.com"))
        assertFalse(redacted.contains("alice"))
        assertFalse(redacted.contains("s3cr3tPass"))
        assertFalse(redacted.contains("bobUser"))
        assertFalse(redacted.contains("topSecret99"))
        assertFalse(redacted.contains("u1"))
        assertFalse(redacted.contains("p1"))
        assertFalse(redacted.contains("tok_abc"))
        assertFalse(redacted.contains("203.0.113.42"))
        assertFalse(redacted.contains("eyJhbGciOiJSUzI1NiJ9"))
        // Hardware specs and error metadata must remain intact for diagnostics.
        assertTrue(redacted.contains("Device Model: Pixel 9 Pro API 35 HTTP=502"))
    }

    @Test
    fun `redact strips pem private keys and json secrets`() {
        val raw = """
            {"client_secret":"abc123","private_key":"-----BEGIN PRIVATE KEY-----\nMIIEvQIBADANBgkqhkiG9w0BAQ==\n-----END PRIVATE KEY-----"}
        """.trimIndent()

        val redacted = CredentialRedactor.redact(raw)

        assertFalse(redacted.contains("abc123"))
        assertFalse(redacted.contains("MIIEvQIBADANBgkqhkiG9w0BAQ=="))
        assertTrue(redacted.contains("[REDACTED]"))
        assertTrue(redacted.contains("[REDACTED_PRIVATE_KEY]"))
    }

    @Test
    fun `fingerprint is deterministic and prefers com m3u frame`() {
        val trace = """
            java.lang.IllegalStateException: Boom
                at android.os.Handler.dispatchMessage(Handler.java:107)
                at com.m3u.data.service.internal.PlayerManagerImpl.handleError(PlayerManagerImpl.kt:790)
                at android.app.ActivityThread.main(ActivityThread.java:8000)
        """.trimIndent()

        val topFrame = DiagnosticFingerprint.topAppStackFrame(trace)
        assertEquals(
            "com.m3u.data.service.internal.PlayerManagerImpl.handleError(PlayerManagerImpl.kt:790)",
            topFrame,
        )

        val fp1 = DiagnosticFingerprint.compute("UNCAUGHT_EXCEPTION", "java.lang.IllegalStateException", topFrame)
        val fp2 = DiagnosticFingerprint.compute("UNCAUGHT_EXCEPTION", "java.lang.IllegalStateException", topFrame)
        val fpDifferent = DiagnosticFingerprint.compute("ANR_WATCHDOG", "java.lang.IllegalStateException", topFrame)

        assertEquals(64, fp1.length)
        assertEquals(fp1, fp2)
        assertNotEquals(fp1, fpDifferent)
    }
}
