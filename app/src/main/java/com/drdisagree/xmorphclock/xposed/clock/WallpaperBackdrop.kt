package com.drdisagree.xmorphclock.xposed.clock

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.WindowManager
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.scale
import com.drdisagree.xmorphclock.BuildConfig
import com.drdisagree.xmorphclock.data.source.WallpaperContract
import com.drdisagree.xmorphclock.domain.model.ClockFace
import com.drdisagree.xmorphclock.domain.model.ColumnNode
import com.drdisagree.xmorphclock.domain.model.FaceNode
import com.drdisagree.xmorphclock.domain.model.FaceTextEffect
import com.drdisagree.xmorphclock.domain.model.LayerNode
import com.drdisagree.xmorphclock.domain.model.RowNode
import com.drdisagree.xmorphclock.domain.model.StackNode
import com.drdisagree.xmorphclock.engine.ClockFaceView
import com.drdisagree.xmorphclock.xposed.hook.HookEnv
import com.drdisagree.xmorphclock.xposed.hook.onEachPreDraw
import com.drdisagree.xmorphclock.xposed.prefs.ModulePrefs
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

internal class WallpaperBackdrop(private val context: Context) {

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val location = IntArray(2)
    private val frame = RectF()
    private val depthPainter = DepthPainter()
    private var view: WeakReference<ClockFaceView>? = null
    private var bitmapWidth = 0
    private var bitmapHeight = 0
    private var loaded = false
    private var live = false
    private var screen = false
    private var liveStamp = 0L
    private var depthKey = 0L
    private var depth: Bitmap? = null

