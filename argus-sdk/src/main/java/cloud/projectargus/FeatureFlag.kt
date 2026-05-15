package cloud.projectargus

import kotlin.reflect.KClass

/**
 * Standalone copy of a host app's FeatureFlag enum.
 * This is included in the SDK so it can compile independently. In a real app
 * integration, the app's own version is used via dependency injection.
 */
enum class FeatureFlag(
    val key: String,
    val rendererType: KClass<out FeatureFlagData>? = null,
    val groupTag: String = GROUP_DEFAULT,
    val defaultValue: Any? = null
) {
    ENABLE_SWEEPR("enable_sweepr", defaultValue = false),
    ENABLE_OAUTH2_LOGIN("enable_oauth2_login", defaultValue = false),
    TWH_SUPPORTED("twh_supported"),
    ENABLE_NETWORK_GUEST_WIFI("enable_network_guest_wifi"),
    AUTO_PLAY_LIVE_FEED_ENABLED("auto_play_live_feed_enabled"),
    ENABLE_SUPPORT("enable_support"),
    ENABLE_ONE_TRUST("enable_one_trust"),
    ENABLE_INTERNET_ONLY_SKU("enable_internet_only_sku"),
    ENABLE_HIDE_CLIP_LENGTH("hide_clip_length_from_clips_list_ui"),
    ENABLE_1P_CAMERA_CONNECTIVITY("enable_1p_camera_connectivity"),
    ENABLE_REWARDS_POINTS("enable_rewards_points"),
    ENABLE_AUTOMATION_SKU("enable_automation_sku"),
    ENABLE_TPLINK_BRIGHTNESS_CONTROL("enable_tplink_brightness_control"),
    ENABLE_TPLINK_COLOUR_CONTROL("enable_tplink_colour_control"),
    ENABLE_ZWAVE_BRIGHTNESS_CONTROL("enable_zwave_brightness_control"),
    ENABLE_ZWAVE_COLOUR_CONTROL("enable_zwave_colour_control"),
    ENABLE_CHECK_FOR_DEVICE_COMMANDS("enable_check_for_device_commands"),
    ENABLE_DISCOVER_TAB("enable_services_discover_marketing"),
    ENABLE_HUB("enable_hub_onboarding"),
    ENABLE_NETWORK_DEVICE_ONBOARDING_QUERY("enable_network_device_onboarding_query"),
    IOTMI_3P("enable_iotmi_3p"),
    ENABLE_ACCESS_MANAGEMENT("enable_access_management_feature"),
    ENABLE_SPEED_TEST("enable_network_speed_test"),
    ENABLE_NETWORK_RESTART("enable_network_restart"),
    ENABLE_NETWORK_WPS_PAIR("enable_network_wps_pair"),
    ENABLE_REFRESH_ON_LAUNCH("enable_internetdevices_refresh_on_app_launch"),
    ENABLE_ZWAVE_REFRESH_DEVICES("zwave_device_update_enabled"),
    POST_REFRESH_DELAY("post_refresh_delay_sec"),
    ENABLE_NETWORK_TOPOLOGY("enable_network_topology"),
    ENABLE_NETWORK_WIFI_DETAIL("enable_network_wifi_detail"),
    ENABLE_NETWORK_WIFI_DETAIL_FOR_T3200("enable_network_wifi_detail_for_t3200"),
    ENABLE_NETWORK_DEVICE_ONBOARDING_SCANNER("enable_network_device_onboarding_scanner"),
    ENABLE_CAMERA_MIN_FIRMWARE_VERSION("enable_camera_min_firmware_version"),
    ENABLE_DEVICE_NOTIFICATION_SETTINGS("enable_device_notification_settings"),
    ENABLE_HOME_OCCUPANCY("enable_home_occupancy"),
    ENABLE_CAMERA_EVENTS_AS_ROUTINE_TRIGGER("enable_camera_events_as_routine_triggers"),
    ENABLE_UNAUTHENTICATED_LOGS("enable_unauthenticated_logs"),
    ENABLE_NEST_ENERGY_EVENTS("enable_nest_energy_events"),
    ENABLE_PROFILE_HH_REFRESH("enable_profile_hh_refresh"),
    PROFILE_HH_REFRESH_INTERVAL_SECONDS("profile_hh_refresh_interval_seconds"),
    ENABLE_VIDEO_DOWNLOAD("enable_video_download"),
    ENABLE_BOOST_WIFI_6_LITE_EXTENDER_DIY("enable_boost_wifi_6_lite_extender_diy"),
    ENABLE_BOOST_WIFI_7_EXTENDER_DIY("enable_boost_wifi_7_extender_diy"),
    ENABLE_DIY_TWH("enable_twh_diy"),
    ENABLE_ROUTINE_ACTION_SCHEDULED_DELAY("enable_routine_action_scheduled_delay"),
    ENABLE_ZWAVE_SENSORS_ONBOARDING("enable_zwave_sensors_onboarding"),
    ENABLE_SHORTEN_OUTDOOR_CAMERA_ONBOARDING("enable_shorten_outdoor_camera_onboarding"),
    ENABLE_SINOPE_ENERGY_EVENT_INFO_CARD("enable_sinope_energy_event_opt_out"),
    ENABLE_HIDE_SINOPE_OPT_OUT("enable_hide_sinope_opt_out"),
    CONNECTING_TO_HUB_TIMEOUT("connecting_to_hub_timeout"),
    ENABLE_HUB_EXCLUSION_MODE("zwave_device_onboarding_hub_exclusion_flow_enabled"),
    HUB_EXCLUSION_TIMEOUT("zwave_ssflow_exclusion_resetting_device_timeout_duration"),
    ENHANCED_ALMOST_READY_Z_WAVE_ONBOARDING("enhanced_almost_ready_z_wave_onboarding"),
    ZWAVE_ONBOARDING_TIMEOUT_SEC("zwave_onboarding_timeout_sec"),
    ENABLE_INTERNET_END_USER_DEVICES("enable_internet_end_user_devices"),
    ENABLE_IEUD_RENAME("enable_ieud_rename"),
    ENABLE_NEW_HOME_CARDS("enable_new_home_cards"),
    ENABLE_NETWORK_SECURITY("enable_network_security"),
    ENABLE_MY_PROFILE_ENDPOINT("enable_my_profile_endpoint"),
    ENABLE_1P_CAMERA_UNLINK("enable_camera_remove_or_unlink"),
    ENABLE_DEVICE_GROUPING("enable_device_grouping"),
    ENABLE_WIFI_ROUTINE("enable_wifi_routines"),
    ENABLE_DISABLE_ROUTINE_TOGGLE("enable_disable_routine_toggle"),
    ENABLE_INTERNET_DIY("enable_internet_diy"),
    EXCLUSION_MODE_ENTRY_POINT_TIMEOUT("exclusion_mode_entry_point_timeout"),
    DEVICE_COMMAND_POLLING_INTERVALS("device_command_polling_intervals"),
    ENABLE_IN_APP_DEVICE_REFRESH_POLLING("enable_in_app_device_refresh_polling"),
    IN_APP_DEVICE_POLLING_INTERVAL("in_app_device_refresh_polling_interval"),

    // Feedback Prompt Config
    FEEDBACK_PROMPT_ENABLED("feedback_prompt_enabled", groupTag = GROUP_FEEDBACK_PROMPT),
    FEEDBACK_PROMPT_COOLDOWN_PERIOD_DAYS(
        "feedback_prompt_cooldown_period_days",
        groupTag = GROUP_FEEDBACK_PROMPT
    ),
    FEEDBACK_PROMPT_SESSIONS_THRESHOLD(
        "feedback_prompt_sessions_threshold",
        groupTag = GROUP_FEEDBACK_PROMPT
    ),
    FEEDBACK_PROMPT_DEVICES_THRESHOLD(
        "feedback_prompt_devices_threshold",
        groupTag = GROUP_FEEDBACK_PROMPT
    ),
    FEEDBACK_PROMPT_LIVE_VIEW_THRESHOLD(
        "feedback_prompt_live_view_loading_threshold",
        groupTag = GROUP_FEEDBACK_PROMPT
    ),

    // Special flags with data renderers
    APP_VERSION_KEY(
        "app_version",
        rendererType = AppVersion::class,
        groupTag = GROUP_SPECIAL,
        defaultValue = """{
            "android": {
                "minSupported": "0.0.0",
                "enforcementDate": "9999-12-31",
                "snoozePeriod": 7
            },
            "ios": {
                "minSupported": "0.0.0",
                "enforcementDate": "9999-12-31",
                "snoozePeriod": 7
            }
        }"""
    ),
    ENABLE_BOOK_CALL(
        "enable_book_call",
        rendererType = BookCall::class,
        groupTag = GROUP_SPECIAL
    ),
    ENABLED_3P_CONNECTORS_KEY(
        "enabled_3p_connectors",
        rendererType = Connectors::class,
        groupTag = GROUP_SPECIAL
    ),
    CAMERA_MIN_FIRMWARE_VERSION(
        "camera_min_firmware_version",
        rendererType = CameraMinFirmwareVersion::class,
        groupTag = GROUP_SPECIAL
    ),
    MAINTENANCE_MODE(
        "maintenance_mode",
        rendererType = MaintenanceMode::class,
        groupTag = GROUP_SPECIAL
    ),
    ENABLE_SH_ASSISTANT(
        "enable_sh_assistant",
        rendererType = ShAssistantConfiguration::class,
        groupTag = GROUP_SPECIAL
    ),
    ENABLE_INTERNET_BACKUP(
        key = "enable_internet_backup",
        rendererType = InternetBackupConfiguration::class,
        groupTag = GROUP_SPECIAL
    ),
    ENABLE_DEVICE_LIST_V2(
        key = "enable_device_list_v2",
        rendererType = DeviceListConfiguration::class,
        groupTag = GROUP_SPECIAL
    ),
    DEVICE_LIST_CATEGORY(
        key = "device_list_category",
        rendererType = DeviceTypeConfig::class,
        groupTag = GROUP_SPECIAL
    ),
    REFRESH_DEVICE_CONFIGURATION(
        key = "refresh_device_configuration",
        rendererType = RefreshCallConfiguration::class,
        groupTag = GROUP_SPECIAL
    ),
    POLLING_CONFIG(
        key = "polling_config",
        rendererType = PollingIntervalConfiguration::class,
        groupTag = GROUP_SPECIAL
    ),
    SDUI_CONFIG(
        key = "sdui_href_component_models_map",
        groupTag = GROUP_SPECIAL
    );

    companion object {
        /**
         * Returns a map of default values for all feature flags.
         */
        fun getDefaultValues(): Map<String, Any> {
            val map = mutableMapOf<String, Any>()
            FeatureFlag.entries.forEach {
                if (it.defaultValue != null) {
                    map[it.key] = it.defaultValue
                }
            }
            return map
        }
    }
}

// Group tag constants
internal const val GROUP_DEFAULT = "default"
internal const val GROUP_FEEDBACK_PROMPT = "feedback_prompt"
internal const val GROUP_SPECIAL = "special"
