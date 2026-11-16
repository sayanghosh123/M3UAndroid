package com.m3u.smartphone.ui.common.helper

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.net.toUri
import com.m3u.data.service.ExternalPlaybackTarget
import com.m3u.i18n.R

/**
 * Builds and dispatches `ACTION_VIEW` intents compatible with VLC (`org.videolan.vlc`),
 * MX Player, and generic Android video players.
 */
object ExternalPlayerLauncher {
    fun buildIntent(target: ExternalPlaybackTarget): Intent =
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(target.url.toUri(), target.mimeType)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("title", target.title)
            putExtra(Intent.EXTRA_TITLE, target.title)
            val headers = target.toIntentHeadersArray()
            if (headers.isNotEmpty()) {
                putExtra("headers", headers)
            }
            target.userAgent?.takeIf { it.isNotBlank() }?.let { putExtra("user-agent", it) }
            target.referer?.takeIf { it.isNotBlank() }?.let { putExtra("referer", it) }
        }

    fun launch(
        context: Context,
        target: ExternalPlaybackTarget,
        chooserTitle: String = context.getString(R.string.feat_channel_open_in_external_app),
    ): Boolean {
        val intent = buildIntent(target)
        val chooser = Intent.createChooser(intent, chooserTitle).apply {
            if (context !is Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        return try {
            context.startActivity(chooser)
            true
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(
                context,
                R.string.feat_channel_external_player_not_found,
                Toast.LENGTH_SHORT,
            ).show()
            false
        }
    }
}
