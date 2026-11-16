package com.m3u.data.telemetry

import android.os.Debug
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Samples the main thread stack trace on a background thread when the UI looper stalls past
 * [STALL_THRESHOLD_MS], capturing redacted ANR diagnostic bundles without blocking the main looper.
 */
@Singleton
class MainThreadWatchdog @Inject constructor(
    private val telemetryCoordinator: TelemetryCoordinator,
) {
    private val timber = Timber.tag("MainThreadWatchdog")
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val started = AtomicBoolean(false)
    private var monitorJob: Job? = null

    fun start() {
        if (!started.compareAndSet(false, true)) return
        monitorJob = scope.launch {
            var lastCaptureAtMs = 0L
            while (isActive) {
                if (Debug.isDebuggerConnected()) {
                    delay(CHECK_INTERVAL_MS)
                    continue
                }
                val pingHandled = AtomicBoolean(false)
                val postedAtMs = System.currentTimeMillis()
                mainHandler.post { pingHandled.set(true) }
                delay(CHECK_INTERVAL_MS)
                if (!pingHandled.get()) {
                    // Continue waiting up to STALL_THRESHOLD_MS before sampling the stalled stack.
                    delay(STALL_THRESHOLD_MS - CHECK_INTERVAL_MS)
                    val nowMs = System.currentTimeMillis()
                    if (
                        !pingHandled.get() &&
                        !Debug.isDebuggerConnected() &&
                        nowMs - lastCaptureAtMs >= COOLDOWN_MS
                    ) {
                        val stallDurationMs = nowMs - postedAtMs
                        val mainThread = Looper.getMainLooper().thread
                        val stackTrace = buildString {
                            appendLine("Main thread (${mainThread.name}, state=${mainThread.state}) stalled for ${stallDurationMs}ms:")
                            mainThread.stackTrace.forEach { element ->
                                append("    at ")
                                appendLine(element.toString())
                            }
                        }
                        lastCaptureAtMs = nowMs
                        timber.w("Main thread stall detected (%dms); queuing diagnostic bundle", stallDurationMs)
                        telemetryCoordinator.captureAnrStall(
                            mainThreadStackTrace = stackTrace,
                            stallDurationMs = stallDurationMs,
                        )
                    }
                }
            }
        }
    }

    fun stop() {
        started.set(false)
        monitorJob?.cancel()
        monitorJob = null
    }

    private companion object {
        const val CHECK_INTERVAL_MS = 1_500L
        const val STALL_THRESHOLD_MS = 3_000L
        const val COOLDOWN_MS = 30_000L
    }
}
