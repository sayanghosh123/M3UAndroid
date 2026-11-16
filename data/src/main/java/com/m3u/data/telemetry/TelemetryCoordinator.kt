package com.m3u.data.telemetry

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.StatFs
import androidx.media3.common.Player
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.m3u.core.foundation.architecture.Publisher
import com.m3u.core.foundation.architecture.preferences.PreferencesKeys
import com.m3u.core.foundation.architecture.preferences.Settings
import com.m3u.core.foundation.architecture.preferences.get
import com.m3u.data.service.AppLogger
import com.m3u.data.service.PlayerManager
import com.m3u.data.service.PlayerTelemetrySnapshot
import com.m3u.data.worker.TelemetryUploadWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Coordinates crash/ANR/LeakCanary/manual diagnostic capture, credential redaction, on-disk
 * queuing in `filesDir/telemetry_queue/`, and WorkManager upload scheduling.
 */
@Singleton
class TelemetryCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val publisher: Publisher,
    private val settings: Settings,
    private val appLogger: AppLogger,
    private val playerManagerProvider: Provider<PlayerManager>,
    private val credentialStore: TelemetryCredentialStore,
) {
    private val ioDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    private val queueStore: TelemetryQueueStore by lazy {
        TelemetryQueueStore(File(context.filesDir, QUEUE_DIRECTORY_NAME))
    }

    private val _status = MutableStateFlow(TelemetryStatusSnapshot())
    val status: StateFlow<TelemetryStatusSnapshot> = _status.asStateFlow()

    init {
        scope.launch {
            refreshStatusLocked()
        }
    }

    /**
     * Captures an uncaught exception strictly off the crashing UI/player thread on a dedicated
     * daemon thread with a bounded latch before process termination.
     */
    fun captureUncaughtException(
        thread: Thread,
        throwable: Throwable,
    ) {
        val stackTrace = throwable.stackTraceString()
        val summary = throwable.message?.takeIf { it.isNotBlank() }
            ?: throwable::class.java.simpleName
        val latch = CountDownLatch(1)
        val worker = Thread(
            {
                runCatching {
                    enqueueBundleSync(
                        eventType = TelemetryEventType.UNCAUGHT_EXCEPTION,
                        threadName = thread.name,
                        exceptionClass = throwable::class.java.name,
                        summary = summary,
                        rawStackTrace = stackTrace,
                        allowDuplicate = false,
                    )
                }
                latch.countDown()
            },
            "m3u-crash-telemetry",
        ).apply { isDaemon = true }
        worker.start()
        runCatching {
            latch.await(CRASH_PERSISTENCE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }
    }

    suspend fun captureAnrStall(
        mainThreadStackTrace: String,
        stallDurationMs: Long,
    ): Boolean = withContext(ioDispatcher) {
        val written = enqueueBundleSync(
            eventType = TelemetryEventType.ANR_WATCHDOG,
            threadName = "main",
            exceptionClass = "MainThreadWatchdogTimeout",
            summary = "Main thread stalled for ${stallDurationMs}ms",
            rawStackTrace = mainThreadStackTrace,
            allowDuplicate = false,
        )
        if (written) {
            scheduleUploadIfConfigured(expedite = false)
        }
        written
    }

    fun captureLeakAnalysis(
        signature: String,
        report: String,
    ) {
        scope.launch {
            val written = enqueueBundleSync(
                eventType = TelemetryEventType.LEAK_CANARY,
                threadName = Thread.currentThread().name,
                exceptionClass = signature.ifBlank { "LeakCanaryAnalysis" },
                summary = "LeakCanary analysis recorded ($signature)",
                rawStackTrace = report,
                allowDuplicate = false,
            )
            if (written) {
                scheduleUploadIfConfigured(expedite = false)
            }
        }
    }

    suspend fun captureManualDiagnostic(): Boolean = withContext(ioDispatcher) {
        val written = enqueueBundleSync(
            eventType = TelemetryEventType.MANUAL_DIAGNOSTIC,
            threadName = Thread.currentThread().name,
            exceptionClass = "ManualDiagnosticSnapshot",
            summary = "User-requested telemetry snapshot",
            rawStackTrace = Throwable("Manual diagnostic snapshot").stackTraceString(),
            allowDuplicate = true,
        )
        if (written) {
            scheduleUploadIfConfigured(expedite = true)
        }
        written
    }

    suspend fun importServiceAccountJson(rawJson: String): Result<String> = withContext(ioDispatcher) {
        runCatching {
            val parsed = credentialStore.importServiceAccountJson(rawJson)
            refreshStatusLocked()
            parsed.clientEmail
        }
    }

    suspend fun clearServiceAccount() = withContext(ioDispatcher) {
        credentialStore.clearServiceAccount()
        refreshStatusLocked()
    }

    suspend fun triggerUploadNow() = withContext(ioDispatcher) {
        scheduleUploadIfConfigured(expedite = true, force = true)
    }

    fun schedulePendingUploadsAsync() {
        scope.launch {
            scheduleUploadIfConfigured(expedite = false, force = false)
        }
    }

    suspend fun refreshStatus() = withContext(ioDispatcher) {
        refreshStatusLocked()
    }

    fun listPendingFiles(): List<File> = queueStore.listPendingFiles()

    fun readBundle(file: File): TelemetryBundle? = queueStore.readBundle(file)

    fun markUploaded(file: File) {
        queueStore.remove(file)
        refreshStatusLocked()
    }

    suspend fun scheduleUploadIfConfigured(
        expedite: Boolean = false,
        force: Boolean = false,
    ) {
        val enabled = settings.get(PreferencesKeys.TELEMETRY_DRIVE_ENABLED)
        val folderId = settings.get(PreferencesKeys.TELEMETRY_DRIVE_FOLDER_ID).trim()
        val hasAccount = !credentialStore.serviceAccountEmail().isNullOrBlank()
        if (!enabled || folderId.isEmpty() || !hasAccount) {
            return
        }
        if (!force && queueStore.pendingCount() == 0) {
            return
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<TelemetryUploadWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TelemetryUploadWorker.WORK_TAG)
            .build()
        val policy = if (expedite) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
        WorkManager.getInstance(context).enqueueUniqueWork(
            TelemetryUploadWorker.UNIQUE_WORK_NAME,
            policy,
            request,
        )
    }

    private fun enqueueBundleSync(
        eventType: TelemetryEventType,
        threadName: String,
        exceptionClass: String,
        summary: String,
        rawStackTrace: String,
        allowDuplicate: Boolean,
    ): Boolean {
        val redactedStack = CredentialRedactor.redact(rawStackTrace)
        val sanitizedClass = CredentialRedactor.redact(exceptionClass)
        val topFrame = DiagnosticFingerprint.topAppStackFrame(redactedStack)
        val fingerprint = DiagnosticFingerprint.compute(
            eventType = eventType.wireName,
            exceptionClass = sanitizedClass,
            topAppStackFrame = topFrame,
        )
        val nowMs = System.currentTimeMillis()
        val createdAtIso = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(nowMs))
        val device = collectDeviceInfo()
        val memory = collectMemoryInfo()
        val network = collectNetworkInfo()
        val player = collectPlayerInfo()
        val logsTail = appLogger.recentEntries(LOG_TAIL_LIMIT).map(CredentialRedactor::redact)
        val redactedSummary = CredentialRedactor.redact(summary)

        val markdownReport = buildMarkdownReport(
            eventType = eventType.wireName,
            fingerprint = fingerprint,
            createdAtIso = createdAtIso,
            threadName = threadName,
            exceptionClass = sanitizedClass,
            topFrame = topFrame,
            summary = redactedSummary,
            device = device,
            memory = memory,
            network = network,
            player = player,
            stackTrace = redactedStack,
            logsTail = logsTail,
        )

        val bundle = TelemetryBundle(
            id = UUID.randomUUID().toString(),
            eventType = eventType.wireName,
            fingerprint = fingerprint,
            createdAtIso = createdAtIso,
            createdAtEpochMs = nowMs,
            threadName = CredentialRedactor.redact(threadName),
            exceptionClass = sanitizedClass,
            topAppStackFrame = topFrame,
            summary = redactedSummary,
            stackTrace = redactedStack,
            device = device,
            memoryAndStorage = memory,
            network = network,
            playerState = player,
            logTail = logsTail,
            markdownReport = markdownReport,
        )

        val enqueuedFile = queueStore.enqueue(bundle, allowDuplicate = allowDuplicate)
        refreshStatusLocked()
        return enqueuedFile != null
    }

    private fun refreshStatusLocked() {
        _status.value = TelemetryStatusSnapshot(
            pendingCount = queueStore.pendingCount(),
            serviceAccountEmail = credentialStore.serviceAccountEmail(),
            lastUploadAtEpochMs = credentialStore.lastUploadAtEpochMs(),
            lastUploadError = credentialStore.lastUploadError(),
        )
    }

    private fun collectDeviceInfo(): TelemetryDeviceInfo = TelemetryDeviceInfo(
        applicationId = publisher.applicationId,
        versionName = publisher.versionName,
        versionCode = publisher.versionCode,
        manufacturer = Build.MANUFACTURER.orEmpty(),
        model = Build.MODEL.orEmpty(),
        androidRelease = Build.VERSION.RELEASE.orEmpty(),
        sdkInt = Build.VERSION.SDK_INT,
        abi = Build.SUPPORTED_ABIS.firstOrNull() ?: publisher.abi.value,
    )

    private fun collectMemoryInfo(): TelemetryMemoryInfo {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memoryInfo)

        val runtime = Runtime.getRuntime()
        val heapUsed = (runtime.totalMemory() - runtime.freeMemory()).coerceAtLeast(0L)
        val heapMax = runtime.maxMemory().coerceAtLeast(0L)

        val statFs = runCatching { StatFs(context.filesDir.absolutePath) }.getOrNull()
        return TelemetryMemoryInfo(
            availableSystemRamBytes = memoryInfo.availMem,
            totalSystemRamBytes = memoryInfo.totalMem,
            lowMemory = memoryInfo.lowMemory,
            jvmHeapUsedBytes = heapUsed,
            jvmHeapMaxBytes = heapMax,
            internalStorageAvailableBytes = statFs?.availableBytes ?: 0L,
            internalStorageTotalBytes = statFs?.totalBytes ?: 0L,
        )
    }

    private fun collectNetworkInfo(): TelemetryNetworkInfo {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return TelemetryNetworkInfo(
                connected = false,
                transports = emptyList(),
                validated = false,
                metered = true,
            )
        val activeNetwork = cm.activeNetwork
        val capabilities = activeNetwork?.let(cm::getNetworkCapabilities)
        if (capabilities == null) {
            return TelemetryNetworkInfo(
                connected = false,
                transports = emptyList(),
                validated = false,
                metered = true,
            )
        }
        val transports = buildList {
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("WIFI")
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("CELLULAR")
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("ETHERNET")
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("VPN")
        }
        return TelemetryNetworkInfo(
            connected = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            transports = transports,
            validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            metered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
        )
    }

    private fun collectPlayerInfo(): TelemetryPlayerInfo {
        val pm = runCatching { playerManagerProvider.get() }.getOrNull()
        val snapshot: PlayerTelemetrySnapshot = pm?.telemetrySnapshot?.value ?: PlayerTelemetrySnapshot()
        val channelTitle = pm?.channel?.value?.title?.let(CredentialRedactor::redact)
        val playbackException = pm?.playbackException?.value
        val stateName = when (snapshot.playbackState) {
            Player.STATE_IDLE -> "IDLE"
            Player.STATE_BUFFERING -> "BUFFERING"
            Player.STATE_READY -> "READY"
            Player.STATE_ENDED -> "ENDED"
            else -> "UNKNOWN(${snapshot.playbackState})"
        }
        return TelemetryPlayerInfo(
            playbackState = stateName,
            isPlaying = snapshot.isPlaying,
            channelTitle = channelTitle,
            streamFormat = CredentialRedactor.redactNullable(snapshot.streamFormat),
            videoCodec = CredentialRedactor.redactNullable(snapshot.videoCodec),
            audioCodec = CredentialRedactor.redactNullable(snapshot.audioCodec),
            videoResolution = CredentialRedactor.redactNullable(snapshot.resolution),
            httpResponseCode = snapshot.httpResponseCode,
            bufferedDurationMs = snapshot.totalBufferedDurationMs.coerceAtLeast(0L),
            totalBufferedDurationMs = snapshot.totalBufferedDurationMs.coerceAtLeast(0L),
            lastErrorCodeName = snapshot.lastErrorName,
            lastErrorMessage = playbackException?.message?.let(CredentialRedactor::redact),
        )
    }

    private fun buildMarkdownReport(
        eventType: String,
        fingerprint: String,
        createdAtIso: String,
        threadName: String,
        exceptionClass: String,
        topFrame: String,
        summary: String,
        device: TelemetryDeviceInfo,
        memory: TelemetryMemoryInfo,
        network: TelemetryNetworkInfo,
        player: TelemetryPlayerInfo,
        stackTrace: String,
        logsTail: List<String>,
    ): String = buildString {
        appendLine("# M3UAndroid Diagnostic Report ($eventType)")
        appendLine("- Timestamp: $createdAtIso")
        appendLine("- Fingerprint: `$fingerprint`")
        appendLine("- Thread: `$threadName`")
        appendLine("- Exception / Signature: `$exceptionClass`")
        appendLine("- Top App Frame: `$topFrame`")
        appendLine("- Summary: $summary")
        appendLine()
        appendLine("## Device & Memory")
        appendLine("- App: `${device.applicationId}` ${device.versionName} (${device.versionCode})")
        appendLine("- Device: ${device.manufacturer} ${device.model} | Android ${device.androidRelease} (API ${device.sdkInt}, ${device.abi})")
        appendLine("- RAM: ${memory.availableSystemRamBytes / BYTES_PER_MB}MB free / ${memory.totalSystemRamBytes / BYTES_PER_MB}MB total (lowMemory=${memory.lowMemory})")
        appendLine("- JVM Heap: ${memory.jvmHeapUsedBytes / BYTES_PER_MB}MB / ${memory.jvmHeapMaxBytes / BYTES_PER_MB}MB")
        appendLine("- Internal Storage: ${memory.internalStorageAvailableBytes / BYTES_PER_MB}MB free / ${memory.internalStorageTotalBytes / BYTES_PER_MB}MB total")
        appendLine("- Network: connected=${network.connected}, transports=${network.transports}, validated=${network.validated}, metered=${network.metered}")
        appendLine()
        appendLine("## Player State")
        appendLine("- State: ${player.playbackState}, isPlaying=${player.isPlaying}")
        appendLine("- Channel: ${player.channelTitle ?: "none"}")
        appendLine("- Format/Codecs: format=${player.streamFormat}, video=${player.videoCodec}, audio=${player.audioCodec}, res=${player.videoResolution}")
        appendLine("- Buffer: ${player.bufferedDurationMs}ms | HTTP=${player.httpResponseCode ?: "n/a"} | Error=${player.lastErrorCodeName ?: "none"}")
        appendLine()
        appendLine("## Stack Trace / Analysis")
        appendLine("```")
        appendLine(stackTrace.trimEnd())
        appendLine("```")
        if (logsTail.isNotEmpty()) {
            appendLine()
            appendLine("## Recent Redacted Logs (${logsTail.size})")
            appendLine("```")
            logsTail.forEach(::appendLine)
            appendLine("```")
        }
    }

    private fun Throwable.stackTraceString(): String {
        val writer = StringWriter()
        PrintWriter(writer).use { printStackTrace(it) }
        return writer.toString()
    }

    private companion object {
        const val QUEUE_DIRECTORY_NAME = "telemetry_queue"
        const val CRASH_PERSISTENCE_TIMEOUT_MS = 750L
        const val LOG_TAIL_LIMIT = 80
        const val BYTES_PER_MB = 1024L * 1024L
    }
}
