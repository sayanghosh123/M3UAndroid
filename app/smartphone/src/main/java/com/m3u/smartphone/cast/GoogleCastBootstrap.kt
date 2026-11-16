package com.m3u.smartphone.cast

import android.content.Context
import com.google.android.gms.cast.framework.CastContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

object GoogleCastBootstrap {
    private val initialized = AtomicBoolean(false)
    private val _castContextFlow = MutableStateFlow<CastContext?>(null)
    val castContextFlow: StateFlow<CastContext?> = _castContextFlow.asStateFlow()

    fun initialize(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        runCatching {
            CastContext.getSharedInstance(appContext, Dispatchers.IO.asExecutor())
                .addOnSuccessListener { contextInstance ->
                    _castContextFlow.value = contextInstance
                }
                .addOnFailureListener { error ->
                    Timber.w(error, "Google Cast is unavailable on this device")
                }
        }.onFailure { error ->
            Timber.w(error, "Google Cast initialization failed")
        }
    }

    fun get(): CastContext? = _castContextFlow.value
}
