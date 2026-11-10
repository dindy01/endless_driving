package com.endlessdrive.game

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * All sound effects are synthesised at startup (no audio assets), written once as small WAV
 * files to the cache dir and played through SoundPool.
 */
class Sfx(context: Context) {
    var enabled = true
        set(v) {
            field = v
            if (!v) stopEngine()
        }

    private val pool: SoundPool = SoundPool.Builder()
        .setMaxStreams(8)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        ).build()

    private val ids = IntArray(N) { 0 }
    private var engineStream = 0
    private var engineRate = 1f
    private val rng = java.util.Random(3)

    init {
        try {
            val dir = File(context.cacheDir, "sfx").apply { mkdirs() }
            for (i in 0 until N) {
                val f = File(dir, "s${i}_v$VERSION.wav")
                if (!f.exists() || f.length() == 0L) writeWav(f, synth(i))
                ids[i] = pool.load(f.absolutePath, 1)
            }
        } catch (e: Exception) {
            Log.w("Sfx", "audio disabled", e)
        }
    }

    fun play(which: Int, volume: Float = 1f, rate: Float = 1f) {
        if (!enabled || ids[which] == 0) return
        pool.play(ids[which], volume, volume, 1, 0, rate.coerceIn(0.5f, 2f))
    }

    fun startEngine() {
        if (!enabled || ids[ENGINE] == 0 || engineStream != 0) return
        engineStream = pool.play(ids[ENGINE], 0.16f, 0.16f, 0, -1, 1f)
    }

    /** speedNorm in [0,1]. Only touches SoundPool when the rate changes noticeably. */
    fun updateEngine(speedNorm: Float) {
        if (engineStream == 0) return
        val r = 0.8f + 0.7f * speedNorm
        if (kotlin.math.abs(r - engineRate) > 0.01f) {
            engineRate = r
            pool.setRate(engineStream, r)
        }
    }

    fun stopEngine() {
        if (engineStream != 0) {
            pool.stop(engineStream)
            engineStream = 0
        }
    }

    fun pauseAll() = pool.autoPause()
    fun resumeAll() = pool.autoResume()

    fun release() {
        stopEngine()
        pool.release()
    }

    // ---------------- synthesis ----------------

    private fun synth(which: Int): ShortArray = when (which) {
        COIN -> tones(floatArrayOf(988f, 1319f), 0.055f, 0.5f)
        LANE -> noise(0.045f, 0.18f, 8f, lowpass = 0.25f)
        NEAR_MISS -> whoosh(0.32f)
        CRASH -> crash(0.75f)
        SHIELD -> tones(floatArrayOf(523f, 659f, 784f, 1047f), 0.06f, 0.45f)
        SHIELD_BREAK -> shatter(0.4f)
        OIL -> wobble(0.35f)
        CLICK -> tones(floatArrayOf(1400f), 0.03f, 0.35f)
        ENGINE -> engine()
        else -> ShortArray(1)
    }

    private fun buf(seconds: Float) = FloatArray((RATE * seconds).toInt())

    private fun tones(freqs: FloatArray, each: Float, amp: Float): ShortArray {
        val out = buf(each * freqs.size + 0.08f)
        val n = (RATE * each).toInt()
        for ((k, f) in freqs.withIndex()) {
            val start = k * n
            val len = n + (RATE * 0.08f).toInt()
            for (i in 0 until len) {
                if (start + i >= out.size) break
                val t = i / RATE.toFloat()
                val env = exp(-t * 18f) * (if (i < 80) i / 80f else 1f)
                val s = sin(2 * PI * f * t).toFloat() + 0.3f * sin(4 * PI * f * t).toFloat()
                out[start + i] += amp * env * s * 0.8f
            }
        }
        return pcm(out)
    }

    private fun noise(seconds: Float, amp: Float, decay: Float, lowpass: Float): ShortArray {
        val out = buf(seconds)
        var lp = 0f
        for (i in out.indices) {
            val t = i / RATE.toFloat()
            lp += lowpass * ((rng.nextFloat() * 2 - 1) - lp)
            out[i] = amp * lp * 3f * exp(-t * decay * 10f) * (if (i < 40) i / 40f else 1f)
        }
        return pcm(out)
    }

    private fun whoosh(seconds: Float): ShortArray {
        val out = buf(seconds)
        var lp = 0f
        for (i in out.indices) {
            val p = i / out.size.toFloat()
            val cutoff = 0.05f + 0.35f * sin(PI * p).toFloat()
            lp += cutoff * ((rng.nextFloat() * 2 - 1) - lp)
            out[i] = 0.9f * lp * sin(PI * p).toFloat()
        }
        return pcm(out)
    }

    private fun crash(seconds: Float): ShortArray {
        val out = buf(seconds)
        var lp = 0f
        for (i in out.indices) {
            val t = i / RATE.toFloat()
            lp += 0.3f * ((rng.nextFloat() * 2 - 1) - lp)
            val rumble = sin(2 * PI * (70f - 30f * t) * t).toFloat()
            out[i] = (0.9f * lp * exp(-t * 5f) + 0.5f * rumble * exp(-t * 4f)) * (if (i < 30) i / 30f else 1f)
        }
        return pcm(out)
    }

    private fun shatter(seconds: Float): ShortArray {
        val out = buf(seconds)
        for (i in out.indices) {
            val t = i / RATE.toFloat()
            val n = rng.nextFloat() * 2 - 1
            val ring = sin(2 * PI * (2200f - 1600f * t) * t).toFloat()
            out[i] = (0.35f * n + 0.4f * ring) * exp(-t * 9f)
        }
        return pcm(out)
    }

    private fun wobble(seconds: Float): ShortArray {
        val out = buf(seconds)
        for (i in out.indices) {
            val t = i / RATE.toFloat()
            val f = 140f + 60f * sin(2 * PI * 18f * t).toFloat()
            out[i] = 0.6f * sin(2 * PI * f * t).toFloat() * exp(-t * 6f)
        }
        return pcm(out)
    }

    private fun engine(): ShortArray {
        // 0.5 s loop; 110 Hz and harmonics complete whole cycles, so the loop is seamless.
        val out = buf(0.5f)
        for (i in out.indices) {
            val t = i / RATE.toFloat()
            var s = 0f
            for (h in 1..6) s += sin(2 * PI * 110f * h * t).toFloat() / (h * 1.3f)
            s += 0.25f * sin(2 * PI * 55f * t).toFloat()
            out[i] = 0.35f * s
        }
        return pcm(out)
    }

    private fun pcm(f: FloatArray): ShortArray {
        var peak = 1e-6f
        for (v in f) peak = maxOf(peak, kotlin.math.abs(v))
        val g = if (peak > 0.95f) 0.95f / peak else 1f
        return ShortArray(f.size) { (f[it] * g * Short.MAX_VALUE).toInt().toShort() }
    }

    private fun writeWav(file: File, data: ShortArray) {
        val bb = ByteBuffer.allocate(44 + data.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()).putInt(36 + data.size * 2).put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(RATE).putInt(RATE * 2).putShort(2).putShort(16)
        bb.put("data".toByteArray()).putInt(data.size * 2)
        for (s in data) bb.putShort(s)
        FileOutputStream(file).use { it.write(bb.array()) }
    }

    companion object {
        const val COIN = 0
        const val LANE = 1
        const val NEAR_MISS = 2
        const val CRASH = 3
        const val SHIELD = 4
        const val SHIELD_BREAK = 5
        const val OIL = 6
        const val CLICK = 7
        const val ENGINE = 8
        private const val N = 9
        private const val RATE = 22050
        private const val VERSION = 1
    }
}
