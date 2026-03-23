package com.m3u.core.architecture.preferences

import android.util.Log

@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.VALUE_PARAMETER,
    AnnotationTarget.TYPE
)
@Retention(AnnotationRetention.SOURCE)
annotation class LogLevel {
    companion object {
        const val ERROR = Log.ERROR
        const val WARN = Log.WARN
        const val INFO = Log.INFO
        const val DEBUG = Log.DEBUG
        const val VERBOSE = Log.VERBOSE
    }
}
