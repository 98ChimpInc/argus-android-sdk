package cloud.projectargus

/**
 * Client-side flag resolution (#215).
 *
 * Mirrors the server `resolveFlags` algorithm in argus-web-app
 * `functions/index.js` EXACTLY so a flag resolves to the same value whether it
 * arrives via the real-time Firestore listener (primary channel) or the
 * `resolveFlags` HTTP fetch (cold-start / fallback). Keep the two in lock-step:
 * if the server algorithm changes, change this in the same PR.
 *
 * Pure + dependency-free (no Firebase types) so it is unit-testable without a
 * live backend. Reuses [FNV1a] for rollout bucketing and an inlined
 * [compareVersions] that matches `functions/index.js#compareVersions`.
 *
 * Snapshot payloads are passed in as plain maps so the caller (the service's
 * Firestore listeners) can hand over decoded document data verbatim.
 */
internal object ArgusFlagResolver {

    /** One flag's documents, as decoded from Firestore snapshots. */
    data class FlagInput(
        /** `/flags/{flagId}` document data. */
        val flag: Map<String, Any?>,
        /** `/flags/{flagId}/environments/{env}` document data, or null if absent. */
        val env: Map<String, Any?>?,
        /**
         * `/flags/{flagId}/environments/{env}/tenants/{tenantId}` document data
         * for the configured tenant, or null when not tenant-scoped / no override.
         */
        val tenant: Map<String, Any?>?
    )

    /** The request context — platform/version/userId/language for targeting. */
    data class Context(
        val platform: String?,
        val version: String?,
        val userId: String?,
        /**
         * The client's BCP-47 language tag (e.g. `en-US`), or null when
         * unavailable. Matched case-insensitively against a condition's
         * `languageFilter` clause, mirroring the server + iOS.
         */
        val language: String?
    )

    /**
     * Resolve every flag into a `name -> stringValue` map, mirroring the
     * server. Values are stringified the same way the OkHttp path stringifies
     * them (`value.toString()`), so the downstream cache + typed getters behave
     * identically regardless of channel.
     *
     * @param flags decoded flag documents keyed by flagId (key is unused for
     *   resolution; the flag's own `name` field is the output key).
     * @param conditionsByName decoded `/conditions` docs keyed by `name`.
     */
    fun resolve(
        flags: Collection<FlagInput>,
        conditionsByName: Map<String, Map<String, Any?>>,
        context: Context
    ): Map<String, String> {
        val resolved = HashMap<String, String>()
        for (input in flags) {
            val flag = input.flag
            val flagName = (flag["name"] as? String) ?: continue

            // Skip archived + draft flags — invisible to SDKs (server parity).
            if (flag["archived"] == true) continue
            if (flag["draft"] == true) continue

            val defaultValue = flag["defaultValue"]
            val envData = input.env

            if (envData == null) {
                // No environment doc — use the flag's default value.
                stringify(defaultValue)?.let { resolved[flagName] = it }
                continue
            }

            var resolvedValue: Any? = envData["value"] ?: defaultValue

            // ── Tenant override takes priority ──────────────────────
            val tenantData = input.tenant
            if (tenantData != null) {
                stringify(tenantData["value"])?.let { resolved[flagName] = it }
                continue
            }

            // ── Conditional values (only when platform + version supplied) ─
            val conditionalValues = if (context.platform != null && context.version != null) {
                envData["conditionalValues"] as? Map<*, *>
            } else {
                null
            }
            if (conditionalValues != null) {
                val entries = conditionalValues.entries
                    .mapNotNull { (rawName, value) ->
                        val condName = rawName as? String ?: return@mapNotNull null
                        val def = conditionsByName[condName]
                        ConditionEntry(
                            value = value,
                            priority = (def?.get("priority") as? Number)?.toLong()
                                ?: Long.MAX_VALUE,
                            definition = def
                        )
                    }
                    .sortedBy { it.priority }

                for (entry in entries) {
                    val def = entry.definition ?: continue
                    if (evaluateCondition(def, context)) {
                        resolvedValue = entry.value
                        break // first match wins
                    }
                }
            }

            // ── Rollout evaluation ────────────────────────────────
            val rollout = envData["rollout"] as? Map<*, *>
            if (rollout != null) {
                val enabled = rollout["enabled"] == true
                if (!enabled) {
                    // Paused — all users receive pre-rollout (default) value.
                    stringify(defaultValue)?.let { resolved[flagName] = it }
                    continue
                }
                val userId = context.userId
                if (userId != null) {
                    val seed = rollout["seed"] as? String ?: ""
                    val percentage = (rollout["percentage"] as? Number)?.toInt() ?: 0
                    val bucket = FNV1a.percentageBucket(seed, userId)
                    if (bucket >= percentage) {
                        // User NOT in rollout — pre-rollout (default) value.
                        stringify(defaultValue)?.let { resolved[flagName] = it }
                        continue
                    }
                    // User IS in rollout — falls through to resolvedValue.
                }
                // No userId — skip bucketing, use resolvedValue.
            }

            stringify(resolvedValue)?.let { resolved[flagName] = it }
        }
        return resolved
    }

