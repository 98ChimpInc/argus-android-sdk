package cloud.projectargus

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Standalone copy of a host app's FeatureFlagService interface.
 * This is included in the SDK so it can compile independently. In a real app
 * integration, the app's own version is used via dependency injection.
 */
@Suppress("TooManyFunctions")
interface FeatureFlagService {
    fun initialize()
    val isActive: StateFlow<Boolean>

    /**
     * Emits after each refresh that publishes resolved flag values, mirroring
     * the iOS SDK's `configUpdatedPublisher`:
     *  - `null` on a full refresh (the cold-start HTTP fetch and the first
     *    live snapshot) — treat every flag as potentially changed;
     *  - the set of keys whose resolved value changed on subsequent updates;
     *  - nothing when a re-resolution changes no values.
     *
     * Event-stream semantics (no replay): collectors only see updates that
     * arrive after they subscribe. Use [isActive] to gate the first read.
     */
    val configUpdated: SharedFlow<Set<String>?>
    fun getString(key: String): String
    fun getBoolean(key: String): Boolean
    fun getLong(featureFlag: FeatureFlag): Long

    fun getFeatureData(flag: FeatureFlag): FeatureFlagData?
    fun isFeatureEnabled(featureFlag: FeatureFlag): Boolean

    fun getAppVersion(): AppVersion?
    fun getEnabledConnectors(): Connectors?
    fun getShAssistantConfiguration(): ShAssistantConfiguration?
    fun getMaintenanceMode(): MaintenanceMode
    fun getCameraMinFirmwareVersion(): CameraMinFirmwareVersion?
    fun getInternetBackupConfiguration(): InternetBackupConfiguration?
    fun getProfileHHRefreshIntervalSeconds(): Long
    fun getDeviceListV2ApiConfiguration(): DeviceListConfiguration?
    fun getDeviceListCategory(): DeviceTypeConfig?
    fun getRefreshCallConfiguration(): RefreshCallConfiguration?
    fun getPollingConfig(): PollingIntervalConfiguration?
    fun getDeviceCommandPollingIntervals(): List<Int>
    fun getDeviceRefreshPollingIntervalSeconds(): Long
    fun getFeedbackPromptConfiguration(): FeedbackPromptConfiguration
    fun isBookCallEnabled(): Boolean
    fun shouldUseGenesysV2Api(): Boolean

    @Deprecated("Use isFeatureEnabled with FeatureFlag enum instead")
    fun isTwhSupported(): Boolean

    @Deprecated("Use isFeatureEnabled with FeatureFlag enum instead")
    fun isSupportChatEnabled(): Boolean

    @Deprecated("Use isFeatureEnabled with FeatureFlag enum instead")
    fun isSupportEnabled(): Boolean

    @Deprecated("Use isFeatureEnabled with FeatureFlag enum instead")
    fun isAutoPlayLiveFeedEnabled(): Boolean

    @Deprecated("Use isFeatureEnabled with FeatureFlag enum instead")
    fun isOneTrustEnabled(): Boolean

    @Deprecated("Use isFeatureEnabled with FeatureFlag enum instead")
    fun isInternetOnlySkuEnabled(): Boolean

    @Deprecated("Use isFeatureEnabled with FeatureFlag enum instead")
    fun isHiddenClipLengthEnabled(): Boolean

    @Deprecated("Use isFeatureEnabled with FeatureFlag enum instead")
    fun isCameraConnectivityCheckEnabled(): Boolean

    @Deprecated("Use isFeatureEnabled with FeatureFlag enum instead")
    fun isRewardsPointsEnable(): Boolean

    @Deprecated("Use isFeatureEnabled with FeatureFlag enum instead")
    fun isAutomationSkuEnabled(): Boolean

    @Deprecated("Use isFeatureEnabled with FeatureFlag enum instead")
    fun isTplinkBrightnessEnabled(): Boolean

    @Deprecated("Use isFeatureEnabled with FeatureFlag enum instead")
    fun isTplinkColourControlEnabled(): Boolean

    @Deprecated("Use isFeatureEnabled with FeatureFlag enum instead")
    fun isZwaveBrightnessEnabled(): Boolean

    @Deprecated("Use isFeatureEnabled with FeatureFlag enum instead")
    fun isZwaveColourControlEnabled(): Boolean

    @Deprecated("Use isFeatureEnabled with FeatureFlag enum instead")
    fun isCheckForDeviceCommandsEnabled(): Boolean

    @Deprecated("Use isFeatureEnabled with FeatureFlag enum instead")
    fun isDiscoverTabEnabled(): Boolean

    @Deprecated("Use isFeatureEnabled with FeatureFlag enum instead")
    fun isHubEnabled(): Boolean

    @Deprecated("Use isFeatureEnabled with FeatureFlag enum instead")
    fun isNetworkDeviceOnboardingQueryEnabled(): Boolean

    @Deprecated("Use isFeatureEnabled with FeatureFlag enum instead")
    fun isIotmi3pEnabled(): Boolean

    @Deprecated("Use isFeatureEnabled with FeatureFlag enum instead")
    fun isAccessManagementEnabled(): Boolean

    @Deprecated("Use isFeatureEnabled with FeatureFlag enum instead")
    fun isSpeedTestEnabled(): Boolean

    @Deprecated("Use isFeatureEnabled with FeatureFlag enum instead")
    fun isRestartEnabled(): Boolean

    @Deprecated("Use isFeatureEnabled with FeatureFlag enum instead")
    fun isNetworkTopologyEnabled(): Boolean

    @Deprecated("Use isFeatureEnabled with FeatureFlag enum instead")
    fun isNetworkDeviceOnboardingScannerEnabled(): Boolean

    @Deprecated("Use isFeatureEnabled with FeatureFlag enum instead")
    fun isCameraMinFirmwareVersionEnable(): Boolean

    @Deprecated("Use isFeatureEnabled with FeatureFlag enum instead")
    fun isRefreshCommandOnLaunchEnabled(): Boolean

    @Deprecated("Use isFeatureEnabled with FeatureFlag enum instead")
    fun isDeviceNotificationSettingsEnabled(): Boolean

    @Deprecated("Use isFeatureEnabled with FeatureFlag enum instead")
    fun isHomeOccupancyEnabled(): Boolean

    @Deprecated("Use isFeatureEnabled with FeatureFlag enum instead")
    fun isCameraEventsAsTriggerEnabled(): Boolean

    @Deprecated("Use isFeatureEnabled with FeatureFlag enum instead")
    fun showNotificationsSettings(): Boolean
}
