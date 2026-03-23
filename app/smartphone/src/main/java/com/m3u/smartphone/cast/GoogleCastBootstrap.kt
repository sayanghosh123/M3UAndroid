package com.m3u.smartphone.cast

import android.content.Context
import com.google.android.gms.cast.framework.CastContext
import timber.log.Timber

object GoogleCastBootstrap {
    @Volatile
    private var castContext: CastContext? = null

    fun initialize(context: Context) {
        if (castContext != null) return
        synchronized(this) {
            if (castContext != null) return
            castContext = runCatching {
                CastContext.getSharedInstance(context.applicationContext)
            }
                .onFailure { Timber.w(it, "Google Cast is unavailable on this device") }
                .getOrNull()
        }
    }

    fun get(): CastContext? = castContext
}
