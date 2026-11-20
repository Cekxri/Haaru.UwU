/* SPDX-License-Identifier: Apache-2.0 */
package dev.cekxri.haaru

import android.app.ActivityManager
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.view.ViewConfiguration
import android.view.Display
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import haaru.ui.HaaruColours
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.lang.ref.WeakReference

internal data class SculpturePalette(
    val primary: FloatArray, val secondary: FloatArray, val tertiary: FloatArray,
    val surface: FloatArray, val ink: FloatArray, val light: FloatArray
) {
    companion object {
        fun from(colours: HaaruColours): SculpturePalette {
            fun linear(colour: Color): FloatArray {
                val converted = colour.convert(ColorSpaces.LinearSrgb)
                return floatArrayOf(converted.red, converted.green, converted.blue)
            }
            val ink = linear(colours.onSurface)
            val porcelain = linear(colours.surfaceContainerLowest)
            fun luminance(value: FloatArray) = value[0] * .2126f + value[1] * .7152f + value[2] * .0722f
            // In a night palette the foreground is the bright role. In a day palette porcelain is.
            val emitter = if (luminance(ink) >= luminance(porcelain)) ink else porcelain
            return SculpturePalette(linear(colours.primary), linear(colours.secondary),
                linear(colours.tertiary), linear(colours.background), ink, emitter)
        }
    }
}

internal data class SculptureInput(
    val page: Int = 0, val palette: SculpturePalette? = null,
    val reduced: Boolean = false, val active: Boolean = true,
    val foreground: Boolean = true, val attached: Boolean = false,
    val scrollPx: Float = 0f, val density: Float = 1f,
    val pointerX: Float = .67f, val pointerY: Float = .51f, val pressed: Boolean = false,
    val lowRam: Boolean = false, val powerSave: Boolean = false, val thermal: Int = 0,
    val requestedHz: Float = 0f
)

