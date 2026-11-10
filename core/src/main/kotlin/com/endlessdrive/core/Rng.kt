package com.endlessdrive.core

/** Small deterministic, allocation-free PRNG (SplitMix64). */
class Rng(seed: Long) {
    private var state: Long = seed

    fun setSeed(seed: Long) {
        state = seed
    }

    fun nextLong(): Long {
        state += -0x61c8864680b583ebL
        var z = state
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }

    /** Uniform in [0, 1). */
    fun nextFloat(): Float = (nextLong() ushr 40).toFloat() / (1 shl 24).toFloat()

    /** Uniform in [0, n). */
    fun nextInt(n: Int): Int {
        require(n > 0)
        return ((nextLong() ushr 33) % n).toInt()
    }

    fun range(a: Float, b: Float): Float = a + (b - a) * nextFloat()

    fun chance(p: Float): Boolean = nextFloat() < p
}
