package com.targetx.app.di

import android.content.Context
import com.targetx.app.data.receipt.ReceiptRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideKtorJson(): Json = Json { ignoreUnknownKeys = true }

    @Provides
    @Singleton
    fun provideHttpClient(): HttpClient = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(provideKtorJson())
        }
        // Additional config (timeouts, logging) can be added here
    }

    @Provides
    @Singleton
    fun provideReceiptRepository(
        client: HttpClient,
        @ApplicationContext context: Context
    ): ReceiptRepository {
        // Expect these values to be provided via BuildConfig in the app module
        val edgeUrl = try {
            val field = Class.forName("com.targetx.app.BuildConfig").getField("SUPABASE_FUNCTION_URL").get(null) as String
            field
        } catch (e: Exception) {
            ""
        }

        val bearer = try {
            val field = Class.forName("com.targetx.app.BuildConfig").getField("SUPABASE_FUNCTION_BEARER").get(null) as String
            field
        } catch (e: Exception) {
            null
        }

        val apiKey = try {
            val field = Class.forName("com.targetx.app.BuildConfig").getField("SUPABASE_API_KEY").get(null) as String
            field
        } catch (e: Exception) {
            null
        }

        return ReceiptRepository(client, edgeUrl, bearer, apiKey)
    }
}
