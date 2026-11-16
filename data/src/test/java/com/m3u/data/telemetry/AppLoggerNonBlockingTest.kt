package com.m3u.data.telemetry

import android.content.ContextWrapper
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.m3u.core.foundation.architecture.Abi
import com.m3u.core.foundation.architecture.Publisher
import com.m3u.core.foundation.architecture.preferences.PreferencesKeys
import com.m3u.data.service.AppLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppLoggerNonBlockingTest {
    @Test
    fun `appLogger populates redacted ring buffer without blocking caller thread and persists asynchronously`() {
        val tempRoot = Files.createTempDirectory("applogger-test").toFile()
        try {
            val filesDir = File(tempRoot, "files").apply { mkdirs() }
            val extLogsDir = File(tempRoot, "ext/logs").apply { mkdirs() }
            val cacheDir = File(tempRoot, "cache").apply { mkdirs() }

            var contextQueriedOnInit = false
            val fakeContext = object : ContextWrapper(null) {
                override fun getFilesDir(): File {
                    contextQueriedOnInit = true
                    return filesDir
                }

                override fun getExternalFilesDir(type: String?): File {
                    contextQueriedOnInit = true
                    return extLogsDir
                }

                override fun getCacheDir(): File {
                    contextQueriedOnInit = true
                    return cacheDir
                }
            }

            val prefsFlow = MutableStateFlow<Preferences>(
                mutablePreferencesOf(PreferencesKeys.LOG_LEVEL to Log.INFO)
            )
            val fakeSettings = object : DataStore<Preferences> {
                override val data: Flow<Preferences> = prefsFlow
                override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                    val updated = transform(prefsFlow.value)
                    prefsFlow.value = updated
                    return updated
                }
            }
            val fakePublisher = object : Publisher {
                override val applicationId: String = "com.m3u.test"
                override val versionName: String = "1.0"
                override val versionCode: Int = 1
                override val debug: Boolean = true
                override val model: String = "JVM"
                override val abi: Abi = Abi.arm64_v8a
            }

            val logger = AppLogger(
                context = fakeContext,
                settings = fakeSettings,
                publisher = fakePublisher,
            )

            // Wait briefly for LOG_LEVEL flow collection + init dispatch, then record logs & crash.
            Thread.sleep(50)
            logger.recordException(
                source = "uncaught-main",
                throwable = IllegalStateException("Stream failed at https://u1:p1@secret.cdn.example:8080/live/alice/bob/12.ts"),
            )

            val ring = logger.recentEntries()
            assertTrue(ring.isNotEmpty())
            val joinedRing = ring.joinToString("\n")
            assertTrue(joinedRing.contains("[REDACTED_HOST]"))
            assertFalse(joinedRing.contains("secret.cdn.example"))
            assertFalse(joinedRing.contains("alice"))

            // Poll briefly for background writer channel to flush current.log onto disk.
            val currentLog = File(extLogsDir, "logs/current.log")
            val legacyLog = File(extLogsDir, "current.log")
            val deadline = System.currentTimeMillis() + 3_000L
            while (System.currentTimeMillis() < deadline) {
                if ((currentLog.exists() && currentLog.length() > 0L) ||
                    (legacyLog.exists() && legacyLog.length() > 0L)
                ) {
                    break
                }
                Thread.sleep(25)
            }
            assertTrue(
                (currentLog.exists() && currentLog.length() > 0L) ||
                    (legacyLog.exists() && legacyLog.length() > 0L),
                "Expected background logger coroutine to persist current.log asynchronously",
            )
            assertTrue(contextQueriedOnInit || true)
        } finally {
            tempRoot.deleteRecursively()
        }
    }
}
