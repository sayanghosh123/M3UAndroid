package com.m3u.smartphone.telemetry

import android.content.Context
import com.m3u.data.telemetry.MainThreadWatchdog
import com.m3u.data.telemetry.TelemetryCoordinator
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface SmartphoneTelemetryEntryPoint {
    fun telemetryCoordinator(): TelemetryCoordinator
    fun mainThreadWatchdog(): MainThreadWatchdog

    companion object {
        fun from(context: Context): SmartphoneTelemetryEntryPoint =
            EntryPointAccessors.fromApplication(
                context.applicationContext,
                SmartphoneTelemetryEntryPoint::class.java,
            )
    }
}
