package com.endlessdrive.core

/** Deterministic, allocation-free random numbers (xorshift64*). Same seed, same sequence, on every platform. */
class Rng(seed: Long) {
    private var x = 0L

    init {
        setSeed(seed)
    }

    fun setSeed(seed: Long) {
        // scramble so that small consecutive seeds give unrelated streams; state must never be 0
        var z = seed + 0x632BE59BD9B4E019L
        z = (z xor (z ushr 33)) * -0xae502812aa7333L
        z = (z xor (z ushr 33)) * -0x3b314601e57a13adL
        z = z xor (z ushr 33)
        x = if (z == 0L) 0x2545F4914F6CDD1DL else z
    }

    private fun next(): Long {
        x = x xor (x ushr 12)
        x = x xor (x shl 25)
        x = x xor (x ushr 27)
        return x * 0x2545F4914F6CDD1DL
    }

    /** Uniform in [0, 1). */
    fun nextFloat(): Float = (next() ushr 40).toFloat() * (1f / 16777216f)

    /** Uniform integer in [0, n). */
    fun nextInt(n: Int): Int = ((next() ushr 33) % n).toInt()

    fun range(lo: Float, hi: Float): Float = lo + (hi - lo) * nextFloat()

    fun chance(p: Float): Boolean = nextFloat() < p
}
