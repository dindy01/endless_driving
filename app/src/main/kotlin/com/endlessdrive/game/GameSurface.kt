package com.endlessdrive.game

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.util.Log
import android.view.View
import com.endlessdrive.engine.Quality
import com.endlessdrive.engine.Renderer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * The GL surface: each frame it steps the game (GameController) and draws it (engine Renderer),
 * both on the GL thread. All GL resources are rebuilt in onSurfaceCreated, so losing the EGL
 * context (screen off, backgrounding, low memory) is harmless.
 */
class GameSurface(context: Context, attrs: AttributeSet?) : GLSurfaceView(context, attrs), GLSurfaceView.Renderer {
    interface FrameHook {
        /** Called on the GL thread after every simulated frame. */
        fun onFrame()
    }

    lateinit var controller: GameController
        private set
    private val renderer = Renderer(Quality.MEDIUM)
    private var quality = Quality.MEDIUM
    private var lastNanos = 0L
    private var frames = 0
    var hud: View? = null
    var frameHook: FrameHook? = null
    @Volatile var failed = false
        private set

    fun setup(controller: GameController, quality: Quality) {
        this.controller = controller
        this.quality = quality
        renderer.quality = quality
        if (!glEnabled) return
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 0, 16, 0)
        preserveEGLContextOnPause = true
        setRenderer(this)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    fun applyQuality(q: Quality) {
        quality = q
        if (glEnabled) queueEvent { renderer.quality = q } else renderer.quality = q
        applyResolution(width, height)
    }

    private fun applyResolution(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        if (quality.resolutionScale >= 0.999f) holder.setSizeFromLayout()
        else holder.setFixedSize((w * quality.resolutionScale).toInt(), (h * quality.resolutionScale).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyResolution(w, h)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        try {
            renderer.init(AndroidGl())
            failed = false
        } catch (e: Throwable) {
            // e.g. a driver rejecting a shader: keep the app alive (menus still work) rather than crash
            Log.e("GameSurface", "renderer init failed", e)
            failed = true
        }
        lastNanos = 0L
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        renderer.resize(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val dt = if (lastNanos == 0L) 1f / 60f else (now - lastNanos) * 1e-9f
        lastNanos = now
        controller.frame(dt)
        frameHook?.onFrame()
        if (!failed) renderer.draw(controller.world, if (controller.paused) 0f else if (dt > 0.1f) 0.1f else dt)
        if (++frames and 1 == 0) hud?.postInvalidateOnAnimation()
    }

    override fun onResume() {
        if (glEnabled) super.onResume()
        lastNanos = 0L
    }

    override fun onPause() {
        if (glEnabled) super.onPause()
    }

    companion object {
        /** Tests run without a GPU: they turn this off and step the controller directly. */
        @JvmStatic var glEnabled = true
    }
}