/** GL state is owned by one Looper; Compose never receives the per-frame clock. */
class SculptureController(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val thread = HandlerThread("HaaruSculptureGL").apply { start() }
    private val handler = Handler(thread.looper)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val closed = AtomicBoolean(false)
    private val wakeQueued = AtomicBoolean(false)
    private val windowWakeQueued = AtomicBoolean(false)
    private val inputLock = Any()
    @Volatile private var input = SculptureInput(
        lowRam = appContext.getSystemService(ActivityManager::class.java)?.isLowRamDevice == true
    )
    private var renderer: SculptureRenderer? = null // GL-thread-only.
    @Volatile internal var latestStats = SculptureStats()
        private set
    private val power = appContext.getSystemService(PowerManager::class.java)
    private val displays = appContext.getSystemService(DisplayManager::class.java)
    @Volatile private var displayId = 0
    @Volatile private var viewportHeight = 1
    private var windowReference = WeakReference<Window>(null)
    private var originalMode = 0
    private var originalRefresh = 0f
    private var ownsWindowRequest = false
    private var ownedMode = 0
    private var ownedRefresh = 0f
    @Volatile private var supportedHz = ""
    @Volatile private var requestedHz = 0f
    @Volatile private var rendererFailed = false
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) = Unit
        override fun onDisplayRemoved(id: Int) = Unit
        override fun onDisplayChanged(id: Int) {
            if (id == displayId) {
                displays?.getDisplay(id)?.refreshRate?.let { rate -> guarded { it.refresh(rate) } }
                requestWindowUpdate()
            }
        }
    }
    private val thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
        edit { it.copy(thermal = status) }
    }
    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            edit { it.copy(powerSave = power?.isPowerSaveMode == true) }
        }
    }
    private var thermalRegistered = false
    private var receiverRegistered = false
    private var displayRegistered = false

    init {
        displayRegistered = runCatching { displays?.registerDisplayListener(displayListener, handler); displays != null }.getOrDefault(false)
        input = input.copy(powerSave = power?.isPowerSaveMode == true, thermal = power?.currentThermalStatus ?: 0)
        thermalRegistered = runCatching {
            power?.addThermalStatusListener(Executor { action -> handler.post(action) }, thermalListener)
            power != null
        }.getOrDefault(false)
        receiverRegistered = runCatching {
            val filter = IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            if (Build.VERSION.SDK_INT >= 33) appContext.registerReceiver(powerReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else {
                @Suppress("DEPRECATION")
                appContext.registerReceiver(powerReceiver, filter)
            }
            true
        }.getOrDefault(false)
    }

    fun update(page: Int, colours: HaaruColours, reducedMotion: Boolean, active: Boolean, offsetPx: Float, density: Float) {
        val palette = SculpturePalette.from(colours)
        edit { current ->
            current.copy(page = page, palette = palette, reduced = reducedMotion, active = active,
                scrollPx = offsetPx, density = density, pressed = current.pressed && current.page == page)
        }
    }

    fun scroll(page: Int, offsetPx: Float) {
        edit { if (it.page == page) it.copy(scrollPx = offsetPx) else it }
    }

    fun pointer(xFraction: Float, yFraction: Float, pressed: Boolean) {
        edit { it.copy(pointerX = if (xFraction.isFinite()) xFraction.coerceIn(0f, 1f) else .67f,
            pointerY = if (yFraction.isFinite()) yFraction.coerceIn(0f, 1f) else .51f, pressed = pressed) }
    }

    internal fun foreground(value: Boolean) { edit { it.copy(foreground = value) } }
    internal fun attached(value: Boolean) { edit { it.copy(attached = value, pressed = if (value) it.pressed else false) } }

    private fun edit(change: (SculptureInput) -> SculptureInput) {
        if (closed.get()) return
        synchronized(inputLock) { input = change(input) }
        wakeRenderer()
        requestWindowUpdate()
    }

    private fun wakeRenderer() {
        if (wakeQueued.compareAndSet(false, true)) handler.post {
            wakeQueued.set(false)
            guarded { it.update(input) }
        }
    }

    private fun guarded(action: (SculptureRenderer) -> Unit) {
        val current = renderer ?: return
        try { action(current) }
        catch (failure: Throwable) {
            current.stopWithFailure(failure)
            renderer = null
        }
    }

    internal fun bindWindow(window: Window?, display: Display?) {
        mainHandler.post {
            if (windowReference.get() !== window) {
                runCatching { restoreWindowRequest() }
                windowReference = WeakReference(window)
                originalMode = window?.attributes?.preferredDisplayModeId ?: 0
                originalRefresh = window?.attributes?.preferredRefreshRate ?: 0f
            }
            if (display != null) displayId = display.displayId
            safelyApplyWindowRequest()
        }
    }

    private fun requestWindowUpdate() {
        if (windowWakeQueued.compareAndSet(false, true)) mainHandler.post {
            windowWakeQueued.set(false)
            safelyApplyWindowRequest()
        }
    }

    private fun safelyApplyWindowRequest() {
        try { applyWindowRequest() }
        catch (failure: Throwable) {
            runCatching { restoreWindowRequest() }
            requestedHz = 0f
            synchronized(inputLock) { input = input.copy(requestedHz = 0f) }
            wakeRenderer()
            latestStats = latestStats.copy(requestedHz = 0f)
            Log.w("HaaruSculpture", "Window refresh request unavailable: " + failure.message.orEmpty().take(200))
        }
    }

    private fun restoreWindowRequest() {
        val window = windowReference.get()
        if (ownsWindowRequest && window != null) {
            val attributes = window.attributes
            // Do not overwrite an unrelated component's later request.
            if (attributes.preferredDisplayModeId == ownedMode && attributes.preferredRefreshRate == ownedRefresh) {
                attributes.preferredDisplayModeId = originalMode
                attributes.preferredRefreshRate = originalRefresh
                window.attributes = attributes
            }
        }
        ownsWindowRequest = false
    }

    private fun applyWindowRequest() {
        val display = displays?.getDisplay(displayId)
        val currentMode = display?.mode
        val modes = display?.supportedModes.orEmpty().filter { mode ->
            currentMode != null && mode.physicalWidth == currentMode.physicalWidth &&
                mode.physicalHeight == currentMode.physicalHeight
        }
        supportedHz = modes.map { it.refreshRate }.distinct().sorted().joinToString(",")
        val state = input
        val normal = !closed.get() && !rendererFailed && state.active && state.foreground && state.attached &&
            !state.reduced && !state.powerSave && state.thermal < PowerManager.THERMAL_STATUS_MODERATE &&
            state.scrollPx < viewportHeight * 1.18f
        val highest = modes.maxByOrNull { it.refreshRate }
        val window = windowReference.get()
        val requested = if (normal && highest != null && window != null) {
            val attributes = window.attributes
            val changed = attributes.preferredDisplayModeId != highest.modeId ||
                attributes.preferredRefreshRate != highest.refreshRate
            attributes.preferredDisplayModeId = highest.modeId
            attributes.preferredRefreshRate = highest.refreshRate
            ownedMode = highest.modeId; ownedRefresh = highest.refreshRate
            if (changed) window.attributes = attributes
            ownsWindowRequest = true
            highest.refreshRate
        } else {
            restoreWindowRequest()
            0f
        }
        if (requested != requestedHz) {
            requestedHz = requested
            synchronized(inputLock) { input = input.copy(requestedHz = requested) }
            wakeRenderer()
            Log.i("HaaruSculpture", "Window refresh request=" + requested + "; current=" +
                (display?.refreshRate ?: 0f) + "; same-resolution supported=" + supportedHz)
        }
        latestStats = latestStats.copy(supportedHz = supportedHz, requestedHz = requestedHz)
    }

    internal fun available(surface: SurfaceTexture, width: Int, height: Int, refreshRate: Float, displayId: Int) {
        if (closed.get()) return
        this.displayId = displayId
        viewportHeight = height.coerceAtLeast(1)
        rendererFailed = false
        requestWindowUpdate()
        handler.post {
            if (closed.get()) return@post
            renderer?.close()
            val created = runCatching {
                SculptureRenderer(appContext, handler, surface, width, height, refreshRate) {
                    latestStats = it.copy(supportedHz = supportedHz, requestedHz = requestedHz)
                    if (it.error.isNotEmpty()) {
                        rendererFailed = true
                        requestWindowUpdate()
                    }
                }
            }.onFailure {
                latestStats = SculptureStats(error = it.message.orEmpty().take(400))
                rendererFailed = true
                requestWindowUpdate()
                Log.e("HaaruSculpture", "Renderer unavailable: " + it.message.orEmpty().take(400))
            }.getOrNull()
            renderer = created
            guarded { it.update(input) }
        }
    }

    internal fun resize(width: Int, height: Int, refreshRate: Float) {
        viewportHeight = height.coerceAtLeast(1)
        requestWindowUpdate()
        handler.post { guarded { it.resize(width, height, refreshRate) } }
    }

    internal fun destroyed(surface: SurfaceTexture): Boolean {
        attached(false)
        // Returning false transfers release ownership to the GL thread.
        if (!handler.post {
                try {
                    if (renderer?.surfaceTexture === surface) { renderer?.close(); renderer = null }
                } finally { surface.release() }
            }) surface.release()
        return false
    }

    /** A read-only diagnostic snapshot; it is not a hardware performance claim. */
    fun debugStats(): String = latestStats.toString()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        requestedHz = 0f
        mainHandler.post {
            runCatching { restoreWindowRequest() }
            windowReference.clear()
            latestStats = latestStats.copy(requestedHz = 0f)
        }
        if (thermalRegistered) runCatching { power?.removeThermalStatusListener(thermalListener) }
        if (receiverRegistered) runCatching { appContext.unregisterReceiver(powerReceiver) }
        if (displayRegistered) runCatching { displays?.unregisterDisplayListener(displayListener) }
        handler.post {
            try { renderer?.close() }
            finally {
                renderer = null
                latestStats = latestStats.copy(running = false)
                thread.quitSafely()
            }
        }
    }
}

