package com.m3u.data.telemetry

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GoogleDriveTelemetryClientTest {
    @Test
    fun `jwt assertion signs cleanly and uploadBundle performs oauth plus multipart upload`(): Unit = runBlocking {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val pkcs8Pem = buildString {
            appendLine("-----BEGIN PRIVATE KEY-----")
            appendLine(Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(keyPair.private.encoded))
            append("-----END PRIVATE KEY-----")
        }

        MockWebServer().use { server ->
            server.start()
            val tokenUrl = server.url("/oauth2/token").toString()
            val uploadUrl = server.url("/upload/drive/v3/files?uploadType=multipart").toString()

            val serviceAccount = GoogleServiceAccountKey(
                type = "service_account",
                projectId = "m3u-test",
                privateKeyId = "key-1",
                privateKey = pkcs8Pem,
                clientEmail = "telemetry-bot@m3u-test.iam.gserviceaccount.com",
                tokenUri = tokenUrl,
            )

            val client = GoogleDriveTelemetryClient(OkHttpClient())

            // Verify JWT assertion cryptographic signature directly against public key.
            val jwt = client.createJwtAssertion(serviceAccount, nowEpochSeconds = 1_700_000_000L)
            val jwtParts = jwt.split('.')
            assertEquals(3, jwtParts.size)
            val verifier = Signature.getInstance("SHA256withRSA").apply {
                initVerify(keyPair.public)
                update("${jwtParts[0]}.${jwtParts[1]}".toByteArray(Charsets.UTF_8))
            }
            assertTrue(verifier.verify(Base64.getUrlDecoder().decode(jwtParts[2])))

            // Enqueue OAuth response then Drive upload response.
            server.enqueue(
                MockResponse(
                    code = 200,
                    body = """{"access_token":"mock-access-token","token_type":"Bearer","expires_in":3600}""",
                )
            )
            server.enqueue(
                MockResponse(
                    code = 200,
                    body = """{"id":"drive-file-123"}""",
                )
            )

            val result = client.uploadBundle(
                serviceAccount = serviceAccount,
                folderId = "folder_abc",
                fileName = "bundle-1.json",
                contentJson = """{"summary":"redacted"}""",
                fingerprint = "deadbeef",
                uploadEndpoint = uploadUrl,
                nowEpochSeconds = 1_700_000_000L,
            )

            val success = assertIs<DriveUploadResult.Success>(result)
            assertEquals("drive-file-123", success.fileId)

            val tokenRequest = server.takeRequest()
            assertTrue(tokenRequest.body?.utf8().orEmpty().contains("grant_type=urn"))

            val uploadRequest = server.takeRequest()
            assertEquals("Bearer mock-access-token", uploadRequest.headers["Authorization"])
            val uploadPayload = uploadRequest.body?.utf8().orEmpty()
            assertTrue(uploadPayload.contains("folder_abc"))
            assertTrue(uploadPayload.contains("bundle-1.json"))
            assertTrue(uploadPayload.contains(""""summary":"redacted""""))
        }
    }

    @Test
    fun `uploadBundle returns RetryableFailure on 503 and PermanentFailure on 403`(): Unit = runBlocking {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val pkcs8Pem = buildString {
            appendLine("-----BEGIN PRIVATE KEY-----")
            appendLine(Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(keyPair.private.encoded))
            append("-----END PRIVATE KEY-----")
        }

        MockWebServer().use { server ->
            server.start()
            val serviceAccount = GoogleServiceAccountKey(
                type = "service_account",
                privateKey = pkcs8Pem,
                clientEmail = "telemetry-bot@m3u-test.iam.gserviceaccount.com",
                tokenUri = server.url("/token").toString(),
            )
            val client = GoogleDriveTelemetryClient(OkHttpClient())

            server.enqueue(MockResponse(code = 200, body = """{"access_token":"tok"}"""))
            server.enqueue(MockResponse(code = 503, body = "unavailable"))
            val retryResult = client.uploadBundle(
                serviceAccount = serviceAccount,
                folderId = "folder_abc",
                fileName = "b1.json",
                contentJson = "{}",
                fingerprint = "fp1",
                uploadEndpoint = server.url("/upload").toString(),
            )
            assertIs<DriveUploadResult.RetryableFailure>(retryResult)

            server.enqueue(MockResponse(code = 200, body = """{"access_token":"tok"}"""))
            server.enqueue(MockResponse(code = 403, body = "forbidden"))
            val permResult = client.uploadBundle(
                serviceAccount = serviceAccount,
                folderId = "folder_abc",
                fileName = "b1.json",
                contentJson = "{}",
                fingerprint = "fp1",
                uploadEndpoint = server.url("/upload").toString(),
            )
            assertIs<DriveUploadResult.PermanentFailure>(permResult)
        }
    }
}
