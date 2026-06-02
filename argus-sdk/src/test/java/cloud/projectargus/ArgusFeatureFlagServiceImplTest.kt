package cloud.projectargus

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

/**
 * Tests for [ArgusFeatureFlagServiceImpl].
 *
 * These tests exercise the cache-based getters directly by populating the
 * internal flagCache via reflection, avoiding the need for Firebase Auth
 * and real HTTP calls. Integration tests with MockWebServer would be added
 * in the host app's test suite.
 */
@ExperimentalCoroutinesApi
class ArgusFeatureFlagServiceImplTest {

    private lateinit var moshi: Moshi
    private lateinit var service: ArgusFeatureFlagServiceImpl
    private lateinit var flagCache: ConcurrentHashMap<String, String>

    @Before
    fun setUp() {
        moshi = Moshi.Builder()
            .addLast(KotlinJsonAdapterFactory())
            .build()

        val configuration = ArgusConfiguration(
            apiKey = "argus_prod_test_key",
            baseURL = "https://example.com",
            tenantId = "acme_corp",
            environment = "prod",
            userId = "test-user-123"
        )

        service = ArgusFeatureFlagServiceImpl(
            appVersionName = "1.42.0",
            appCoroutineScope = kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.Dispatchers.Unconfined
            ),
            moshi = moshi,
            configuration = configuration
        )

