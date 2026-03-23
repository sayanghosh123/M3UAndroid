package com.m3u.data.repository.media

import android.content.Context
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toBitmap
import android.provider.MediaStore
import coil.Coil
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.SuccessResult
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.util.cio.writeChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.copyAndClose
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject

private const val BITMAP_QUALITY = 100

internal class MediaRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : MediaRepository {
    private val applicationName = "M3U"
    private val legacyPictureDirectory = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
        applicationName
    )
    private val apkInstallDirectory = File(context.cacheDir, "apks")

    override suspend fun savePicture(url: String): SavedPicture = withContext(Dispatchers.IO) {
        val drawable = checkNotNull(loadDrawable(url))
        val bitmap = drawable.toBitmap()
        val name = "Picture_${System.currentTimeMillis()}.png"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val relativePath = "${Environment.DIRECTORY_PICTURES}${File.separator}$applicationName"
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = checkNotNull(
                resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            )
            try {
                resolver.openOutputStream(uri)?.buffered().use { output ->
                    val stream = checkNotNull(output)
                    bitmap.compress(Bitmap.CompressFormat.PNG, BITMAP_QUALITY, stream)
                    stream.flush()
                }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (throwable: Throwable) {
                resolver.delete(uri, null, null)
                throw throwable
            }
            SavedPicture(
                displayPath = "$relativePath${File.separator}$name",
                uri = uri
            )
        } else {
            legacyPictureDirectory.mkdirs()
            val file = File(legacyPictureDirectory, name)
            file.outputStream().buffered().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, BITMAP_QUALITY, it)
                it.flush()
            }
            MediaScannerConnection.scanFile(
                context,
                arrayOf(file.absolutePath),
                arrayOf("image/png"),
                null
            )
            SavedPicture(
                displayPath = file.absolutePath,
                uri = Uri.fromFile(file)
            )
        }
    }

    override suspend fun installApk(channel: ByteReadChannel): ApkInstallResult = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            val settingsIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(settingsIntent)
            channel.cancel(null)
            return@withContext ApkInstallResult.PermissionRequired
        }
        apkInstallDirectory.mkdirs()
        apkInstallDirectory.listFiles()
            ?.filter { it.isFile && it.extension.equals("apk", ignoreCase = true) }
            ?.forEach(File::delete)
        val file = File(apkInstallDirectory, "update.apk")
        channel.copyAndClose(file.writeChannel())
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
        ApkInstallResult.Started
    }

    override suspend fun loadDrawable(url: String): Drawable? = withContext(Dispatchers.IO) {
        val loader = Coil.imageLoader(context)
        val request: ImageRequest = ImageRequest.Builder(context)
            .data(url)
            .build()
        when (val result = loader.execute(request)) {
            is SuccessResult -> result.drawable
            is ErrorResult -> throw result.throwable
        }
    }

    override fun openOutputStream(uri: Uri): OutputStream? {
        return context.contentResolver.openOutputStream(uri)
    }

    override fun openInputStream(uri: Uri): InputStream? {
        return context.contentResolver.openInputStream(uri)
    }
}
