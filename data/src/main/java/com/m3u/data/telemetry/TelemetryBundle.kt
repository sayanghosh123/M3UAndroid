package com.m3u.data.telemetry

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Serializable
data class TelemetryBundle(
    val schemaVersion: Int = 1,
    val id: String,
    val eventType: String,
    val fingerprint: String,
    val createdAtIso: String,
    val createdAtEpochMs: Long,
    val threadName: String,
    val exceptionClass: String,
    val topAppStackFrame: String,
    val summary: String,
    val stackTrace: String,
    val device: TelemetryDeviceInfo,
    val memoryAndStorage: TelemetryMemoryInfo,
    val network: TelemetryNetworkInfo,
    val playerState: TelemetryPlayerInfo,
    val logTail: List<String>,
    val markdownReport: String,
)

@Serializable
data class TelemetryDeviceInfo(
    val applicationId: String,
    val versionName: String,
    val versionCode: Int,
    val manufacturer: String,
    val model: String,
    val androidRelease: String,
    val sdkInt: Int,
    val abi: String,
)

@Serializable
data class TelemetryMemoryInfo(
    val availableSystemRamBytes: Long,
    val totalSystemRamBytes: Long,
    val lowMemory: Boolean,
    val jvmHeapUsedBytes: Long,
    val jvmHeapMaxBytes: Long,
    val internalStorageAvailableBytes: Long,
    val internalStorageTotalBytes: Long,
)

@Serializable
data class TelemetryNetworkInfo(
    val connected: Boolean,
    val transports: List<String>,
    val validated: Boolean,
    val metered: Boolean,
)

@Serializable
data class TelemetryPlayerInfo(
    val playbackState: String,
    val isPlaying: Boolean,
    val channelTitle: String? = null,
    val streamFormat: String? = null,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val videoResolution: String? = null,
    val httpResponseCode: Int? = null,
    val bufferedDurationMs: Long = 0L,
    val totalBufferedDurationMs: Long = 0L,
    val lastErrorCodeName: String? = null,
    val lastErrorMessage: String? = null,
)

@Immutable
data class TelemetryStatusSnapshot(
    val pendingCount: Int = 0,
    val serviceAccountEmail: String? = null,
    val lastUploadAtEpochMs: Long? = null,
    val lastUploadError: String? = null,
)

enum class TelemetryEventType(val wireName: String) {
    UNCAUGHT_EXCEPTION("UNCAUGHT_EXCEPTION"),
    ANR_WATCHDOG("ANR_WATCHDOG"),
    LEAK_CANARY("LEAK_CANARY"),
    PLAYBACK_ERROR("PLAYBACK_ERROR"),
    MANUAL_DIAGNOSTIC("MANUAL_DIAGNOSTIC"),
}
