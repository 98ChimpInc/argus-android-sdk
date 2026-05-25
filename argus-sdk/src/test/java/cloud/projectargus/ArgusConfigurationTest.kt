package cloud.projectargus

import android.content.Context
import android.content.pm.ApplicationInfo
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for [ArgusConfiguration.autoDetectedEnvironment].
 *
 * Three branches to pin:
 *   1. Host app is debuggable → "dev".
 *   2. Host app is release + BuildConfig.ARGUS_TRACK present → that value.
 *   3. Host app is release + no BuildConfig override → "prod".
 *
 * Branch 2 is verified against a hand-rolled fixture class at
 * `cloud.projectargus.buildconfigfixture.BuildConfig` (see the .java file in the
 * same test source tree). Branch 3 points the reflection lookup at a package
 * with no BuildConfig class so the lookup falls through.
 */
class ArgusConfigurationTest {

    private fun mockContext(
        packageName: String,
        debuggable: Boolean
    ): Context {
        // ApplicationInfo's constructor is a stub in the unit-test android.jar,
        // so we mock the instance and stub only the `flags` field we read.
        val appInfo = mockk<ApplicationInfo>(relaxed = true)
        appInfo.flags = if (debuggable) ApplicationInfo.FLAG_DEBUGGABLE else 0

        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context
        every { context.applicationInfo } returns appInfo
        every { context.packageName } returns packageName
        return context
    }

    @Test
    fun `debuggable host app maps to dev`() {
        val context = mockContext(
            packageName = "cloud.projectargus.buildconfigfixture",
            debuggable = true
        )

        // Even though the fixture package DOES carry a BuildConfig.ARGUS_TRACK,
        // the debuggable signal must win ... debug builds always resolve to "dev".
        assertEquals("dev", ArgusConfiguration.autoDetectedEnvironment(context))
    }

    @Test
    fun `release build with BuildConfig ARGUS_TRACK uses the override value`() {
        val context = mockContext(
            packageName = "cloud.projectargus.buildconfigfixture",
            debuggable = false
        )

        // The fixture class declares `ARGUS_TRACK = "staging"`.
        assertEquals("staging", ArgusConfiguration.autoDetectedEnvironment(context))
    }

    @Test
    fun `release build with no BuildConfig override falls back to prod`() {
        val context = mockContext(
            packageName = "cloud.projectargus.no_buildconfig_here",
            debuggable = false
        )

        assertEquals("prod", ArgusConfiguration.autoDetectedEnvironment(context))
    }

    @Test
    fun `explicit environment on create wins over auto-detect`() {
        val context = mockContext(
            packageName = "cloud.projectargus.buildconfigfixture",
            debuggable = true
        )

        val config = ArgusConfiguration.create(
            context = context,
            baseURL = "https://example.com",
            tenantId = "northwind",
            userId = "user-1",
            environment = "prod"
        )

        assertEquals("prod", config.environment)
    }

    @Test
    fun `create without explicit environment uses auto-detect`() {
        val context = mockContext(
            packageName = "cloud.projectargus.buildconfigfixture",
            debuggable = false
        )

        val config = ArgusConfiguration.create(
            context = context,
            baseURL = "https://example.com",
            tenantId = "northwind",
            userId = "user-1"
        )

        assertEquals("staging", config.environment)
    }
}
