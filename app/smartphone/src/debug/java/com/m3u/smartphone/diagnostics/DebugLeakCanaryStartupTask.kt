package com.m3u.smartphone.diagnostics

import androidx.media3.common.Player
import androidx.work.WorkManager
import com.m3u.data.service.PlayerManager
import com.m3u.data.telemetry.TelemetryCoordinator
import com.m3u.smartphone.startup.ApplicationStartupTask
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import leakcanary.LeakCanary
import leakcanary.OnHeapAnalyzedListener
import shark.HeapAnalysis
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Debug-only startup hook that defers LeakCanary heap dumps while video playback or buffering is
 * active, and forwards heap analysis reports to [TelemetryCoordinator] on [Dispatchers.IO].
 */
@Singleton
class DebugLeakCanaryStartupTask @Inject constructor(
    private val playerManagerProvider: Provider<PlayerManager>,
    private val telemetryCoordinator: TelemetryCoordinator,
) : ApplicationStartupTask {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val configLock = Any()
    private val installed = AtomicBoolean(false)

    override fun enqueue(workManager: WorkManager) {
        if (!installed.compareAndSet(false, true)) return

        scope.launch {
            installListener()
            val playerManager = playerManagerProvider.get()
            combine(
                playerManager.isPlaying,
                playerManager.playbackState,
                playerManager.channel,
            ) { isPlaying, playbackState, channel ->
                val playbackActive = isPlaying ||
                    playbackState == Player.STATE_BUFFERING ||
                    channel != null
                !playbackActive
            }
                .distinctUntilChanged()
                .collect { dumpHeapAllowed ->
                    updateDumpHeap(dumpHeapAllowed)
                }
        }
    }

    private fun installListener() {
        synchronized(configLock) {
            val previousListener = LeakCanary.config.onHeapAnalyzedListener
            val telemetryListener = OnHeapAnalyzedListener { heapAnalysis: HeapAnalysis ->
                scope.launch(Dispatchers.IO) {
                    val report = heapAnalysis.toString()
                    val signature = extractLeakSignature(report)
                        ?: heapAnalysis::class.java.simpleName
                    telemetryCoordinator.captureLeakAnalysis(
                        signature = signature,
                        report = report,
                    )
                }
                previousListener.onHeapAnalyzed(heapAnalysis)
            }
            LeakCanary.config = LeakCanary.config.copy(
                onHeapAnalyzedListener = telemetryListener,
            )
        }
    }

    private fun updateDumpHeap(dumpHeapAllowed: Boolean) {
        synchronized(configLock) {
            if (LeakCanary.config.dumpHeap != dumpHeapAllowed) {
                LeakCanary.config = LeakCanary.config.copy(dumpHeap = dumpHeapAllowed)
            }
        }
    }

    private fun extractLeakSignature(report: String): String? =
        SIGNATURE_REGEX.find(report)?.groupValues?.getOrNull(1)

    private companion object {
        val SIGNATURE_REGEX = Regex("""(?m)^\s*Signature:\s*([a-fA-F0-9]+)""")
    }
}

@Module
@InstallIn(SingletonComponent::class)
interface DebugLeakCanaryStartupModule {
    @Binds
    @IntoSet
    fun bindDebugLeakCanaryStartupTask(
        task: DebugLeakCanaryStartupTask,
    ): ApplicationStartupTask
}
