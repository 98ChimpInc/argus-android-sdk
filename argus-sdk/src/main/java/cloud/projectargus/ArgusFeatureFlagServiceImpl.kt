package cloud.projectargus

import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.memoryCacheSettings
import com.squareup.moshi.Moshi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Argus-backed implementation of [FeatureFlagService].
 *
 * Drop-in replacement for the Firebase Remote Config-backed FeatureFlagServiceImpl.
 *
 * **Primary channel (#215): real-time push via Firestore listeners.** On
 * [initialize] the SDK trades its Argus apiKey for a scoped Firebase custom
 * token (`issueStreamToken`), signs in to a *named* Firebase app, and opens
 * snapshot listeners on its own Product's flag/env/tenant/condition documents.
 * Any change re-runs client-side resolution ([ArgusFlagResolver]) and pushes
 * the new values into the [isActive]-gated cache in ~1s — no polling.
 *
 * **Fallback channel: the `resolveFlags` HTTP fetch.** Used (a) for first paint
 * before the listener delivers its first snapshot, and (b) as a graceful
 * degrade if Firebase init / sign-in fails. Resolved values are cached in a
 * thread-safe [ConcurrentHashMap]; bundled [FeatureFlag] enum defaults seed the
 * cache so getters always return something sane.
 */
@Singleton
@Suppress("TooManyFunctions")
class ArgusFeatureFlagServiceImpl @Inject constructor(
    private val appVersionName: String,
    private val appCoroutineScope: CoroutineScope,
    private val moshi: Moshi,
    private val configuration: ArgusConfiguration
) : FeatureFlagService {

    private val _remoteConfigState = MutableStateFlow(false)
    override val isActive: StateFlow<Boolean> = _remoteConfigState.asStateFlow()

    private val flagCache = ConcurrentHashMap<String, String>()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    // ── Real-time push state (#215) ─────────────────────────────────────
    // Named Firebase app + listener registrations, torn down on [close].
    // The latest snapshot of each source is held here and re-resolved on any
    // change. Guarded by [streamMutex] because listener callbacks and the
    // bootstrap can interleave on different threads.
    private var firebaseApp: FirebaseApp? = null
    private var firestore: FirebaseFirestore? = null
    // Thread-safe: env/tenant listeners are bound from the flags-listener
    // coroutine while [close] may iterate concurrently.
    private val listenerRegistrations =
        java.util.concurrent.CopyOnWriteArrayList<ListenerRegistration>()
    private val streamMutex = Mutex()

    // flagId -> /flags/{flagId} doc data
    private val flagDocs = mutableMapOf<String, Map<String, Any?>>()
    // flagId -> /flags/{flagId}/environments/{env} doc data
    private val envDocs = mutableMapOf<String, Map<String, Any?>>()
    // flagId -> /flags/{flagId}/environments/{env}/tenants/{tenantId} doc data
    private val tenantDocs = mutableMapOf<String, Map<String, Any?>>()
    // condition name -> /conditions/{id} doc data
    private val conditionDocs = mutableMapOf<String, Map<String, Any?>>()

    // ── Initialisation ──────────────────────────────────────────────────

    override fun initialize() {
        appCoroutineScope.launch {
            loadDefaults()
            // Cold-start paint via the HTTP fallback first, so getters return
            // current server values immediately even before the listener's
            // first snapshot arrives. Failure here is non-fatal — the
            // listener (or bundled defaults) covers it.
            try {
                fetchFlags()
            } catch (ex: Exception) {
                Timber.w(ex, "Argus cold-start fetch failed; awaiting real-time listener")
            }
            // Promote to the real-time channel. On any failure we stay on the
            // last fetched/default values and keep [isActive] true.
            try {
                startRealtime()
            } catch (ex: Exception) {
                Timber.e(ex, "Argus real-time init failed; remaining on HTTP fallback values")
                _remoteConfigState.value = true
            }
        }
    }

    private fun loadDefaults() {
        FeatureFlag.getDefaultValues().forEach { (key, value) ->
            flagCache[key] = value.toString()
        }
    }

    // ── Fallback channel: resolveFlags HTTP fetch ───────────────────────

    private suspend fun fetchFlags() {
        val url = buildString {
            append(configuration.baseURL)
            append("/resolveFlags")
            append("?platform=android")
            append("&version=").append(URLEncoder.encode(appVersionName, "UTF-8"))
            append("&userId=").append(URLEncoder.encode(configuration.userId, "UTF-8"))
            append("&tenantId=").append(URLEncoder.encode(configuration.tenantId, "UTF-8"))
            append("&env=").append(URLEncoder.encode(configuration.environment, "UTF-8"))
        }

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer ${configuration.apiKey}")
            .get()
            .build()

        val response = withContext(Dispatchers.IO) {
            httpClient.newCall(request).execute()
        }

        if (!response.isSuccessful) {
            throw IOException("Argus fetch failed: ${response.code}")
        }

        val body = response.body?.string()
            ?: throw IOException("Argus response body is null")

        val json = JSONObject(body)
        val flags = json.getJSONObject("flags")
        flags.keys().forEach { key ->
            flagCache[key] = flags.get(key).toString()
        }

        _remoteConfigState.value = true
    }

    // ── Primary channel: real-time Firestore listeners (#215) ───────────

    /**
     * Bootstrap the real-time push channel:
     *  1. trade the apiKey for a scoped Firebase custom token (existing OkHttp);
     *  2. init a *named* Firebase app (so we never clash with the host app's
     *     default app), pointed at the emulator when configured;
     *  3. sign in with the custom token;
     *  4. open snapshot listeners on the flags query + each env doc (+ tenant
     *     override doc when tenant-scoped) + conditions.
     *
     * Throws on any bootstrap failure so [initialize] can fall back cleanly.
     */
    private suspend fun startRealtime() {
        val bootstrap = issueStreamToken()

        val app = initFirebaseApp()
        firebaseApp = app

        val auth = FirebaseAuth.getInstance(app)
        val db = FirebaseFirestore.getInstance(app)
        firestore = db

        auth.signInWithCustomToken(bootstrap.token).await()

        attachListeners(db, bootstrap)
    }

    /** Bootstrap claims returned by `issueStreamToken`. */
    private data class StreamBootstrap(
        val token: String,
        val customerId: String,
        val productId: String,
        val env: String,
        val tenantId: String?
    )

    private suspend fun issueStreamToken(): StreamBootstrap {
        val request = Request.Builder()
            .url("${configuration.baseURL}/issueStreamToken")
            .addHeader("Authorization", "Bearer ${configuration.apiKey}")
            .post(ByteArray(0).toRequestBody(null))
            .build()

        val response = withContext(Dispatchers.IO) {
            httpClient.newCall(request).execute()
        }
        if (!response.isSuccessful) {
            throw IOException("issueStreamToken failed: ${response.code}")
        }
        val body = response.body?.string()
            ?: throw IOException("issueStreamToken response body is null")

        val json = JSONObject(body)
        val token = json.optString("token").takeIf { it.isNotEmpty() }
            ?: throw IOException("issueStreamToken response missing token")
        return StreamBootstrap(
            token = token,
            customerId = json.optString("customerId"),
            productId = json.optString("productId"),
            env = json.optString("env"),
            tenantId = json.optString("tenantId").takeIf { it.isNotEmpty() }
        )
    }

    private fun initFirebaseApp(): FirebaseApp {
        FirebaseApp.getApps(/* context */ requireContext()).forEach { existing ->
            if (existing.name == FIREBASE_APP_NAME) return existing
        }

        val fb = configuration.firebase
        val options = FirebaseOptions.Builder()
            .setProjectId(fb.projectId)
            .setApplicationId(fb.applicationId)
            .setApiKey(fb.apiKey)
            .build()

        val app = FirebaseApp.initializeApp(requireContext(), options, FIREBASE_APP_NAME)

        if (fb.useEmulator) {
            FirebaseAuth.getInstance(app)
                .useEmulator(fb.emulatorHost, fb.authEmulatorPort)
            FirebaseFirestore.getInstance(app).apply {
                useEmulator(fb.emulatorHost, fb.firestoreEmulatorPort)
                // Emulator: in-memory cache only, avoid stale on-disk state
                // across harness runs.
                firestoreSettings = FirebaseFirestoreSettings.Builder()
                    .setLocalCacheSettings(memoryCacheSettings { })
                    .build()
            }
        }
        return app
    }

    /**
     * Resolve a [android.content.Context] for Firebase init. The SDK does not
     * hold a Context directly; it relies on the host app having already
     * initialised the default [FirebaseApp] (the standard `firebase-bom`
     * auto-init via the ContentProvider), whose context we borrow. If the host
     * has no default app, real-time init fails and we fall back to HTTP.
     */
    private fun requireContext(): android.content.Context =
        FirebaseApp.getInstance().applicationContext

    private suspend fun attachListeners(db: FirebaseFirestore, bootstrap: StreamBootstrap) {
        val env = configuration.environment
        val tenantId = bootstrap.tenantId ?: configuration.tenantId.takeIf { it.isNotEmpty() }

        // ── Conditions (scoped to this Product) ──────────────────────
        val conditionsReg = db.collection("conditions")
            .whereEqualTo("customerId", bootstrap.customerId)
            .whereEqualTo("productId", bootstrap.productId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Timber.w(error, "Argus conditions listener error")
                    return@addSnapshotListener
                }
                if (snapshot == null) return@addSnapshotListener
                appCoroutineScope.launch {
                    streamMutex.withLock {
                        conditionDocs.clear()
                        snapshot.documents.forEach { doc ->
                            val data = doc.data ?: return@forEach
                            val name = data["name"] as? String ?: return@forEach
                            conditionDocs[name] = data
                        }
                    }
                    recomputeAndPublish()
                }
            }

        // ── Flags query (scoped to this Product) ─────────────────────
        // On each flags snapshot we (re)bind a per-flag env listener and, when
        // tenant-scoped, a per-flag tenant-override listener. The env/tenant
        // listeners are what deliver live value changes between flag-doc
        // changes; the flags query delivers archive/draft/default changes and
        // add/remove of flags.
        val flagsReg = db.collection("flags")
            .whereEqualTo("customerId", bootstrap.customerId)
            .whereEqualTo("productId", bootstrap.productId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Timber.w(error, "Argus flags listener error")
                    return@addSnapshotListener
                }
                if (snapshot == null) return@addSnapshotListener
                appCoroutineScope.launch {
                    val currentIds = mutableSetOf<String>()
                    streamMutex.withLock {
                        snapshot.documents.forEach { doc ->
                            val data = doc.data ?: return@forEach
                            flagDocs[doc.id] = data
                            currentIds.add(doc.id)
                        }
                        // Drop any flags no longer present.
                        val removed = flagDocs.keys - currentIds
                        removed.forEach { id ->
                            flagDocs.remove(id)
                            envDocs.remove(id)
                            tenantDocs.remove(id)
                        }
                    }
                    // Bind env + tenant listeners for the current flag set.
                    snapshot.documents.forEach { doc ->
                        bindEnvListener(db, doc.id, env)
                        if (tenantId != null) {
                            bindTenantListener(db, doc.id, env, tenantId)
                        }
                    }
                    recomputeAndPublish()
                }
            }

        streamMutex.withLock {
            listenerRegistrations.add(conditionsReg)
            listenerRegistrations.add(flagsReg)
        }
    }

    // Tracks which flagIds already have env/tenant listeners so the flags
    // listener does not stack duplicates on every snapshot.
    private val boundEnvFlagIds = ConcurrentHashMap.newKeySet<String>()
    private val boundTenantFlagIds = ConcurrentHashMap.newKeySet<String>()

    private fun bindEnvListener(db: FirebaseFirestore, flagId: String, env: String) {
        if (!boundEnvFlagIds.add(flagId)) return
        val reg = db.collection("flags").document(flagId)
            .collection("environments").document(env)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Timber.w(error, "Argus env listener error (%s)", flagId)
                    return@addSnapshotListener
                }
                appCoroutineScope.launch {
                    streamMutex.withLock {
                        val data = snapshot?.data
                        if (data != null) envDocs[flagId] = data else envDocs.remove(flagId)
                    }
                    recomputeAndPublish()
                }
            }
        listenerRegistrations.add(reg)
    }

    private fun bindTenantListener(
        db: FirebaseFirestore,
        flagId: String,
        env: String,
        tenantId: String
    ) {
        if (!boundTenantFlagIds.add(flagId)) return
        val reg = db.collection("flags").document(flagId)
            .collection("environments").document(env)
            .collection("tenants").document(tenantId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Timber.w(error, "Argus tenant listener error (%s)", flagId)
                    return@addSnapshotListener
                }
                appCoroutineScope.launch {
                    streamMutex.withLock {
                        val data = snapshot?.data
                        if (data != null) tenantDocs[flagId] = data else tenantDocs.remove(flagId)
                    }
                    recomputeAndPublish()
                }
            }
        listenerRegistrations.add(reg)
    }

    /** Re-resolve all flags from the latest snapshots and update the cache. */
    private suspend fun recomputeAndPublish() {
        val inputs: List<ArgusFlagResolver.FlagInput>
        val conditions: Map<String, Map<String, Any?>>
        streamMutex.withLock {
            inputs = flagDocs.map { (flagId, flag) ->
                ArgusFlagResolver.FlagInput(
                    flag = flag,
                    env = envDocs[flagId],
                    tenant = tenantDocs[flagId]
                )
            }
            conditions = HashMap(conditionDocs)
        }

        val resolved = ArgusFlagResolver.resolve(
            flags = inputs,
            conditionsByName = conditions,
            context = ArgusFlagResolver.Context(
                platform = "android",
                version = appVersionName,
                userId = configuration.userId.takeIf { it.isNotEmpty() }
            )
        )

        resolved.forEach { (key, value) -> flagCache[key] = value }
        _remoteConfigState.value = true
    }

    /**
     * Detach all listeners and tear down the named Firebase app. Idempotent.
     * Call when the SDK consumer no longer needs flag updates (e.g. sign-out).
     */
    fun close() {
        appCoroutineScope.launch {
            streamMutex.withLock {
                listenerRegistrations.forEach { it.remove() }
                listenerRegistrations.clear()
                boundEnvFlagIds.clear()
                boundTenantFlagIds.clear()
                flagDocs.clear()
                envDocs.clear()
                tenantDocs.clear()
                conditionDocs.clear()
            }
            runCatching { firebaseApp?.delete() }
            firebaseApp = null
            firestore = null
        }
    }

    // ── Primitive Getters ───────────────────────────────────────────────

    override fun getString(key: String): String {
        return flagCache[key] ?: ""
    }

    override fun getBoolean(key: String): Boolean {
        return flagCache[key]?.toBooleanStrictOrNull() ?: false
    }

    override fun getLong(featureFlag: FeatureFlag): Long {
        return flagCache[featureFlag.key]?.toLongOrNull() ?: 0L
    }

    override fun isFeatureEnabled(featureFlag: FeatureFlag): Boolean {
        return getBoolean(featureFlag.key)
    }

    // ── Structured Data Getter ──────────────────────────────────────────

    override fun getFeatureData(flag: FeatureFlag): FeatureFlagData? {
        val json = flagCache[flag.key] ?: return null
        if (json.isEmpty()) return null

        return flag.rendererType?.let { rendererType ->
            try {
                val jsonObject = JSONObject(json)
                val platformJson = if (jsonObject.has("android")) {
                    jsonObject.getJSONObject("android").toString()
                } else {
                    json
                }

                moshi.adapter(rendererType.java).fromJson(platformJson)
            } catch (e: Exception) {
                Timber.e(e, "Failed to parse FeatureFlagData for key: ${flag.key}")
                null
            }
        }
    }

    // ── Typed Getters ───────────────────────────────────────────────────

    override fun getAppVersion(): AppVersion? =
        getFeatureData(FeatureFlag.APP_VERSION_KEY) as? AppVersion

    override fun getEnabledConnectors(): Connectors? =
        getFeatureData(FeatureFlag.ENABLED_3P_CONNECTORS_KEY) as? Connectors

    override fun getShAssistantConfiguration(): ShAssistantConfiguration? =
        getFeatureData(FeatureFlag.ENABLE_SH_ASSISTANT) as? ShAssistantConfiguration

    override fun getMaintenanceMode(): MaintenanceMode =
        getFeatureData(FeatureFlag.MAINTENANCE_MODE) as? MaintenanceMode
            ?: MaintenanceMode()

    override fun getCameraMinFirmwareVersion(): CameraMinFirmwareVersion? =
        getFeatureData(FeatureFlag.CAMERA_MIN_FIRMWARE_VERSION)
            as? CameraMinFirmwareVersion

    override fun getInternetBackupConfiguration(): InternetBackupConfiguration? =
        getFeatureData(FeatureFlag.ENABLE_INTERNET_BACKUP)
            as? InternetBackupConfiguration

    override fun getProfileHHRefreshIntervalSeconds(): Long =
        getLong(FeatureFlag.PROFILE_HH_REFRESH_INTERVAL_SECONDS)

    override fun getDeviceListV2ApiConfiguration(): DeviceListConfiguration? =
        getFeatureData(FeatureFlag.ENABLE_DEVICE_LIST_V2)
            as? DeviceListConfiguration

    override fun getDeviceListCategory(): DeviceTypeConfig? =
        getFeatureData(FeatureFlag.DEVICE_LIST_CATEGORY) as? DeviceTypeConfig

    override fun getRefreshCallConfiguration(): RefreshCallConfiguration? =
        getFeatureData(FeatureFlag.REFRESH_DEVICE_CONFIGURATION)
            as? RefreshCallConfiguration

    override fun getPollingConfig(): PollingIntervalConfiguration? =
        getFeatureData(FeatureFlag.POLLING_CONFIG)
            as? PollingIntervalConfiguration

    override fun getDeviceCommandPollingIntervals(): List<Int> {
        val defaultIntervals = listOf(3, 3, 3, 3)
        val rawValue = getString(FeatureFlag.DEVICE_COMMAND_POLLING_INTERVALS.key)
        if (rawValue.isBlank()) return defaultIntervals

        return runCatching {
            val array = JSONArray(rawValue)
            List(array.length()) { i -> array.getInt(i) }
        }.onFailure { e ->
            Timber.e(e, "Failed to parse polling intervals. raw=%s", rawValue)
        }.getOrElse { defaultIntervals }.ifEmpty { defaultIntervals }
    }

    override fun getDeviceRefreshPollingIntervalSeconds(): Long {
        val value = getLong(FeatureFlag.IN_APP_DEVICE_POLLING_INTERVAL)
        return if (value > 0L) value
        else FeatureFlagConstants.DEFAULT_DEVICE_REFRESH_POLLING_INTERVAL_SECONDS
    }

    override fun getFeedbackPromptConfiguration(): FeedbackPromptConfiguration {
        val defaultConfig = FeedbackPromptConfiguration()

        val sessionThresholdRemote = getLong(FeatureFlag.FEEDBACK_PROMPT_SESSIONS_THRESHOLD)
        val sessionThreshold = if (sessionThresholdRemote > 0L)
            sessionThresholdRemote.toInt()
        else defaultConfig.feedbackPromptSessionThreshold

        val cooldownDaysRemote = getLong(FeatureFlag.FEEDBACK_PROMPT_COOLDOWN_PERIOD_DAYS)
        val cooldownDays = if (cooldownDaysRemote > 0L)
            cooldownDaysRemote.toInt()
        else defaultConfig.feedbackPromptCooldownPeriodDays

        val liveViewThresholdRemote = getLong(FeatureFlag.FEEDBACK_PROMPT_LIVE_VIEW_THRESHOLD)
        val liveViewThreshold = if (liveViewThresholdRemote > 0L)
            liveViewThresholdRemote.toInt()
        else defaultConfig.appRatingPromptLiveViewLoadingThreshold

        val devicesThresholdRemote = getLong(FeatureFlag.FEEDBACK_PROMPT_DEVICES_THRESHOLD)
        val devicesThreshold = if (devicesThresholdRemote > 0L)
            devicesThresholdRemote.toInt()
        else defaultConfig.feedbackPromptDevicesThreshold

        return FeedbackPromptConfiguration(
            feedbackPromptEnabled = getBoolean(FeatureFlag.FEEDBACK_PROMPT_ENABLED.key),
            feedbackPromptSessionThreshold = sessionThreshold,
            feedbackPromptCooldownPeriodDays = cooldownDays,
            feedbackPromptDevicesThreshold = devicesThreshold,
            appRatingPromptLiveViewLoadingThreshold = liveViewThreshold
        )
    }

    override fun isBookCallEnabled(): Boolean {
        val bookCall = getFeatureData(FeatureFlag.ENABLE_BOOK_CALL) as? BookCall
        val firebaseAppVersion = bookCall?.appVersion.orEmpty()
        if (firebaseAppVersion.isNotEmpty()) {
            return compareVersion(appVersionName, firebaseAppVersion) >= 0
        }
        return false
    }

    override fun shouldUseGenesysV2Api(): Boolean {
        val bookCall = getFeatureData(FeatureFlag.ENABLE_BOOK_CALL) as? BookCall
        return bookCall?.useGenesysV2Api ?: false
    }

    // ── Deprecated Boolean Getters ──────────────────────────────────────

    @Deprecated("Use isFeatureEnabled with FeatureFlag enum instead")
    override fun isTwhSupported() = getBoolean(FeatureFlag.TWH_SUPPORTED.key)

    @Deprecated("Use isFeatureEnabled with FeatureFlag enum instead")
    override fun isSupportChatEnabled() = getBoolean(FeatureFlag.ENABLE_SUPPORT_CHAT.key)

    @Deprecated("Use isFeatureEnabled with FeatureFlag enum instead")
    override fun isSupportEnabled() = getBoolean(FeatureFlag.ENABLE_SUPPORT.key)

    @Deprecated("Use isFeatureEnabled with FeatureFlag enum instead")
    override fun isAutoPlayLiveFeedEnabled() =
        getBoolean(FeatureFlag.AUTO_PLAY_LIVE_FEED_ENABLED.key)

    @Deprecated("Use isFeatureEnabled with FeatureFlag enum instead")
    override fun isOneTrustEnabled() = getBoolean(FeatureFlag.ENABLE_ONE_TRUST.key)

    @Deprecated("Use isFeatureEnabled with FeatureFlag enum instead")
    override fun isInternetOnlySkuEnabled() =
        getBoolean(FeatureFlag.ENABLE_INTERNET_ONLY_SKU.key)

    @Deprecated("Use isFeatureEnabled with FeatureFlag enum instead")
    override fun isHiddenClipLengthEnabled() =
        getBoolean(FeatureFlag.ENABLE_HIDE_CLIP_LENGTH.key)

    @Deprecated("Use isFeatureEnabled with FeatureFlag enum instead")
    override fun isCameraConnectivityCheckEnabled() =
        getBoolean(FeatureFlag.ENABLE_1P_CAMERA_CONNECTIVITY.key)

    @Deprecated("Use isFeatureEnabled with FeatureFlag enum instead")
    override fun isRewardsPointsEnable() =
        getBoolean(FeatureFlag.ENABLE_REWARDS_POINTS.key)

    @Deprecated("Use isFeatureEnabled with FeatureFlag enum instead")
    override fun isAutomationSkuEnabled() =
        getBoolean(FeatureFlag.ENABLE_AUTOMATION_SKU.key)

    @Deprecated("Use isFeatureEnabled with FeatureFlag enum instead")
    override fun isTplinkBrightnessEnabled() =
        getBoolean(FeatureFlag.ENABLE_TPLINK_BRIGHTNESS_CONTROL.key)

    @Deprecated("Use isFeatureEnabled with FeatureFlag enum instead")
    override fun isTplinkColourControlEnabled() =
        getBoolean(FeatureFlag.ENABLE_TPLINK_COLOUR_CONTROL.key)

    @Deprecated("Use isFeatureEnabled with FeatureFlag enum instead")
    override fun isZwaveBrightnessEnabled() =
        getBoolean(FeatureFlag.ENABLE_ZWAVE_BRIGHTNESS_CONTROL.key)

    @Deprecated("Use isFeatureEnabled with FeatureFlag enum instead")
    override fun isZwaveColourControlEnabled() =
        getBoolean(FeatureFlag.ENABLE_ZWAVE_COLOUR_CONTROL.key)

    @Deprecated("Use isFeatureEnabled with FeatureFlag enum instead")
    override fun isCheckForDeviceCommandsEnabled() =
        getBoolean(FeatureFlag.ENABLE_CHECK_FOR_DEVICE_COMMANDS.key)

    @Deprecated("Use isFeatureEnabled with FeatureFlag enum instead")
    override fun isDiscoverTabEnabled() =
        getBoolean(FeatureFlag.ENABLE_DISCOVER_TAB.key)

    @Deprecated("Use isFeatureEnabled with FeatureFlag enum instead")
    override fun isHubEnabled() = getBoolean(FeatureFlag.ENABLE_HUB.key)

    @Deprecated("Use isFeatureEnabled with FeatureFlag enum instead")
    override fun isNetworkDeviceOnboardingQueryEnabled() =
        getBoolean(FeatureFlag.ENABLE_NETWORK_DEVICE_ONBOARDING_QUERY.key)

    @Deprecated("Use isFeatureEnabled with FeatureFlag enum instead")
    override fun isIotmi3pEnabled() = getBoolean(FeatureFlag.IOTMI_3P.key)

    @Deprecated("Use isFeatureEnabled with FeatureFlag enum instead")
    override fun isAccessManagementEnabled() =
        getBoolean(FeatureFlag.ENABLE_ACCESS_MANAGEMENT.key)

    @Deprecated("Use isFeatureEnabled with FeatureFlag enum instead")
    override fun isSpeedTestEnabled() =
        getBoolean(FeatureFlag.ENABLE_SPEED_TEST.key)

    @Deprecated("Use isFeatureEnabled with FeatureFlag enum instead")
    override fun isRestartEnabled() =
        getBoolean(FeatureFlag.ENABLE_NETWORK_RESTART.key)

    @Deprecated("Use isFeatureEnabled with FeatureFlag enum instead")
    override fun isNetworkTopologyEnabled() =
        getBoolean(FeatureFlag.ENABLE_NETWORK_TOPOLOGY.key)

    @Deprecated("Use isFeatureEnabled with FeatureFlag enum instead")
    override fun isNetworkDeviceOnboardingScannerEnabled() =
        getBoolean(FeatureFlag.ENABLE_NETWORK_DEVICE_ONBOARDING_SCANNER.key)

    @Deprecated("Use isFeatureEnabled with FeatureFlag enum instead")
    override fun isCameraMinFirmwareVersionEnable() =
        getBoolean(FeatureFlag.ENABLE_CAMERA_MIN_FIRMWARE_VERSION.key)

    @Deprecated("Use isFeatureEnabled with FeatureFlag enum instead")
    override fun isRefreshCommandOnLaunchEnabled() =
        getBoolean(FeatureFlag.ENABLE_REFRESH_ON_LAUNCH.key)

    @Deprecated("Use isFeatureEnabled with FeatureFlag enum instead")
    override fun isDeviceNotificationSettingsEnabled() =
        getBoolean(FeatureFlag.ENABLE_DEVICE_NOTIFICATION_SETTINGS.key)

    @Deprecated("Use isFeatureEnabled with FeatureFlag enum instead")
    override fun isHomeOccupancyEnabled() =
        getBoolean(FeatureFlag.ENABLE_HOME_OCCUPANCY.key)

    @Deprecated("Use isFeatureEnabled with FeatureFlag enum instead")
    override fun isCameraEventsAsTriggerEnabled() =
        getBoolean(FeatureFlag.ENABLE_CAMERA_EVENTS_AS_ROUTINE_TRIGGER.key)

    @Deprecated("Duplicated method, use isDeviceNotificationSettingsEnabled instead")
    override fun showNotificationsSettings() =
        getBoolean(FeatureFlag.ENABLE_DEVICE_NOTIFICATION_SETTINGS.key)

    // ── Version Comparison Utility ──────────────────────────────────────

    /**
     * Compare two semantic version strings.
     * Returns a negative integer, zero, or a positive integer if [v1] is less than,
     * equal to, or greater than [v2].
     */
    private fun compareVersion(v1: String, v2: String): Int {
        val parts1 = v1.split(".").map { it.toIntOrNull() ?: 0 }
        val parts2 = v2.split(".").map { it.toIntOrNull() ?: 0 }
        val maxLength = maxOf(parts1.size, parts2.size)

        for (i in 0 until maxLength) {
            val p1 = parts1.getOrElse(i) { 0 }
            val p2 = parts2.getOrElse(i) { 0 }
            if (p1 != p2) return p1.compareTo(p2)
        }
        return 0
    }

    private companion object {
        // Named secondary Firebase app so the SDK never collides with the host
        // app's default [FirebaseApp].
        const val FIREBASE_APP_NAME = "argus-sdk"
    }
}
