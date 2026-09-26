package cloud.projectargus

/**
 * Gates the real-time stream's FIRST consolidated emission (#25 — the Android
 * twin of argus-ios-sdk#16).
 *
 * The stream binds a `/flags/{id}/environments/{env}` listener (and, when the
 * apiKey is tenant-scoped, a `.../tenants/{tenantId}` listener) per flag, but
 * those env/tenant docs arrive a beat AFTER the flags snapshot. If the first
 * `recomputeAndPublish` runs in that window, [ArgusFlagResolver] maps every
 * not-yet-arrived env doc to the flag's default value, so the first values
 * written into the cache — and the first `configUpdated` full-refresh — are all
 * defaults, then the real values follow a beat later. On Loomi that flashed a
 * whitelisted account as non-premium for ~1s after launch.
 *
 * This gate holds the first publish until every flag seen in the initial flags
 * snapshot has had its env listener (and tenant listener, when scoped) deliver
 * at least once — whether or not the doc exists, since a missing doc still fires
 * the listener once, so a flag with no env doc does not stall the gate. Once the
 * first complete publish passes, the gate is permanently open: later snapshots
 * publish exactly as before.
 *
 * Pure logic (no Firebase), so the emission rule is unit-testable even though
 * [ArgusFeatureFlagServiceImpl] itself is Firebase-locked. Not thread-safe: the
 * caller mutates it under `streamMutex`, the same lock that guards the doc maps
 * its state tracks.
 */
internal class FirstEmissionGate {

    /** Flags whose env listener has not yet delivered its first callback. */
    private val pendingEnv = mutableSetOf<String>()

    /**
     * Flags whose tenant-override listener has not yet delivered its first
     * callback (only populated when the apiKey is tenant-scoped).
     */
    private val pendingTenant = mutableSetOf<String>()

    /**
     * `true` once the first complete publish has been released. From then on the
     * gate never suppresses again, so post-initial-load changes (a flag added
     * later, a value change) flow through immediately.
     */
    var isOpen = false
        private set

    /**
     * The flags listener has not yet delivered its first snapshot. Until it
     * does, no flag has been [expect]ed, so an independent sibling listener (the
     * conditions query, which also drives [ArgusFeatureFlagServiceImpl.recomputeAndPublish])
     * must NOT be allowed to open the gate on an empty pending set and publish an
     * all-defaults snapshot before flags arrive — a real race on 2nd+ launches
     * with on-disk cache.
     */
    private var awaitingFlagsSnapshot = true

    /**
     * The flags listener delivered its first snapshot: every flag it contains
     * has now been [expect]ed, so the gate is finally allowed to open.
     */
    fun flagsSnapshotArrived() {
        if (isOpen) return
        awaitingFlagsSnapshot = false
    }

    /**
     * Register that a flag's listeners are being bound during the initial load,
     * so the first publish waits for them. A no-op once the gate is open — a flag
     * first seen after the initial load must not re-gate the stream. Idempotent.
     */
    fun expect(flagId: String, tenantScoped: Boolean) {
        if (isOpen) return
        pendingEnv.add(flagId)
        if (tenantScoped) pendingTenant.add(flagId)
    }

    /** A flag's env listener delivered its first callback (or errored). */
    fun envArrived(flagId: String) {
        pendingEnv.remove(flagId)
    }

    /** A flag's tenant-override listener delivered its first callback (or errored). */
    fun tenantArrived(flagId: String) {
        pendingTenant.remove(flagId)
    }

    /**
     * A flag disappeared before the initial load completed; stop waiting on it,
     * otherwise its never-cleared entry would hold the gate shut forever.
     */
    fun drop(flagId: String) {
        pendingEnv.remove(flagId)
        pendingTenant.remove(flagId)
    }

    /**
     * Whether the stream should publish now. While the gate is closed it releases
     * only once the flags snapshot has arrived and nothing is pending; that first
     * release flips [isOpen], so every later call returns `true`. Opening is the
     * one-way transition the caller relies on.
     */
    fun shouldEmit(): Boolean {
        if (isOpen) return true
        // The flags snapshot must arrive first, so a sibling listener can't open
        // the gate on an empty pending set before any flag is even known.
        if (awaitingFlagsSnapshot) return false
        if (pendingEnv.isEmpty() && pendingTenant.isEmpty()) {
            isOpen = true
            return true
        }
        return false
    }

    /** Reset to the pre-initialisation state so a fresh `initialize()` re-gates. */
    fun reset() {
        pendingEnv.clear()
        pendingTenant.clear()
        isOpen = false
        awaitingFlagsSnapshot = true
    }
}
