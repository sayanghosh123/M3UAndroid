package com.m3u.data.telemetry

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Persists redacted diagnostic bundles in an on-disk queue with deterministic fingerprint
 * deduplication and bounded retention. Pure JVM/file logic for straightforward unit testing.
 */
internal class TelemetryQueueStore(
    private val queueDir: File,
    private val nowMsProvider: () -> Long = System::currentTimeMillis,
    private val dedupWindowMs: Long = DEFAULT_DEDUP_WINDOW_MS,
    private val maxQueuedBundles: Int = DEFAULT_MAX_QUEUED_BUNDLES,
) {
    private val lock = Any()
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    fun enqueue(
        bundle: TelemetryBundle,
        allowDuplicate: Boolean = false,
    ): File? = synchronized(lock) {
        ensureDirectory()
        val nowMs = nowMsProvider()
        val fingerprints = loadFingerprintsLocked(nowMs).toMutableMap()
        if (!allowDuplicate) {
            val previousTimestamp = fingerprints[bundle.fingerprint]
            if (previousTimestamp != null && nowMs - previousTimestamp < dedupWindowMs) {
                return@synchronized null
            }
        }

        val sanitizedName = "${bundle.createdAtEpochMs}-${bundle.eventType.lowercase()}-${bundle.fingerprint.take(10)}.json"
        val targetFile = File(queueDir, sanitizedName)
        val encoded = json.encodeToString(TelemetryBundle.serializer(), bundle)
        writeAtomically(targetFile, encoded)

        fingerprints[bundle.fingerprint] = nowMs
        saveFingerprintsLocked(fingerprints)
        pruneQueueLocked()
        return@synchronized targetFile
    }

    fun listPendingFiles(): List<File> = synchronized(lock) {
        if (!queueDir.exists()) return emptyList()
        return queueDir.listFiles()
            ?.filter { file ->
                file.isFile &&
                    file.extension.equals("json", ignoreCase = true) &&
                    file.name != FINGERPRINTS_FILE_NAME
            }
            ?.sortedBy(File::getName)
            .orEmpty()
    }

    fun pendingCount(): Int = listPendingFiles().size

    fun readBundle(file: File): TelemetryBundle? = synchronized(lock) {
        if (!file.isFile) return null
        return runCatching {
            json.decodeFromString(TelemetryBundle.serializer(), file.readText(Charsets.UTF_8))
        }.getOrNull()
    }

    fun remove(file: File): Boolean = synchronized(lock) {
        !file.exists() || file.delete()
    }

    private fun ensureDirectory() {
        if (!queueDir.exists()) {
            queueDir.mkdirs()
        }
    }

    private fun writeAtomically(target: File, content: String) {
        val temp = File(queueDir, "${target.name}.tmp")
        temp.writeText(content, Charsets.UTF_8)
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
    }

    private fun loadFingerprintsLocked(nowMs: Long): Map<String, Long> {
        val file = File(queueDir, FINGERPRINTS_FILE_NAME)
        if (!file.isFile) return emptyMap()
        val decoded = runCatching {
            json.decodeFromString<Map<String, Long>>(file.readText(Charsets.UTF_8))
        }.getOrDefault(emptyMap())
        return decoded.filterValues { recordedAt -> nowMs - recordedAt < dedupWindowMs }
    }

    private fun saveFingerprintsLocked(fingerprints: Map<String, Long>) {
        val trimmed = if (fingerprints.size <= MAX_TRACKED_FINGERPRINTS) {
            fingerprints
        } else {
            fingerprints.entries
                .sortedByDescending { it.value }
                .take(MAX_TRACKED_FINGERPRINTS)
                .associate { it.key to it.value }
        }
        writeAtomically(
            target = File(queueDir, FINGERPRINTS_FILE_NAME),
            content = json.encodeToString(trimmed),
        )
    }

    private fun pruneQueueLocked() {
        val pending = queueDir.listFiles()
            ?.filter { file ->
                file.isFile &&
                    file.extension.equals("json", ignoreCase = true) &&
                    file.name != FINGERPRINTS_FILE_NAME
            }
            ?.sortedBy(File::getName)
            .orEmpty()
        val excess = pending.size - maxQueuedBundles
        if (excess > 0) {
            pending.take(excess).forEach(File::delete)
        }
    }

    private companion object {
        const val FINGERPRINTS_FILE_NAME = "fingerprints.json"
        const val DEFAULT_DEDUP_WINDOW_MS = 24L * 60L * 60L * 1_000L
        const val DEFAULT_MAX_QUEUED_BUNDLES = 50
        const val MAX_TRACKED_FINGERPRINTS = 200
    }
}