        // Access the private flagCache via reflection so we can seed test data
        val cacheField = ArgusFeatureFlagServiceImpl::class.java
            .getDeclaredField("flagCache")
        cacheField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        flagCache = cacheField.get(service) as ConcurrentHashMap<String, String>
    }

    // ── isActive ────────────────────────────────────────────────────────

    @Test
    fun `isActive is false before initialize`() {
        assertFalse(service.isActive.value)
    }

    // ── getString ───────────────────────────────────────────────────────

    @Test
    fun `getString returns cached value`() {
        flagCache["my_key"] = "hello_world"
        assertEquals("hello_world", service.getString("my_key"))
    }

    @Test
    fun `getString returns empty string when key not found`() {
        assertEquals("", service.getString("nonexistent_key"))
    }

    // ── getBoolean ──────────────────────────────────────────────────────

    @Test
    fun `getBoolean parses true string`() {
        flagCache["bool_key"] = "true"
        assertTrue(service.getBoolean("bool_key"))
    }

    @Test
    fun `getBoolean parses false string`() {
        flagCache["bool_key"] = "false"
        assertFalse(service.getBoolean("bool_key"))
    }

    @Test
    fun `getBoolean returns false for non-boolean string`() {
        flagCache["bool_key"] = "not_a_bool"
        assertFalse(service.getBoolean("bool_key"))
    }

    @Test
    fun `getBoolean returns false for missing key`() {
        assertFalse(service.getBoolean("missing_key"))
    }

    // ── getLong ──────────────────────────────────────────────────────────

    @Test
    fun `getLong parses numeric string`() {
        flagCache[FeatureFlag.PROFILE_HH_REFRESH_INTERVAL_SECONDS.key] = "120"
        assertEquals(120L, service.getLong(FeatureFlag.PROFILE_HH_REFRESH_INTERVAL_SECONDS))
    }

    @Test
    fun `getLong returns 0 for non-numeric string`() {
        flagCache[FeatureFlag.PROFILE_HH_REFRESH_INTERVAL_SECONDS.key] = "abc"
        assertEquals(0L, service.getLong(FeatureFlag.PROFILE_HH_REFRESH_INTERVAL_SECONDS))
    }

    @Test
    fun `getLong returns 0 for missing key`() {
        assertEquals(0L, service.getLong(FeatureFlag.PROFILE_HH_REFRESH_INTERVAL_SECONDS))
    }

    // ── isFeatureEnabled ────────────────────────────────────────────────

    @Test
    fun `isFeatureEnabled delegates to getBoolean`() {
        flagCache[FeatureFlag.ENABLE_SUPPORT_CHAT.key] = "true"
        assertTrue(service.isFeatureEnabled(FeatureFlag.ENABLE_SUPPORT_CHAT))
    }

    @Test
    fun `isFeatureEnabled returns false for missing flag`() {
        assertFalse(service.isFeatureEnabled(FeatureFlag.ENABLE_SUPPORT_CHAT))
    }

    // ── getFeatureData ──────────────────────────────────────────────────

    @Test
    fun `getFeatureData extracts android sub-object`() {
        val json = """{"android":{"minSupported":"1.40.0","enforcementDate":"2025-12-31","snoozePeriod":7},"ios":{"minSupported":"2.0.0","enforcementDate":"2025-12-31","snoozePeriod":14}}"""
        flagCache[FeatureFlag.APP_VERSION_KEY.key] = json

        val result = service.getFeatureData(FeatureFlag.APP_VERSION_KEY) as? AppVersion
        assertNotNull(result)
        assertEquals("1.40.0", result?.minSupported)
        assertEquals(7, result?.snoozePeriod)
    }

    @Test
    fun `getFeatureData falls back to full JSON when no android key`() {
        val json = """{"isEnabled":true,"data":[]}"""
        flagCache[FeatureFlag.MAINTENANCE_MODE.key] = json

        val result = service.getFeatureData(FeatureFlag.MAINTENANCE_MODE) as? MaintenanceMode
        assertNotNull(result)
        assertTrue(result!!.isEnabled)
    }

    @Test
    fun `getFeatureData returns null for empty string`() {
        flagCache[FeatureFlag.APP_VERSION_KEY.key] = ""
        assertNull(service.getFeatureData(FeatureFlag.APP_VERSION_KEY))
    }

    @Test
    fun `getFeatureData returns null for flag without rendererType`() {
        flagCache[FeatureFlag.ENABLE_SUPPORT_CHAT.key] = "true"
        assertNull(service.getFeatureData(FeatureFlag.ENABLE_SUPPORT_CHAT))
    }

    @Test
    fun `getFeatureData returns null for missing key`() {
        assertNull(service.getFeatureData(FeatureFlag.APP_VERSION_KEY))
    }

    // ── getMaintenanceMode ──────────────────────────────────────────────

    @Test
    fun `getMaintenanceMode returns default when parse fails`() {
        flagCache[FeatureFlag.MAINTENANCE_MODE.key] = "not_valid_json"
        val result = service.getMaintenanceMode()
        assertFalse(result.isEnabled)
        assertTrue(result.data.isEmpty())
    }

    @Test
    fun `getMaintenanceMode returns default when key missing`() {
        val result = service.getMaintenanceMode()
        assertFalse(result.isEnabled)
    }

    // ── getDeviceCommandPollingIntervals ─────────────────────────────────

    @Test
    fun `getDeviceCommandPollingIntervals parses JSON array`() {
        flagCache[FeatureFlag.DEVICE_COMMAND_POLLING_INTERVALS.key] = "[1,2,3,4,5]"
        assertEquals(listOf(1, 2, 3, 4, 5), service.getDeviceCommandPollingIntervals())
    }

    @Test
    fun `getDeviceCommandPollingIntervals returns default on parse failure`() {
        flagCache[FeatureFlag.DEVICE_COMMAND_POLLING_INTERVALS.key] = "not_json"
        assertEquals(listOf(3, 3, 3, 3), service.getDeviceCommandPollingIntervals())
    }

    @Test
    fun `getDeviceCommandPollingIntervals returns default for blank value`() {
        flagCache[FeatureFlag.DEVICE_COMMAND_POLLING_INTERVALS.key] = ""
        assertEquals(listOf(3, 3, 3, 3), service.getDeviceCommandPollingIntervals())
    }

    @Test
    fun `getDeviceCommandPollingIntervals returns default for missing key`() {
        assertEquals(listOf(3, 3, 3, 3), service.getDeviceCommandPollingIntervals())
    }

    // ── getDeviceRefreshPollingIntervalSeconds ───────────────────────────

    @Test
    fun `getDeviceRefreshPollingIntervalSeconds returns value when positive`() {
        flagCache[FeatureFlag.IN_APP_DEVICE_POLLING_INTERVAL.key] = "60"
        assertEquals(60L, service.getDeviceRefreshPollingIntervalSeconds())
    }

    @Test
    fun `getDeviceRefreshPollingIntervalSeconds returns default when zero`() {
        flagCache[FeatureFlag.IN_APP_DEVICE_POLLING_INTERVAL.key] = "0"
        assertEquals(
            FeatureFlagConstants.DEFAULT_DEVICE_REFRESH_POLLING_INTERVAL_SECONDS,
            service.getDeviceRefreshPollingIntervalSeconds()
        )
    }

    @Test
    fun `getDeviceRefreshPollingIntervalSeconds returns default when missing`() {
        assertEquals(
            FeatureFlagConstants.DEFAULT_DEVICE_REFRESH_POLLING_INTERVAL_SECONDS,
            service.getDeviceRefreshPollingIntervalSeconds()
        )
    }

    // ── getFeedbackPromptConfiguration ───────────────────────────────────

    @Test
    fun `getFeedbackPromptConfiguration assembles from multiple flags`() {
        flagCache[FeatureFlag.FEEDBACK_PROMPT_ENABLED.key] = "true"
        flagCache[FeatureFlag.FEEDBACK_PROMPT_SESSIONS_THRESHOLD.key] = "5"
        flagCache[FeatureFlag.FEEDBACK_PROMPT_COOLDOWN_PERIOD_DAYS.key] = "14"
        flagCache[FeatureFlag.FEEDBACK_PROMPT_LIVE_VIEW_THRESHOLD.key] = "3"
        flagCache[FeatureFlag.FEEDBACK_PROMPT_DEVICES_THRESHOLD.key] = "1"

        val config = service.getFeedbackPromptConfiguration()
        assertTrue(config.feedbackPromptEnabled)
        assertEquals(5, config.feedbackPromptSessionThreshold)
        assertEquals(14, config.feedbackPromptCooldownPeriodDays)
        assertEquals(3, config.appRatingPromptLiveViewLoadingThreshold)
        assertEquals(1, config.feedbackPromptDevicesThreshold)
    }

    @Test
    fun `getFeedbackPromptConfiguration uses defaults when values are zero or missing`() {
        val config = service.getFeedbackPromptConfiguration()
        val defaults = FeedbackPromptConfiguration()
        assertEquals(defaults.feedbackPromptSessionThreshold, config.feedbackPromptSessionThreshold)
        assertEquals(defaults.feedbackPromptCooldownPeriodDays, config.feedbackPromptCooldownPeriodDays)
        assertEquals(defaults.feedbackPromptDevicesThreshold, config.feedbackPromptDevicesThreshold)
        assertEquals(
            defaults.appRatingPromptLiveViewLoadingThreshold,
            config.appRatingPromptLiveViewLoadingThreshold
        )
    }

    // ── isBookCallEnabled ───────────────────────────────────────────────

    @Test
    fun `isBookCallEnabled returns true when app version meets minimum`() {
        val json = """{"min_android_version":"1.40.0","use_genesys_v2_api":false}"""
        flagCache[FeatureFlag.ENABLE_BOOK_CALL.key] = json
        // appVersionName is "1.42.0" which is >= "1.40.0"
        assertTrue(service.isBookCallEnabled())
    }

    @Test
    fun `isBookCallEnabled returns false when app version is below minimum`() {
        val json = """{"min_android_version":"2.0.0","use_genesys_v2_api":false}"""
        flagCache[FeatureFlag.ENABLE_BOOK_CALL.key] = json
        // appVersionName is "1.42.0" which is < "2.0.0"
        assertFalse(service.isBookCallEnabled())
    }

    @Test
    fun `isBookCallEnabled returns false when no data`() {
        assertFalse(service.isBookCallEnabled())
    }

    // ── shouldUseGenesysV2Api ───────────────────────────────────────────

    @Test
    fun `shouldUseGenesysV2Api reads from BookCall data`() {
        val json = """{"min_android_version":"1.0.0","use_genesys_v2_api":true}"""
        flagCache[FeatureFlag.ENABLE_BOOK_CALL.key] = json
        assertTrue(service.shouldUseGenesysV2Api())
    }

    @Test
    fun `shouldUseGenesysV2Api returns false when no data`() {
        assertFalse(service.shouldUseGenesysV2Api())
    }

    // ── Deprecated Boolean Getters ──────────────────────────────────────

    @Test
    fun `deprecated boolean getters delegate correctly`() {
        flagCache[FeatureFlag.ENABLE_SUPPORT_CHAT.key] = "true"
        flagCache[FeatureFlag.TWH_SUPPORTED.key] = "true"
        flagCache[FeatureFlag.ENABLE_SUPPORT.key] = "false"

        @Suppress("DEPRECATION")
        assertTrue(service.isSupportChatEnabled())
        @Suppress("DEPRECATION")
        assertTrue(service.isTwhSupported())
        @Suppress("DEPRECATION")
        assertFalse(service.isSupportEnabled())
    }

    // ── Offline Fallback ────────────────────────────────────────────────

    @Test
    fun `offline fallback uses FeatureFlag enum defaults`() {
        // Simulate loadDefaults()
        FeatureFlag.getDefaultValues().forEach { (key, value) ->
            flagCache[key] = value.toString()
        }

        // ENABLE_SUPPORT_CHAT has defaultValue = false
        assertFalse(service.getBoolean(FeatureFlag.ENABLE_SUPPORT_CHAT.key))

        // ENABLE_OAUTH2_LOGIN has defaultValue = false
        assertFalse(service.getBoolean(FeatureFlag.ENABLE_OAUTH2_LOGIN.key))
    }
}
