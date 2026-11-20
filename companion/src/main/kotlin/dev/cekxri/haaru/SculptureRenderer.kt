/* SPDX-License-Identifier: Apache-2.0 */
package dev.cekxri.haaru

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.opengl.Matrix
import android.os.Handler
import android.os.Build
import android.os.PowerManager
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

internal data class SculptureStats(
    val running: Boolean = false, val frames: Long = 0, val draws: Long = 0,
    val page: Int = 0, val morph: String = "", val tier: String = "uninitialised",
    val bufferWidth: Int = 0, val bufferHeight: Int = 0, val triangles: Int = 0,
    val displayHz: Float = 0f, val observedHz: Float = 0f, val cpuSubmitMs: Float = 0f,
    val glVersion: String = "", val error: String = "",
    val supportedHz: String = "", val requestedHz: Float = 0f,
    val depthOfField: Boolean = false, val focusPlane: Float = 4.4f, val emissiveSurface: Boolean = true,
    val cacheClears: Long = 0
)

private class SculptureProgram(context: Context, vertexResource: Int, fragmentResource: Int) : AutoCloseable {
    val id: Int
    private val locations = HashMap<String, Int>()
    init {
        fun shader(type: Int, resource: Int): Int {
            val code = context.resources.openRawResource(resource).bufferedReader().use { it.readText() }
            val shader = GLES30.glCreateShader(type)
            GLES30.glShaderSource(shader, code)
            GLES30.glCompileShader(shader)
            val status = IntArray(1)
            GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                val reason = GLES30.glGetShaderInfoLog(shader).take(1500)
                GLES30.glDeleteShader(shader)
                error("Sculpture shader compilation: " + reason)
            }
            return shader
        }
        val vertex = shader(GLES30.GL_VERTEX_SHADER, vertexResource)
        val fragment = try { shader(GLES30.GL_FRAGMENT_SHADER, fragmentResource) }
            catch (failure: Exception) { GLES30.glDeleteShader(vertex); throw failure }
        id = GLES30.glCreateProgram()
        GLES30.glAttachShader(id, vertex); GLES30.glAttachShader(id, fragment)
        GLES30.glLinkProgram(id)
        GLES30.glDeleteShader(vertex); GLES30.glDeleteShader(fragment)
        val status = IntArray(1)
        GLES30.glGetProgramiv(id, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val reason = GLES30.glGetProgramInfoLog(id).take(1500)
            GLES30.glDeleteProgram(id)
            error("Sculpture shader linking: " + reason)
        }
    }
    fun use() { GLES30.glUseProgram(id) }
    private fun location(name: String): Int = locations.getOrPut(name) { GLES30.glGetUniformLocation(id, name) }
    fun float(name: String, value: Float) { GLES30.glUniform1f(location(name), value) }
    fun int(name: String, value: Int) { GLES30.glUniform1i(location(name), value) }
    fun vec2(name: String, x: Float, y: Float) { GLES30.glUniform2f(location(name), x, y) }
    fun vec3(name: String, values: FloatArray) { GLES30.glUniform3fv(location(name), 1, values, 0) }
    fun vec3(name: String, x: Float, y: Float, z: Float) { GLES30.glUniform3f(location(name), x, y, z) }
    fun vec4(name: String, values: FloatArray) { GLES30.glUniform4fv(location(name), 1, values, 0) }
    fun matrix(name: String, values: FloatArray) { GLES30.glUniformMatrix4fv(location(name), 1, false, values, 0) }
    override fun close() { GLES30.glDeleteProgram(id) }
}

/** GPU-copy destination only; it is never attached to the framebuffer being sampled. */
private class SculptureFrameCopy(val width: Int, val height: Int) : AutoCloseable {
    private val name = IntArray(1)
    val texture: Int get() = name[0]
    init {
        GLES30.glGenTextures(1, name, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, width, height, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
    }
    override fun close() { GLES30.glDeleteTextures(1, name, 0) }
}

private class SculptureTarget(val width: Int, val height: Int, mipmaps: Boolean) : AutoCloseable {
    private val names = IntArray(3)
    val framebuffer: Int get() = names[0]
    val texture: Int get() = names[1]
    init {
        GLES30.glGenFramebuffers(1, names, 0)
        GLES30.glGenTextures(1, names, 1)
        GLES30.glGenRenderbuffers(1, names, 2)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, width, height, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER,
            if (mipmaps) GLES30.GL_LINEAR_MIPMAP_LINEAR else GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER,
            if (mipmaps) GLES30.GL_LINEAR else GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        if (mipmaps) GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, names[2])
        GLES30.glRenderbufferStorage(GLES30.GL_RENDERBUFFER, GLES30.GL_DEPTH_COMPONENT24, width, height)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, texture, 0)
        GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT, GLES30.GL_RENDERBUFFER, names[2])
        check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE) {
            "Sculpture framebuffer is incomplete"
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }
    override fun close() {
        GLES30.glDeleteFramebuffers(1, names, 0)
        GLES30.glDeleteTextures(1, names, 1)
        GLES30.glDeleteRenderbuffers(1, names, 2)
    }
}

