package com.m3u.core.foundation.architecture.preferences

@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.VALUE_PARAMETER,
    AnnotationTarget.TYPE
)
@Retention(AnnotationRetention.SOURCE)
annotation class PlayerMode {
    companion object {
        const val INTERNAL = 0
        const val EXTERNAL = 1
    }
}
