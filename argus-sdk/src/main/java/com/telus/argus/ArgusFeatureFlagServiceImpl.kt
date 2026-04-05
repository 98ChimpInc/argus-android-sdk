package com.telus.argus

import com.google.firebase.auth.FirebaseAuth
import com.squareup.moshi.Moshi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
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
 * Fetches resolved flag values from the Argus HTTP endpoint and caches them in a
 * thread-safe [ConcurrentHashMap]. Falls back to bundled defaults from [FeatureFlag]
 * enum entries if the endpoint is unreachable.
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

    // ── Initialisation ──────────────────────────────────────────────────

    override fun initialize() {
        appCoroutineScope.launch {
            try {
                loadDefaults()
                fetchFlags()
            } catch (ex: Exception) {
                Timber.e(ex, "Argus initialisation failed, using defaults")
                _remoteConfigState.value = true
            }
        }
    }

    private fun loadDefaults() {
        FeatureFlag.getDefaultValues().forEach { (key, value) ->
            flagCache[key] = value.toString()
        }
    }

    private suspend fun fetchFlags() {
        _remoteConfigState.value = false

        val url = buildString {
            append(configuration.baseURL)
            append("/api/flags")
            append("?platform=android")
            append("&version=").append(URLEncoder.encode(appVersionName, "UTF-8"))
            append("&userId=").append(URLEncoder.encode(configuration.userId, "UTF-8"))
            append("&tenantId=").append(URLEncoder.encode(configuration.tenantId, "UTF-8"))
            append("&env=").append(URLEncoder.encode(configuration.environment, "UTF-8"))
        }

        val idToken = FirebaseAuth.getInstance().currentUser
            ?.getIdToken(false)?.await()?.token
            ?: throw IllegalStateException("No Firebase Auth token available")

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $idToken")
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
    override fun isSweeprEnabled() = getBoolean(FeatureFlag.ENABLE_SWEEPR.key)

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
}
