package cloud.projectargus

import android.content.Context
import android.content.pm.ApplicationInfo

/**
 * Configuration for the Argus feature flag SDK.
 *
 * @property apiKey Argus API key for this Customer workspace (Argus dashboard → Settings → API key).
 * @property baseURL Root URL of the Argus Cloud Function endpoint.
 * @property tenantId Tenant identifier for multi-tenant resolution.
 * @property environment Target environment for flag resolution (dev, staging, prod).
 *   When the caller cannot determine the environment at construction time,
 *   use [ArgusConfiguration.create] to derive it from the host app's build
 *   context via [autoDetectedEnvironment].
 * @property userId Stable user identifier for rollout bucketing.
 */
data class ArgusConfiguration(
    val apiKey: String,
    val baseURL: String,
    val tenantId: String,
    val environment: String,
    val userId: String
) {

    companion object {

        /**
         * Build an [ArgusConfiguration] with [environment] auto-detected from the
         * host app's build context.
         *
         * Detection priority:
         *  1. If `ApplicationInfo.FLAG_DEBUGGABLE` is set on the host app, returns
         *     `"dev"`. This is true for debug builds (`buildTypes.debug`) and any
         *     build with `android:debuggable="true"` in the manifest.
         *  2. Otherwise, looks up a customer-provided `BuildConfig.ARGUS_TRACK`
         *     String constant on the host app via reflection. When present, its
         *     value is returned verbatim (recommended values: `"staging"`, `"prod"`).
         *     Customers wire this in via `buildConfigField` on a product flavor
         *     (see README for the recommended pattern).
         *  3. Otherwise, returns `"prod"`.
         *
         * Callers who already know the environment should construct
         * [ArgusConfiguration] directly with the explicit value ... this overload
         * exists only for callers who want the SDK to infer it.
         */
        @JvmStatic
        @JvmOverloads
        fun create(
            context: Context,
            apiKey: String,
            baseURL: String,
            tenantId: String,
            userId: String,
            environment: String = autoDetectedEnvironment(context)
        ): ArgusConfiguration = ArgusConfiguration(
            apiKey = apiKey,
            baseURL = baseURL,
            tenantId = tenantId,
            environment = environment,
            userId = userId
        )

        /**
         * Detect the target environment from the host app's build context.
         *
         * See [create] for the detection priority. Exposed as `internal` so the
         * unit tests can pin each branch directly; production callers should go
         * through [create].
         */
        internal fun autoDetectedEnvironment(context: Context): String {
            // Prefer the application context where available; some callers pass
            // an Activity or Service, and Context#applicationContext is the
            // canonical handle for build-info reads. Falls back to the supplied
            // context if a host environment ever returns null here.
            @Suppress("USELESS_ELVIS")
            val appContext: Context = context.applicationContext ?: context

            // 1. Debuggable host app → "dev".
            val isDebuggable =
                (appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
            if (isDebuggable) return ENV_DEV

            // 2. Optional customer override via BuildConfig.ARGUS_TRACK.
            // Reflection so the SDK doesn't take a compile-time dep on the
            // customer's BuildConfig. Every failure path falls through to "prod".
            val overrideTrack = readBuildConfigArgusTrack(appContext.packageName)
            if (!overrideTrack.isNullOrBlank()) return overrideTrack

            // 3. Default to production.
            return ENV_PROD
        }

        /**
         * Reflectively read a `String ARGUS_TRACK` field from the host app's
         * generated `BuildConfig` class. Returns null on any failure (class not
         * found, field absent, wrong type, access denied, etc.).
         */
        private fun readBuildConfigArgusTrack(packageName: String): String? {
            if (packageName.isBlank()) return null
            return runCatching {
                val buildConfigClass = Class.forName("$packageName.BuildConfig")
                val field = buildConfigClass.getDeclaredField(BUILD_CONFIG_FIELD)
                field.isAccessible = true
                field.get(null) as? String
            }.getOrNull()
        }

        internal const val ENV_DEV = "dev"
        internal const val ENV_PROD = "prod"
        private const val BUILD_CONFIG_FIELD = "ARGUS_TRACK"
    }
}
