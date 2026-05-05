package com.opencode.remote.di

import com.opencode.remote.data.api.OConnectorApiClient
import com.opencode.remote.data.api.OConnectorSseClient
import com.opencode.remote.data.repository.OConnectorRepository
import com.opencode.remote.data.repository.OConnectorRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {
    @Binds
    @Singleton
    abstract fun bindRepository(impl: OConnectorRepositoryImpl): OConnectorRepository

    companion object {
        @Provides
        @Singleton
        @OptIn(ExperimentalSerializationApi::class)
        fun provideJson(): Json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
            explicitNulls = false
        }

        @Provides
        @Singleton
        fun provideApiClient(json: Json): OConnectorApiClient =
            OConnectorApiClient(json)

        @Provides
        @Singleton
        fun provideSseClient(json: Json): OConnectorSseClient =
            OConnectorSseClient(json)
    }
}
