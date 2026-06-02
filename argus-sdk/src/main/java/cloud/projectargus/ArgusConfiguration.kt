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
 * @property firebase Firebase project + emulator settings used by the
 *   real-time push channel (#215). Optional: defaults to Argus's production
 *   Firebase project so a consumer normally supplies only the Argus [apiKey].
 *   Set [FirebaseConfig.useEmulator] (via the convergence harness / local dev)
 *   to point the listener at the Firestore + Auth emulators instead of prod.
 */
data class ArgusConfiguration(
    val apiKey: String,
    val baseURL: String,
    val tenantId: String,
    val environment: String,
    val userId: String,
    val firebase: FirebaseConfig = FirebaseConfig()
) {

    /**
     * Firebase connection settings for the real-time push channel.
     *
     * The SDK initialises a *named* secondary [com.google.firebase.FirebaseApp]
     * from these values (never the host app's default app), trades the Argus
     * apiKey for a scoped custom token via `issueStreamToken`, signs in, and
     * opens Firestore snapshot listeners on its own Product's flag docs.
     *
     * @property projectId Argus Firebase project id. Defaults to the Argus prod
     *   project. Must match the project that minted the custom token.
     * @property applicationId Firebase Android `applicationId` (a.k.a. mobilesdk
     *   app id) for the Argus app registration. Placeholder for prod wiring.
     * @property apiKey Firebase Web/Android API key for the Argus project. This
     *   is the public Firebase config key (safe to ship) — NOT the Argus apiKey.
     * @property useEmulator When true, the named app's Auth + Firestore point at
     *   the local emulator at [emulatorHost] / the given ports instead of prod.
     *   Used by the convergence harness and local dev for dev/prod parity.
     * @property emulatorHost Host the emulators bind to (IPv4 `127.0.0.1` for the
     *   Firebase emulators, per the project's local-dev notes).
     * @property authEmulatorPort Firebase Auth emulator port.
     * @property firestoreEmulatorPort Firestore emulator port.
     */
    data class FirebaseConfig(
        val projectId: String = DEFAULT_PROJECT_ID,
        val applicationId: String = DEFAULT_APPLICATION_ID,
        val apiKey: String = DEFAULT_FIREBASE_API_KEY,
        val useEmulator: Boolean = false,
        val emulatorHost: String = DEFAULT_EMULATOR_HOST,
        val authEmulatorPort: Int = DEFAULT_AUTH_EMULATOR_PORT,
        val firestoreEmulatorPort: Int = DEFAULT_FIRESTORE_EMULATOR_PORT
    )

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
            environment: String = autoDetectedEnvironment(context),
            firebase: FirebaseConfig = FirebaseConfig()
        ): ArgusConfiguration = ArgusConfiguration(
            apiKey = apiKey,
            baseURL = baseURL,
            tenantId = tenantId,
            environment = environment,
            userId = userId,
            firebase = firebase
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

        // ── Firebase prod placeholders (#215) ───────────────────────
        // Argus's production Firebase project. The apiKey here is the
        // PUBLIC Firebase config key (ships in every Firebase client app
        // and is safe to commit) — distinct from the secret Argus apiKey.
        // Real prod values are wired in before GA; these placeholders let
        // the SDK compile and run against the emulator today.
        private const val DEFAULT_PROJECT_ID = "demo-argus"
        private const val DEFAULT_APPLICATION_ID = "1:000000000000:android:0000000000000000"
        private const val DEFAULT_FIREBASE_API_KEY = "AIzaSyArgusPlaceholderKey0000000000000000"

        // Emulators bind to IPv4 127.0.0.1 (project local-dev convention).
        private const val DEFAULT_EMULATOR_HOST = "127.0.0.1"
        private const val DEFAULT_AUTH_EMULATOR_PORT = 9099
        private const val DEFAULT_FIRESTORE_EMULATOR_PORT = 8080
    }
}
