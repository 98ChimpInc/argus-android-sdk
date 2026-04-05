package com.telus.argus

/**
 * FNV-1a 32-bit hash implementation.
 *
 * Produces identical output to the JavaScript reference in functions/index.js.
 * Uses Kotlin's [UInt] for unsigned 32-bit arithmetic, matching JavaScript's
 * `>>> 0` unsigned right-shift semantics. The `*` operator on [UInt] wraps on
 * overflow, equivalent to `Math.imul` in JavaScript.
 *
 * The hash operates on UTF-16 code units (matching JavaScript's `charCodeAt`),
 * ensuring cross-language parity for all string inputs.
 */
object FNV1a {
    private const val FNV_OFFSET_BASIS = 0x811c9dc5u
    private const val FNV_PRIME = 0x01000193u

    /**
     * Compute FNV-1a 32-bit hash.
     * Produces identical output to the JavaScript reference in functions/index.js.
     */
    fun hash(input: String): UInt {
        var hash = FNV_OFFSET_BASIS
        for (char in input) {
            hash = hash xor char.code.toUInt()
            hash = hash * FNV_PRIME
        }
        return hash
    }

    /**
     * Bucket a user into 0-99 based on seed + userId.
     * Used for percentage-based rollout targeting.
     */
    fun percentageBucket(seed: String, userId: String): Int {
        return (hash(seed + userId) % 100u).toInt()
    }
}
