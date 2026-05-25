package cloud.projectargus

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Optional standalone Hilt module for the Argus SDK.
 *
 * For standalone use (e.g., testing, or apps without their own binding for it),
 * the SDK ships this module. In a typical host app, the [ArgusConfiguration] is
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
