package com.m3u.data.telemetry

import com.m3u.data.api.OkhttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

sealed interface DriveUploadResult {
    data class Success(val fileId: String?) : DriveUploadResult
    data class RetryableFailure(val statusCode: Int?, val message: String) : DriveUploadResult
    data class PermanentFailure(val statusCode: Int?, val message: String) : DriveUploadResult
}

@Singleton
class GoogleDriveTelemetryClient @Inject constructor(
    @OkhttpClient(false) private val okHttpClient: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun uploadBundle(
        serviceAccount: GoogleServiceAccountKey,
        folderId: String,
        fileName: String,
        contentJson: String,
        fingerprint: String,
        uploadEndpoint: String = DEFAULT_UPLOAD_ENDPOINT,
        nowEpochSeconds: Long = System.currentTimeMillis() / 1_000L,
    ): DriveUploadResult = withContext(Dispatchers.IO) {
        val normalizedFolderId = folderId.trim()
        if (normalizedFolderId.isEmpty()) {
            return@withContext DriveUploadResult.PermanentFailure(
                statusCode = null,
                message = "Target Google Drive folder ID is empty",
            )
        }

        val accessToken = try {
            fetchAccessToken(serviceAccount, nowEpochSeconds)
        } catch (failure: IOException) {
            return@withContext DriveUploadResult.RetryableFailure(
                statusCode = null,
                message = failure.message ?: "Network error requesting OAuth token",
            )
        } catch (failure: DriveHttpException) {
            return@withContext if (failure.statusCode >= 500 || failure.statusCode == 429) {
                DriveUploadResult.RetryableFailure(failure.statusCode, failure.message)
            } else {
                DriveUploadResult.PermanentFailure(failure.statusCode, failure.message)
            }
        } catch (failure: Throwable) {
            return@withContext DriveUploadResult.PermanentFailure(
                statusCode = null,
                message = failure.message ?: "Failed to create Google Drive OAuth assertion",
            )
        }

        val metadataJson = json.encodeToString(
            DriveFileMetadata.serializer(),
            DriveFileMetadata(
                name = fileName,
                parents = listOf(normalizedFolderId),
                mimeType = JSON_MEDIA_TYPE_STRING,
                description = "M3UAndroid redacted diagnostic bundle (fingerprint=$fingerprint)",
            ),
        )
        val multipartBody = MultipartBody.Builder()
            .setType("multipart/related".toMediaType())
            .addPart(metadataJson.toRequestBody(JSON_MEDIA_TYPE))
            .addPart(contentJson.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val request = Request.Builder()
            .url(uploadEndpoint)
            .header("Authorization", "Bearer $accessToken")
            .post(multipartBody)
            .build()

        try {
            okHttpClient.newCall(request).execute().use { response ->
                val responseText = response.body.string()
                when {
                    response.isSuccessful -> {
                        val fileId = runCatching {
                            json.decodeFromString(DriveFileUploadResponse.serializer(), responseText).id
                        }.getOrNull()
                        DriveUploadResult.Success(fileId)
                    }
                    response.code == 429 || response.code >= 500 -> {
                        DriveUploadResult.RetryableFailure(
                            statusCode = response.code,
                            message = "Google Drive temporary failure (${response.code})",
                        )
                    }
                    else -> {
                        DriveUploadResult.PermanentFailure(
                            statusCode = response.code,
                            message = "Google Drive rejected upload (${response.code})",
                        )
                    }
                }
            }
        } catch (failure: IOException) {
            DriveUploadResult.RetryableFailure(
                statusCode = null,
                message = failure.message ?: "Network error uploading diagnostic bundle",
            )
        }
    }

    internal fun createJwtAssertion(
        serviceAccount: GoogleServiceAccountKey,
        nowEpochSeconds: Long,
        scope: String = DRIVE_FILE_SCOPE,
    ): String {
        val header = """{"alg":"RS256","typ":"JWT"}"""
        val audience = serviceAccount.tokenUri.ifBlank { GoogleServiceAccountKey.DEFAULT_TOKEN_URI }
        val claims = json.encodeToString(
            JwtClaims.serializer(),
            JwtClaims(
                iss = serviceAccount.clientEmail,
                scope = scope,
                aud = audience,
                iat = nowEpochSeconds,
                exp = nowEpochSeconds + 3600L,
            ),
        )
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val signingInput = buildString {
            append(encoder.encodeToString(header.toByteArray(Charsets.UTF_8)))
            append('.')
            append(encoder.encodeToString(claims.toByteArray(Charsets.UTF_8)))
        }
        val privateKey = decodeRsaPrivateKey(serviceAccount.privateKey)
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(privateKey)
            update(signingInput.toByteArray(Charsets.UTF_8))
            sign()
        }
        return "$signingInput.${encoder.encodeToString(signature)}"
    }

    private fun fetchAccessToken(
        serviceAccount: GoogleServiceAccountKey,
        nowEpochSeconds: Long,
    ): String {
        val assertion = createJwtAssertion(serviceAccount, nowEpochSeconds)
        val tokenEndpoint = serviceAccount.tokenUri.ifBlank { GoogleServiceAccountKey.DEFAULT_TOKEN_URI }
        val body = FormBody.Builder()
            .add("grant_type", JWT_BEARER_GRANT_TYPE)
            .add("assertion", assertion)
            .build()
        val request = Request.Builder()
            .url(tokenEndpoint)
            .post(body)
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            val responseText = response.body.string()
            if (!response.isSuccessful) {
                throw DriveHttpException(
                    statusCode = response.code,
                    message = "OAuth token exchange failed (${response.code})",
                )
            }
            val token = json.decodeFromString(OAuthTokenResponse.serializer(), responseText)
            if (token.accessToken.isBlank()) {
                throw DriveHttpException(response.code, "OAuth response missing access_token")
            }
            return token.accessToken
        }
    }

    @Serializable
    private data class JwtClaims(
        val iss: String,
        val scope: String,
        val aud: String,
        val iat: Long,
        val exp: Long,
    )

    @Serializable
    private data class OAuthTokenResponse(
        @SerialName("access_token") val accessToken: String = "",
    )

    @Serializable
    private data class DriveFileMetadata(
        val name: String,
        val parents: List<String>,
        val mimeType: String,
        val description: String,
    )

    @Serializable
    private data class DriveFileUploadResponse(
        val id: String? = null,
    )

    private class DriveHttpException(
        val statusCode: Int,
        override val message: String,
    ) : RuntimeException(message)

    companion object {
        const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"
        const val DEFAULT_UPLOAD_ENDPOINT =
            "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&supportsAllDrives=true"
        private const val JWT_BEARER_GRANT_TYPE = "urn:ietf:params:oauth:grant-type:jwt-bearer"
        private const val JSON_MEDIA_TYPE_STRING = "application/json; charset=UTF-8"
        private val JSON_MEDIA_TYPE = JSON_MEDIA_TYPE_STRING.toMediaType()

        fun decodeRsaPrivateKey(pem: String): PrivateKey {
            val base64Body = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("\\n", "")
                .replace("\r", "")
                .replace("\n", "")
                .trim()
            val derBytes = Base64.getDecoder().decode(base64Body)
            return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(derBytes))
        }
    }
}
