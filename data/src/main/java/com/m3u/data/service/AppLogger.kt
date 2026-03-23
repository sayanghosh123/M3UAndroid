package com.m3u.data.service

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.m3u.core.architecture.Publisher
import com.m3u.core.architecture.preferences.LogLevel
import com.m3u.core.architecture.preferences.PreferencesKeys
import com.m3u.core.architecture.preferences.Settings
import com.m3u.core.architecture.preferences.flowOf
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

data class AppLogSnapshot(
    val fileCount: Int = 0,
    val totalBytes: Long = 0L
)

@Singleton
class AppLogger @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: Settings,
    private val publisher: Publisher
) : Timber.Tree() {
    private val dispatcher = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val lock = Any()
    private val commands = Channel<LogCommand>(Channel.UNLIMITED)
    private val logsDir = (
            context.getExternalFilesDir("logs")
                ?: File(context.filesDir, "logs")
            ).apply { mkdirs() }
    private val exportsDir = File(context.cacheDir, "log-exports").apply { mkdirs() }

    private val _snapshot = MutableStateFlow(AppLogSnapshot())
    val snapshot: StateFlow<AppLogSnapshot> get() = _snapshot.asStateFlow()

    @Volatile
    private var minPriority: Int = LogLevel.ERROR

    init {
        scope.launch {
            settings.flowOf(PreferencesKeys.LOG_LEVEL).collect {
                minPriority = it
            }
        }
        synchronized(lock) {
            refreshSnapshotLocked()
        }
        scope.launch {
            for (command in commands) {
                when (command) {
                    is LogCommand.Write -> runCatching {
                        synchronized(lock) {
                            writeEntryLocked(
                                priority = command.priority,
                                tag = command.tag,
                                message = command.message,
                                throwable = command.throwable
                            )
                        }
                    }.onFailure {
                        Log.e("AppLogger", "Failed to persist log entry", it)
                    }

                    is LogCommand.Clear -> runCatching {
                        synchronized(lock) {
                            clearLogsLocked()
                        }
                    }.onSuccess {
                        command.reply.complete(Unit)
                    }.onFailure {
                        Log.e("AppLogger", "Failed to clear logs", it)
                        command.reply.completeExceptionally(it)
                    }

                    is LogCommand.Export -> runCatching {
                        synchronized(lock) {
                            createEmailAttachmentLocked()
                        }
                    }.onSuccess {
                        command.reply.complete(it)
                    }.onFailure {
                        Log.e("AppLogger", "Failed to export logs", it)
                        command.reply.completeExceptionally(it)
                    }
                }
            }
        }
    }

    override fun log(
        priority: Int,
        tag: String?,
        message: String,
        t: Throwable?
    ) {
        if (priority < minPriority) return
        val result = commands.trySend(
            LogCommand.Write(
                priority = priority,
                tag = tag ?: "App",
                message = message,
                throwable = t
            )
        )
        if (result.isFailure) {
            synchronized(lock) {
                writeEntryLocked(
                    priority = priority,
                    tag = tag ?: "App",
                    message = message,
                    throwable = t
                )
            }
        }
    }

    fun recordException(
        source: String,
        throwable: Throwable
    ) {
        synchronized(lock) {
            writeEntryLocked(
                priority = Log.ERROR,
                tag = source,
                message = throwable.message?.takeIf { it.isNotBlank() } ?: source,
                throwable = throwable
            )
        }
    }

    suspend fun clearLogs() {
        val reply = CompletableDeferred<Unit>()
        commands.send(LogCommand.Clear(reply))
        reply.await()
    }

    suspend fun createEmailAttachment(): Uri? {
        val reply = CompletableDeferred<Uri?>()
        commands.send(LogCommand.Export(reply))
        return reply.await()
    }

    private fun clearLogsLocked() {
        logFilesLocked().forEach(File::delete)
        exportsDir.listFiles()
            ?.filter { it.extension.equals("zip", ignoreCase = true) }
            ?.forEach(File::delete)
        refreshSnapshotLocked()
    }

    private fun createEmailAttachmentLocked(): Uri? {
        val files = logFilesLocked()
        if (files.isEmpty()) return null

        exportsDir.mkdirs()
        pruneExportsLocked()

        val zipFile = File(
            exportsDir,
            "m3uandroid-logs-${System.currentTimeMillis()}.zip"
        )
        ZipOutputStream(FileOutputStream(zipFile)).use { zip ->
            zip.putNextEntry(ZipEntry("summary.txt"))
            zip.write(createSummary(files).toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            files.forEach { file ->
                zip.putNextEntry(ZipEntry(file.name))
                file.inputStream().use { input ->
                    input.copyTo(zip)
                }
                zip.closeEntry()
            }
        }

        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            zipFile
        )
    }

    private fun writeEntryLocked(
        priority: Int,
        tag: String,
        message: String,
        throwable: Throwable?
    ) {
        logsDir.mkdirs()
        val currentLog = currentLogFileLocked()
        val file = currentLog.file
        val createdEntryFile = file.length() == 0L
        val line = buildString {
            append(timestampFormatter.format(ZonedDateTime.now()))
            append(' ')
            append(priorityLabel(priority))
            append('/')
            append(tag)
            append(": ")
            appendLine(message)
            throwable?.let {
                appendLine(it.toStackTraceString())
            }
        }.toByteArray(Charsets.UTF_8)
        file.appendBytes(line)
        if (currentLog.structureChanged) {
            refreshSnapshotLocked()
        } else {
            updateSnapshotLocked(
                fileDelta = if (createdEntryFile) 1 else 0,
                byteDelta = line.size.toLong()
            )
        }
    }

    private fun createSummary(files: List<File>): String = buildString {
        appendLine("repository=${publisher.repository}")
        appendLine("applicationId=${publisher.applicationId}")
        appendLine("versionName=${publisher.versionName}")
        appendLine("versionCode=${publisher.versionCode}")
        appendLine("model=${publisher.model}")
        appendLine("abi=${publisher.abi.value}")
        appendLine("logLevel=${priorityLabel(minPriority)}")
        appendLine("exportedAt=${timestampFormatter.format(ZonedDateTime.now())}")
        appendLine("logFiles=${files.size}")
        appendLine("totalBytes=${files.sumOf(File::length)}")
    }

    private fun currentLogFileLocked(): CurrentLogFile {
        val current = File(logsDir, "current.log")
        var structureChanged = false
        if (current.exists() && current.length() >= maxLogFileBytes) {
            val archived = File(
                logsDir,
                "log-${fileTimestampFormatter.format(ZonedDateTime.now())}.log"
            )
            if (!current.renameTo(archived)) {
                current.copyTo(archived, overwrite = true)
                current.delete()
            }
            structureChanged = true
        }
        if (!current.exists()) {
            current.createNewFile()
        }
        if (pruneLogsLocked()) {
            structureChanged = true
        }
        return CurrentLogFile(
            file = current,
            structureChanged = structureChanged
        )
    }

    private fun pruneLogsLocked(): Boolean {
        val expired = logFilesLocked().drop(maxLogFiles)
        expired.forEach(File::delete)
        return expired.isNotEmpty()
    }

    private fun pruneExportsLocked() {
        exportsDir.listFiles()
            ?.filter { it.extension.equals("zip", ignoreCase = true) }
            ?.sortedByDescending(File::lastModified)
            ?.drop(maxExportFiles)
            ?.forEach(File::delete)
    }

    private fun refreshSnapshotLocked() {
        _snapshot.value = readSnapshotLocked()
    }

    private fun updateSnapshotLocked(
        fileDelta: Int = 0,
        byteDelta: Long = 0L
    ) {
        _snapshot.value = _snapshot.value.copy(
            fileCount = (_snapshot.value.fileCount + fileDelta).coerceAtLeast(0),
            totalBytes = (_snapshot.value.totalBytes + byteDelta).coerceAtLeast(0L)
        )
    }

    private fun readSnapshotLocked(): AppLogSnapshot {
        val files = logFilesLocked()
        return AppLogSnapshot(
            fileCount = files.size,
            totalBytes = files.sumOf(File::length)
        )
    }

    private fun logFilesLocked(): List<File> {
        return logsDir.listFiles()
            ?.filter {
                it.isFile &&
                        it.extension.equals("log", ignoreCase = true) &&
                        it.length() > 0L
            }
            ?.sortedByDescending(File::lastModified)
            ?: emptyList()
    }

    private fun priorityLabel(priority: Int): String = when (priority) {
        Log.ERROR -> "ERROR"
        Log.WARN -> "WARN"
        Log.INFO -> "INFO"
        Log.DEBUG -> "DEBUG"
        Log.VERBOSE -> "VERBOSE"
        else -> "LOG"
    }

    private fun Throwable.toStackTraceString(): String {
        val writer = StringWriter()
        PrintWriter(writer).use { printer ->
            printStackTrace(printer)
        }
        return writer.toString()
    }

    companion object {
        private const val maxLogFiles = 7
        private const val maxExportFiles = 3
        private const val maxLogFileBytes = 1024 * 1024L
        private val timestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS Z")
        private val fileTimestampFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
    }
}

private sealed interface LogCommand {
    data class Write(
        val priority: Int,
        val tag: String,
        val message: String,
        val throwable: Throwable?
    ) : LogCommand

    data class Clear(
        val reply: CompletableDeferred<Unit>
    ) : LogCommand

    data class Export(
        val reply: CompletableDeferred<Uri?>
    ) : LogCommand
}

private data class CurrentLogFile(
    val file: File,
    val structureChanged: Boolean
)
