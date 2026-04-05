package com.telus.argus

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Optional standalone Hilt module for the Argus SDK.
 *
 * For standalone use outside the SmartHome+ app (e.g., testing, other TELUS apps),
 * the SDK ships this module. In the SmartHome+ app, the [ArgusConfiguration] is
 * provided by the app's own Hilt module using BuildConfig values and the
 * authenticated user's UID.
 */
@Module
@InstallIn(SingletonComponent::class)
object ArgusModule {

    @Provides
    @Singleton
    fun provideArgusConfiguration(): ArgusConfiguration {
        // Standalone consumers override this binding
        throw IllegalStateException(
            "ArgusConfiguration must be provided by the consuming app"
        )
    }
}