    val isLoaded: Boolean
        get() = loaded
    private var listening = false
    private var generation = 0
    private var liveView: FloatArray? = null
    private var receiving = false
    private var measuredThisWake = false
    private val keyguard by lazy { context.getSystemService(KeyguardManager::class.java) }
    private val power by lazy { context.getSystemService(PowerManager::class.java) }
    private val measure = Runnable {
        val target = view?.get()
        if (target != null && target.isShown && isLockscreenVisible()) requestView() else measuredThisWake = false
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context, intent: Intent) {
            when (intent.action) {
                WallpaperContract.ACTION_VIEW -> {
                    liveView = parseView(intent.getStringExtra(WallpaperContract.EXTRA_VIEW))
                    view?.get()?.let(::updateFrame)
                }

                Intent.ACTION_SCREEN_OFF -> {
                    mainHandler.removeCallbacks(measure)
                    measuredThisWake = false
                }
            }
        }
    }

    fun attach(target: ClockFaceView) {
        view = WeakReference(target)
        target.context.getSystemService(WindowManager::class.java)?.maximumWindowMetrics?.bounds?.let { bounds ->
            DepthOverlay.windowSize = bounds.width() to bounds.height()
        }
        target.depthDrawer = { canvas -> depthPainter.draw(target, canvas, false) }
        DepthOverlay.register(target)
        target.onEachPreDraw {
            updateFrame(target)
            scheduleMeasure(target)
        }
        listen()
        refresh()
    }

    fun reapplyDepth() {
        view?.get()?.let { applyDepth(it, depth) }
    }

    private fun applyDepth(target: ClockFaceView, subject: Bitmap?) {
        val front = ModulePrefs.depthWidgetsFront
        val inside = front || target.face.layers.any { it.depthFront != null }
        if ((subject == null) != (DepthOverlay.subject == null)) HookEnv.log("Depth subject ${if (subject == null) "cleared" else "set"}")
        DepthOverlay.update(subject, ModulePrefs.depthOpacity, inside)
        target.widgetsAboveDepth = front && subject != null
        target.depthPresent = inside && subject != null
    }

    private fun scheduleMeasure(target: ClockFaceView) {
        if (measuredThisWake || !target.isShown || !isLockscreenVisible()) return
        measuredThisWake = true
        mainHandler.postDelayed(measure, MEASURE_DELAY_MS)
    }

    private fun isLockscreenVisible(): Boolean =
        power?.isInteractive == true && keyguard?.isKeyguardLocked == true

    private fun requestView() {
        try {
            context.sendBroadcast(
                Intent(WallpaperContract.ACTION_REQUEST_VIEW)
                    .setPackage(BuildConfig.APPLICATION_ID)
                    .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES or Intent.FLAG_RECEIVER_FOREGROUND)
            )
        } catch (throwable: Throwable) {
            HookEnv.log("Wallpaper view request failed", throwable)
        }
    }

    private fun parseView(raw: String?): FloatArray? = raw
        ?.split(WallpaperContract.SEPARATOR)
        ?.mapNotNull { it.toFloatOrNull() }
        ?.takeIf { it.size == VIEW_PARTS && it[2] > 0f && it[3] > 0f }
        ?.toFloatArray()

    fun refresh(force: Boolean = false) {
        val target = view?.get() ?: return
        if (!needsBackdrop(target.face) && depthKey() == 0L && WallpaperCapture.frame == null) {
            if (loaded) {
                target.setBackdrop(null)
                loaded = false
                bitmapWidth = 0
                bitmapHeight = 0
            }
            depth = null
            target.setDepth(null, 0f)
            applyDepth(target, null)
            return
        }
        if (loaded && !force) return
        val token = ++generation
        executor.execute {
            val captured = WallpaperCapture.frame
            val liveFrame = if (captured == null) liveFrame() else null
            val bitmap = captured ?: liveFrame ?: load()
            val subject = if (depthKey() != 0L) depthSubject() else null
            mainHandler.post {
                if (token != generation) return@post
                val current = view?.get() ?: return@post
                live = liveFrame != null
                screen = captured != null
                current.setBackdrop(bitmap)
                depth = subject
                current.setDepth(null, 0f)
                applyDepth(current, subject)
                loaded = bitmap != null
                bitmapWidth = bitmap?.width ?: 0
                bitmapHeight = bitmap?.height ?: 0
                updateFrame(current)
            }
        }
    }

    private fun listen() {
        if (!receiving) {
            receiving = true
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter().apply {
                    addAction(WallpaperContract.ACTION_VIEW)
                    addAction(Intent.ACTION_SCREEN_OFF)
                },
                ContextCompat.RECEIVER_EXPORTED
            )
        }
        if (listening) return
        listening = true
        WallpaperCapture.listen(context)
        WallpaperCapture.addListener { refresh(force = true) }
        WallpaperCapture.addScaleListener { view?.get()?.let(::updateFrame) }
        liveStamp = ModulePrefs.liveBackdrop
        depthKey = depthKey()
        ModulePrefs.addListener {
            val stamp = ModulePrefs.liveBackdrop
            val key = depthKey()
            if (stamp != liveStamp || key != depthKey) {
                HookEnv.log("Depth key $depthKey -> $key")
                liveStamp = stamp
                depthKey = key
                refresh(force = true)
            } else {
                view?.get()?.let { applyDepth(it, depth) } ?: DepthOverlay.update(depth, ModulePrefs.depthOpacity, ModulePrefs.depthWidgetsFront)
            }
        }
        WallpaperManager.getInstance(context).addOnColorsChangedListener(
            { _, _ -> refresh(force = true) },
            mainHandler
        )
    }

    private fun liveFrame(): Bitmap? {
        if (ModulePrefs.liveBackdrop == 0L) return null
        return try {
            HookEnv.module.openRemoteFile(WallpaperContract.LIVE_BACKDROP_FILE).use { descriptor ->
                BitmapFactory.decodeFileDescriptor(descriptor.fileDescriptor)
            }?.let(::fitScreen)
        } catch (throwable: Throwable) {
            HookEnv.log("Live wallpaper frame unavailable", throwable)
            null
        }
    }

    private fun depthKey(): Long = if (ModulePrefs.depthEnabled) ModulePrefs.depthStamp else 0L

    private fun depthSubject(): Bitmap? = try {
        HookEnv.module.openRemoteFile(WallpaperContract.DEPTH_FILE).use { descriptor ->
            BitmapFactory.decodeFileDescriptor(descriptor.fileDescriptor)
        }
    } catch (throwable: Throwable) {
        HookEnv.log("Depth subject unavailable", throwable)
        null
    }

    @SuppressLint("MissingPermission")
    private fun load(): Bitmap? = try {
        val manager = WallpaperManager.getInstance(context)
        val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            (manager.getDrawable(WallpaperManager.FLAG_LOCK) ?: manager.drawable)?.takeIf(::hasSize)?.toBitmap()
        } else {
            legacyWallpaper(manager)
        }
        bitmap?.let(::fitScreen)
    } catch (throwable: Throwable) {
        HookEnv.log("Wallpaper backdrop unavailable", throwable)
        null
    }

    @SuppressLint("MissingPermission")
    private fun legacyWallpaper(manager: WallpaperManager): Bitmap? {
        val file = manager.getWallpaperFile(WallpaperManager.FLAG_LOCK) ?: manager.getWallpaperFile(WallpaperManager.FLAG_SYSTEM)
        return file?.use { descriptor -> BitmapFactory.decodeFileDescriptor(descriptor.fileDescriptor) }
            ?: manager.drawable?.takeIf(::hasSize)?.toBitmap()
    }

    private fun hasSize(drawable: Drawable): Boolean = drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0

    private fun fitScreen(bitmap: Bitmap): Bitmap {
        val screen = screenSize()
        val ratio = max(screen.first.toFloat() / bitmap.width, screen.second.toFloat() / bitmap.height) * FIT_MARGIN
        if (ratio >= 1f) return bitmap
        return bitmap.scale((bitmap.width * ratio).roundToInt().coerceAtLeast(1), (bitmap.height * ratio).roundToInt().coerceAtLeast(1))
    }

    private fun updateFrame(target: ClockFaceView) {
        if (bitmapWidth == 0 || bitmapHeight == 0) return
        val (screenWidth, screenHeight) = screenSize()
        target.getLocationOnScreen(location)
        if (screen) {
            val scale = WallpaperCapture.scale
            val width = screenWidth * scale
            val height = screenHeight * scale
            val left = (screenWidth - width) / 2f - location[0] + WallpaperCapture.shiftX * scale
            val top = (screenHeight - height) / 2f - location[1] + WallpaperCapture.shiftY * scale
            frame.set(left, top, left + width, top + height)
            target.setBackdropFrame(frame)
            return
        }
        val view = if (live) null else liveView ?: ModulePrefs.wallpaperView
        if (view != null) {
            val scaleX = screenWidth / (view[2] * bitmapWidth)
            val scaleY = screenHeight / (view[3] * bitmapHeight)
            val left = -view[0] * bitmapWidth * scaleX - location[0]
            val top = -view[1] * bitmapHeight * scaleY - location[1]
            frame.set(left, top, left + bitmapWidth * scaleX, top + bitmapHeight * scaleY)
        } else {
            val zoom = if (live) 1f else wallpaperZoom()
            val scale = max(screenWidth.toFloat() / bitmapWidth, screenHeight.toFloat() / bitmapHeight) * zoom
            val width = bitmapWidth * scale
            val height = bitmapHeight * scale
            val left = (screenWidth - width) / 2f - location[0]
            val top = (screenHeight - height) / 2f - location[1]
            frame.set(left, top, left + width, top + height)
        }
        target.setBackdropFrame(frame)
    }

    private fun screenSize(): Pair<Int, Int> {
        val bounds = context.getSystemService(WindowManager::class.java)?.maximumWindowMetrics?.bounds
        return max(1, bounds?.width() ?: 1) to max(1, bounds?.height() ?: 1)
    }

    @SuppressLint("DiscouragedApi")
    private fun wallpaperZoom(): Float {
        val resources = Resources.getSystem()
        val id = resources.getIdentifier(MAX_SCALE, DIMEN, ANDROID)
        if (id == 0) return 1f
        return try {
            resources.getFloat(id).takeIf { it in 1f..MAX_SANE_SCALE } ?: 1f
        } catch (_: Resources.NotFoundException) {
            1f
        }
    }

    private fun needsBackdrop(face: ClockFace): Boolean =
        (face.layers.map { it.effect } + effectsOf(face.large) + effectsOf(face.small)).any { it.usesBackdrop() }

    private fun effectsOf(node: FaceNode): List<FaceTextEffect> = when (node) {
        is LayerNode -> listOfNotNull(node.effect)
        is RowNode -> node.children.flatMap(::effectsOf)
        is ColumnNode -> node.children.flatMap(::effectsOf)
        is StackNode -> node.children.flatMap(::effectsOf)
        else -> emptyList()
    }

    private fun FaceTextEffect.usesBackdrop(): Boolean = this == FaceTextEffect.BLEND || this == FaceTextEffect.GLASS

    private companion object {
        const val MAX_SCALE = "config_wallpaperMaxScale"
        const val DIMEN = "dimen"
        const val ANDROID = "android"
        const val MAX_SANE_SCALE = 2f
        const val FIT_MARGIN = 1.5f
        const val VIEW_PARTS = 4
        const val MEASURE_DELAY_MS = 1200L
    }
}
