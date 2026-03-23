package com.m3u.data.repository.media

import android.graphics.drawable.Drawable
import android.net.Uri
import io.ktor.utils.io.ByteReadChannel
import java.io.InputStream
import java.io.OutputStream

data class SavedPicture(
    val displayPath: String,
    val uri: Uri
)

enum class ApkInstallResult {
    Started,
    PermissionRequired
}

interface MediaRepository {
    suspend fun savePicture(url: String): SavedPicture
    fun openOutputStream(uri: Uri): OutputStream?
    fun openInputStream(uri: Uri): InputStream?

    suspend fun loadDrawable(url: String): Drawable?
    suspend fun installApk(channel: ByteReadChannel): ApkInstallResult
}
