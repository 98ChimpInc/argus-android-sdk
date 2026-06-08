package cloud.projectargus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ArgusFlagResolver] — the client-side mirror of the server
 * `resolveFlags` algorithm. No live Firebase required; documents are passed in
 * as plain maps exactly as the Firestore listeners decode them.
 *
 * Parity target: argus-web-app `functions/index.js` resolveFlags + helpers.
 */
class ArgusFlagResolverTest {

    private val androidCtx = ArgusFlagResolver.Context(
        platform = "android",
        version = "1.42.0",
        userId = "user-123",
        language = null
    )

    private fun flag(input: ArgusFlagResolver.FlagInput) =
        ArgusFlagResolver.resolve(listOf(input), emptyMap(), androidCtx)

    // ── env value vs default ────────────────────────────────────────────

    @Test
    fun `env value wins over flag default`() {
        val result = flag(
            ArgusFlagResolver.FlagInput(
                flag = mapOf("name" to "f", "defaultValue" to false),
                env = mapOf("value" to true),
                tenant = null
            )
        )
        assertEquals("true", result["f"])
    }

    @Test
    fun `falls back to flag default when no env doc`() {
        val result = flag(
            ArgusFlagResolver.FlagInput(
                flag = mapOf("name" to "f", "defaultValue" to "hello"),
                env = null,
                tenant = null
            )
        )
        assertEquals("hello", result["f"])
    }

    @Test
    fun `env value missing falls back to default`() {
        val result = flag(
            ArgusFlagResolver.FlagInput(
                flag = mapOf("name" to "f", "defaultValue" to 7L),
                env = mapOf("other" to 1),
                tenant = null
            )
        )
        assertEquals("7", result["f"])
    }

    // ── archived / draft skipped ────────────────────────────────────────

    @Test
    fun `archived flag is skipped`() {
        val result = flag(
            ArgusFlagResolver.FlagInput(
                flag = mapOf("name" to "f", "archived" to true, "defaultValue" to true),
                env = mapOf("value" to true),
                tenant = null
            )
        )
        assertFalse(result.containsKey("f"))
    }

    @Test
    fun `draft flag is skipped`() {
        val result = flag(
            ArgusFlagResolver.FlagInput(
                flag = mapOf("name" to "f", "draft" to true, "defaultValue" to true),
                env = mapOf("value" to true),
                tenant = null
            )
        )
        assertFalse(result.containsKey("f"))
    }

    // ── tenant override priority ────────────────────────────────────────

    @Test
    fun `tenant override beats env value and conditions`() {
        val result = flag(
            ArgusFlagResolver.FlagInput(
                flag = mapOf("name" to "f", "defaultValue" to false),
                env = mapOf("value" to false),
                tenant = mapOf("value" to true)
            )
        )
        assertEquals("true", result["f"])
    }

    // ── conditional values (priority + first match wins) ────────────────

    @Test
    fun `first matching condition by priority wins`() {
        val conditions = mapOf(
            "cond_low_pri" to mapOf<String, Any?>(
                "name" to "cond_low_pri",
                "priority" to 10,
                "platform" to "android"
            ),
            "cond_high_pri" to mapOf<String, Any?>(
                "name" to "cond_high_pri",
                "priority" to 1,
                "platform" to "android"
            )
        )
        val result = ArgusFlagResolver.resolve(
            flags = listOf(
                ArgusFlagResolver.FlagInput(
                    flag = mapOf("name" to "f", "defaultValue" to "base"),
                    env = mapOf(
                        "value" to "env",
                        "conditionalValues" to mapOf(
                            "cond_low_pri" to "low",
                            "cond_high_pri" to "high"
                        )
                    ),
                    tenant = null
                )
            ),
            conditionsByName = conditions,
            context = androidCtx
        )
        // priority 1 (high) sorts first and matches → "high".
        assertEquals("high", result["f"])
    }

    @Test
    fun `condition not matching platform is skipped`() {
        val conditions = mapOf(
            "ios_only" to mapOf<String, Any?>(
                "name" to "ios_only",
                "priority" to 1,
                "platform" to "ios"
            )
        )
        val result = ArgusFlagResolver.resolve(
            flags = listOf(
                ArgusFlagResolver.FlagInput(
                    flag = mapOf("name" to "f", "defaultValue" to "base"),
                    env = mapOf(
                        "value" to "env",
                        "conditionalValues" to mapOf("ios_only" to "ios")
                    ),
                    tenant = null
                )
            ),
            conditionsByName = conditions,
            context = androidCtx
        )
        // No condition matches → env value stands.
        assertEquals("env", result["f"])
    }

    @Test
    fun `version constraint gte matches`() {
        val conditions = mapOf(
            "min_140" to mapOf<String, Any?>(
                "name" to "min_140",
                "priority" to 1,
                "versionConstraint" to mapOf(
                    "operator" to ">=",
                    "versions" to listOf("1.40.0")
                )
            )
        )
        val result = ArgusFlagResolver.resolve(
            flags = listOf(
                ArgusFlagResolver.FlagInput(
                    flag = mapOf("name" to "f", "defaultValue" to "base"),
                    env = mapOf(
                        "value" to "env",
                        "conditionalValues" to mapOf("min_140" to "gated")
                    ),
                    tenant = null
                )
            ),
            conditionsByName = conditions,
            context = androidCtx // 1.42.0 >= 1.40.0
        )
        assertEquals("gated", result["f"])
    }

