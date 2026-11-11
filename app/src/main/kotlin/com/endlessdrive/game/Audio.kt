package com.endlessdrive.game

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * All audio is synthesised in code: one streaming AudioTrack fed by a small mixer thread that
 * generates the engine tone (pitch and loudness follow speed) and mixes pre-rendered one-shot
 * effects. No audio assets, no SoundPool. If anything about audio fails, it turns itself off and
 * the game carries on silently.
 */
class Audio {
    @Volatile var enabled = true
    /** 0..1, set every frame by the game thread. */
    @Volatile var engineSpeed = 0f
    @Volatile var engineOn = false
    @Volatile var boosting = false

    private val clips = arrayOf(coin(), shield(), shatter(), crash(), nitro(), whoosh(), click())
    private val voiceClip = IntArray(VOICES) { -1 }
    private val voicePos = IntArray(VOICES)
    private val voiceVol = FloatArray(VOICES)
    private val lock = Any()

    @Volatile private var running = false
    private var thread: Thread? = null

    fun play(clip: Int, volume: Float = 1f) {
        if (!enabled || !running) return
        synchronized(lock) {
            var slot = 0
            var bestPos = -1
            for (i in 0 until VOICES) {
                if (voiceClip[i] < 0) { slot = i; bestPos = Int.MAX_VALUE; break }
                if (voicePos[i] > bestPos) { bestPos = voicePos[i]; slot = i }
            }
            voiceClip[slot] = clip; voicePos[slot] = 0; voiceVol[slot] = volume
        }
    }

    /** Starts the mixer (activity resumed). Safe to call repeatedly. */
    fun start() {
        if (running || !threadEnabled) return
        running = true
        thread = Thread({ mixLoop() }, "audio-mixer").apply { isDaemon = true; start() }
    }

    /** Stops the mixer and releases the track (activity paused). */
    fun stop() {
        running = false
        thread?.let { runCatching { it.join(400) } }
        thread = null
        synchronized(lock) { for (i in 0 until VOICES) voiceClip[i] = -1 }
    }