    private data class ConditionEntry(
        val value: Any?,
        val priority: Long,
        val definition: Map<String, Any?>?
    )

    /**
     * Evaluate one condition against the context. All present clauses must
     * match (logical AND). Mirrors `functions/index.js#evaluateCondition`.
     *
     * The client has platform/version/userId/language. The language clause is
     * evaluated client-side (lowercased exact-equality, mirroring the server +
     * iOS). The audience clause still needs membership data the client never
     * sends, so it follows the server's conservative behaviour: an audience
     * restriction cannot be satisfied client-side and therefore fails the match.
     */
    private fun evaluateCondition(condition: Map<String, Any?>, context: Context): Boolean {
        // Platform check.
        val platform = condition["platform"] as? String
        if (platform != null && platform != "all") {
            if (platform != context.platform) return false
        }

        // App ID check is skipped: the client does not resolve the
        // platform→appId config map (server-only), so when the server would
        // compare appId we have no client-side equivalent. We omit the clause
        // rather than guess, matching the server's "no platformAppId → skip".

        // Version constraint check.
        @Suppress("UNCHECKED_CAST")
        val versionConstraint = condition["versionConstraint"] as? Map<String, Any?>
        if (versionConstraint != null) {
            if (!evaluateVersionConstraint(context.version, versionConstraint)) return false
        }

        // Percentage range check.
        val percentageRange = condition["percentageRange"] as? Map<*, *>
        if (percentageRange != null) {
            val userId = context.userId ?: return false // cannot bucket without userId
            val seed = percentageRange["seed"] as? String ?: ""
            val low = (percentageRange["low"] as? Number)?.toInt() ?: 0
            val high = (percentageRange["high"] as? Number)?.toInt() ?: 0
            val bucket = FNV1a.percentageBucket(seed, userId)
            if (bucket < low || bucket >= high) return false
        }

        // Audience check: client has no audience membership → cannot satisfy.
        val audienceIds = condition["audienceIds"] as? List<*>
        if (audienceIds != null && audienceIds.isNotEmpty()) return false

        // Language filter: lowercased exact-equality against the client's
        // language tag, mirroring the server + iOS. A non-empty filter with no
        // client language (or no match) fails the clause.
        val languageFilter = condition["languageFilter"] as? List<*>
        if (languageFilter != null && languageFilter.isNotEmpty()) {
            val language = context.language ?: return false
            val clientLang = language.lowercase()
            val matches = languageFilter.any { (it as? String)?.lowercase() == clientLang }
            if (!matches) return false
        }

        return true
    }

    /**
     * Evaluate a version constraint against the client version.
     * Mirrors `functions/index.js#evaluateVersionConstraint`.
     */
    private fun evaluateVersionConstraint(
        clientVersion: String?,
        constraint: Map<String, Any?>
    ): Boolean {
        val operator = constraint["operator"] as? String
        @Suppress("UNCHECKED_CAST")
        val versions = (constraint["versions"] as? List<*>)?.mapNotNull { it as? String }
        if (operator == null || versions.isNullOrEmpty()) return true

        val client = parseVersion(clientVersion ?: "")

        // List-membership operators.
        if (operator == "exactlyMatches" || operator == "contains" || operator == "matches") {
            return versions.any { compareVersions(client, parseVersion(it)) == 0 }
        }

        val target = parseVersion(versions[0])
        val cmp = compareVersions(client, target)
        return when (operator) {
            ">=" -> cmp >= 0
            "<=" -> cmp <= 0
            "<" -> cmp < 0
            "==" -> cmp == 0
            else -> false
        }
    }

    /** Parse a dotted version into integer segments (non-numeric → 0). */
    private fun parseVersion(version: String): List<Int> =
        version.split(".").map { it.toIntOrNull() ?: 0 }

    /**
     * Compare two version segment lists, padding the shorter with zeroes.
     * Returns -1, 0, or 1. Matches `functions/index.js#compareVersions`.
     */
    private fun compareVersions(a: List<Int>, b: List<Int>): Int {
        val maxLen = maxOf(a.size, b.size)
        for (i in 0 until maxLen) {
            val segA = a.getOrElse(i) { 0 }
            val segB = b.getOrElse(i) { 0 }
            if (segA != segB) return segA.compareTo(segB)
        }
        return 0
    }

    /**
     * Stringify a resolved value so the cache holds the same string a flag
     * would carry on the OkHttp path: scalars via `toString()`, objects/arrays
     * via `org.json` so the structured getters can re-parse them identically.
     *
     * Returns null for a genuinely absent value (Kotlin null), so the caller
     * skips writing that flag rather than caching a literal "null" string —
     * this preserves any bundled [FeatureFlag] enum default already in the
     * cache instead of clobbering it.
     */
    private fun stringify(value: Any?): String? = when (value) {
        null -> null
        is String -> value
        is Map<*, *>, is List<*> -> JsonCompat.stringify(value)
        else -> value.toString()
    }
}
