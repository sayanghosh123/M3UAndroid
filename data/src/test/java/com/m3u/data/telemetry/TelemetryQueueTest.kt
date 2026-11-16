package com.m3u.data.telemetry

import org.junit.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TelemetryQueueTest {
    @Test
    fun `enqueue persists bundle deduplicates within window and cleans up removed items`() {
        val tempDir = Files.createTempDirectory("telemetry-queue-test").toFile()
        try {
            var nowMs = 1_700_000_000_000L
            val store = TelemetryQueueStore(
                queueDir = tempDir,
                nowMsProvider = { nowMs },
                dedupWindowMs = 60_000L,
                maxQueuedBundles = 10,
            )

            val first = sampleBundle(id = "1", fingerprint = "fp-same", createdAtMs = nowMs)
            val written1 = store.enqueue(first)
            assertNotNull(written1)
            assertEquals(1, store.pendingCount())

            // Same fingerprint inside dedup window must be suppressed.
            nowMs += 10_000L
            val duplicate = sampleBundle(id = "2", fingerprint = "fp-same", createdAtMs = nowMs)
            assertNull(store.enqueue(duplicate))
            assertEquals(1, store.pendingCount())

            // Manual diagnostic bypasses dedup when allowDuplicate = true.
            val manual = sampleBundle(id = "3", fingerprint = "fp-same", createdAtMs = nowMs + 1)
            val writtenManual = store.enqueue(manual, allowDuplicate = true)
            assertNotNull(writtenManual)
            assertEquals(2, store.pendingCount())

            // Advancing past dedupWindowMs allows queueing that fingerprint again.
            nowMs += 120_000L
            val afterWindow = sampleBundle(id = "4", fingerprint = "fp-same", createdAtMs = nowMs)
            val writtenAfter = store.enqueue(afterWindow)
            assertNotNull(writtenAfter)
            assertEquals(3, store.pendingCount())

            store.listPendingFiles().forEach { store.remove(it) }
            assertEquals(0, store.pendingCount())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun sampleBundle(
        id: String,
        fingerprint: String,
        createdAtMs: Long,
    ): TelemetryBundle = TelemetryBundle(
        id = id,
        eventType = TelemetryEventType.UNCAUGHT_EXCEPTION.wireName,
        fingerprint = fingerprint,
        createdAtIso = "2026-11-16T00:00:00Z",
        createdAtEpochMs = createdAtMs,
        threadName = "main",
        exceptionClass = "java.lang.RuntimeException",
        topAppStackFrame = "com.m3u.Test.run(Test.kt:10)",
        summary = "Test summary",
        stackTrace = "java.lang.RuntimeException: Test",
        device = TelemetryDeviceInfo(
            applicationId = "com.m3u.smartphone.debug",
            versionName = "1.0",
            versionCode = 1,
            manufacturer = "Google",
            model = "Pixel",
            androidRelease = "15",
            sdkInt = 35,
            abi = "arm64-v8a",
        ),
        memoryAndStorage = TelemetryMemoryInfo(
            availableSystemRamBytes = 100L,
            totalSystemRamBytes = 200L,
            lowMemory = false,
            jvmHeapUsedBytes = 50L,
            jvmHeapMaxBytes = 100L,
            internalStorageAvailableBytes = 500L,
            internalStorageTotalBytes = 1000L,
        ),
        network = TelemetryNetworkInfo(
            connected = true,
            transports = listOf("WIFI"),
            validated = true,
            metered = false,
        ),
        playerState = TelemetryPlayerInfo(
            playbackState = "READY",
            isPlaying = true,
        ),
        logTail = listOf("INFO/Test: ok"),
        markdownReport = "# Test",
    )
}