    private fun mixLoop() {
        var track: AudioTrack? = null
        try {
            val min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) return
            track = AudioTrack(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
                AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
                maxOf(min, BLOCK * 4), AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE,
            )
            if (track.state != AudioTrack.STATE_INITIALIZED) return
            track.play()
            val out = ShortArray(BLOCK)
            val mix = FloatArray(BLOCK)
            var phase = 0f
            var freq = 60f
            var vol = 0f
            var lp = 0f
            var seed = 12345
            while (running) {
                val on = enabled && engineOn
                val targetF = 58f + 150f * engineSpeed + (if (boosting) 28f else 0f)
                val targetV = if (on) 0.11f + 0.07f * engineSpeed else 0f
                for (i in 0 until BLOCK) {
                    freq += (targetF - freq) * 0.0006f
                    vol += (targetV - vol) * 0.0015f
                    phase += freq / RATE
                    if (phase >= 1f) phase -= 1f
                    // two detuned-ish saw partials plus a sub, then a gentle low-pass: a soft engine drone
                    val saw = 2f * phase - 1f
                    var p2 = phase * 2f; if (p2 >= 1f) p2 -= 1f
                    val sub = if (phase < 0.5f) 1f else -1f
                    seed = seed * 1103515245 + 12345
                    val noise = ((seed ushr 16) and 0x7fff) / 16384f - 1f
                    val raw = 0.55f * saw + 0.3f * (2f * p2 - 1f) + 0.25f * sub + 0.1f * noise
                    lp += (raw - lp) * (0.12f + 0.2f * engineSpeed)
                    mix[i] = lp * vol
                }
                synchronized(lock) {
                    for (v in 0 until VOICES) {
                        val c = voiceClip[v]
                        if (c < 0) continue
                        val data = clips[c]
                        var pos = voicePos[v]
                        val g = if (enabled) voiceVol[v] else 0f
                        var i = 0
                        while (i < BLOCK && pos < data.size) { mix[i] += data[pos] * g; i++; pos++ }
                        if (pos >= data.size) voiceClip[v] = -1 else voicePos[v] = pos
                    }
                }
                for (i in 0 until BLOCK) {
                    var s = mix[i]
                    if (s > 1f) s = 1f
                    if (s < -1f) s = -1f
                    out[i] = (s * 32000f).toInt().toShort()
                }
                if (track.write(out, 0, BLOCK) < 0) break
            }
        } catch (e: Throwable) {
            Log.w("Audio", "audio disabled", e)
        } finally {
            running = false
            runCatching { track?.pause(); track?.flush() }
            runCatching { track?.release() }
        }
    }

    companion object {
        const val COIN = 0
        const val SHIELD = 1
        const val SHATTER = 2
        const val CRASH = 3
        const val NITRO = 4
        const val WHOOSH = 5
        const val CLICK = 6
        private const val RATE = 22050
        private const val BLOCK = 512
        private const val VOICES = 6

        /** Tests switch the mixer thread off (there is no audio device to block on). */
        @JvmStatic var threadEnabled = true

        private fun clip(seconds: Float, f: (t: Float, i: Int) -> Float): FloatArray {
            val n = (RATE * seconds).toInt()
            return FloatArray(n) { i ->
                val t = i / RATE.toFloat()
                val fadeIn = if (i < 60) i / 60f else 1f
                val fadeOut = if (n - i < 200) (n - i) / 200f else 1f
                f(t, i) * fadeIn * fadeOut
            }
        }

        private fun tone(f: Float, t: Float) = sin(2.0 * PI * f * t).toFloat()

        private fun coin() = clip(0.22f) { t, _ ->
            val f = if (t < 0.07f) 1047f else 1568f
            0.3f * (tone(f, t) + 0.3f * tone(2 * f, t)) * exp(-(if (t < 0.07f) t else t - 0.07f) * 16f)
        }

        private fun shield() = clip(0.5f) { t, _ ->
            val f = 420f + 900f * t
            0.3f * (tone(f, t) + 0.5f * tone(f * 1.5f, t)) * exp(-t * 5f)
        }

        private fun shatter(): FloatArray {
            var seed = 7
            return clip(0.45f) { t, _ ->
                seed = seed * 1103515245 + 12345
                val n = ((seed ushr 16) and 0x7fff) / 16384f - 1f
                (0.3f * n + 0.3f * tone(1900f - 1500f * t, t)) * exp(-t * 9f)
            }
        }

        private fun crash(): FloatArray {
            var seed = 99
            var lp = 0f
            return clip(0.9f) { t, _ ->
                seed = seed * 1103515245 + 12345
                val n = ((seed ushr 16) and 0x7fff) / 16384f - 1f
                lp += 0.35f * (n - lp)
                0.9f * lp * exp(-t * 5f) + 0.5f * tone(75f - 35f * t, t) * exp(-t * 4f)
            }
        }

        private fun nitro(): FloatArray {
            var seed = 5
            var lp = 0f
            return clip(0.8f) { t, _ ->
                seed = seed * 1103515245 + 12345
                val n = ((seed ushr 16) and 0x7fff) / 16384f - 1f
                lp += (0.08f + 0.3f * t) * (n - lp)
                val env = (if (t < 0.1f) t / 0.1f else 1f) * exp(-t * 2.5f)
                0.8f * lp * env + 0.2f * tone(160f + 500f * t, t) * env
            }
        }

        private fun whoosh(): FloatArray {
            var seed = 31
            var lp = 0f
            return clip(0.3f) { t, _ ->
                seed = seed * 1103515245 + 12345
                val n = ((seed ushr 16) and 0x7fff) / 16384f - 1f
                val p = t / 0.3f
                lp += (0.05f + 0.4f * sin(PI * p).toFloat()) * (n - lp)
                0.7f * lp * sin(PI * p).toFloat()
            }
        }

        private fun click() = clip(0.05f) { t, _ -> 0.25f * tone(1300f, t) * exp(-t * 60f) }
    }
}
