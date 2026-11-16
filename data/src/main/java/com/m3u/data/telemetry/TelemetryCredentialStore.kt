package com.m3u.data.telemetry

import android.content.Context
import com.m3u.data.extension.security.ExtensionSecretStore
import com.m3u.extension.api.security.CredentialHandle
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class GoogleServiceAccountKey(
    val type: String,
    @SerialName("project_id") val projectId: String? = null,
    @SerialName("private_key_id") val privateKeyId: String? = null,
    @SerialName("private_key") val privateKey: String,
    @SerialName("client_email") val clientEmail: String,
    @SerialName("client_id") val clientId: String? = null,
    @SerialName("token_uri") val tokenUri: String = DEFAULT_TOKEN_URI,
) {
    companion object {
        const val DEFAULT_TOKEN_URI = "https://oauth2.googleapis.com/token"
        private val json = Json { ignoreUnknownKeys = true }

        fun parseAndValidate(rawJson: String): GoogleServiceAccountKey {
            val parsed = json.decodeFromString(serializer(), rawJson)
            require(parsed.type == "service_account") {
                "Expected service_account JSON key"
            }
            require(parsed.clientEmail.isNotBlank() && parsed.clientEmail.contains("@")) {
                "Service account JSON is missing a valid client_email"
            }
            require(
                parsed.privateKey.contains("PRIVATE KEY") &&
                    parsed.privateKey.length > 64
            ) {
                "Service account JSON is missing a valid PKCS#8 private_key"
            }
            // Validate the RSA PKCS#8 key eagerly so invalid keys fail at import time.
            GoogleDriveTelemetryClient.decodeRsaPrivateKey(parsed.privateKey)
            return parsed
        }
    }
}

@Singleton
class TelemetryCredentialStore @Inject constructor(
    @ApplicationContext context: Context,
    private val secretStore: ExtensionSecretStore,
) {
    private val preferences = context.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    @Synchronized
    fun importServiceAccountJson(rawJson: String): GoogleServiceAccountKey {
        val parsed = GoogleServiceAccountKey.parseAndValidate(rawJson)
        val existingHandle = preferences.getString(KEY_HANDLE, null)?.let(::CredentialHandle)
        val handle = secretStore.store(
            extensionId = TELEMETRY_EXTENSION_ID,
            settingKey = SERVICE_ACCOUNT_SETTING_KEY,
            secret = rawJson,
            existingHandle = existingHandle,
        )
        preferences.edit()
            .putString(KEY_HANDLE, handle.value)
            .putString(KEY_CLIENT_EMAIL, parsed.clientEmail)
            .putString(KEY_LAST_UPLOAD_ERROR, null)
            .apply()
        return parsed
    }

    @Synchronized
    fun loadServiceAccountKey(): GoogleServiceAccountKey? {
        val handleValue = preferences.getString(KEY_HANDLE, null) ?: return null
        val raw = secretStore.resolve(
            extensionId = TELEMETRY_EXTENSION_ID,
            handle = CredentialHandle(handleValue),
        ) ?: return null
        return runCatching { GoogleServiceAccountKey.parseAndValidate(raw) }.getOrNull()
    }

    @Synchronized
    fun serviceAccountEmail(): String? =
        preferences.getString(KEY_CLIENT_EMAIL, null)?.takeIf(String::isNotBlank)

    @Synchronized
    fun clearServiceAccount() {
        preferences.getString(KEY_HANDLE, null)?.let { handle ->
            secretStore.delete(TELEMETRY_EXTENSION_ID, CredentialHandle(handle))
        }
        preferences.edit()
            .remove(KEY_HANDLE)
            .remove(KEY_CLIENT_EMAIL)
            .apply()
    }

    @Synchronized
    fun recordUploadSuccess(epochMs: Long) {
        preferences.edit()
            .putLong(KEY_LAST_UPLOAD_AT, epochMs)
            .remove(KEY_LAST_UPLOAD_ERROR)
            .apply()
    }

    @Synchronized
    fun recordUploadError(message: String) {
        preferences.edit()
            .putString(KEY_LAST_UPLOAD_ERROR, CredentialRedactor.redact(message).take(240))
            .apply()
    }

    @Synchronized
    fun lastUploadAtEpochMs(): Long? =
        preferences.getLong(KEY_LAST_UPLOAD_AT, -1L).takeIf { it > 0L }

    @Synchronized
    fun lastUploadError(): String? =
        preferences.getString(KEY_LAST_UPLOAD_ERROR, null)?.takeIf(String::isNotBlank)

    private companion object {
        const val PREFERENCES_NAME = "telemetry-drive-credentials"
        const val TELEMETRY_EXTENSION_ID = "com.m3u.data.telemetry"
        const val SERVICE_ACCOUNT_SETTING_KEY = "gdrive-service-account-json"
        const val KEY_HANDLE = "service_account_handle"
        const val KEY_CLIENT_EMAIL = "service_account_email"
        const val KEY_LAST_UPLOAD_AT = "last_upload_at_ms"
        const val KEY_LAST_UPLOAD_ERROR = "last_upload_error"
    }
}
