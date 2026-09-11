package dev.openhands.mobile.di

import okhttp3.Interceptor

/** Release builds log no HTTP traffic. */
internal fun debugInterceptors(): List<Interceptor> = emptyList()