@Composable
fun SculptureScene(controller: SculptureController, modifier: Modifier = Modifier) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, controller) {
        controller.foreground(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        val observer = LifecycleEventObserver { _, _ ->
            controller.foreground(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            controller.foreground(false)
        }
    }
    AndroidView(factory = { SculptureTextureView(it, controller) }, modifier = modifier,
        onRelease = { controller.attached(false) })
}

private class SculptureTextureView(context: Context, private val controller: SculptureController) : TextureView(context),
    TextureView.SurfaceTextureListener {
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var startX = 0f
    private var startY = 0f
    private var tracking = false
    private var rotating = false

    init {
        isOpaque = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        surfaceTextureListener = this
    }

    private fun refreshRate(): Float = display?.refreshRate?.takeIf { it.isFinite() && it > 0f } ?: 60f
    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        controller.attached(true)
        controller.available(surface, width, height, refreshRate(), display?.displayId ?: 0)
    }
    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        controller.resize(width, height, refreshRate())
    }
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = controller.destroyed(surface)
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        controller.bindWindow(findActivity(context)?.window, display)
        controller.attached(true)
    }
    override fun onDetachedFromWindow() {
        controller.attached(false)
        controller.bindWindow(null, null)
        super.onDetachedFromWindow()
    }

    private fun findActivity(value: Context): Activity? {
        var context = value
        while (context is ContextWrapper) {
            if (context is Activity) return context
            val next = context.baseContext
            if (next === context) return null
            context = next
        }
        return context as? Activity
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x / width.coerceAtLeast(1)
        val y = event.y / height.coerceAtLeast(1)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Keep the title/state plane and scrolling outside the lead sculpture untouched.
                tracking = x in .40f..1f && y in .20f.. .93f
                if (!tracking) return false
                startX = event.x; startY = event.y; rotating = false
                controller.pointer(x, y, true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return false
                val dx = event.x - startX; val dy = event.y - startY
                if (!rotating && kotlin.math.abs(dy) > slop && kotlin.math.abs(dy) > kotlin.math.abs(dx) * 1.15f) {
                    controller.pointer(x, y, false)
                    tracking = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return false
                }
                if (kotlin.math.abs(dx) > slop) {
                    rotating = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                controller.pointer(x, y, true)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!tracking) return false
                controller.pointer(x, y, false)
                parent?.requestDisallowInterceptTouchEvent(false)
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                tracking = false; rotating = false
            }
        }
        return tracking || event.actionMasked == MotionEvent.ACTION_UP
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
