package com.endlessdrive.game

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.util.Log
import com.endlessdrive.core.GameSession
import com.endlessdrive.core.Mode
import com.endlessdrive.render.Quality
import com.endlessdrive.render.SceneRenderer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * GL surface that runs the game loop on the GL thread: each frame advances the [GameSession]
 * (pure logic) and renders it with [SceneRenderer]. UI threads only post requests to the session.
 */
class GameSurfaceView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    GLSurfaceView(context, attrs), GLSurfaceView.Renderer {

    val session = GameSession()
    private val scene = SceneRenderer()
    private val gl = AndroidGL()
    private var lastNanos = 0L
    @Volatile var qualityLevel = Quality.MEDIUM
    private var appliedQuality = -1
    @Volatile var fps = 0f; private set
    private var fpsAccum = 0f
    private var fpsFrames = 0

    init {
        if (glEnabled) {
            setEGLContextClientVersion(3)
            setEGLConfigChooser(8, 8, 8, 8, 24, 0)
            preserveEGLContextOnPause = true
            setRenderer(this)
            renderMode = RENDERMODE_CONTINUOUSLY
        }
    }

    /** Resolution scale per quality preset (low renders at 75% and upscales). */
    fun applyResolution(level: Int) {
        if (!glEnabled) return
        val w = width; val h = height
        if (w <= 0 || h <= 0) return
        if (level == Quality.LOW) holder.setFixedSize((w * 0.75f).toInt(), (h * 0.75f).toInt()) else holder.setSizeFromLayout()
    }

    override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
        // Also called after an EGL context loss: recreate every GL resource.
        try {
            scene.onSurfaceCreated(gl)
            appliedQuality = -1
        } catch (e: Exception) {
            Log.e("GameSurfaceView", "GL init failed", e)
        }
        lastNanos = 0L
    }

    override fun onSurfaceChanged(unused: GL10?, width: Int, height: Int) {
        scene.onSurfaceChanged(gl, width, height)
    }

    override fun onDrawFrame(unused: GL10?) {
        val now = System.nanoTime()
        val dt = if (lastNanos == 0L) 0f else ((now - lastNanos) / 1e9f).coerceIn(0f, 0.05f)
        lastNanos = now
        val q = qualityLevel
        if (q != appliedQuality) {
            appliedQuality = q
            scene.quality = Quality.of(q)
            session.fx.limit = Quality.of(q).particleLimit
        }
        session.frame(dt)
        scene.render(gl, session, dt)
        fpsAccum += dt
        fpsFrames++
        if (fpsAccum >= 1f) {
            fps = fpsFrames / fpsAccum
            fpsAccum = 0f
            fpsFrames = 0
        }
        if (session.mode == Mode.PAUSED && renderMode == RENDERMODE_CONTINUOUSLY) {
            // Paused: stop burning frames; the last frame stays on screen.
            post { if (session.mode == Mode.PAUSED) renderMode = RENDERMODE_WHEN_DIRTY }
        }
    }

    fun resumeRendering() {
        if (glEnabled) {
            lastNanos = 0L
            renderMode = RENDERMODE_CONTINUOUSLY
        }
    }

    override fun onPause() {
        if (glEnabled) super.onPause()
    }

    override fun onResume() {
        lastNanos = 0L
        if (glEnabled) super.onResume()
    }

    companion object {
        /** Robolectric has no GL: tests disable the GL thread and step the session directly. */
        @JvmStatic var glEnabled = true
    }
}
