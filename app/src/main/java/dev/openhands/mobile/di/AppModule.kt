package dev.openhands.mobile.di

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.openhands.mobile.data.local.OpenHandsDatabase
import dev.openhands.mobile.data.remote.AgentServerApi
import dev.openhands.mobile.data.remote.AuthInterceptor
import dev.openhands.mobile.data.remote.OpenHandsApi
import dev.openhands.mobile.data.remote.UnauthorizedInterceptor
import dev.openhands.mobile.security.TokenVault
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.ConnectionSpec
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

private const val BASE_URL = "https://app.all-hands.dev/"

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun json(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
        coerceInputValues = true
    }

    @Provides
    @Singleton
    fun okHttp(
        auth: AuthInterceptor,
        unauthorized: UnauthorizedInterceptor,
    ): OkHttpClient = OkHttpClient.Builder()
        // Reject cleartext and legacy TLS outright; every endpoint used here is HTTPS/WSS.
        .connectionSpecs(listOf(ConnectionSpec.RESTRICTED_TLS))
        .addInterceptor(auth)
        .addInterceptor(unauthorized)
        // Empty in release builds, so no logging code is linked into the shipped APK.
        .apply { debugInterceptors().forEach(::addInterceptor) }
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Provides
    @Singleton
    fun retrofit(client: OkHttpClient, json: Json): Retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    @Provides
    @Singleton
    fun openHandsApi(retrofit: Retrofit): OpenHandsApi = retrofit.create(OpenHandsApi::class.java)

    @Provides
    @Singleton
    fun agentServerApi(retrofit: Retrofit): AgentServerApi =
        retrofit.create(AgentServerApi::class.java)

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): OpenHandsDatabase =
        Room.databaseBuilder(context, OpenHandsDatabase::class.java, "openhands.db")
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides
    @Singleton
    fun tokenVault(@ApplicationContext context: Context): TokenVault = TokenVault(context)

    @Provides
    @Singleton
    fun biometricManager(@ApplicationContext context: Context): BiometricManager =
        BiometricManager.from(context)
}
