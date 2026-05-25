package cloud.projectargus

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Standalone copies of a host app's feature flag data models.
 * These are included in the SDK so it can compile independently. In a real app
 * integration, the app's own versions are used via dependency injection.
 */

interface FeatureFlagData

data class AppVersion(
    val minSupported: String,
    val enforcementDate: String,
    val snoozePeriod: Int
) : FeatureFlagData

data class MaintenanceMode(
    val isEnabled: Boolean = false,
    val data: List<MaintenanceModeData> = emptyList()
) : FeatureFlagData

data class MaintenanceModeData(
    val locale: String = "",
    val title: String = "",
    val description: String = "",
    val imgLink: String = ""
)

data class BookCall(
    @Json(name = "min_android_version")
    val appVersion: String,
    @Json(name = "use_genesys_v2_api")
    val useGenesysV2Api: Boolean = false,
) : FeatureFlagData

data class ShAssistantConfiguration(
    @Json(name = "unsupported_language")
    val unsupportedLanguage: List<String>,
    @Json(name = "unsupported_province")
    val unsupportedProvince: List<String>,
    @Json(name = "unsupported_sku")
    val unsupportedSku: List<String>,
    @Json(name = "force_enabled")
    var forceEnabled: Boolean,
    @Json(name = "sku_enabled")
    var skuEnabled: Boolean,
    @Json(name = "energy_sku_enabled")
    var energySkuEnabled: Boolean? = null,
    @Json(name = "beta_screen_enabled")
    var betaScreenEnabled: Boolean? = null,
    @Json(name = "enable_live_audio")
    var enableLiveAudio: Boolean? = null,
    @Json(name = "onboarding_top_bar")
    var onboardingTopBar: Boolean? = null
) : FeatureFlagData

@JsonClass(generateAdapter = true)
data class InternetBackupConfiguration(
    @Json(name = "backup_enable")
    val backupEnable: Boolean,
    @Json(name = "supported_firmware_versions")
    val supportedFirmwareVersions: List<String>
) : FeatureFlagData

data class CameraMinFirmwareVersion(
    val doorbell: String = "",
    val indoor: String = "",
    val outdoor: String = ""
) : FeatureFlagData

data class Connectors(
    val connectors: List<String> = emptyList()
) : FeatureFlagData

@JsonClass(generateAdapter = true)
data class DeviceListConfiguration(
    @Json(name = "enable_unified_device_list_v2")
    val enableUnifiedDeviceListV2: Boolean = false,
    @Json(name = "config")
    val config: Config
) : FeatureFlagData

@JsonClass(generateAdapter = true)
data class Config(
    @Json(name = "enable_new_home_screen_api")
    val enableNewHomeScreenApi: Boolean,
    @Json(name = "enable_routine_internet_devices")
    val enableRoutineInternetDevices: Boolean,
    @Json(name = "device_list_v2_hubid_filter_zwave_onboarding")
    val deviceListV2HubidFilterZwaveOnboarding: Boolean,
    @Json(name = "device_list_v2_poll_individual_device")
    val deviceListV2PollIndividualDevice: Boolean,
    @Json(name = "device_list_page_size")
    val deviceListPageSize: Int
) : FeatureFlagData

@JsonClass(generateAdapter = true)
data class DeviceTypeConfig(
    @Json(name = "internetNetworkDevices")
    val internetNetworkDevices: DeviceList,
    @Json(name = "internetEndUserDevices")
    val internetEndUserDevices: DeviceList,
    @Json(name = "managedEndUserDevices")
    val managedEndUserDevices: DeviceList
) : FeatureFlagData

@JsonClass(generateAdapter = true)
data class DeviceList(
    @Json(name = "devices")
    val devices: List<String>
)

@JsonClass(generateAdapter = true)
data class RefreshCallConfiguration(
    @Json(name = "cooldown_period")
    val cooldownPeriod: Long?,
    @Json(name = "cooldown_period_enabled")
    val isCooldownPeriodEnabled: Boolean?
) : FeatureFlagData

@JsonClass(generateAdapter = true)
data class PollingIntervalConfiguration(
    @Json(name = "default")
    val default: IntervalConfig?,
    @Json(name = "network_device_onboarding")
    val networkDeviceOnboarding: IntervalConfig?,
    @Json(name = "network_device_refresh")
    val networkDeviceRefresh: IntervalConfig?
) : FeatureFlagData

@JsonClass(generateAdapter = true)
data class IntervalConfig(
    @Json(name = "interval_s")
    val intervalS: Int?,
    @Json(name = "timeout_s")
    val timeoutS: Int?,
    @Json(name = "retry_count")
    val retryCount: Int?
)

@JsonClass(generateAdapter = true)
data class FeedbackPromptConfiguration(
    @Json(name = "feedback_prompt_enabled")
    val feedbackPromptEnabled: Boolean = true,
    @Json(name = "feedback_prompt_cooldown_period_days")
    val feedbackPromptCooldownPeriodDays: Int = 21,
    @Json(name = "feedback_prompt_sessions_threshold")
    val feedbackPromptSessionThreshold: Int = 10,
    @Json(name = "feedback_prompt_devices_threshold")
    val feedbackPromptDevicesThreshold: Int = 2,
    @Json(name = "app_rating_prompt_live_view_loading_threshold")
    val appRatingPromptLiveViewLoadingThreshold: Int = 5
)
