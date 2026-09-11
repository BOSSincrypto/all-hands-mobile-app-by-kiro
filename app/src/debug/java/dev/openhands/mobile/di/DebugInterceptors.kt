package dev.openhands.mobile.di

import okhttp3.Interceptor
import okhttp3.logging.HttpLoggingInterceptor

/**
 * Debug-only HTTP logging. Credentials are redacted even here, and the release variant
 * supplies an empty list so no logging class reaches the shipped APK.
 */
internal fun debugInterceptors(): List<Interceptor> = listOf(
    HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BASIC
        redactHeader("Authorization")
        redactHeader("X-Session-API-Key")
    },
)
