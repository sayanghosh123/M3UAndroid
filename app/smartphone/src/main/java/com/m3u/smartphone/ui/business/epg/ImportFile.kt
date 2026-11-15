package com.m3u.smartphone.ui.business.epg

import android.content.ContentResolver
import android.net.Uri

private const val MAX_IMPORT_CHARACTERS = 1_000_000

fun ContentResolver.readImportText(uri: Uri): String =
    openInputStream(uri)?.bufferedReader()?.use { reader ->
        buildString {
            val buffer = CharArray(8_192)
            while (true) {
                val count = reader.read(buffer)
                if (count < 0) break
                require(length + count <= MAX_IMPORT_CHARACTERS) { "Import file is too large" }
                append(buffer, 0, count)
            }
        }
    } ?: error("Unable to read import file")
