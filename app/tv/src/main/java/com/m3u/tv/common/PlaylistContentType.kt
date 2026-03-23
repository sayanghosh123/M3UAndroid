package com.m3u.tv.common

import androidx.annotation.StringRes
import com.m3u.data.database.model.Playlist
import com.m3u.data.database.model.isSeries
import com.m3u.data.database.model.isVod
import com.m3u.i18n.R

internal enum class PlaylistContentType(
    @StringRes val labelResId: Int,
    @StringRes val sectionTitleResId: Int,
    @StringRes val primaryActionResId: Int,
    val opensDetails: Boolean,
) {
    Live(
        labelResId = R.string.ui_content_type_live,
        sectionTitleResId = R.string.ui_playlist_section_live,
        primaryActionResId = R.string.ui_action_play_live,
        opensDetails = false
    ),
    Vod(
        labelResId = R.string.ui_content_type_vod,
        sectionTitleResId = R.string.ui_playlist_section_vod,
        primaryActionResId = R.string.ui_action_play_vod,
        opensDetails = true
    ),
    Series(
        labelResId = R.string.ui_content_type_series,
        sectionTitleResId = R.string.ui_playlist_section_series,
        primaryActionResId = R.string.ui_action_play_series,
        opensDetails = true
    )
}

internal fun Playlist?.contentType(): PlaylistContentType = when {
    this?.isSeries == true -> PlaylistContentType.Series
    this?.isVod == true -> PlaylistContentType.Vod
    else -> PlaylistContentType.Live
}
