package cloud.projectargus

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the #25 gate: the stream's first consolidated publish is held until
 * the flags snapshot has arrived AND every flag's env (and tenant, when scoped)
 * listener has delivered once, so the consumer's first snapshot carries real env
 * values instead of defaults. Pure value logic — no live Firebase.
 *
 * Twin of argus-ios-sdk FirstEmissionGateTests.
 */
class FirstEmissionGateTest {

    // The handoff scenario: flags arrive, then env docs arrive separately. The
    // first publish must be held until BOTH env docs land, then release once.
    @Test
    fun firstEmissionHeldUntilAllEnvDocsArrive() {
        val gate = FirstEmissionGate()

        // Flags snapshot binds env listeners for A and B, marks the flags
        // snapshot arrived, then tries to publish.
        gate.expect(flagId = "a", tenantScoped = false)
        gate.expect(flagId = "b", tenantScoped = false)
        gate.flagsSnapshotArrived()
        assertFalse("must not emit before any env doc arrives", gate.shouldEmit())

        // First env doc arrives — still one outstanding.
        gate.envArrived("a")
        assertFalse("must not emit while env b is still pending", gate.shouldEmit())

        // Second env doc arrives — the single first publish is released.
        gate.envArrived("b")
        assertTrue("emits once every env doc has arrived", gate.shouldEmit())
        assertTrue(gate.isOpen)

        // Every later publish passes through unconditionally.
        assertTrue(gate.shouldEmit())
        assertTrue(gate.shouldEmit())
    }

    // Regression (#25): the conditions listener also drives publishing and
    // registers no expectations. Before the flags snapshot arrives, the gate must
    // stay shut even though nothing is pending — otherwise conditions-before-flags
    // opens it on an empty flag set and publishes all-defaults.
    @Test
    fun doesNotOpenBeforeFlagsSnapshotEvenWithNothingPending() {
        val gate = FirstEmissionGate()
        assertFalse("a sibling listener must not open the gate before flags arrive", gate.shouldEmit())
        assertFalse(gate.isOpen)

        gate.flagsSnapshotArrived() // flags arrive (empty product)
        assertTrue("opens once flags have arrived and nothing is pending", gate.shouldEmit())
    }

    // Tenant-scoped flags must wait for the tenant-override listener too, not just
    // the env listener.
    @Test
    fun tenantScopedWaitsForEnvAndTenant() {
        val gate = FirstEmissionGate()
        gate.expect(flagId = "a", tenantScoped = true)
        gate.flagsSnapshotArrived()

        gate.envArrived("a")
        assertFalse("env alone is not enough when tenant-scoped", gate.shouldEmit())

        gate.tenantArrived("a")
        assertTrue("emits once env AND tenant have arrived", gate.shouldEmit())
    }

    // A product with no flags: the empty snapshot is a valid first answer and must
    // emit as soon as the flags snapshot arrives.
    @Test
    fun zeroFlagsEmitsOnceFlagsArrive() {
        val gate = FirstEmissionGate()
        gate.flagsSnapshotArrived()
        assertTrue(gate.shouldEmit())
        assertTrue(gate.isOpen)
    }

    // A flag removed before the initial load completes must not hold the gate shut
    // forever on a listener that will never fire again.
    @Test
    fun flagDroppedMidLoadDoesNotStall() {
        val gate = FirstEmissionGate()
        gate.expect(flagId = "a", tenantScoped = false)
        gate.expect(flagId = "b", tenantScoped = false)
        gate.flagsSnapshotArrived()

        gate.drop("b") // b removed mid-load
        gate.envArrived("a")
        assertTrue("dropping the removed flag lets the gate open", gate.shouldEmit())
    }

    // `drop` on a tenant-scoped flag must clear BOTH the env and tenant slots,
    // otherwise a leftover tenant entry would hold the gate shut.
    @Test
    fun dropClearsBothEnvAndTenantSlots() {
        val gate = FirstEmissionGate()
        gate.expect(flagId = "a", tenantScoped = true)
        gate.flagsSnapshotArrived()

        gate.drop("a") // the only expected flag removed
        assertTrue("dropping a tenant-scoped flag clears env AND tenant", gate.shouldEmit())
    }

    // Once open, a flag first seen AFTER the initial load must not re-gate the
    // stream — its change should flow through as a normal live update.
    @Test
    fun postOpenExpectDoesNotRegate() {
        val gate = FirstEmissionGate()
        gate.expect(flagId = "a", tenantScoped = false)
        gate.flagsSnapshotArrived()
        gate.envArrived("a")
        assertTrue(gate.shouldEmit()) // opens

        gate.expect(flagId = "c", tenantScoped = true) // a new flag, post-load
        assertTrue("a late-added flag must not suppress the stream again", gate.shouldEmit())
    }

    // reset() returns the gate to its pre-initialisation state so a fresh
    // initialize() (after close()) re-gates the first publish.
    @Test
    fun resetReGatesAfterClose() {
        val gate = FirstEmissionGate()
        gate.expect(flagId = "a", tenantScoped = false)
        gate.flagsSnapshotArrived()
        gate.envArrived("a")
        assertTrue(gate.shouldEmit()) // opens
        assertTrue(gate.isOpen)

        gate.reset()
        assertFalse(gate.isOpen)
        assertFalse("after reset, a sibling listener must not open the gate before flags re-arrive", gate.shouldEmit())

        gate.expect(flagId = "a", tenantScoped = false)
        gate.flagsSnapshotArrived()
        assertFalse("still gated until the re-bound env listener delivers", gate.shouldEmit())
        gate.envArrived("a")
        assertTrue(gate.shouldEmit())
    }
}
