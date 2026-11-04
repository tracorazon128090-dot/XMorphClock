package com.drdisagree.xmorphclock.xposed.clock

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import com.drdisagree.xmorphclock.BuildConfig
import com.drdisagree.xmorphclock.data.mapper.ClockFaceJsonMapper
import com.drdisagree.xmorphclock.data.source.ClockFaces
import com.drdisagree.xmorphclock.domain.model.ClockFace
import com.drdisagree.xmorphclock.domain.model.FaceLayerType
import com.drdisagree.xmorphclock.engine.ClockFaceView
import com.drdisagree.xmorphclock.engine.FaceColors
import com.drdisagree.xmorphclock.xposed.data.LiveFaceData
import com.drdisagree.xmorphclock.xposed.hook.HookEnv
import com.drdisagree.xmorphclock.xposed.prefs.ModulePrefs
import com.drdisagree.xmorphclock.xposed.report.HookReporter
import com.drdisagree.xmorphclock.xposed.signal.ClockSignals
import java.util.Collections
import java.util.WeakHashMap

class ClockHost {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val slots: MutableSet<ClockSlot> = Collections.newSetFromMap(WeakHashMap())
    private val attachTimes = WeakHashMap<View, Long>()
    private val heights = SlotHeights()
    private var moduleContext: Context? = null
    private var clockView: ClockFaceView? = null
    private var backdrop: WallpaperBackdrop? = null

    val isEnabled: Boolean
        get() = ModulePrefs.isClockEnabled

    private val slotAttachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {
            attachTimes[v] = System.nanoTime()
            place()
        }

        override fun onViewDetachedFromWindow(v: View) {
            attachTimes.remove(v)
            mainHandler.post { place() }
        }
    }

    init {
        HookReporter.state(::snapshot)
        ModulePrefs.addListener { reloadFace() }
        if (BuildConfig.DEBUG) ClockFaces.addChangeListener { reloadFace() }
    }

    private fun snapshot(): Map<String, String> {
        val view = clockView
        val attached = slots.count { it.isAttachedToWindow }
        return linkedMapOf(
            "enabled" to isEnabled.toString(),
            "faceStyle" to ModulePrefs.faceStyle.toString(),
            "customFace" to (ModulePrefs.faceJson != null).toString(),
            "slots" to "${slots.size} created, $attached attached",
            "clockView" to when {
                view == null -> "not created"
                view.parent == null -> "detached"
                else -> "attached ${view.width}x${view.height}, shown=${view.isShown}, alpha=${view.alpha}"
            },
            "largeClock" to ClockSignals.isLargeClock().toString(),
            "sleep" to "%.2f".format(ClockSignals.sleepProgress),
            "aod" to "%.2f".format(ClockSignals.aodProgress),
            "asleep" to ClockSignals.isAsleep.toString(),
            "backdrop" to (backdrop?.isLoaded ?: false).toString(),
            "depth" to DepthOverlay.describe(),
            "depthPrefs" to "enabled=${ModulePrefs.depthEnabled} stamp=${ModulePrefs.depthStamp} front=${ModulePrefs.depthWidgetsFront}"
        )
    }

    fun createSlot(
        context: Context,
        large: Boolean,
        pinned: Boolean = false,
        stockBurnIn: Boolean = false,
        capWidth: Boolean = false
    ): View =
        ClockSlot(context, heights, large, pinned, stockBurnIn, capWidth) { mainHandler.post { place() } }.also { slot ->
            slots += slot
            slot.addOnAttachStateChangeListener(slotAttachListener)
        }

    private fun place() {
        val attached = slots.filter { it.isAttachedToWindow }
        val target = attached.filter { it.isVisible }.ifEmpty { attached }
            .maxByOrNull { attachTimes[it] ?: Long.MIN_VALUE } ?: return
        val view = clockView ?: createClock(target.context) ?: return
        if (view.parent === target) return
        target.post {
            if (!target.isAttachedToWindow || view.parent === target) return@post
            (view.parent as? ViewGroup)?.removeView(view)
            target.addView(
                view,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
        }
    }

    private fun createClock(context: Context): ClockFaceView? {
        val fontContext = moduleContext(context) ?: return null
        val face = resolveFace(fontContext) ?: return null
        FaceColors.setPalette(ModulePrefs.palette)
        return ClockFaceView(context, face, fontContext).also { view ->
            HookReporter.count("clockCreated")
            clockView = view
            LiveFaceData.start(context)
            LiveFaceData.configure(face)
            LiveFaceData.addListener { data -> view.data = data }
            bindTiles(view)
            ClockController.attach(view)
            backdrop = WallpaperBackdrop(context).also { it.attach(view) }
            HookReporter.clockFace(face.name)
        }
    }

    private fun reloadFace() {
        val version = FaceColors.paletteVersion
        FaceColors.setPalette(ModulePrefs.palette)
        val view = clockView ?: return
        if (version != FaceColors.paletteVersion) {
            view.requestLayout()
            view.invalidate()
        }
        val context = moduleContext ?: return
        resolveFace(context)?.let { face ->
            if (face != view.face) {
                view.face = face
                LiveFaceData.configure(face)
                bindTiles(view)
                backdrop?.refresh()
                backdrop?.reapplyDepth()
                HookReporter.clockFace(face.name)
                HookEnv.log("Face reloaded: ${face.name}")
            }
        }
    }

    private fun bindTiles(view: ClockFaceView) {
        val hasTiles = view.face.layers.any { it.type == FaceLayerType.TILE }
        val hasMedia = view.face.layers.any { it.type in MEDIA_LAYERS || it.tile?.media != null }
        view.onTileTap = if (hasTiles) LiveFaceData::toggle else null
        view.onMediaAction = if (hasMedia) LiveFaceData::media else null
    }

    private fun resolveFace(context: Context): ClockFace? =
        ModulePrefs.faceJson?.let { ClockFaceJsonMapper.fromJson(it) }
            ?: ClockFaces.faceOf(context, ModulePrefs.faceStyle)
            ?: ClockFaces.presets(context).firstOrNull()

    private fun moduleContext(context: Context): Context? = moduleContext ?: try {
        context.createPackageContext(BuildConfig.APPLICATION_ID, Context.CONTEXT_IGNORE_SECURITY)
            .also { moduleContext = it }
    } catch (throwable: Throwable) {
        HookEnv.log("Module context unavailable", throwable)
        null
    }
}

private val MEDIA_LAYERS = setOf(FaceLayerType.MEDIA_TITLE, FaceLayerType.MEDIA_ARTIST, FaceLayerType.MEDIA_ICON, FaceLayerType.MEDIA_ART)