    @Test
    fun `conditions ignored when platform or version absent`() {
        val conditions = mapOf(
            "android_cond" to mapOf<String, Any?>(
                "name" to "android_cond",
                "priority" to 1,
                "platform" to "android"
            )
        )
        val result = ArgusFlagResolver.resolve(
            flags = listOf(
                ArgusFlagResolver.FlagInput(
                    flag = mapOf("name" to "f", "defaultValue" to "base"),
                    env = mapOf(
                        "value" to "env",
                        "conditionalValues" to mapOf("android_cond" to "cond")
                    ),
                    tenant = null
                )
            ),
            conditionsByName = conditions,
            // version null → conditionalValues not evaluated
            context = ArgusFlagResolver.Context("android", null, "u", null)
        )
        assertEquals("env", result["f"])
    }

    // ── language filter (lowercased exact-equality, server + iOS parity) ─

    private fun languageCondition() = mapOf(
        "en_only" to mapOf<String, Any?>(
            "name" to "en_only",
            "priority" to 1,
            "languageFilter" to listOf("en-US")
        )
    )

    private fun resolveWithLanguage(language: String?): Map<String, String> =
        ArgusFlagResolver.resolve(
            flags = listOf(
                ArgusFlagResolver.FlagInput(
                    flag = mapOf("name" to "f", "defaultValue" to "base"),
                    env = mapOf(
                        "value" to "env",
                        "conditionalValues" to mapOf("en_only" to "gated")
                    ),
                    tenant = null
                )
            ),
            conditionsByName = languageCondition(),
            context = ArgusFlagResolver.Context("android", "1.42.0", "user-123", language)
        )

    @Test
    fun `language filter matches exact tag`() {
        assertEquals("gated", resolveWithLanguage("en-US")["f"])
    }

    @Test
    fun `language filter matches case-insensitively`() {
        // Client "EN-us" lowercases to match filter "en-US".
        assertEquals("gated", resolveWithLanguage("EN-us")["f"])
    }

    @Test
    fun `language filter does not match different language`() {
        // "fr-FR" is not in the filter → condition fails → env value stands.
        assertEquals("env", resolveWithLanguage("fr-FR")["f"])
    }

    @Test
    fun `language filter fails when context language is null`() {
        // No client language → non-empty filter cannot be satisfied → env value.
        assertEquals("env", resolveWithLanguage(null)["f"])
    }

    // ── rollout ─────────────────────────────────────────────────────────

    @Test
    fun `paused rollout returns default for everyone`() {
        val result = flag(
            ArgusFlagResolver.FlagInput(
                flag = mapOf("name" to "f", "defaultValue" to false),
                env = mapOf(
                    "value" to true,
                    "rollout" to mapOf("enabled" to false, "percentage" to 100, "seed" to "s")
                ),
                tenant = null
            )
        )
        assertEquals("false", result["f"])
    }

    @Test
    fun `rollout 100 percent includes user`() {
        val result = flag(
            ArgusFlagResolver.FlagInput(
                flag = mapOf("name" to "f", "defaultValue" to false),
                env = mapOf(
                    "value" to true,
                    "rollout" to mapOf("enabled" to true, "percentage" to 100, "seed" to "s")
                ),
                tenant = null
            )
        )
        // bucket is always < 100 → user is in rollout → resolved value.
        assertEquals("true", result["f"])
    }

    @Test
    fun `rollout 0 percent excludes user`() {
        val result = flag(
            ArgusFlagResolver.FlagInput(
                flag = mapOf("name" to "f", "defaultValue" to false),
                env = mapOf(
                    "value" to true,
                    "rollout" to mapOf("enabled" to true, "percentage" to 0, "seed" to "s")
                ),
                tenant = null
            )
        )
        // bucket >= 0 is always true → user NOT in rollout → default.
        assertEquals("false", result["f"])
    }

    @Test
    fun `rollout bucketing matches server FNV1a`() {
        // Cross-check the exact bucket the server would compute for this
        // (seed,userId) so the include/exclude boundary lines up. Resolver
        // and server share FNV1a, so a percentage just above/below the bucket
        // flips inclusion deterministically.
        val bucket = FNV1a.percentageBucket("seed-x", "user-123")

        val included = flag(
            ArgusFlagResolver.FlagInput(
                flag = mapOf("name" to "f", "defaultValue" to false),
                env = mapOf(
                    "value" to true,
                    "rollout" to mapOf(
                        "enabled" to true,
                        "percentage" to bucket + 1, // bucket < percentage → in
                        "seed" to "seed-x"
                    )
                ),
                tenant = null
            )
        )
        assertEquals("true", included["f"])

        val excluded = flag(
            ArgusFlagResolver.FlagInput(
                flag = mapOf("name" to "f", "defaultValue" to false),
                env = mapOf(
                    "value" to true,
                    "rollout" to mapOf(
                        "enabled" to true,
                        "percentage" to bucket, // bucket >= percentage → out
                        "seed" to "seed-x"
                    )
                ),
                tenant = null
            )
        )
        assertEquals("false", excluded["f"])
    }

    // ── object-valued flags round-trip through org.json ─────────────────

    @Test
    fun `object value is serialised to parseable json`() {
        val result = flag(
            ArgusFlagResolver.FlagInput(
                flag = mapOf("name" to "app_version", "defaultValue" to null),
                env = mapOf(
                    "value" to mapOf(
                        "android" to mapOf("minSupported" to "1.40.0", "snoozePeriod" to 7)
                    )
                ),
                tenant = null
            )
        )
        val json = org.json.JSONObject(result["app_version"]!!)
        assertTrue(json.has("android"))
        assertEquals("1.40.0", json.getJSONObject("android").getString("minSupported"))
    }
}
