package com.m3u.data.telemetry

import java.security.MessageDigest

/**
 * Sanitizes diagnostic strings, stack traces, log lines, and player metadata before writing to
 * disk or uploading telemetry, stripping IPTV server hosts, IPv4 addresses, credentials, and keys.
 */
object CredentialRedactor {
    private val PEM_PRIVATE_KEY_REGEX = Regex(
        """-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----[\s\S]*?-----END [A-Z0-9 ]*PRIVATE KEY-----"""
    )
    private val BEARER_REGEX = Regex(
        """(?i)\b(Bearer|Basic)\s+[A-Za-z0-9\-._~+/]+=*"""
    )
    private val HEADER_SECRET_REGEX = Regex(
        """(?im)^(\s*(?:Authorization|Proxy-Authorization|Cookie|Set-Cookie|X-Api-Key|X-Auth-Token)\s*:\s*).*$"""
    )
    private val URL_USERINFO_REGEX = Regex(
        """(?i)\b((?:https?|rtmps?|rtsp|udp)://)([^/\s:@?#]+):([^/\s@?#]+)@"""
    )
    private val XTREAM_PATH_REGEX = Regex(
        """(?i)(/(?:live|movie|series|timeshift)/)([^/\s?#]+)/([^/\s?#]+)(/)"""
    )
    private val QUERY_SECRET_REGEX = Regex(
        """(?i)([?&;,\s])(username|user|password|pass|passwd|token|auth|key|apikey|api_key|secret|sig|signature|session|cookie|access_token|refresh_token|client_secret|jwt|assertion)(=)([^&\s#"'`,;)}\]]+)"""
    )
    private val JSON_SECRET_REGEX = Regex(
        """(?i)("?(?:username|user|password|pass|passwd|token|auth|apiKey|api_key|secret|private_key|access_token|refresh_token|client_secret)"?\s*[:=]\s*")((?!\[REDACTED)[^"]+)(")"""
    )
    private val URL_AUTHORITY_REGEX = Regex(
        """(?i)\b((?:https?|rtmps?|rtsp|udp)://)(?:[^\s/?#@]+@)?(?:\[[^\]]+\]|[^/\s:?#]+)(?::\d{1,5})?"""
    )
    private val IPV4_REGEX = Regex(
        """\b(?:(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(?::\d{1,5})?\b"""
    )

    fun redact(input: String): String {
        if (input.isEmpty()) return input
        var sanitized = PEM_PRIVATE_KEY_REGEX.replace(input, "[REDACTED_PRIVATE_KEY]")
        sanitized = HEADER_SECRET_REGEX.replace(sanitized, "$1[REDACTED]")
        sanitized = BEARER_REGEX.replace(sanitized, "$1 [REDACTED]")
        sanitized = URL_USERINFO_REGEX.replace(sanitized, "$1[REDACTED_CREDENTIALS]@")
        sanitized = XTREAM_PATH_REGEX.replace(sanitized, "$1[REDACTED_USER]/[REDACTED_PASS]$4")
        sanitized = QUERY_SECRET_REGEX.replace(sanitized, "$1$2$3[REDACTED]")
        sanitized = JSON_SECRET_REGEX.replace(sanitized, "$1[REDACTED]$3")
        sanitized = URL_AUTHORITY_REGEX.replace(sanitized, "$1[REDACTED_HOST]")
        sanitized = IPV4_REGEX.replace(sanitized, "[REDACTED_IP]")
        return sanitized
    }

    fun redactNullable(input: String?): String? = input?.let(::redact)
}

object DiagnosticFingerprint {
    private val STACK_FRAME_REGEX = Regex("""^\s*at\s+([^\s(]+\([^)]*\))""")

    fun topAppStackFrame(stackTrace: String): String {
        val frames = stackTrace
            .lineSequence()
            .mapNotNull { line -> STACK_FRAME_REGEX.find(line)?.groupValues?.get(1) }
            .toList()
        val appFrame = frames.firstOrNull { frame -> frame.startsWith("com.m3u.") }
            ?: frames.firstOrNull()
            ?: "unknown"
        return CredentialRedactor.redact(appFrame)
    }

    fun compute(
        eventType: String,
        exceptionClass: String,
        topAppStackFrame: String,
    ): String {
        val normalized = listOf(
            eventType.trim(),
            exceptionClass.trim(),
            topAppStackFrame.trim(),
        ).joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(normalized.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
