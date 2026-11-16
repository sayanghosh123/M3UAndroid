package com.m3u.data.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.m3u.core.foundation.architecture.preferences.PreferencesKeys
import com.m3u.core.foundation.architecture.preferences.Settings
import com.m3u.core.foundation.architecture.preferences.get
import com.m3u.data.telemetry.DriveUploadResult
import com.m3u.data.telemetry.GoogleDriveTelemetryClient
import com.m3u.data.telemetry.TelemetryCoordinator
import com.m3u.data.telemetry.TelemetryCredentialStore
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber

/**
 * Uploads queued redacted diagnostic bundles from `filesDir/telemetry_queue/` into the
 * user's restricted Google Drive folder and deletes local copies upon HTTP 2xx confirmation.
 */
@HiltWorker
class TelemetryUploadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val settings: Settings,
    private val telemetryCoordinator: TelemetryCoordinator,
    private val credentialStore: TelemetryCredentialStore,
    private val driveClient: GoogleDriveTelemetryClient,
) : CoroutineWorker(context, params) {
    private val timber = Timber.tag("TelemetryUploadWorker")

    override suspend fun doWork(): Result {
        val enabled = settings.get(PreferencesKeys.TELEMETRY_DRIVE_ENABLED)
        val folderId = settings.get(PreferencesKeys.TELEMETRY_DRIVE_FOLDER_ID).trim()
        val serviceAccount = credentialStore.loadServiceAccountKey()
        if (!enabled || folderId.isEmpty() || serviceAccount == null) {
            telemetryCoordinator.refreshStatus()
            return Result.success()
        }

        val pendingFiles = telemetryCoordinator.listPendingFiles()
        if (pendingFiles.isEmpty()) {
            telemetryCoordinator.refreshStatus()
            return Result.success()
        }

        for (file in pendingFiles) {
            val contentJson = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: continue
            val fingerprint = telemetryCoordinator.readBundle(file)?.fingerprint ?: file.nameWithoutExtension
            when (
                val outcome = driveClient.uploadBundle(
                    serviceAccount = serviceAccount,
                    folderId = folderId,
                    fileName = file.name,
                    contentJson = contentJson,
                    fingerprint = fingerprint,
                )
            ) {
                is DriveUploadResult.Success -> {
                    telemetryCoordinator.markUploaded(file)
                    credentialStore.recordUploadSuccess(System.currentTimeMillis())
                }

                is DriveUploadResult.RetryableFailure -> {
                    timber.w("Retryable Drive upload failure (${outcome.statusCode}): ${outcome.message}")
                    credentialStore.recordUploadError(outcome.message)
                    telemetryCoordinator.refreshStatus()
                    return Result.retry()
                }

                is DriveUploadResult.PermanentFailure -> {
                    timber.e("Permanent Drive upload failure (${outcome.statusCode}): ${outcome.message}")
                    credentialStore.recordUploadError(outcome.message)
                    telemetryCoordinator.refreshStatus()
                    return Result.failure()
                }
            }
        }

        telemetryCoordinator.refreshStatus()
        return Result.success()
    }

    companion object {
        const val UNIQUE_WORK_NAME = "telemetry-drive-upload"
        const val WORK_TAG = "telemetry-drive-upload"
    }
}
