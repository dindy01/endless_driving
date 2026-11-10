package com.endlessdrive.render

import org.lwjgl.egl.EGL
import org.lwjgl.egl.EGL10.EGL_NONE
import org.lwjgl.egl.EGL10.EGL_NO_CONTEXT
import org.lwjgl.egl.EGL10.eglChooseConfig
import org.lwjgl.egl.EGL10.eglCreateContext
import org.lwjgl.egl.EGL10.eglCreatePbufferSurface
import org.lwjgl.egl.EGL10.eglInitialize
import org.lwjgl.egl.EGL10.eglMakeCurrent
import org.lwjgl.egl.EGL12.eglBindAPI
import org.lwjgl.opengles.GLES
import org.lwjgl.opengles.GLES30
import org.lwjgl.system.Configuration
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Headless OpenGL ES 3 context via EGL (Mesa surfaceless platform, software llvmpipe).
 * Requires system packages: libegl1 libgles2 libegl-mesa0 libgl1-mesa-dri.
 */
class HeadlessContext(val width: Int, val height: Int) {
    companion object {
        fun tryCreate(w: Int, h: Int): HeadlessContext? = try {
            HeadlessContext(w, h).also { it.init() }
        } catch (t: Throwable) {
            println("Headless GL unavailable: ${t.javaClass.simpleName}: ${t.message}"); t.printStackTrace(System.out)
            null
        }
    }

    var renderer = ""; private set

    private fun init() {
        Configuration.EGL_LIBRARY_NAME.set("libEGL.so.1")
        Configuration.OPENGLES_LIBRARY_NAME.set("libGLESv2.so.2")
        EGL.getCapabilities() // loads libEGL
        MemoryStack.stackPush().use { st ->
            // Platform chosen via EGL_PLATFORM=surfaceless (set on the Gradle test task).
            val display = org.lwjgl.egl.EGL10.eglGetDisplay(0L)
            check(display != 0L) { "no EGL display" }
            val maj = st.mallocInt(1); val min = st.mallocInt(1)
            check(eglInitialize(display, maj, min)) { "eglInitialize failed" }
            EGL.createDisplayCapabilities(display, maj[0], min[0])
            check(eglBindAPI(0x30A0 /* EGL_OPENGL_ES_API */))
            val attribs = st.ints(
                0x3040, 0x40, // EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT
                0x3033, 0x01, // EGL_SURFACE_TYPE, EGL_PBUFFER_BIT
                0x3024, 8, 0x3023, 8, 0x3022, 8, 0x3021, 8, 0x3025, 24,
                EGL_NONE,
            )
            val configs = st.mallocPointer(1); val num = st.mallocInt(1)
            check(eglChooseConfig(display, attribs, configs, num) && num[0] > 0) { "no EGL config" }
            val cfg = configs[0]
            val ctx = eglCreateContext(display, cfg, EGL_NO_CONTEXT, st.ints(0x3098, 3, EGL_NONE))
            check(ctx != 0L) { "eglCreateContext failed" }
            val surf = eglCreatePbufferSurface(display, cfg, st.ints(0x3057, width, 0x3056, height, EGL_NONE))
            check(surf != 0L) { "pbuffer failed" }
            check(eglMakeCurrent(display, surf, surf, ctx)) { "eglMakeCurrent failed" }
        }
        GLES.createCapabilities()
        renderer = GLES30.glGetString(GLES30.GL_RENDERER) + " / " + GLES30.glGetString(GLES30.GL_VERSION)
    }

    fun readPixels(): IntArray {
        val buf = MemoryUtil.memAlloc(width * height * 4)
        GLES30.glReadPixels(0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
        val out = IntArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            val i = ((height - 1 - y) * width + x) * 4
            val r = buf.get(i).toInt() and 255; val g = buf.get(i + 1).toInt() and 255; val b = buf.get(i + 2).toInt() and 255
            out[y * width + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        MemoryUtil.memFree(buf)
        return out
    }

    fun save(pixels: IntArray, file: File) {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        img.setRGB(0, 0, width, height, pixels, 0, width)
        file.parentFile.mkdirs()
        ImageIO.write(img, "png", file)
    }
}