/** All EGL/GL calls, resources, motion integration and vsync callbacks stay on one GL Looper. */
internal class SculptureRenderer(
    private val context: Context, private val handler: Handler,
    val surfaceTexture: SurfaceTexture, width: Int, height: Int, refreshRate: Float,
    private val report: (SculptureStats) -> Unit
) : AutoCloseable, Choreographer.FrameCallback {
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var nativeSurface: Surface? = null
    private val choreographer = Choreographer.getInstance()
    private var queued = false
    private var closed = false
    private var input = SculptureInput()
    private var widthPx = width.coerceAtLeast(1)
    private var heightPx = height.coerceAtLeast(1)
    private var displayHz = validRefresh(refreshRate)
    private var bufferWidth = 1
    private var bufferHeight = 1
    private var windowWidth = 1
    private var windowHeight = 1
    private val surfaceSize = IntArray(1)
    private var maximumTexture = 2048
    private var tier = -1
    private var scene: SculptureTarget? = null
    private var backs: SculptureTarget? = null
    private var focusDepth: SculptureTarget? = null
    private var frameCopy: SculptureFrameCopy? = null
    private var mainMesh: SculptureMesh? = null
    private var detailMesh: SculptureMesh? = null
    private var quad: SculptureQuad? = null
    private var surfaceProgram: SculptureProgram? = null
    private var depthProgram: SculptureProgram? = null
    private var backgroundProgram: SculptureProgram? = null
    private var focusProgram: SculptureProgram? = null
    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val viewProjection = FloatArray(16)
    private val model = FloatArray(16)
    private val inverseModel = FloatArray(16)
    private val normalModel = FloatArray(16)
    private val weights = floatArrayOf(1f, 0f, 0f, 0f)
    private val weightVelocity = FloatArray(4)
    private val material = FloatArray(4)
    private val baseColour = FloatArray(3)
    private var time = 0f
    private var yaw = 0f
    private var yawVelocity = 0f
    private var pitch = 0f
    private var pitchVelocity = 0f
    private var press = 0f
    private var scroll = 0f
    private var frames = 0L
    private var draws = 0L
    private var lastFrame = 0L
    private var lastRenderedFrame = 0L
    private var intervalStart = 0L
    private var intervalFrames = 0L
    private var observedHz = 0f
    private var slowFrames = 0
    private var calmFrames = 0
    private var submitMs = 0f
    private var glVersion = ""
    private var dirty = true
    private var lastLog = 0L
    private var lastPressed = false
    private var lastPointerX = .67f
    private var terminalError = ""
    private var surfaceRequestedHz = -1f
    private var cacheHasScene = false
    private var cacheClears = 0L

    init {
        try {
            createEgl()
            glVersion = GLES30.glGetString(GLES30.GL_VERSION).orEmpty()
            check(glVersion.contains("OpenGL ES 3")) { "OpenGL ES 3 is unavailable" }
            val textureLimit = IntArray(1)
            val renderbufferLimit = IntArray(1)
            GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, textureLimit, 0)
            GLES30.glGetIntegerv(GLES30.GL_MAX_RENDERBUFFER_SIZE, renderbufferLimit, 0)
            maximumTexture = min(textureLimit[0], renderbufferLimit[0]).coerceAtLeast(1)
            surfaceProgram = SculptureProgram(context, R.raw.sculpture_mesh_vert, R.raw.sculpture_surface_frag)
            depthProgram = SculptureProgram(context, R.raw.sculpture_mesh_vert, R.raw.sculpture_depth_frag)
            backgroundProgram = SculptureProgram(context, R.raw.sculpture_quad_vert, R.raw.sculpture_background_frag)
            focusProgram = SculptureProgram(context, R.raw.sculpture_quad_vert, R.raw.sculpture_focus_frag)
            quad = SculptureQuad()
            setTier(0)
            Log.i("HaaruSculpture", "Created GLES3 TextureView renderer; displayHz=" + displayHz + "; " + glVersion)
        } catch (failure: Exception) {
            terminalError = failure.message.orEmpty().take(500)
            close()
            throw failure
        }
    }

    private fun validRefresh(rate: Float): Float = if (rate.isFinite() && rate > 0f) rate else 60f

    private fun createEgl() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "EGL display unavailable" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "EGL initialisation failed" }
        val configurations = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val attributes = intArrayOf(EGL14.EGL_RENDERABLE_TYPE, 0x40, // EGL_OPENGL_ES3_BIT_KHR.
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT, EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_DEPTH_SIZE, 24, EGL14.EGL_NONE)
        check(EGL14.eglChooseConfig(display, attributes, 0, configurations, 0, 1, count, 0) && count[0] > 0) {
            "No transparent GLES3 EGL configuration"
        }
        val configuration = checkNotNull(configurations[0])
        eglContext = EGL14.eglCreateContext(display, configuration, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "GLES3 context creation failed" }
        val initialScale = min(1f, 1024f / widthPx)
        surfaceTexture.setDefaultBufferSize(max(1, (widthPx * initialScale).toInt()),
            max(1, (heightPx * initialScale).toInt()))
        nativeSurface = Surface(surfaceTexture)
        eglSurface = EGL14.eglCreateWindowSurface(display, configuration, nativeSurface,
            intArrayOf(EGL14.EGL_NONE), 0)
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "EGL window surface creation failed" }
        check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, eglContext)) { "EGL make-current failed" }
        EGL14.eglSwapInterval(display, 0)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glFrontFace(GLES30.GL_CCW)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        // Packed depth is data: framebuffer dithering would corrupt its least significant bytes.
        GLES30.glDisable(GLES30.GL_DITHER)
    }

    fun resize(width: Int, height: Int, refreshRate: Float) {
        if (closed) return
        widthPx = width.coerceAtLeast(1); heightPx = height.coerceAtLeast(1)
        displayHz = validRefresh(refreshRate)
        resetInterval()
        allocateTargets()
        dirty = true
        requestFrame()
    }

    fun refresh(refreshRate: Float) {
        if (closed) return
        displayHz = validRefresh(refreshRate)
        resetInterval()
        dirty = true
        requestFrame()
        Log.i("HaaruSculpture", "Display refresh changed to " + displayHz + "Hz; no application FPS ceiling")
    }

    fun update(value: SculptureInput) {
        if (closed) return
        val wasVisible = visible()
        val wasReduced = input.reduced
        val wasPowerLimited = input.powerSave || input.thermal >= PowerManager.THERMAL_STATUS_MODERATE
        if (!value.reduced && lastPressed && value.pressed) {
            yawVelocity = (yawVelocity + (value.pointerX - lastPointerX) * 15f).coerceIn(-4f, 4f)
        }
        lastPointerX = value.pointerX
        lastPressed = value.pressed
        input = value
        val surfaceRate = if (value.active && value.foreground && value.attached && !value.reduced)
            value.requestedHz else 0f
        if (Build.VERSION.SDK_INT >= 30 && surfaceRequestedHz != surfaceRate) {
            runCatching {
                if (Build.VERSION.SDK_INT >= 31) nativeSurface?.setFrameRate(surfaceRate,
                    Surface.FRAME_RATE_COMPATIBILITY_DEFAULT, Surface.CHANGE_FRAME_RATE_ALWAYS)
                else nativeSurface?.setFrameRate(surfaceRate, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
            }.onFailure { Log.w("HaaruSculpture", "Surface frame-rate request unavailable: " + it.message.orEmpty().take(200)) }
            surfaceRequestedHz = surfaceRate
        }
        if (value.reduced) {
            for (index in 0..3) { weights[index] = if (index == value.page) 1f else 0f; weightVelocity[index] = 0f }
            yaw = 0f; pitch = 0f; yawVelocity = 0f; pitchVelocity = 0f; press = 0f
            scroll = value.scrollPx
        }
        val requiredTier = when {
            value.thermal >= PowerManager.THERMAL_STATUS_SEVERE || value.lowRam -> 2
            value.reduced && !value.powerSave && value.thermal < PowerManager.THERMAL_STATUS_MODERATE -> 0
            value.powerSave || value.thermal >= PowerManager.THERMAL_STATUS_MODERATE -> max(tier, 1)
            else -> tier.coerceAtLeast(0)
        }
        if (requiredTier != tier) setTier(requiredTier)
        dirty = true
        if (!wasVisible || !visible()) lastFrame = 0L
        val powerLimited = value.powerSave || value.thermal >= PowerManager.THERMAL_STATUS_MODERATE
        if (wasVisible != visible() || wasReduced != value.reduced || wasPowerLimited != powerLimited) resetInterval()
        if (visible()) requestFrame() else {
            if (queued) { choreographer.removeFrameCallback(this); queued = false }
            clearOffscreenCache()
            publish(false)
        }
    }

    private fun clearOffscreenCache() {
        if (!cacheHasScene || closed || !input.attached || !input.foreground ||
            nativeSurface?.isValid != true || display == EGL14.EGL_NO_DISPLAY ||
            eglContext == EGL14.EGL_NO_CONTEXT || eglSurface == EGL14.EGL_NO_SURFACE) return
        check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, eglContext)) {
            "EGL make-current failed during offscreen cache clear"
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        GLES30.glColorMask(true, true, true, true)
        GLES30.glDepthMask(true)
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        checkGl("offscreen cache clear")
        check(EGL14.eglSwapBuffers(display, eglSurface)) {
            "EGL offscreen clear swap failed: " + EGL14.eglGetError()
        }
        cacheHasScene = false
        cacheClears++
        Log.i("HaaruSculpture", "Cleared offscreen TextureView cache once; page=" + input.page +
            "; scrollPx=" + input.scrollPx + "; cacheClears=" + cacheClears)
    }

    private fun visible(): Boolean = !closed && input.palette != null && input.active && input.foreground &&
        input.attached && input.scrollPx < heightPx * 1.18f

    private fun requestFrame() {
        if (!queued && visible()) { queued = true; choreographer.postFrameCallback(this) }
    }

    private fun setTier(value: Int) {
        val next = value.coerceIn(0, 2)
        if (next == tier) return
        tier = next
        mainMesh?.close(); detailMesh?.close()
        mainMesh = when (tier) { 0 -> SculptureMesh(96, 32); 1 -> SculptureMesh(72, 24); else -> SculptureMesh(48, 16) }
        detailMesh = when (tier) { 0 -> SculptureMesh(64, 24); 1 -> SculptureMesh(48, 16); else -> SculptureMesh(32, 12) }
        allocateTargets()
        slowFrames = 0; calmFrames = 0
        Log.i("HaaruSculpture", "Quality=" + tierName() + "; buffer=" + bufferWidth + "x" + bufferHeight)
    }

    private fun allocateTargets() {
        scene?.close(); backs?.close()
        focusDepth?.close(); frameCopy?.close()
        scene = null; backs = null
        focusDepth = null; frameCopy = null
        val maximumWidth = when (tier) { 0 -> 1024; 1 -> 768; else -> 512 }
        val scale = min(1f, min(maximumWidth.toFloat() / widthPx,
            maximumTexture.toFloat() / max(widthPx, heightPx)))
        bufferWidth = max(1, (widthPx * scale).toInt())
        bufferHeight = max(1, (heightPx * scale).toInt())
        surfaceTexture.setDefaultBufferSize(bufferWidth, bufferHeight)
        scene = SculptureTarget(bufferWidth, bufferHeight, true)
        backs = SculptureTarget(bufferWidth, bufferHeight, false)
        if (tier < 2) focusDepth = SculptureTarget(bufferWidth, bufferHeight, false)
        checkGl("target allocation")
        Matrix.perspectiveM(projection, 0, 38f, widthPx.toFloat() / heightPx, .1f, 12f)
        Matrix.setLookAtM(view, 0, 0f, 0f, 4.4f, 0f, 0f, 0f, 0f, 1f, 0f)
        Matrix.multiplyMM(viewProjection, 0, projection, 0, view, 0)
    }

    override fun doFrame(frameTimeNanos: Long) {
        queued = false
        if (!visible()) { publish(false); return }
        val powerLimited = input.powerSave || input.thermal >= PowerManager.THERMAL_STATUS_MODERATE
        val minimumPeriod = if (powerLimited) 1_000_000_000L / min(displayHz, 30f).toLong().coerceAtLeast(1L) else 0L
        if (!input.reduced && lastRenderedFrame != 0L && frameTimeNanos - lastRenderedFrame < minimumPeriod * .90) {
            requestFrame(); return
        }
        if (input.reduced && !dirty) { publish(false); return }
        val rawPeriod = if (lastFrame == 0L) 1f / displayHz else (frameTimeNanos - lastFrame) / 1_000_000_000f
        val dt = rawPeriod.coerceIn(.001f, .05f)
        lastFrame = frameTimeNanos
        if (!input.reduced) integrate(dt)
        val start = System.nanoTime()
        try {
            render()
            if (frames % 30L == 0L) checkGl("drawing")
            check(EGL14.eglSwapBuffers(display, eglSurface)) { "EGL swap failed: " + EGL14.eglGetError() }
            cacheHasScene = true
            frames++; lastRenderedFrame = frameTimeNanos; dirty = false
            submitMs = (System.nanoTime() - start) / 1_000_000f
            if (intervalStart == 0L) intervalStart = frameTimeNanos
            intervalFrames++
            if (frameTimeNanos - intervalStart >= 2_000_000_000L) {
                observedHz = intervalFrames * 1_000_000_000f / (frameTimeNanos - intervalStart)
                intervalFrames = 0L; intervalStart = frameTimeNanos
            }
            // Ordinary frame pressure reduces detail and fill rate, never installs a 30fps default.
            val budgetMs = 1000f / if (powerLimited) min(displayHz, 30f) else displayHz
            val late = rawPeriod * 1000f > budgetMs * 1.70f || submitMs > budgetMs * .92f
            if (late) { slowFrames++; calmFrames = 0 } else { slowFrames = max(0, slowFrames - 1); calmFrames++ }
            if (slowFrames >= 18 && tier < 2) setTier(tier + 1)
            if (calmFrames > 1200 && tier > 0 && !input.lowRam && !powerLimited) setTier(tier - 1)
            if (input.reduced || frames % 15L == 0L) publish(!input.reduced)
            if (frameTimeNanos - lastLog > 4_000_000_000L) {
                lastLog = frameTimeNanos
                Log.i("HaaruSculpture", "frame=" + frames + "; page=" + input.page + "; morph=" + morphString() +
                    "; quality=" + tierName() + "; displayHz=" + displayHz + "; observedHz=" + observedHz +
                    "; cpuSubmitMs=" + submitMs + "; fourMeshes=true; dof=" + (tier < 2) +
                    "; focusPlane=4.4; surfaceEmission=true")
            }
        } catch (failure: Exception) {
            stopWithFailure(failure)
            return
        }
        if (!input.reduced) requestFrame()
    }

    private fun integrate(dt: Float) {
        time += dt
        val steps = max(1, (dt / .008f).toInt())
        val step = dt / steps
        repeat(steps) {
            var total = 0f
            for (index in 0..3) {
                val target = if (index == input.page) 1f else 0f
                weightVelocity[index] += ((target - weights[index]) * 66f - weightVelocity[index] * 14f) * step
                weights[index] = (weights[index] + weightVelocity[index] * step).coerceIn(0f, 1f)
                total += weights[index]
            }
            if (total > .0001f) for (index in 0..3) weights[index] /= total
            val targetYaw = if (input.pressed) (input.pointerX - .67f) * 1.2f else 0f
            val targetPitch = if (input.pressed) (input.pointerY - .51f) * .70f else 0f
            yawVelocity += ((targetYaw - yaw) * 35f - yawVelocity * 6.8f) * step
            pitchVelocity += ((targetPitch - pitch) * 38f - pitchVelocity * 7.5f) * step
            yaw = (yaw + yawVelocity * step).coerceIn(-.9f, .9f)
            pitch = (pitch + pitchVelocity * step).coerceIn(-.65f, .65f)
            press += ((if (input.pressed) 1f else 0f) - press) * min(1f, step * 13f)
            scroll += (input.scrollPx - scroll) * min(1f, step * 15f)
        }
    }

    private fun palette(program: SculptureProgram) {
        val colours = checkNotNull(input.palette)
        program.vec3("uPrimary", colours.primary); program.vec3("uSecondary", colours.secondary)
        program.vec3("uTertiary", colours.tertiary); program.vec3("uSurface", colours.surface)
        program.vec3("uInk", colours.ink); program.vec3("uLight", colours.light)
    }

    private fun render() {
        val sceneTarget = checkNotNull(scene)
        val backTarget = checkNotNull(backs)
        val surfaceShader = checkNotNull(surfaceProgram)
        val depthShader = checkNotNull(depthProgram)
        val backgroundShader = checkNotNull(backgroundProgram)
        val presentationEncoding = if (tier < 2) 2 else 1
        GLES30.glViewport(0, 0, bufferWidth, bufferHeight)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, sceneTarget.framebuffer)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, backTarget.texture)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, backTarget.texture)
        GLES30.glDisable(GLES30.GL_BLEND); GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glClearColor(0f, 0f, 0f, 1f); GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        backgroundShader.use(); palette(backgroundShader); backgroundShader.int("uMode", 0)
        backgroundShader.int("uEncoding", 0)
        backgroundShader.vec2("uResolution", bufferWidth.toFloat(), bufferHeight.toFloat())
        backgroundShader.float("uTime", if (input.reduced) 0f else time)
        backgroundShader.float("uScroll", scroll / heightPx)
        backgroundShader.vec4("uWeights", weights)
        quad?.draw(); draws++
        GLES30.glEnable(GLES30.GL_DEPTH_TEST); GLES30.glEnable(GLES30.GL_CULL_FACE); GLES30.glCullFace(GLES30.GL_BACK)
        drawObject(surfaceShader, 1, 0); drawObject(surfaceShader, 3, 0)
        val opaqueMain = mainTransmission() <= .001f
        if (opaqueMain) drawObject(surfaceShader, 0, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sceneTarget.texture)
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        EGL14.eglQuerySurface(display, eglSurface, EGL14.EGL_WIDTH, surfaceSize, 0)
        windowWidth = surfaceSize[0].coerceAtLeast(1)
        EGL14.eglQuerySurface(display, eglSurface, EGL14.EGL_HEIGHT, surfaceSize, 0)
        windowHeight = surfaceSize[0].coerceAtLeast(1)
        GLES30.glViewport(0, 0, windowWidth, windowHeight)
        GLES30.glClearColor(0f, 0f, 0f, 0f); GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST); GLES30.glDisable(GLES30.GL_CULL_FACE); GLES30.glEnable(GLES30.GL_BLEND)
        backgroundShader.use(); palette(backgroundShader); backgroundShader.int("uMode", 1)
        backgroundShader.int("uEncoding", presentationEncoding)
        backgroundShader.float("uScroll", scroll / heightPx)
        quad?.draw(); draws++
        GLES30.glEnable(GLES30.GL_DEPTH_TEST); GLES30.glEnable(GLES30.GL_CULL_FACE); GLES30.glCullFace(GLES30.GL_BACK)
        GLES30.glDisable(GLES30.GL_BLEND)
        drawObject(surfaceShader, 1, presentationEncoding); drawObject(surfaceShader, 3, presentationEncoding)
        if (opaqueMain) drawObject(surfaceShader, 0, presentationEncoding)
        drawTransparent(2, surfaceShader, depthShader, sceneTarget, backTarget, presentationEncoding)
        if (!opaqueMain) drawTransparent(0, surfaceShader, depthShader, sceneTarget, backTarget, presentationEncoding)
        GLES30.glDepthFunc(GLES30.GL_LESS)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDepthMask(true)
        if (tier < 2) drawDepthOfField(depthShader)
    }

    private fun mainTransmission(): Float = .90f * weights[0] + .68f * weights[2]

    private fun drawTransparent(index: Int, surfaceShader: SculptureProgram, depthShader: SculptureProgram,
                                sceneTarget: SculptureTarget, backTarget: SculptureTarget, encoding: Int) {
        // Record this object's first rear-facing exit, never another overlapping sculpture's exit.
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, backTarget.framebuffer)
        GLES30.glViewport(0, 0, bufferWidth, bufferHeight)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST); GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDepthMask(true); GLES30.glColorMask(true, true, true, true)
        GLES30.glDepthFunc(GLES30.GL_LESS); GLES30.glCullFace(GLES30.GL_FRONT)
        GLES30.glClearColor(1f, 1f, 1f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        drawObject(depthShader, index, 0)

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, windowWidth, windowHeight)
        GLES30.glCullFace(GLES30.GL_BACK)
        GLES30.glColorMask(false, false, false, false)
        drawObject(depthShader, index, encoding)
        // A closed transparent form shades its nearest front surface once; it cannot blend itself.
        GLES30.glColorMask(true, true, true, true)
        GLES30.glDepthMask(false); GLES30.glDepthFunc(GLES30.GL_LEQUAL)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sceneTarget.texture)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, backTarget.texture)
        drawObject(surfaceShader, index, encoding)
        GLES30.glDepthMask(true)
    }

    private fun drawDepthOfField(depthShader: SculptureProgram) {
        val depth = checkNotNull(focusDepth)
        val shader = checkNotNull(focusProgram)
        // Camera depth is independent of the rough glass's private refraction texture.
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, depth.framebuffer)
        GLES30.glViewport(0, 0, bufferWidth, bufferHeight)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST); GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glCullFace(GLES30.GL_BACK); GLES30.glDepthFunc(GLES30.GL_LESS)
        GLES30.glDisable(GLES30.GL_BLEND); GLES30.glDepthMask(true)
        GLES30.glColorMask(true, true, true, true)
        GLES30.glClearColor(1f, 1f, 1f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        for (index in 0..3) drawObject(depthShader, index, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, windowWidth, windowHeight)
        if (frameCopy?.width != windowWidth || frameCopy?.height != windowHeight) {
            frameCopy?.close()
            frameCopy = null
            frameCopy = SculptureFrameCopy(windowWidth, windowHeight)
        }
        val colour = checkNotNull(frameCopy)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, colour.texture)
        // A GPU framebuffer copy: no glReadPixels, Bitmap or Compose frame recomposition.
        GLES30.glCopyTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, 0, 0, windowWidth, windowHeight)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, depth.texture)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST); GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glDisable(GLES30.GL_BLEND)
        shader.use()
        shader.int("uColour", 0); shader.int("uDepth", 1)
        shader.vec2("uTexel", 1f / windowWidth, 1f / windowHeight)
        shader.float("uFocusPlane", 4.4f)
        shader.float("uMaximumRadius", if (tier == 0) 3f else 2f)
        quad?.draw(); draws++
    }

    private fun drawObject(program: SculptureProgram, index: Int, encoding: Int) {
        program.use()
        palette(program)
        modelMatrix(index)
        program.matrix("uModel", model); program.matrix("uNormalModel", normalModel)
        program.matrix("uViewProjection", viewProjection)
        program.vec3("uCamera", 0f, 0f, 4.4f)
        program.vec4("uWeights", weights)
        program.float("uTime", if (input.reduced) 0f else time)
        program.float("uDeform", if (input.reduced) 0f else .035f + press * .075f)
        program.float("uObject", index.toFloat())
        program.float("uEmission", if (index == 2) .16f else 0f)
        program.int("uEncoding", encoding)
        program.int("uQuality", tier)
        program.vec2("uResolution", (if (encoding == 0) bufferWidth else windowWidth).toFloat(),
            (if (encoding == 0) bufferHeight else windowHeight).toFloat())
        program.int("uScene", 0); program.int("uBackDepth", 1)
        objectMaterial(index)
        program.vec4("uMaterial", material); program.vec3("uBaseColour", baseColour)
        if (index == 0) mainMesh?.draw() else detailMesh?.draw()
        draws++
    }

    private fun objectMaterial(index: Int) {
        val colours = checkNotNull(input.palette)
        val role = when (index) { 1 -> colours.secondary; 2 -> colours.tertiary; else -> colours.primary }
        for (channel in 0..2) baseColour[channel] = when (index) {
            0 -> role[channel] * .78f + colours.surface[channel] * .12f + colours.light[channel] * .10f
            1 -> role[channel] * .72f + colours.surface[channel] * .17f + colours.light[channel] * .11f
            2 -> role[channel] * .64f + colours.light[channel] * .26f + colours.surface[channel] * .10f
            else -> colours.light[channel] * .66f + role[channel] * .14f + colours.surface[channel] * .20f
        }
        when (index) {
            0 -> {
                material[0] = weights[1] * .91f + weights[3] * .03f
                material[1] = .13f * weights[0] + .23f * weights[1] + .43f * weights[2] + .40f * weights[3]
                material[2] = .90f * weights[0] + .68f * weights[2]
                material[3] = 1.46f
            }
            1 -> { material[0] = .86f; material[1] = .24f; material[2] = 0f; material[3] = 1.45f }
            2 -> { material[0] = .02f; material[1] = .38f; material[2] = .68f; material[3] = 1.43f }
            else -> { material[0] = .035f; material[1] = .48f; material[2] = 0f; material[3] = 1.48f }
        }
    }

    private fun modelMatrix(index: Int) {
        val phase = index * 1.73f
        val aspect = widthPx.toFloat() / heightPx
        val halfHeight = tan(19f * Math.PI.toFloat() / 180f) * 4.4f
        val centreX = when (index) { 0 -> .67f * weights[0] + .53f * weights[1] + .73f * weights[2] + .62f * weights[3]
            1 -> .44f; 2 -> .89f; else -> .80f }
        val centreY = when (index) { 0 -> .48f * weights[0] + .50f * weights[1] + .44f * weights[2] + .52f * weights[3]
            1 -> .32f; 2 -> .32f; else -> .70f }
        val depth = when (index) { 0 -> 0f; 1 -> -.45f; 2 -> -.85f; else -> -.20f }
        val size = when (index) { 0 -> .88f; 1 -> .27f; 2 -> .24f; else -> .30f }
        val fit = min(1f, aspect / .95f)
        val floatY = if (input.reduced) 0f else sin(time * (.62f + index * .13f) + phase) * .042f
        val floatX = if (input.reduced) 0f else sin(time * (.37f + index * .09f) + phase) * .020f
        val breathing = if (input.reduced) 1f else 1f + sin(time * (.52f + index * .11f) + phase) * .015f
        val parallax = scroll / heightPx * (if (index == 0) 1.32f else .86f + index * .20f) * halfHeight
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, (centreX - .5f) * 2f * halfHeight * aspect + floatX,
            (.5f - centreY) * 2f * halfHeight + floatY + parallax, depth)
        val pagePitch = -12f * weights[0] + 16f * weights[1] - 28f * weights[2] + 9f * weights[3]
        val pageYaw = -17f * weights[0] + 24f * weights[1] - 31f * weights[2] + 14f * weights[3]
        val pageRoll = -16f * weights[0] + 19f * weights[1] - 29f * weights[2] + 12f * weights[3]
        Matrix.rotateM(model, 0, pagePitch + index * 33f + pitch * 42f, 1f, 0f, 0f)
        Matrix.rotateM(model, 0, pageYaw + index * 24f + yaw * 57f +
            if (input.reduced) 0f else sin(time * (.27f + index * .08f) + phase) * 5f,
            0f, 1f, 0f)
        Matrix.rotateM(model, 0, pageRoll + index * 28f, 0f, 0f, 1f)
        Matrix.scaleM(model, 0, size * fit * breathing * (1f + press * .035f),
            size * fit * breathing * (1f - press * .028f), size * fit * breathing)
        check(Matrix.invertM(inverseModel, 0, model, 0)) { "Sculpture transform is singular" }
        Matrix.transposeM(normalModel, 0, inverseModel, 0)
    }

    private fun tierName(): String = when (tier) { 0 -> "high"; 1 -> "balanced"; else -> "compact" }
    private fun morphString(): String = weights.joinToString(",") { ((it * 100f).toInt() / 100f).toString() }
    private fun publish(running: Boolean) {
        report(SculptureStats(running, frames, draws, input.page, morphString(), tierName(),
            bufferWidth, bufferHeight, (mainMesh?.triangles ?: 0) + (detailMesh?.triangles ?: 0) * 3,
            displayHz, observedHz, submitMs, glVersion, terminalError,
            depthOfField = tier < 2 && focusDepth != null, focusPlane = 4.4f, emissiveSurface = true,
            cacheClears = cacheClears))
    }

    private fun resetInterval() {
        intervalStart = 0L; intervalFrames = 0L; observedHz = 0f
    }

    private fun checkGl(stage: String) {
        val error = GLES30.glGetError()
        check(error == GLES30.GL_NO_ERROR) { "GLES error " + error + " during " + stage }
    }

    fun stopWithFailure(failure: Throwable) {
        terminalError = failure.message.orEmpty().ifBlank { failure.javaClass.simpleName }.take(500)
        Log.e("HaaruSculpture", "Rendering stopped: " + terminalError)
        close()
    }

    override fun close() {
        if (closed) return
        closed = true
        choreographer.removeFrameCallback(this); queued = false
        if (display != EGL14.EGL_NO_DISPLAY) {
            runCatching {
                if (Build.VERSION.SDK_INT >= 30) nativeSurface?.setFrameRate(0f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) runCatching {
                if (eglSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglMakeCurrent(display, eglSurface, eglSurface, eglContext)
                    mainMesh?.close(); detailMesh?.close(); quad?.close()
                    scene?.close(); backs?.close(); focusDepth?.close(); frameCopy?.close()
                    surfaceProgram?.close(); depthProgram?.close(); backgroundProgram?.close(); focusProgram?.close()
                }
            }
            runCatching { EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT) }
            if (eglSurface != EGL14.EGL_NO_SURFACE) runCatching { EGL14.eglDestroySurface(display, eglSurface) }
            if (eglContext != EGL14.EGL_NO_CONTEXT) runCatching { EGL14.eglDestroyContext(display, eglContext) }
            runCatching {
                EGL14.eglReleaseThread()
                EGL14.eglTerminate(display)
            }
        }
        runCatching { nativeSurface?.release() }; nativeSurface = null
        publish(false)
        Log.i("HaaruSculpture", "Released EGL renderer; frames=" + frames)
    }
}
