package com.drdisagree.xmorphclock.engine

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Camera
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.text.format.DateFormat
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.PathInterpolator
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.withClip
import androidx.core.graphics.withRotation
import androidx.core.graphics.withScale
import androidx.core.graphics.withTranslation
import com.drdisagree.xmorphclock.domain.editor.FaceComposer
import com.drdisagree.xmorphclock.domain.model.ClockFace
import com.drdisagree.xmorphclock.domain.model.ColumnNode
import com.drdisagree.xmorphclock.domain.model.FaceAlign
import com.drdisagree.xmorphclock.domain.model.FaceBadge
import com.drdisagree.xmorphclock.domain.model.FaceChangeStyle
import com.drdisagree.xmorphclock.domain.model.FaceData
import com.drdisagree.xmorphclock.domain.model.FaceGradient
import com.drdisagree.xmorphclock.domain.model.FaceLayer
import com.drdisagree.xmorphclock.domain.model.FaceLayerType
import com.drdisagree.xmorphclock.domain.model.FaceNode
import com.drdisagree.xmorphclock.domain.model.FaceTextEffect
import com.drdisagree.xmorphclock.domain.model.FaceTile
import com.drdisagree.xmorphclock.domain.model.LayerNode
import com.drdisagree.xmorphclock.domain.model.MediaAction
import com.drdisagree.xmorphclock.domain.model.RowNode
import com.drdisagree.xmorphclock.domain.model.StackNode
import com.drdisagree.xmorphclock.engine.render.Axes
import com.drdisagree.xmorphclock.engine.render.BackdropCache
import com.drdisagree.xmorphclock.engine.render.Box
import com.drdisagree.xmorphclock.engine.render.DataPhrases
import com.drdisagree.xmorphclock.engine.render.FaceGlyphs
import com.drdisagree.xmorphclock.engine.render.FaceValues
import com.drdisagree.xmorphclock.engine.render.GlassRenderer
import com.drdisagree.xmorphclock.engine.render.IdleFade
import com.drdisagree.xmorphclock.engine.render.LayerMetrics
import com.drdisagree.xmorphclock.engine.render.Leaf
import com.drdisagree.xmorphclock.engine.render.MetricsKey
import com.drdisagree.xmorphclock.engine.render.OutlineKey
import com.drdisagree.xmorphclock.engine.render.RingKey
import com.drdisagree.xmorphclock.engine.render.Placement
import com.drdisagree.xmorphclock.engine.render.TextTokens
import com.drdisagree.xmorphclock.engine.render.WordPhrases
import com.drdisagree.xmorphclock.engine.render.linear
import com.drdisagree.xmorphclock.engine.render.withRomanNumerals
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

@SuppressLint("ViewConstructor")
class ClockFaceView(
    context: Context,
    face: ClockFace,
    fontContext: Context = context
) : View(context) {

    var face: ClockFace = face
        set(value) {
            if (field == value) return
            val typography = field.layers != value.layers
            if (instantFace || !morphOnChange) {
                morphFrom = null
            } else {
                morphFrom = if (isAttachedToWindow && width > 0) snapshot() else null
                morphStart = SystemClock.uptimeMillis()
            }
            field = value
            if (!instantFace || typography) FaceFont.prewarm(value)
            refreshFace()
        }

    private var instantFace = false

    var morphOnChange = true

    fun editFace(value: ClockFace) {
        instantFace = true
        face = value
        instantFace = false
    }

    private fun refreshFace() {
        metrics.clear()
        invalidateFrames()
        texts.clear()
        updateTime()
        syncIdle()
        requestLayout()
        invalidate()
    }

    var data: FaceData = FaceData()
        set(value) {
            if (field == value) return
            field = value
            updateTime()
            syncIdle()
        }

    var onTileTap: ((FaceTile) -> Unit)? = null
        set(value) {
            field = value
            isClickable = value != null || onMediaAction != null
        }

    var onMediaAction: ((MediaAction) -> Unit)? = null
        set(value) {
            field = value
            isClickable = value != null || onTileTap != null
        }

    private var pressedLayer: FaceLayer? = null
    private var swiping = false
    private var downX = 0f
    private var downY = 0f

    var colorOf: (String) -> Int = { FaceColors.resolve(context, it) }
        set(value) {
            field = value
            invalidateFrames()
            invalidate()
        }

    var aodColor: Int = Color.WHITE
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    var largeProgress = 1f
        private set

    var sleepProgress = 0f
        private set

    var aodProgress = 0f
        private set

    private val paint = Paint(TEXT_FLAGS).apply { textSize = BASE_SIZE }
    private val outlinePaint = Paint(TEXT_FLAGS).apply {
        textSize = BASE_SIZE
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val measurePaint = Paint(TEXT_FLAGS).apply { textSize = BASE_SIZE }
    private val fitPaint = Paint(TEXT_FLAGS).apply { textSize = BASE_SIZE }
    private val bounds = Rect()
    private val outlinePaths = HashMap<OutlineKey, Path>()
    private var morphFrom: Map<String, Placement>? = null
    private var morphStart = 0L
    private val drawMatrix = Matrix()
    private val tiltMatrix = Matrix()
    private val tiltCamera = Camera()
    private val tiltCorners = FloatArray(TILT_POINTS)
    private val inverseMatrix = Matrix()
    private val shaderMatrix = Matrix()
    private val glassPath = Path()
    private val glassRun = Path()
    private val glassKey = StringBuilder()
    private var drawingEffect = FaceTextEffect.SOLID
    private var drawingInk: Shader? = null
    private var drawingBorderInk: Shader? = null
    private val inkMatrix = Matrix()
    private var handHour = 0
    private var handMinute = 0
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
    }
    private val badgeRect = RectF()
    private val extent = RectF()
    private val cardRect = RectF()
    private val cardPath = Path()
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val gaugePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val handPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val stickerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringStroker = Paint().apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val ringPaths = HashMap<RingKey, Path>()
    private var glassRenderer: GlassRenderer? = null
    private val glassFilter = ColorMatrixColorFilter(ColorMatrix().apply {
        setScale(GLASS_BRIGHTNESS, GLASS_BRIGHTNESS, GLASS_BRIGHTNESS, 1f)
    })
    private val glassFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val blendFilter = ColorMatrixColorFilter(ColorMatrix().apply {
        setSaturation(BLEND_SATURATION)
        postConcat(ColorMatrix().apply { setScale(BLEND_BRIGHTNESS, BLEND_BRIGHTNESS, BLEND_BRIGHTNESS, 1f) })
    })
    private val backdropFrame = RectF()
    private var blendBitmap: Bitmap? = null
    private var glassBitmap: Bitmap? = null
    private var blendShader: BitmapShader? = null
    private var glassShader: BitmapShader? = null
    private var depthBitmap: Bitmap? = null
    private var depthOpacity = 0f
    private val depthPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shadowPaint = Paint(TEXT_FLAGS).apply { textSize = BASE_SIZE }
    private val shadowBlurs = HashMap<Int, BlurMaskFilter>()
    private val imageShaders = HashMap<Bitmap, BitmapShader>()
    private val artShaders = HashMap<Bitmap, BitmapShader>()
    private val artPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var artShown: Bitmap? = null
    private var artPrevious: Bitmap? = null
    private var artStart = 0L
    private val imageBounds = RectF()
    private val imageMatrix = Matrix()
    private val monoFilters = HashMap<Int, ColorMatrixColorFilter>()

    private val texts = HashMap<Int, String>()
    private val previousTexts = HashMap<Int, String>()
    private val changeStarts = HashMap<Int, LongArray>()

    private val metrics = HashMap<MetricsKey, LayerMetrics>()
    private var framesWidth = 0f
    private var framesPalette = -1
    private var smallFrames: Map<String, Placement>? = null
    private var largeFrames: Map<String, Placement>? = null
    private var aodSmallFrames: Map<String, Placement>? = null
    private var aodLargeFrames: Map<String, Placement>? = null

    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateTime()
        }
    }

    private val phrases by lazy { WordPhrases.from(fontContext.resources) }
    private val idleFades = HashMap<String, IdleFade>()
    private var idleGroups: Set<String> = emptySet()
    private val dataPhrases by lazy { DataPhrases.from(fontContext.resources) }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        FaceFont.init(fontContext)
        FaceFont.prewarm(face)
        updateTime()
        syncIdle()
    }

    fun setLargeProgress(progress: Float) {
        val value = progress.coerceIn(0f, 1f)
        if (value == largeProgress) return
        largeProgress = value
        requestLayout()
        invalidate()
    }

    fun setSleepProgress(progress: Float) {
        val value = progress.coerceIn(0f, 1f)
        if (value == sleepProgress) return
        sleepProgress = value
        invalidate()
    }

    fun setAodProgress(progress: Float) {
        val value = progress.coerceIn(0f, 1f)
        if (value == aodProgress) return
        aodProgress = value
        if (face.aodLarge != null || face.aodSmall != null) requestLayout()
        invalidate()
    }

    private fun describe(calendar: Calendar) {
        val time = DateFormat.getTimeFormat(context).format(calendar.time)
        val day = DateFormat.format(DateFormat.getBestDateTimePattern(Locale.getDefault(), DAY_PATTERN), calendar).toString()
        val extras = face.layers.mapIndexedNotNull { index, layer ->
            val text = texts[index]?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapIndexedNotNull null
            val spoken = !layer.isTime && !layer.isIcon && !layer.isShape && layer.type != FaceLayerType.DATE && !isSymbol(layer)
            text.takeIf { spoken && groupOf(layer.id) !in idleGroups }
        }
        val next = (listOf(time, day) + extras).distinct().joinToString(DESCRIPTION_SEPARATOR)
        if (contentDescription?.toString() != next) contentDescription = next
    }

    private fun String.cased(caps: Boolean): String = if (caps) uppercase(Locale.getDefault()) else this

    fun updateTime() {
        val calendar = Calendar.getInstance()
        val is24Hour = DateFormat.is24HourFormat(context)
        val hour = if (is24Hour) {
            calendar.get(Calendar.HOUR_OF_DAY)
        } else {
            (calendar.get(Calendar.HOUR) + 11) % 12 + 1
        }
        handHour = calendar.get(Calendar.HOUR_OF_DAY)
        handMinute = calendar.get(Calendar.MINUTE)
        val hours = "%02d".format(hour)
        val bareHours = hour.toString()
        val minutes = "%02d".format(calendar.get(Calendar.MINUTE))
        var relayout = false

        face.layers.forEachIndexed { index, layer ->
            val text = when (layer.type) {
                FaceLayerType.HOURS -> if (layer.hourZero) hours else bareHours
                FaceLayerType.MINUTES -> minutes
                FaceLayerType.COLON -> ":"
                FaceLayerType.TIME -> (if (layer.hourZero) hours else bareHours) + ":" + minutes
                FaceLayerType.HOUR_TENS -> if (layer.hourZero || hour >= 10) hours.substring(0, 1) else ""
                FaceLayerType.HOUR_ONES -> hours.substring(1, 2)
                FaceLayerType.MINUTE_TENS -> minutes.substring(0, 1)
                FaceLayerType.MINUTE_ONES -> minutes.substring(1, 2)
                FaceLayerType.HAND_HOUR, FaceLayerType.HAND_MINUTE -> ""
                FaceLayerType.DATE -> DateFormat.format(layer.format.ifEmpty { DATE_FORMAT }, calendar)
                    .toString().cased(layer.caps).let { if (layer.roman) it.withRomanNumerals() else it }

                FaceLayerType.TEXT -> TextTokens.expand(layer.format, calendar, data, phrases, dataPhrases).cased(layer.caps)
                else -> FaceValues.text(layer, data, dataPhrases).orEmpty().cased(layer.caps)
            }
            val old = texts[index]
            texts[index] = fit(layer, text)
            if (old == null || old == text) return@forEachIndexed

            if (old.length != text.length || !layer.isTime) {
                metrics.keys.removeAll { it.index == index }
                invalidateFrames()
                relayout = true
            }
            if (isAttachedToWindow) startChange(index, old, text)
        }

        if (relayout) requestLayout()
        invalidate()
        describe(calendar)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ContextCompat.registerReceiver(
            context,
            timeReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_TIME_TICK)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            },
            ContextCompat.RECEIVER_EXPORTED
        )
        updateTime()
    }

    override fun onDetachedFromWindow() {
        try {
            context.unregisterReceiver(timeReceiver)
        } catch (_: IllegalArgumentException) {
        }
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec).toFloat()
        val now = SystemClock.uptimeMillis()
        val collapsing = idleFades.filterValues { it.target == 0f }.keys
        val full = measureBottom(width, emptySet())
        val bottom = if (collapsing.isEmpty()) {
            full
        } else {
            val shown = collapsing.maxOf { idleFades.getValue(it).value(now) }
            lerp(measureBottom(width, collapsing), full, shown)
        }
        setMeasuredDimension(width.toInt(), resolveSize(ceil(bottom).toInt(), heightMeasureSpec))
    }

    private fun measureBottom(width: Float, exclude: Set<String>): Float {
        val (small, large) = frames(width)
        var bottom = lerp(max(bottomOf(small, exclude), boxBottom(face.small, width)), max(bottomOf(large, exclude), boxBottom(face.large, width)), largeProgress)
        if (aodSmallFrames != null || aodLargeFrames != null) {
            bottom = lerp(bottom, lerp(bottomOf(aodSmallFrames ?: small, exclude), bottomOf(aodLargeFrames ?: large, exclude), largeProgress), aodProgress)
        }
        if (morphFrom != null) {
            face.layers.forEachIndexed { index, layer ->
                if (groupOf(layer.id) in exclude) return@forEachIndexed
                val placement = placement(index, width) ?: return@forEachIndexed
                if (placement.alpha > 0f) bottom = max(bottom, placement.bottom(layer))
            }
        }
        return bottom
    }

    private fun syncIdle() {
        val idle = face.layers.filter { it.hideIdle && FaceValues.isIdle(it, data) }.mapTo(HashSet()) { groupOf(it.id) }
        if (idle == idleGroups) return
        idleGroups = idle
        val now = SystemClock.uptimeMillis()
        face.layers.mapTo(HashSet()) { groupOf(it.id) }.forEach { group ->
            val target = if (group in idle) 0f else 1f
            idleFades.getOrPut(group) { IdleFade(target) }.moveTo(target, now)
        }
        requestLayout()
        invalidate()
    }

    @SuppressLint("UseKtx")
    override fun onDraw(canvas: Canvas) {
        val width = width.toFloat()
        if (width <= 0f) return

        val now = SystemClock.uptimeMillis()
        var animating = false
        var order = 0
        var idleMoving = false

        fun drawLayer(index: Int, layer: FaceLayer) {
            val text = texts[index] ?: return
            val placement = placement(index, width) ?: return
            val widthRatio = placement.advances.sum()

            val hide = if (layer.sleepStyle.hide) awakeOf(0) else 1f
            val sleepAwake = awakeOf(0)
            val idle = idleFades[groupOf(layer.id)]
            val idleAlpha = idle?.value(now) ?: 1f
            if (idle != null && idleAlpha != idle.target) idleMoving = true
            val awakeAlpha = placement.alpha * layer.opacity
            val alpha = (layer.sleepOpacity?.let { lerp(min(it, placement.alpha), awakeAlpha, sleepAwake) } ?: awakeAlpha) * hide * tileAlpha(layer) * idleAlpha
            val rotation = layer.sleepRotation?.let { lerp(it, placement.rotation, sleepAwake) } ?: placement.rotation
            val sleepScale = layer.sleepScale?.let { lerp(it, 1f, sleepAwake) } ?: 1f
            if (alpha <= 0f) {
                if (layer.isTime) order += text.length
                return
            }

            val awakeColor = activeColorOf(layer) ?: placement.color
            val sleepColor = layer.sleepColor?.let(colorOf) ?: if (layer.type == FaceLayerType.CARD) awakeColor else aodColor
            val color = ColorUtils.blendARGB(awakeColor, sleepColor, aodProgress)
            drawingInk = if (activeColorOf(layer) == null) inkOf(layer.color, widthRatio, placement.capRatio, sleepColor) else null
            drawingBorderInk = inkOf(layer.stroke, widthRatio, placement.capRatio, null)
            val scale = placement.size / BASE_SIZE
            val baseline = placement.top + placement.size * placement.capRatio * placement.stretch * (placement.lineHeight + 1f) / 2f
            val pivotX = widthRatio * placement.stretchX * placement.size / 2f
            val pivotY = -placement.size * placement.capRatio * placement.stretch / 2f
            val stretch = if (layer.isShape) 1f else lerp(layer.sleepStretch ?: placement.stretch, placement.stretch, sleepAwake)
            val lift = placement.capRatio * BASE_SIZE * (placement.stretch - stretch) / 2f
            val stretchX = if (layer.isShape) 1f else lerp(layer.sleepStretchX ?: placement.stretchX, placement.stretchX, sleepAwake)
            val shiftX = widthRatio * BASE_SIZE * (placement.stretchX - stretchX) / 2f

            canvas.save()
            canvas.translate(placement.left, baseline)
            if (rotation != 0f) canvas.rotate(rotation, pivotX, pivotY)
            val tilted = placement.tiltX != 0f || placement.tiltY != 0f
            if (tilted) {
                tiltInto(tiltMatrix, placement.tiltX, placement.tiltY, pivotX, pivotY, max(pivotX, -pivotY) * 2f)
                canvas.concat(tiltMatrix)
            }
            if (sleepScale != 1f) canvas.scale(sleepScale, sleepScale, pivotX, pivotY)
            canvas.scale(scale, scale)
            if (layer.isHand) {
                drawHand(canvas, layer, color, alpha, weightOf(layer, placement.weight, awakeOf(order)))
                canvas.restore()
                return
            }
            if (layer.type == FaceLayerType.SPACER) {
                canvas.restore()
                return
            }
            if (layer.type == FaceLayerType.CARD || layer.type == FaceLayerType.GAUGE || layer.isImage) {
                drawMatrix.setScale(scale, scale)
                if (sleepScale != 1f) drawMatrix.postScale(sleepScale, sleepScale, pivotX, pivotY)
                if (tilted) drawMatrix.postConcat(tiltMatrix)
                if (rotation != 0f) drawMatrix.postRotate(rotation, pivotX, pivotY)
                drawMatrix.postTranslate(placement.left, baseline)
                drawingEffect = placement.effect
                val awake = awakeOf(0)
                when (layer.type) {
                    FaceLayerType.CARD -> drawCard(canvas, layer, widthRatio, placement.capRatio, color, alpha, outlineOf(layer, awake), awake, placement.size)
                    FaceLayerType.IMAGE -> drawImage(canvas, layer, widthRatio, placement.capRatio, alpha)
                    FaceLayerType.MEDIA_ART -> if (drawArt(canvas, layer, widthRatio, placement.capRatio, color, alpha, now)) animating = true
                    else -> drawGauge(canvas, layer, widthRatio, placement.capRatio, color, alpha, weightOf(layer, placement.weight, awake))
                }
                canvas.restore()
                return
            }
            drawMatrix.setScale(scale, scale)
            if (sleepScale != 1f) drawMatrix.postScale(sleepScale, sleepScale, pivotX, pivotY)
            if (tilted) drawMatrix.postConcat(tiltMatrix)
            if (rotation != 0f) drawMatrix.postRotate(rotation, pivotX, pivotY)
            drawMatrix.postTranslate(placement.left, baseline)
            if (stretch != 1f || stretchX != 1f || lift != 0f || shiftX != 0f) {
                canvas.translate(shiftX, -lift)
                canvas.scale(stretchX, stretch)
                drawMatrix.preTranslate(shiftX, -lift)
                drawMatrix.preScale(stretchX, stretch)
            }

            val previous = previousTexts[index]
            val starts = changeStarts[index]
            drawingEffect = placement.effect

            if (layer.isTime) {
                var x = 0f
                val glassUnion = placement.effect == FaceTextEffect.GLASS
                var glassAlpha = 0f
                var glassOutline = 0f
                var glassTypeface: Typeface? = null
                var glassAwake = 0f
                var glassCount = 0
                if (glassUnion) {
                    glassRun.rewind()
                    glassKey.setLength(0)
                }
                text.forEachIndexed { charIndex, char ->
                    val advance = placement.advances.getOrElse(charIndex) { 0f } * BASE_SIZE
                    val glyphAdvance = if (charIndex < text.lastIndex) {
                        advance - placement.tracking * BASE_SIZE
                    } else {
                        advance
                    }
                    val center = x + glyphAdvance / 2f
                    val awake = awakeOf(order + charIndex)
                    val weight = weightOf(layer, placement.weight, awake)
                    val outline = outlineOf(layer, awake)
                    val axes = axesOf(layer, placement, awake)
                    val start = starts?.getOrNull(charIndex) ?: 0L
                    val change = if (start == 0L) 1f else ((now - start).toFloat() / CHANGE_DURATION)
                        .coerceIn(0f, 1f)
                    val old = previous?.getOrNull(charIndex)

                    if (change < 1f && old != null && old != char) {
                        animating = true
                        drawChange(
                            canvas, old.toString(), char.toString(), center, change, layer, weight, axes,
                            alpha, outline, color, placement.size, Paint.Align.CENTER, advance,
                            placement.capRatio * BASE_SIZE
                        )
                    } else if (glassUnion) {
                        val typeface = FaceFont.typeface(fontOf(layer), weight.roundToInt(), axes.width, axes.height)
                        outlinePaint.typeface = typeface
                        outlinePaint.textAlign = Paint.Align.CENTER
                        glassPath.set(outlinePathOf(char.toString(), typeface, Paint.Align.CENTER))
                        glassPath.offset(center, 0f)
                        glassRun.op(glassPath, Path.Op.UNION)
                        glassKey.append(char).append(weight.roundToInt()).append(',').append(axes.width).append(',')
                            .append(axes.height).append(',').append(center.roundToInt()).append(';')
                        glassAlpha = max(glassAlpha, alpha)
                        glassOutline = max(glassOutline, outline)
                        glassAwake += awake
                        glassCount++
                        glassTypeface = typeface
                    } else {
                        drawText(canvas, char.toString(), center, 0f, layer, weight, axes, alpha, outline, color, placement.size, Paint.Align.CENTER, awake)
                    }
                    x += advance
                }
                if (glassUnion && glassTypeface != null && !glassRun.isEmpty) {
                    val baseAlpha = Color.alpha(color) * glassAlpha
                    val reveal = if (glassCount > 0) glassAwake / glassCount else 1f
                    if (reveal < 1f) {
                        glassFill.shader = null
                        inked(glassFill, color, (baseAlpha * (1f - glassOutline) * (1f - reveal)).roundToInt())
                        canvas.drawPath(glassRun, glassFill)
                        uninked(glassFill)
                    }
                    drawGlassPath(canvas, glassRun, glassKey.toString(), color, baseAlpha * (1f - glassOutline) * reveal, tintOf(layer, FaceTextEffect.GLASS))
                    if (glassOutline > 0f) {
                        outlinePaint.strokeWidth = layer.outlineWidth * resources.displayMetrics.density * BASE_SIZE / placement.size
                        inked(outlinePaint, color, (baseAlpha * glassOutline).roundToInt())
                        canvas.drawPath(glassRun, outlinePaint)
                        uninked(outlinePaint)
                    }
                }
                order += text.length
            } else {
                val awake = awakeOf(0)
                val weight = weightOf(layer, placement.weight, awake)
                val outline = outlineOf(layer, awake)
                val axes = axesOf(layer, placement, awake)
                val start = starts?.firstOrNull() ?: 0L
                val change = if (start == 0L) 1f else ((now - start).toFloat() / CHANGE_DURATION)
                    .coerceIn(0f, 1f)

                if (change < 1f && previous != null) {
                    animating = true
                    paint.letterSpacing = placement.tracking
                    outlinePaint.letterSpacing = placement.tracking
                    drawChange(
                        canvas, previous, text, 0f, change, layer, weight, axes, alpha, outline,
                        color, placement.size, Paint.Align.LEFT, widthRatio * BASE_SIZE,
                        placement.capRatio * BASE_SIZE
                    )
                } else {
                    paint.letterSpacing = placement.tracking
                    outlinePaint.letterSpacing = placement.tracking
                    drawBadge(canvas, layer, widthRatio, placement.capRatio, color, alpha, awake)
                    drawText(canvas, text, 0f, 0f, layer, weight, axes, alpha, outline, badgeTextColor(layer, color, awake), placement.size, Paint.Align.LEFT, awake)
                }
                paint.letterSpacing = 0f
                outlinePaint.letterSpacing = 0f
            }
            canvas.restore()
        }

        val splitDepth = widgetsAboveDepth || ((depthBitmap != null || depthPresent) && face.layers.any { it.depthFront != null })
        if (splitDepth) {
            face.layers.forEachIndexed { index, layer -> if (!inFrontOfDepth(layer)) drawLayer(index, layer) }
            drawingInk = null
            drawingBorderInk = null
            depthDrawer?.invoke(canvas) ?: drawDepth(canvas)
            face.layers.forEachIndexed { index, layer -> if (inFrontOfDepth(layer)) drawLayer(index, layer) }
        } else {
            face.layers.forEachIndexed { index, layer -> drawLayer(index, layer) }
        }

        if (morphFrom != null) {
            if (SystemClock.uptimeMillis() - morphStart >= MORPH_DURATION) {
                morphFrom = null
            }
            requestLayout()
            animating = true
        }
        drawingInk = null
        drawingBorderInk = null
        if (!splitDepth) drawDepth(canvas)
        if (idleMoving) postOnAnimation { requestLayout() }
        if (animating || idleMoving) postInvalidateOnAnimation()
    }

    private fun snapshot(): Map<String, Placement> {
        val width = width.toFloat()
        return face.layers.mapIndexedNotNull { index, layer ->
            placement(index, width)?.let { layer.id to it }
        }.toMap()
    }

    private fun morph(id: String, target: Placement): Placement {
        val from = morphFrom ?: return target
        val raw = ((SystemClock.uptimeMillis() - morphStart).toFloat() / MORPH_DURATION).coerceIn(0f, 1f)
        if (raw >= 1f) return target
        val t = MORPH_EASING.getInterpolation(raw)
        val source = from[id] ?: return blend(target.hidden(), target, t)
        return blend(source, target, t)
    }

    private fun blend(from: Placement, to: Placement, t: Float): Placement = Placement(
        left = lerp(from.left, to.left, t),
        top = lerp(from.top, to.top, t),
        size = lerp(from.size, to.size, t),
        alpha = lerp(from.alpha, to.alpha, t),
        rotation = lerp(from.rotation, to.rotation, t),
        capRatio = lerp(from.capRatio, to.capRatio, t),
        advances = FloatArray(to.advances.size) { i -> lerp(from.advances.getOrElse(i) { 0f }, to.advances[i], t) },
        widthAxis = lerp(from.widthAxis, to.widthAxis, t),
        heightAxis = lerp(from.heightAxis, to.heightAxis, t),
        tracking = lerp(from.tracking, to.tracking, t),
        weight = lerp(from.weight, to.weight, t),
        color = ColorUtils.blendARGB(from.color, to.color, t),
        lineHeight = lerp(from.lineHeight, to.lineHeight, t),
        stretch = lerp(from.stretch, to.stretch, t),
        stretchX = lerp(from.stretchX, to.stretchX, t),
        effect = if (t < EFFECT_SWITCH) from.effect else to.effect,
        hidden = to.hidden,
        tiltX = lerp(from.tiltX, to.tiltX, t),
        tiltY = lerp(from.tiltY, to.tiltY, t)
    )

    private fun drawChange(
        canvas: Canvas,
        old: String,
        new: String,
        x: Float,
        change: Float,
        layer: FaceLayer,
        weight: Float,
        axes: Axes,
        alpha: Float,
        outline: Float,
        color: Int,
        size: Float,
        align: Paint.Align,
        cellWidth: Float,
        capHeight: Float
    ) {
        val eased = CHANGE_EASING.getInterpolation(change)
        val thin = if (layer.sleepStyle.thin) layer.sleepWeight.toFloat() else weight
        val outWeight = lerp(weight, thin, eased)
        val inWeight = lerp(thin, weight, eased)
        val shift = CHANGE_SHIFT * BASE_SIZE
        val centerX = if (align == Paint.Align.CENTER) x else x + cellWidth / 2f
        val centerY = -capHeight / 2f

        fun draw(text: String, dy: Float, textWeight: Float, textAlpha: Float) =
            drawText(canvas, text, x, dy, layer, textWeight, axes, textAlpha, outline, color, size, align)

        when (layer.change) {
            FaceChangeStyle.NONE -> draw(new, 0f, weight, alpha)

            FaceChangeStyle.FADE -> {
                draw(old, 0f, weight, alpha * (1f - eased))
                draw(new, 0f, weight, alpha * eased)
            }

            FaceChangeStyle.WEIGHT -> {
                draw(old, 0f, outWeight, alpha * (1f - eased))
                draw(new, 0f, inWeight, alpha * eased)
            }

            FaceChangeStyle.SLIDE_UP, FaceChangeStyle.SLIDE_DOWN -> {
                val direction = if (layer.change == FaceChangeStyle.SLIDE_UP) 1f else -1f
                draw(old, -direction * shift * eased, outWeight, alpha * (1f - eased))
                draw(new, direction * shift * (1f - eased), inWeight, alpha * eased)
            }

            FaceChangeStyle.ROLL -> {
                val travel = capHeight * ROLL_TRAVEL
                canvas.withClip(
                    centerX - cellWidth / 2f,
                    -capHeight * ROLL_CLIP,
                    centerX + cellWidth / 2f,
                    capHeight * (ROLL_CLIP - 1f)
                ) {
                    draw(old, -travel * eased, weight, alpha)
                    draw(new, travel * (1f - eased), weight, alpha)
                }
            }

            FaceChangeStyle.FLIP -> {
                val first = change < 0.5f
                val half = if (first) 1f - eased * 2f else eased * 2f - 1f
                canvas.withScale(1f, half.coerceIn(0f, 1f), centerX, centerY) {
                    if (first) draw(old, 0f, weight, alpha) else draw(new, 0f, weight, alpha)
                }
            }

            FaceChangeStyle.SCALE -> {
                val shrink = 1f - eased * SCALE_DELTA
                canvas.withScale(shrink, shrink, centerX, centerY) { draw(old, 0f, outWeight, alpha * (1f - eased)) }
                val grow = 1f - (1f - eased) * SCALE_DELTA
                canvas.withScale(grow, grow, centerX, centerY) { draw(new, 0f, inWeight, alpha * eased) }
            }
        }
    }

    private fun outlinePathOf(text: String, typeface: Typeface, align: Paint.Align): Path {
        val key = OutlineKey(text, typeface, align, outlinePaint.letterSpacing)
        outlinePaths[key]?.let { return it }
        if (outlinePaths.size >= MAX_OUTLINE_PATHS) outlinePaths.clear()
        return Path().also { path ->
            outlinePaint.getTextPath(text, 0, text.length, 0f, 0f, path)
            path.op(Path(), Path.Op.UNION)
            outlinePaths[key] = path
        }
    }

    private fun ringPathOf(text: String, typeface: Typeface, align: Paint.Align, width: Float): Path {
        val key = RingKey(OutlineKey(text, typeface, align, outlinePaint.letterSpacing), width)
        ringPaths[key]?.let { return it }
        if (ringPaths.size >= MAX_OUTLINE_PATHS) ringPaths.clear()
        val glyph = outlinePathOf(text, typeface, align)
        ringStroker.strokeWidth = width
        return Path().also { ring ->
            ringStroker.getFillPath(glyph, ring)
            ring.op(glyph, Path.Op.DIFFERENCE)
            ringPaths[key] = ring
        }
    }

    private fun drawText(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        layer: FaceLayer,
        weight: Float,
        axes: Axes,
        alpha: Float,
        outline: Float,
        color: Int,
        size: Float,
        align: Paint.Align,
        awake: Float = 1f
    ) {
        if (alpha <= 0f) return

        val typeface = FaceFont.typeface(fontOf(layer), weight.roundToInt(), axes.width, axes.height)
        val baseAlpha = Color.alpha(color) * alpha
        val fill = 1f - outline
        val density = resources.displayMetrics.density

        if (layer.shadow > 0f && awake > 0f) drawShadow(canvas, text, x, y, typeface, align, layer.shadow, baseAlpha * awake)
        if (fill > 0f && layer.stroke != null && awake > 0f && !layer.badge.filled) {
            outlinePaint.typeface = typeface
            outlinePaint.textAlign = align
            val sticker = colorOf(layer.stroke)
            val borderInk = drawingBorderInk
            if (borderInk == null) {
                stickerPaint.color = ColorUtils.setAlphaComponent(sticker, (Color.alpha(sticker) * alpha * fill * awake).roundToInt())
            } else {
                inkMatrix.setTranslate(-x, -y)
                borderInk.setLocalMatrix(inkMatrix)
                stickerPaint.shader = borderInk
                stickerPaint.color = ColorUtils.setAlphaComponent(Color.WHITE, (OPAQUE * alpha * fill * awake).roundToInt())
            }
            val path = ringPathOf(text, typeface, align, layer.strokeWidth * BASE_SIZE * 2f)
            canvas.withTranslation(x, y) { drawPath(path, stickerPaint) }
            stickerPaint.shader = null
        }
        if (fill > 0f) {
            paint.typeface = typeface
            paint.textAlign = align
            when (drawingEffect) {
                FaceTextEffect.SOLID -> {
                    inked(paint, color, (baseAlpha * fill).roundToInt())
                    canvas.drawText(text, x, y, paint)
                    uninked(paint)
                }

                FaceTextEffect.OVERLAP -> {
                    inked(paint, color, (baseAlpha * fill * OVERLAP_ALPHA).roundToInt())
                    canvas.drawText(text, x, y, paint)
                    uninked(paint)
                }

                FaceTextEffect.BLEND -> {
                    val clear = awake
                    if (clear < 1f) {
                        inked(paint, color, (baseAlpha * fill * (1f - clear)).roundToInt())
                        canvas.drawText(text, x, y, paint)
                        uninked(paint)
                    }
                    val shader = if (clear > 0f) backdropShader(false) else null
                    if (shader != null) {
                        paint.shader = shader
                        paint.colorFilter = blendFilter
                        paint.color = ColorUtils.setAlphaComponent(Color.WHITE, (baseAlpha * fill * clear).roundToInt())
                        canvas.drawText(text, x, y, paint)
                        paint.colorFilter = null
                        paint.shader = null
                    }
                    val tint = if (shader == null) NO_BACKDROP_TINT else tintOf(layer, FaceTextEffect.BLEND)
                    inked(paint, color, (baseAlpha * fill * tint * clear).roundToInt())
                    canvas.drawText(text, x, y, paint)
                    uninked(paint)
                }

                FaceTextEffect.GLASS -> {
                    if (awake < 1f) {
                        inked(paint, color, (baseAlpha * fill * (1f - awake)).roundToInt())
                        canvas.drawText(text, x, y, paint)
                        uninked(paint)
                    }
                    drawGlass(canvas, text, x, y, typeface, align, color, baseAlpha * fill * awake, tintOf(layer, FaceTextEffect.GLASS))
                }
            }
        }
        if (outline > 0f) {
            outlinePaint.typeface = typeface
            outlinePaint.textAlign = align
            outlinePaint.strokeWidth = layer.outlineWidth * density * BASE_SIZE / size
            inked(outlinePaint, color, (baseAlpha * outline).roundToInt(), x, y)
            val path = outlinePathOf(text, typeface, align)
            canvas.withTranslation(x, y) { drawPath(path, outlinePaint) }
            uninked(outlinePaint)
        }
    }

    private fun drawShadow(canvas: Canvas, text: String, x: Float, y: Float, typeface: Typeface, align: Paint.Align, strength: Float, alpha: Float) {
        val level = strength.coerceIn(0f, 1f)
        val radius = (BASE_SIZE * (SHADOW_MIN_RADIUS + SHADOW_RADIUS_RANGE * level)).roundToInt().coerceAtLeast(1)
        shadowPaint.maskFilter = shadowBlurs.getOrPut(radius) { BlurMaskFilter(radius.toFloat(), BlurMaskFilter.Blur.NORMAL) }
        shadowPaint.typeface = typeface
        shadowPaint.textAlign = align
        shadowPaint.color = ColorUtils.setAlphaComponent(Color.BLACK, (alpha * SHADOW_MAX_ALPHA * level).roundToInt().coerceIn(0, OPAQUE.toInt()))
        canvas.drawText(text, x, y + BASE_SIZE * SHADOW_DROP * level, shadowPaint)
    }

    private fun drawGlass(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        typeface: Typeface,
        align: Paint.Align,
        color: Int,
        alpha: Float,
        tint: Float
    ) {
        outlinePaint.typeface = typeface
        outlinePaint.textAlign = align
        glassPath.set(outlinePathOf(text, typeface, align))
        glassPath.offset(x, y)
        val key = "$text|${System.identityHashCode(typeface)}|$align|${x.roundToInt()}|${y.roundToInt()}"
        drawGlassPath(canvas, glassPath, key, color, alpha, tint)
    }

    private fun drawGlassPath(canvas: Canvas, path: Path, key: String, color: Int, alpha: Float, tint: Float) {
        if (alpha <= 0f || path.isEmpty) return
        val shader = backdropShader(true)
        if (shader != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val renderer = glassRenderer ?: GlassRenderer().also { glassRenderer = it }
            val ink = drawingInk
            renderer.draw(canvas, path, key, shader, alpha.roundToInt(), color, if (ink == null) tint else 0f, BASE_SIZE * GLASS_BEVEL)
            if (ink != null) {
                inked(glassFill, color, (alpha * tint).roundToInt())
                canvas.drawPath(path, glassFill)
                uninked(glassFill)
            }
            return
        }
        if (shader != null) {
            glassFill.shader = shader
            glassFill.colorFilter = glassFilter
            glassFill.alpha = alpha.roundToInt()
            canvas.drawPath(path, glassFill)
            glassFill.colorFilter = null
            glassFill.shader = null
        }
        inked(glassFill, color, (alpha * if (shader == null) NO_BACKDROP_TINT else tint).roundToInt())
        canvas.drawPath(path, glassFill)
        uninked(glassFill)
    }

    private fun inkOf(value: String?, widthRatio: Float, capRatio: Float, sleepColor: Int?): Shader? {
        if (aodProgress >= 1f) return null
        val gradient = FaceGradient.parse(value) ?: return null
        val width = widthRatio * BASE_SIZE
        val height = capRatio * BASE_SIZE
        val radians = Math.toRadians(gradient.angle.toDouble())
        val dx = cos(radians).toFloat()
        val dy = sin(radians).toFloat()
        val half = (abs(width * dx) + abs(height * dy)) / 2f
        val centerX = width / 2f
        val centerY = -height / 2f
        val colors = IntArray(gradient.stops.size) { stop ->
            val base = colorOf(gradient.stops[stop])
            if (sleepColor == null) base else ColorUtils.blendARGB(base, sleepColor, aodProgress)
        }
        return LinearGradient(centerX - dx * half, centerY - dy * half, centerX + dx * half, centerY + dy * half, colors, null, Shader.TileMode.CLAMP)
    }

    private fun inked(target: Paint, color: Int, alpha: Int, dx: Float = 0f, dy: Float = 0f) {
        val ink = drawingInk
        if (ink == null) {
            target.color = ColorUtils.setAlphaComponent(color, alpha)
            return
        }
        inkMatrix.setTranslate(-dx, -dy)
        ink.setLocalMatrix(inkMatrix)
        target.shader = ink
        target.color = ColorUtils.setAlphaComponent(Color.WHITE, alpha)
    }

    private fun uninked(target: Paint) {
        if (drawingInk != null) target.shader = null
    }

    private fun monoFilter(amount: Float): ColorMatrixColorFilter {
        val key = (amount * MONO_STEPS).roundToInt()
        monoFilters[key]?.let { return it }
        return ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(1f - key / MONO_STEPS) }).also { monoFilters[key] = it }
    }

    private fun drawImage(canvas: Canvas, layer: FaceLayer, widthRatio: Float, capRatio: Float, alpha: Float) {
        val bitmap = FaceImages.get(layer.format) ?: return
        val shader = imageShaders.getOrPut(bitmap) {
            BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).linear()
        }
        drawBitmap(canvas, layer, bitmap, shader, widthRatio, capRatio, alpha)
    }

    private fun drawArt(canvas: Canvas, layer: FaceLayer, widthRatio: Float, capRatio: Float, color: Int, alpha: Float, now: Long): Boolean {
        val current = FaceImages.mediaArt
        if (current !== artShown) {
            artPrevious = artShown
            artShown = current
            artStart = now
            artShaders.keys.retainAll(setOfNotNull(artShown, artPrevious))
        }
        val progress = if (artStart == 0L) 1f else ((now - artStart) / ART_FADE_MS).coerceIn(0f, 1f)
        if (progress < 1f) drawArtLayer(canvas, layer, artPrevious, widthRatio, capRatio, color, alpha * (1f - progress))
        drawArtLayer(canvas, layer, current, widthRatio, capRatio, color, alpha * progress)
        return progress < 1f
    }

    private fun drawArtLayer(canvas: Canvas, layer: FaceLayer, bitmap: Bitmap?, widthRatio: Float, capRatio: Float, color: Int, alpha: Float) {
        if (alpha <= 0f) return
        if (bitmap != null) {
            val shader = artShaders.getOrPut(bitmap) {
                BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).linear()
            }
            drawBitmap(canvas, layer, bitmap, shader, widthRatio, capRatio, alpha)
            return
        }
        val width = widthRatio * BASE_SIZE
        val height = capRatio * BASE_SIZE
        val corner = min(width, height) * layer.radius.coerceIn(0f, 0.5f)
        cardRect.set(0f, -height, width, 0f)
        artPaint.typeface = null
        artPaint.color = color
        artPaint.alpha = (alpha * ART_FILL_ALPHA * OPAQUE).roundToInt()
        canvas.drawRoundRect(cardRect, corner, corner, artPaint)
        artPaint.typeface = FaceFont.typeface(ART_GLYPH_FONT, ART_GLYPH_WEIGHT, ART_GLYPH_WIDTH, ART_GLYPH_HEIGHT)
        artPaint.textAlign = Paint.Align.CENTER
        artPaint.textSize = min(width, height) * ART_GLYPH_SIZE
        artPaint.alpha = (alpha * ART_GLYPH_ALPHA * OPAQUE).roundToInt()
        val metrics = artPaint.fontMetrics
        canvas.drawText(FaceGlyphs.MUSIC, width / 2f, -height / 2f - (metrics.ascent + metrics.descent) / 2f, artPaint)
    }

    private fun drawBitmap(
        canvas: Canvas,
        layer: FaceLayer,
        bitmap: Bitmap,
        shader: BitmapShader,
        widthRatio: Float,
        capRatio: Float,
        alpha: Float
    ) {
        val width = widthRatio * BASE_SIZE
        val height = capRatio * BASE_SIZE
        val corner = min(width, height) * layer.radius.coerceIn(0f, 0.5f)
        cardRect.set(0f, -height, width, 0f)
        cardPath.rewind()
        cardPath.addRoundRect(cardRect, corner, corner, Path.Direction.CW)
        val bitmapWidth = bitmap.width.toFloat()
        val bitmapHeight = bitmap.height.toFloat()
        val target = width / height
        if (bitmapWidth / bitmapHeight > target) {
            val crop = bitmapHeight * target
            imageBounds.set((bitmapWidth - crop) / 2f, 0f, (bitmapWidth + crop) / 2f, bitmapHeight)
        } else {
            val crop = bitmapWidth / target
            imageBounds.set(0f, (bitmapHeight - crop) / 2f, bitmapWidth, (bitmapHeight + crop) / 2f)
        }
        imageMatrix.setRectToRect(imageBounds, cardRect, Matrix.ScaleToFit.FILL)
        imagePaint.colorFilter = if (layer.sleepMono && aodProgress > 0f) monoFilter(aodProgress) else null
        shader.setLocalMatrix(imageMatrix)
        imagePaint.shader = shader
        imagePaint.alpha = (alpha * OPAQUE).roundToInt()
        canvas.drawPath(cardPath, imagePaint)
        imagePaint.shader = null
        imagePaint.colorFilter = null
    }

    private fun drawCard(
        canvas: Canvas,
        layer: FaceLayer,
        widthRatio: Float,
        capRatio: Float,
        color: Int,
        alpha: Float,
        outline: Float,
        awake: Float,
        size: Float
    ) {
        val width = widthRatio * BASE_SIZE
        val height = capRatio * BASE_SIZE
        val corner = min(width, height) * layer.radius.coerceIn(0f, 0.5f)
        cardRect.set(0f, -height, width, 0f)
        cardPath.rewind()
        cardPath.addRoundRect(cardRect, corner, corner, Path.Direction.CW)
        val baseAlpha = Color.alpha(color) * alpha
        val fill = (1f - outline) * awake
        if (fill > 0f) {
            when (drawingEffect) {
                FaceTextEffect.SOLID -> {
                    inked(glassFill, color, (baseAlpha * fill).roundToInt())
                    canvas.drawPath(cardPath, glassFill)
                    uninked(glassFill)
                }

                FaceTextEffect.OVERLAP -> {
                    inked(glassFill, color, (baseAlpha * fill * OVERLAP_ALPHA).roundToInt())
                    canvas.drawPath(cardPath, glassFill)
                    uninked(glassFill)
                }

                FaceTextEffect.BLEND -> {
                    val shader = backdropShader(false)
                    if (shader != null) {
                        glassFill.shader = shader
                        glassFill.colorFilter = blendFilter
                        glassFill.color = ColorUtils.setAlphaComponent(Color.WHITE, (baseAlpha * fill).roundToInt())
                        canvas.drawPath(cardPath, glassFill)
                        glassFill.colorFilter = null
                        glassFill.shader = null
                    }
                    val tint = tintOf(layer, FaceTextEffect.BLEND)
                    if (shader == null) glassFill.color = cardFallback(color, tint, baseAlpha * fill) else inked(glassFill, color, (baseAlpha * fill * tint).roundToInt())
                    canvas.drawPath(cardPath, glassFill)
                    uninked(glassFill)
                }

                FaceTextEffect.GLASS -> if (backdropShader(true) == null) {
                    drawCardFallback(canvas, color, tintOf(layer, FaceTextEffect.GLASS), baseAlpha * fill, height)
                } else {
                    val key = "card|${width.roundToInt()}|${height.roundToInt()}|${corner.roundToInt()}"
                    drawGlassPath(canvas, cardPath, key, color, baseAlpha * fill, tintOf(layer, FaceTextEffect.GLASS))
                }
            }
        }
        if (outline > 0f) {
            outlinePaint.strokeWidth = layer.outlineWidth * resources.displayMetrics.density * BASE_SIZE / size
            outlinePaint.color = ColorUtils.setAlphaComponent(color, (baseAlpha * outline).roundToInt())
            val inset = outlinePaint.strokeWidth / 2f
            cardRect.set(inset, -height + inset, width - inset, -inset)
            val inner = (corner - inset).coerceAtLeast(0f)
            cardPath.rewind()
            cardPath.addRoundRect(cardRect, inner, inner, Path.Direction.CW)
            canvas.drawPath(cardPath, outlinePaint)
        }
    }

    private fun cardFallback(color: Int, tint: Float, alpha: Float): Int =
        ColorUtils.setAlphaComponent(ColorUtils.blendARGB(Color.WHITE, color, tint), (alpha * max(CARD_FALLBACK, tint * FALLBACK_TINT)).roundToInt())

    private fun drawCardFallback(canvas: Canvas, color: Int, tint: Float, alpha: Float, height: Float) {
        glassFill.color = cardFallback(color, tint, alpha)
        canvas.drawPath(cardPath, glassFill)
        val strength = alpha / OPAQUE
        rimPaint.strokeWidth = BASE_SIZE * RIM_WIDTH
        rimPaint.shader = LinearGradient(
            0f, -height, 0f, 0f,
            ColorUtils.setAlphaComponent(Color.WHITE, (OPAQUE * RIM_TOP * strength).roundToInt()),
            ColorUtils.setAlphaComponent(Color.WHITE, (OPAQUE * RIM_BOTTOM * strength).roundToInt()),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(cardPath, rimPaint)
        rimPaint.shader = null
    }

        private fun drawGauge(canvas: Canvas, layer: FaceLayer, widthRatio: Float, capRatio: Float, color: Int, alpha: Float, weight: Float) {
        val progress = (FaceValues.progress(layer, data) ?: 0f).coerceIn(0f, 1f)
        val width = widthRatio * BASE_SIZE
        val height = capRatio * BASE_SIZE
        val baseAlpha = Color.alpha(color) * alpha
        val track = ColorUtils.setAlphaComponent(color, (baseAlpha * GAUGE_TRACK).roundToInt())
        if (layer.aspect > BAR_ASPECT) {
            gaugePaint.strokeWidth = height * weight / layer.weight.coerceAtLeast(1)
            val y = -height / 2f
            val inset = gaugePaint.strokeWidth / 2f
            gaugePaint.color = track
            canvas.drawLine(inset, y, width - inset, y, gaugePaint)
            if (progress > 0f) {
                inked(gaugePaint, color, baseAlpha.roundToInt())
                canvas.drawLine(inset, y, inset + (width - inset * 2f) * progress, y, gaugePaint)
                uninked(gaugePaint)
            }
            return
        }
        val stroke = width * layer.strokeWidth * weight / layer.weight.coerceAtLeast(1)
        val inset = stroke / 2f
        cardRect.set(inset, -height + inset, width - inset, -inset)
        gaugePaint.strokeWidth = stroke
        gaugePaint.color = track
        canvas.drawArc(cardRect, GAUGE_START, GAUGE_SWEEP, false, gaugePaint)
        if (progress > 0f) {
            inked(gaugePaint, color, baseAlpha.roundToInt())
            canvas.drawArc(cardRect, GAUGE_START, GAUGE_SWEEP * progress, false, gaugePaint)
            uninked(gaugePaint)
        }
    }

    private fun drawBadge(canvas: Canvas, layer: FaceLayer, widthRatio: Float, capRatio: Float, color: Int, alpha: Float, awake: Float) {
        if (layer.badge == FaceBadge.NONE) return
        val badgeColor = layer.badgeColor?.let { ColorUtils.blendARGB(colorOf(it), color, aodProgress) } ?: color
        val pad = layer.badgePadding * BASE_SIZE
        val width = widthRatio * BASE_SIZE
        val height = capRatio * BASE_SIZE
        val filled = layer.badge.filled
        val circle = layer.badge == FaceBadge.CIRCLE || layer.badge == FaceBadge.CIRCLE_FILLED
        badgePaint.strokeWidth = BADGE_STROKE * BASE_SIZE
        if (circle) {
            val radius = max(width, height) / 2f + pad
            if (filled && awake > 0f) {
                badgePaint.style = Paint.Style.FILL
                badgePaint.color = ColorUtils.setAlphaComponent(badgeColor, (Color.alpha(badgeColor) * alpha * awake).roundToInt())
                canvas.drawCircle(width / 2f, -height / 2f, radius, badgePaint)
            }
            badgePaint.style = Paint.Style.STROKE
            badgePaint.color = ColorUtils.setAlphaComponent(badgeColor, (Color.alpha(badgeColor) * alpha).roundToInt())
            canvas.drawCircle(width / 2f, -height / 2f, radius, badgePaint)
        } else {
            badgeRect.set(-pad * BADGE_PILL_SIDE, -height - pad, width + pad * BADGE_PILL_SIDE, pad)
            val corner = badgeRect.height() / 2f
            if (filled && awake > 0f) {
                badgePaint.style = Paint.Style.FILL
                badgePaint.color = ColorUtils.setAlphaComponent(badgeColor, (Color.alpha(badgeColor) * alpha * awake).roundToInt())
                canvas.drawRoundRect(badgeRect, corner, corner, badgePaint)
            }
            badgePaint.style = Paint.Style.STROKE
            badgePaint.color = ColorUtils.setAlphaComponent(badgeColor, (Color.alpha(badgeColor) * alpha).roundToInt())
            canvas.drawRoundRect(badgeRect, corner, corner, badgePaint)
        }
    }

    private fun badgeTextColor(layer: FaceLayer, color: Int, awake: Float): Int {
        if (!layer.badge.filled) return color
        val ink = layer.stroke?.let(colorOf) ?: Color.BLACK
        return ColorUtils.blendARGB(color, ink, awake)
    }

    private fun drawHand(canvas: Canvas, layer: FaceLayer, color: Int, alpha: Float, weight: Float) {
        val minute = handMinute
        val angle = if (layer.type == FaceLayerType.HAND_HOUR) {
            ((handHour % HOURS_ON_DIAL) + minute / MINUTES_PER_HOUR) * DEGREES_PER_HOUR
        } else {
            minute * DEGREES_PER_MINUTE
        }
        val centerX = BASE_SIZE / 2f
        val centerY = -BASE_SIZE / 2f
        inked(handPaint, color, (Color.alpha(color) * alpha).roundToInt())
        handPaint.strokeWidth = BASE_SIZE * HAND_THICKNESS * weight / MAX_WEIGHT
        canvas.withRotation(angle, centerX, centerY) {
            drawLine(centerX, centerY, centerX, centerY - BASE_SIZE * layer.handLength, handPaint)
        }
        uninked(handPaint)
    }

    fun playChange() {
        face.layers.forEachIndexed { index, layer ->
            val text = texts[index] ?: return@forEachIndexed
            if (!layer.isTime) return@forEachIndexed
            val old = text.map { char ->
                if (char.isDigit()) '0' + (char - '0' + 9) % 10 else char
            }.joinToString("")
            startChange(index, old, text)
        }
        invalidate()
    }

    fun setBackdrop(bitmap: Bitmap?) {
        val blurred = bitmap?.let(BackdropCache::of)
        blendBitmap = blurred?.blend
        glassBitmap = blurred?.glass
        blendShader = blendBitmap?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        glassShader = glassBitmap?.let { bitmap ->
            BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).linear()
        }
        invalidate()
    }

    var widgetsAboveDepth = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    var depthDrawer: ((Canvas) -> Unit)? = null

    var depthPresent = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private fun inFrontOfDepth(layer: FaceLayer): Boolean =
        layer.depthFront ?: (widgetsAboveDepth && !FaceComposer.isClockLayer(layer.id))

    fun setDepth(bitmap: Bitmap?, opacity: Float, widgetsFront: Boolean = false) {
        widgetsAboveDepth = widgetsFront
        if (depthBitmap === bitmap && depthOpacity == opacity) return
        depthBitmap = bitmap
        depthOpacity = opacity.coerceIn(0f, 1f)
        invalidate()
    }

    private fun drawDepth(canvas: Canvas) {
        val bitmap = depthBitmap ?: return
        if (backdropFrame.isEmpty) return
        val asleep = max(aodProgress, sleepProgress)
        val alpha = depthOpacity * (1f - asleep / DEPTH_FADE).coerceIn(0f, 1f)
        if (alpha <= 0f) return
        depthPaint.alpha = (alpha * OPAQUE).roundToInt()
        canvas.drawBitmap(bitmap, null, backdropFrame, depthPaint)
    }

    fun setBackdropFrame(frame: RectF) {
        if (backdropFrame == frame) return
        backdropFrame.set(frame)
        invalidate()
    }

    private fun fontOf(layer: FaceLayer): String {
        if (!layer.isIcon) return layer.font
        val base = layer.font.ifEmpty { ICON_FONT }
        val tile = layer.tile
        val fill = if (tile != null && tile.media == null && data.tileState(tile) == FaceData.TILE_OFF) 0 else 1
        val separator = if (base.contains('?')) '&' else '?'
        return "$base${separator}FILL=$fill"
    }

    private fun isTileOn(layer: FaceLayer): Boolean = layer.tile?.let { tile ->
        if (tile.media != null) tile == FaceTile.MEDIA && data.tileState(tile) == FaceData.TILE_ON else data.tileState(tile) != FaceData.TILE_OFF
    } == true

    private fun tintOf(layer: FaceLayer, effect: FaceTextEffect): Float =
        (layer.activeTint?.takeIf { isTileOn(layer) } ?: layer.effectTint) ?: effect.defaultTint

    private fun activeColorOf(layer: FaceLayer): Int? = layer.activeColor?.takeIf { isTileOn(layer) }?.let(colorOf)

    private fun groupOf(id: String): String = FaceComposer.itemOf(id)?.toString() ?: CLOCK_GROUP

    private fun tileAlpha(layer: FaceLayer): Float {
        val tile = layer.tile ?: return 1f
        if (layer.type != FaceLayerType.TILE) return 1f
        return if (data.tileState(tile) == FaceData.TILE_OFF) TILE_OFF_ALPHA else 1f
    }

    override fun performClick(): Boolean = super.performClick()

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (onTileTap == null && onMediaAction == null) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedLayer = touchTargetAt(event.x, event.y)
                swiping = false
                downX = event.x
                downY = event.y
                return pressedLayer != null
            }

            MotionEvent.ACTION_MOVE -> {
                val layer = pressedLayer ?: return false
                if (!swiping && isMedia(layer)) {
                    val dx = event.x - downX
                    val slop = ViewConfiguration.get(context).scaledTouchSlop
                    if (abs(dx) > slop && abs(dx) > abs(event.y - downY)) {
                        swiping = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                val layer = pressedLayer ?: return false
                pressedLayer = null
                val dx = event.x - downX
                if (swiping) {
                    swiping = false
                    if (abs(dx) >= SWIPE_DISTANCE * resources.displayMetrics.density) {
                        performHapticFeedback(HapticFeedbackConstants.GESTURE_END)
                        val forward = (dx < 0f) != (layoutDirection == LAYOUT_DIRECTION_RTL)
                        onMediaAction?.invoke(if (forward) MediaAction.NEXT else MediaAction.PREVIOUS)
                    }
                    return true
                }
                val slop = ViewConfiguration.get(context).scaledTouchSlop
                if (abs(dx) < slop && abs(event.y - downY) < slop && touchTargetAt(event.x, event.y) == layer) {
                    performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    performClick()
                    activate(layer)
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                pressedLayer = null
                swiping = false
            }
        }
        return pressedLayer != null
    }

    private fun activate(layer: FaceLayer) {
        val tile = layer.tile
        val action = tile?.media ?: MediaAction.TOGGLE.takeIf { layer.type in MEDIA_LAYERS }
        when {
            action != null -> onMediaAction?.invoke(action)
            tile != null -> onTileTap?.invoke(tile)
        }
    }

    private fun isMedia(layer: FaceLayer): Boolean = layer.tile?.media != null || layer.type in MEDIA_LAYERS

    private fun touchTargetAt(x: Float, y: Float): FaceLayer? {
        val rects = layerRects()
        return face.layers.lastOrNull { layer ->
            val reachable = if (isMedia(layer)) onMediaAction != null else layer.tile != null && onTileTap != null
            reachable && groupOf(layer.id) !in idleGroups && rects[layer.id]?.let { rect ->
                val pad = rect.height() * TILE_TOUCH_PAD
                x >= rect.left - pad && x <= rect.right + pad && y >= rect.top - pad && y <= rect.bottom + pad
            } == true
        }
    }

    private fun isSymbol(layer: FaceLayer): Boolean = fontOf(layer).startsWith(ICON_FONT)

    fun layerExtents(): Map<String, RectF> {
        val width = width.toFloat()
        if (width <= 0f) return emptyMap()
        return face.layers.mapIndexedNotNull { index, layer ->
            val placement = placement(index, width) ?: return@mapIndexedNotNull null
            if (placement.alpha <= 0f) return@mapIndexedNotNull null
            layer.id to RectF().also { extentOf(layer.id, placement, it) }
        }.toMap()
    }

    fun layerRects(includeHidden: Boolean = false): Map<String, RectF> {
        val width = width.toFloat()
        if (width <= 0f) return emptyMap()
        val shown = if (includeHidden) editedStates(width).first else emptyMap()
        return face.layers.mapIndexedNotNull { index, layer ->
            val placement = placement(index, width) ?: return@mapIndexedNotNull null
            if (placement.alpha <= 0f && (!includeHidden || layer.id !in shown)) return@mapIndexedNotNull null
            layer.id to boundsOf(placement)
        }.toMap()
    }

    fun missingLayerRects(): Map<String, RectF> {
        val width = width.toFloat()
        if (width <= 0f) return emptyMap()
        val (shown, other) = editedStates(width)
        val rects = layerRects(includeHidden = true)
        val missing = face.layers.filter { it.id !in shown && it.id in other }
        if (missing.isEmpty()) return emptyMap()
        return missing.groupBy { groupOf(it.id) }.flatMap { (group, layers) ->
            val common = rects.keys.filter { groupOf(it) == group && it in other }
            val target = unionOf(common.mapNotNull { rects[it] }) ?: return@flatMap emptyList()
            val source = unionOf(common.mapNotNull { other[it]?.let(::boundsOf) }) ?: return@flatMap emptyList()
            val scale = if (source.width() > 0f) target.width() / source.width() else 1f
            layers.map { layer ->
                val rect = boundsOf(other.getValue(layer.id))
                layer.id to RectF(
                    target.left + (rect.left - source.left) * scale,
                    target.top + (rect.top - source.top) * scale,
                    target.left + (rect.right - source.left) * scale,
                    target.top + (rect.bottom - source.top) * scale
                )
            }
        }.toMap()
    }

    private fun editedStates(width: Float): Pair<Map<String, Placement>, Map<String, Placement>> {
        val (small, large) = frames(width)
        val showsLarge = largeProgress >= HALF_PROGRESS
        val shownLarge = if (aodProgress < HALF_PROGRESS) large else aodLargeFrames ?: large
        val shownSmall = if (aodProgress < HALF_PROGRESS) small else aodSmallFrames ?: small
        return if (showsLarge) shownLarge to shownSmall else shownSmall to shownLarge
    }

    private fun boundsOf(placement: Placement): RectF = RectF(
        placement.left,
        placement.top,
        placement.left + placement.advances.sum() * placement.stretchX * placement.size,
        placement.top + placement.size * placement.capRatio * placement.stretch * placement.lineHeight
    )

    private fun unionOf(boxes: List<RectF>): RectF? {
        if (boxes.isEmpty()) return null
        return RectF(boxes.minOf { it.left }, boxes.minOf { it.top }, boxes.maxOf { it.right }, boxes.maxOf { it.bottom })
    }

    private fun backdropShader(glass: Boolean): Shader? {
        val bitmap = (if (glass) glassBitmap else blendBitmap) ?: return null
        val shader = (if (glass) glassShader else blendShader) ?: return null
        if (backdropFrame.isEmpty) return null
        shaderMatrix.setRectToRect(
            RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat()),
            backdropFrame,
            Matrix.ScaleToFit.FILL
        )
        if (!drawMatrix.invert(inverseMatrix)) return null
        shaderMatrix.postConcat(inverseMatrix)
        shader.setLocalMatrix(shaderMatrix)
        return shader
    }

    private fun awakeOf(order: Int): Float {
        val delay = order * WAKE_STAGGER
        val awake = ((1f - sleepProgress - delay) / (1f - WAKE_STAGGER * MAX_STAGGER_STEPS))
            .coerceIn(0f, 1f)
        return WAKE_EASING.getInterpolation(awake)
    }

    private fun weightOf(layer: FaceLayer, base: Float, awake: Float): Float {
        if (!layer.sleepStyle.thin) return base
        return lerp(layer.sleepWeight.toFloat(), base, awake)
    }

    private fun axesOf(layer: FaceLayer, placement: Placement, awake: Float): Axes {
        val width = layer.sleepWidth?.let { lerp(it.toFloat(), placement.widthAxis, awake) }
            ?: placement.widthAxis
        val height = layer.sleepHeight?.let { lerp(it.toFloat(), placement.heightAxis, awake) }
            ?: placement.heightAxis
        return Axes(width.roundToInt(), height.roundToInt())
    }

    private fun outlineOf(layer: FaceLayer, awake: Float): Float {
        val awakeOutline = if (layer.outline) 1f else 0f
        val sleepOutline = if (layer.sleepStyle.outline) 1f else awakeOutline
        return lerp(sleepOutline, awakeOutline, awake)
    }

    fun aodStrokePx(): Float {
        val width = width.toFloat()
        if (width <= 0f) return 0f
        val density = resources.displayMetrics.density
        var thickest = 0f
        face.layers.forEachIndexed { index, layer ->
            if (layer.sleepStyle.hide) return@forEachIndexed
            val placement = placement(index, width) ?: return@forEachIndexed
            if (placement.alpha <= 0f) return@forEachIndexed
            val size = placement.size
            val stroke = when {
                layer.isHand -> size * HAND_THICKNESS * layer.sleepWeight / MAX_WEIGHT
                outlineOf(layer, 0f) >= 1f -> layer.outlineWidth * density
                layer.sleepStyle.thin && FaceFont.hasWeightAxis(fontOf(layer)) -> size * layer.sleepWeight * STEM_PER_WEIGHT
                FaceFont.hasWeightAxis(fontOf(layer)) -> size * layer.weight * STEM_PER_WEIGHT
                else -> size * STATIC_STEM
            }
            val badge = if (layer.badge != FaceBadge.NONE) size * BADGE_STROKE else 0f
            thickest = max(thickest, max(stroke, badge))
        }
        return thickest
    }

    private fun startChange(index: Int, old: String, new: String) {
        previousTexts[index] = old
        val now = SystemClock.uptimeMillis()
        changeStarts[index] = LongArray(new.length) { charIndex ->
            if (old.getOrNull(charIndex) == new[charIndex]) 0L else now + charIndex * CHANGE_STAGGER
        }
    }

    private fun placement(index: Int, width: Float): Placement? {
        val layer = face.layers.getOrNull(index) ?: return null
        val (small, large) = frames(width)
        val awake = stateOf(layer.id, small, large) ?: return null
        val aodSmall = aodSmallFrames
        val aodLarge = aodLargeFrames
        if ((aodSmall == null && aodLarge == null) || aodProgress <= 0f) return morph(layer.id, awake)
        val asleep = stateOf(layer.id, aodSmall ?: small, aodLarge ?: large) ?: awake.hidden()
        return morph(layer.id, blend(awake, asleep, aodProgress))
    }

    private fun stateOf(id: String, small: Map<String, Placement>, large: Map<String, Placement>): Placement? {
        val inSmall = small[id]
        val inLarge = large[id]
        val from = inSmall ?: inLarge?.hidden() ?: return null
        val to = inLarge ?: from.hidden()
        val blended = blend(from, to, largeProgress)
        val alpha = when {
            inLarge == null || inLarge.hidden -> from.alpha * (1f - fade(largeProgress / ONE_SIDED_FADE))
            inSmall == null || inSmall.hidden -> to.alpha * fade((largeProgress - (1f - ONE_SIDED_FADE)) / ONE_SIDED_FADE)
            else -> blended.alpha
        }
        return if (alpha == blended.alpha) blended else blended.withAlpha(alpha)
    }

    private fun Placement.bottom(layer: FaceLayer?): Float {
        val descent = if (layer == null || layer.isTime || layer.isShape) DESCENT else TEXT_DESCENT
        return top + size * (capRatio * stretch * lineHeight + descent)
    }

    private fun boxBottom(root: FaceNode, width: Float): Float =
        if (root is StackNode && root.width != null) root.placement.y * width + measure(root, width).height else 0f

    private fun bottomOf(placements: Map<String, Placement>, exclude: Set<String> = emptySet()): Float =
        placements.filter { (id, placement) -> !placement.hidden && groupOf(id) !in exclude }
            .maxOfOrNull { (id, placement) -> placement.bottom(layerById(id)) } ?: 0f

    private fun Placement.withAlpha(value: Float): Placement = Placement(
        left, top, size, value, rotation, capRatio, advances, widthAxis, heightAxis, tracking, weight, color, lineHeight, stretch, stretchX, effect, hidden, tiltX, tiltY
    )

    private fun fade(value: Float): Float {
        val t = value.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun Placement.hidden(): Placement = Placement(
        left, top, size, 0f, rotation, capRatio, advances, widthAxis, heightAxis, tracking, weight, color, lineHeight, stretch, stretchX, effect,
        tiltX = tiltX, tiltY = tiltY
    )

    private fun frames(width: Float): Pair<Map<String, Placement>, Map<String, Placement>> {
        if (width != framesWidth || smallFrames == null || largeFrames == null || framesPalette != FaceColors.paletteVersion) {
            framesWidth = width
            framesPalette = FaceColors.paletteVersion
            smallFrames = resolve(face.small, width)
            largeFrames = resolve(face.large, width)
            aodSmallFrames = face.aodSmall?.let { resolve(it, width) }
            aodLargeFrames = face.aodLarge?.let { resolve(it, width) }
        }
        return smallFrames!! to largeFrames!!
    }

    private fun invalidateFrames() {
        smallFrames = null
        largeFrames = null
        aodSmallFrames = null
        aodLargeFrames = null
    }

    private fun resolve(root: FaceNode, width: Float): Map<String, Placement> {
        val out = HashMap<String, Placement>()
        val box = measure(root, width)
        val anchor = root.placement.x * width
        val left = when (root.placement.anchor) {
            FaceAlign.START -> anchor
            FaceAlign.CENTER -> anchor - box.width / 2f
            FaceAlign.END -> anchor - box.width
        }
        place(root, left, root.placement.y * width, width, out)
        return out
    }

    private fun extentOf(id: String, placement: Placement, out: RectF) {
        val layer = layerById(id)
        val stroke = layer?.let { if (it.stroke != null) it.strokeWidth else 0f } ?: 0f
        val badge = layer?.takeIf { it.badge != FaceBadge.NONE }
        val pad = placement.size * (stroke + EDGE_SLACK + (badge?.let { (it.badgePadding * BADGE_PILL_SIDE) + BADGE_STROKE } ?: 0f))
        val boxWidth = placement.advances.sum() * placement.stretchX * placement.size
        val boxHeight = placement.capRatio * placement.stretch * placement.size * placement.lineHeight
        val radians = Math.toRadians(placement.rotation.toDouble())
        val cosine = abs(cos(radians)).toFloat()
        val sine = abs(sin(radians)).toFloat()
        val halfWidth = (boxWidth * cosine + boxHeight * sine) / 2f + pad
        val halfHeight = (boxWidth * sine + boxHeight * cosine) / 2f + pad
        val centerX = placement.left + boxWidth / 2f
        val centerY = placement.top + boxHeight / 2f
        if (placement.tiltX == 0f && placement.tiltY == 0f) {
            out.set(centerX - halfWidth, centerY - halfHeight, centerX + halfWidth, centerY + halfHeight)
            return
        }
        tiltInto(tiltMatrix, placement.tiltX, placement.tiltY, centerX, centerY, max(boxWidth, boxHeight))
        if (placement.rotation != 0f) tiltMatrix.postRotate(placement.rotation, centerX, centerY)
        val left = placement.left - pad
        val top = placement.top - pad
        val right = placement.left + boxWidth + pad
        val bottom = placement.top + boxHeight + pad
        tiltCorners[0] = left
        tiltCorners[1] = top
        tiltCorners[2] = right
        tiltCorners[3] = top
        tiltCorners[4] = right
        tiltCorners[5] = bottom
        tiltCorners[6] = left
        tiltCorners[7] = bottom
        tiltMatrix.mapPoints(tiltCorners)
        out.set(
            minOf(tiltCorners[0], tiltCorners[2], tiltCorners[4], tiltCorners[6]),
            minOf(tiltCorners[1], tiltCorners[3], tiltCorners[5], tiltCorners[7]),
            maxOf(tiltCorners[0], tiltCorners[2], tiltCorners[4], tiltCorners[6]),
            maxOf(tiltCorners[1], tiltCorners[3], tiltCorners[5], tiltCorners[7])
        )
    }

    private fun tiltInto(matrix: Matrix, tiltX: Float, tiltY: Float, pivotX: Float, pivotY: Float, extent: Float) {
        tiltCamera.save()
        tiltCamera.setLocation(0f, 0f, -max(extent * TILT_DEPTH / CAMERA_DPI, MIN_TILT_DEPTH))
        tiltCamera.rotateX(tiltX)
        tiltCamera.rotateY(tiltY)
        tiltCamera.getMatrix(matrix)
        tiltCamera.restore()
        matrix.preTranslate(-pivotX, -pivotY)
        matrix.postTranslate(pivotX, pivotY)
    }

    fun contentBounds(out: RectF): Boolean {
        val width = width.toFloat()
        if (width <= 0f) return false
        var found = false
        face.layers.forEachIndexed { index, layer ->
            val placement = placement(index, width) ?: return@forEachIndexed
            if (placement.alpha <= 0f) return@forEachIndexed
            extentOf(layer.id, placement, extent)
            if (found) out.union(extent) else out.set(extent)
            found = true
        }
        return found
    }

    private fun measure(node: FaceNode, width: Float): Box = when (node) {
        is LayerNode -> leaf(node, width)?.let { Box(it.width, it.height, it.descent) } ?: Box(0f, 0f)

        is RowNode -> {
            val boxes = node.children.map { measure(it, width) }
            if (boxes.isEmpty()) {
                Box(0f, 0f)
            } else {
                val base = boxes.maxOf { it.height }
                var x = 0f
                var minLeft = Float.MAX_VALUE
                var maxRight = -Float.MAX_VALUE
                var minTop = Float.MAX_VALUE
                var maxBottom = -Float.MAX_VALUE
                node.children.forEachIndexed { index, child ->
                    val box = boxes[index]
                    val left = x + child.placement.x * width
                    val top = crossOffset((child as? LayerNode)?.align ?: node.align, base, box.height) + child.placement.y * width
                    minLeft = min(minLeft, left)
                    maxRight = max(maxRight, left + box.width)
                    minTop = min(minTop, top)
                    maxBottom = max(maxBottom, top + box.height)
                    x += box.width + node.gap * width
                }
                Box(maxRight - minLeft, maxBottom - minTop, boxes.maxOf { it.descent }, -minLeft, -minTop)
            }
        }

        is ColumnNode -> {
            val boxes = node.children.map { measure(it, width) }
            if (boxes.isEmpty()) {
                Box(0f, 0f)
            } else {
                val base = boxes.maxOf { it.width }
                var y = 0f
                var minLeft = Float.MAX_VALUE
                var maxRight = -Float.MAX_VALUE
                var minTop = Float.MAX_VALUE
                var maxBottom = -Float.MAX_VALUE
                node.children.forEachIndexed { index, child ->
                    val box = boxes[index]
                    val left = crossOffset((child as? LayerNode)?.align ?: node.align, base, box.width) + child.placement.x * width
                    val top = y + child.placement.y * width
                    minLeft = min(minLeft, left)
                    maxRight = max(maxRight, left + box.width)
                    minTop = min(minTop, top)
                    maxBottom = max(maxBottom, top + box.height)
                    y += box.height + node.gap * width
                }
                Box(maxRight - minLeft, maxBottom - minTop, boxes.last().descent, -minLeft, -minTop)
            }
        }

        is StackNode -> if (node.width != null) {
            Box(node.width * width, node.height?.let { it * width } ?: (node.children.maxOfOrNull { measure(it, width).height } ?: 0f))
        } else {
            var right = 0f
            var bottom = 0f
            node.children.forEach { child ->
                val box = measure(child, width)
                right = max(right, stackLeft(child, box, width) + box.width)
                bottom = max(bottom, child.placement.y * width + box.height)
            }
            Box(right, bottom)
        }
    }

    private fun place(
        node: FaceNode,
        left: Float,
        top: Float,
        width: Float,
        out: MutableMap<String, Placement>,
        hidden: Boolean = false
    ) {
        val off = hidden || node.placement.hidden
        when (node) {
            is LayerNode -> {
                val (metrics, _, _, size) = leaf(node, width) ?: return
                val layer = layerById(node.id) ?: return
                out[node.id] = Placement(
                    left = left,
                    top = top,
                    size = size,
                    alpha = if (off) 0f else node.alpha,
                    rotation = node.rotation,
                    capRatio = metrics.capRatio,
                    advances = metrics.advances,
                    widthAxis = (node.width ?: layer.width).toFloat(),
                    heightAxis = (node.height ?: layer.height).toFloat(),
                    tracking = node.tracking ?: layer.tracking,
                    weight = (node.weight ?: layer.weight).toFloat(),
                    color = colorOf(node.color ?: layer.color),
                    lineHeight = node.lineHeight ?: layer.lineHeight,
                    stretch = if (layer.isShape) 1f else node.stretch ?: 1f,
                    stretchX = if (layer.isShape) 1f else node.stretchX ?: 1f,
                    effect = node.effect ?: layer.effect,
                    hidden = off,
                    tiltX = node.tiltX,
                    tiltY = node.tiltY
                )
            }

            is RowNode -> {
                val box = measure(node, width)
                val base = node.children.maxOfOrNull { measure(it, width).height } ?: 0f
                var x = left + box.offsetX
                node.children.forEach { child ->
                    val childBox = measure(child, width)
                    val y = top + box.offsetY + crossOffset((child as? LayerNode)?.align ?: node.align, base, childBox.height)
                    place(child, x + child.placement.x * width, y + child.placement.y * width, width, out, off)
                    x += childBox.width + node.gap * width
                }
            }

            is ColumnNode -> {
                val box = measure(node, width)
                val base = node.children.maxOfOrNull { measure(it, width).width } ?: 0f
                var y = top + box.offsetY
                node.children.forEach { child ->
                    val childBox = measure(child, width)
                    val x = left + box.offsetX + crossOffset((child as? LayerNode)?.align ?: node.align, base, childBox.width)
                    place(child, x + child.placement.x * width, y + child.placement.y * width, width, out, off)
                    y += childBox.height + node.gap * width
                }
            }

            is StackNode -> if (node.width != null) {
                val boxWidth = node.width * width
                val boxHeight = node.height?.let { it * width }
                node.children.forEach { child ->
                    val box = measure(child, width)
                    val x = when ((child as? LayerNode)?.align ?: child.placement.anchor) {
                        FaceAlign.START -> 0f
                        FaceAlign.CENTER -> (boxWidth - box.width) / 2f
                        FaceAlign.END -> boxWidth - box.width
                    }
                    val y = boxHeight?.let { (it - (box.height - box.descent)) / 2f } ?: 0f
                    place(child, left + x + child.placement.x * width, top + y + child.placement.y * width, width, out, off)
                }
            } else {
                node.children.forEach { child ->
                    val box = measure(child, width)
                    place(child, left + stackLeft(child, box, width), top + child.placement.y * width, width, out, off)
                }
            }
        }
    }

    private fun leaf(node: LayerNode, width: Float): Leaf? {
        val index = layerIndex(node.id) ?: return null
        val layer = face.layers[index]
        val metrics = metricsOf(
            index,
            node.width ?: layer.width,
            node.height ?: layer.height,
            node.tracking ?: layer.tracking,
            node.weight ?: layer.weight
        )
        val size = node.size * width
        val lineHeight = node.lineHeight ?: layer.lineHeight
        val descent = if (layer.isTime || layer.isShape) 0f else size * TEXT_DESCENT
        val stretch = if (layer.isShape) 1f else node.stretch ?: 1f
        val stretchX = if (layer.isShape) 1f else node.stretchX ?: 1f
        return Leaf(metrics, metrics.widthRatio * stretchX * size, metrics.capRatio * stretch * size * lineHeight + descent, size, descent)
    }

    private fun stackLeft(child: FaceNode, box: Box, width: Float): Float {
        val anchor = child.placement.x * width
        return when (child.placement.anchor) {
            FaceAlign.START -> anchor
            FaceAlign.CENTER -> anchor - box.width / 2f
            FaceAlign.END -> anchor - box.width
        }
    }

    private fun crossOffset(align: FaceAlign, container: Float, child: Float): Float = when (align) {
        FaceAlign.START -> 0f
        FaceAlign.CENTER -> (container - child) / 2f
        FaceAlign.END -> container - child
    }

    private fun layerIndex(id: String): Int? =
        face.layers.indexOfFirst { it.id == id }.takeIf { it >= 0 }

    private fun layerById(id: String): FaceLayer? = face.layers.firstOrNull { it.id == id }

    private fun fit(layer: FaceLayer, text: String): String {
        val limit = layer.maxEms ?: return text
        if (layer.isTime || layer.isIcon || layer.isShape || text.isEmpty()) return text
        fitPaint.typeface = FaceFont.typeface(fontOf(layer), layer.weight, layer.width, layer.height)
        fitPaint.letterSpacing = layer.tracking
        if (fitPaint.measureText(text) / BASE_SIZE <= limit) return text
        var low = 0
        var high = text.length
        while (low < high) {
            val middle = (low + high + 1) / 2
            if (fitPaint.measureText(text.substring(0, middle).trimEnd() + ELLIPSIS) / BASE_SIZE <= limit) low = middle else high = middle - 1
        }
        return text.substring(0, low).trimEnd() + ELLIPSIS
    }

    private fun metricsOf(index: Int, widthAxis: Int, heightAxis: Int, tracking: Float, weight: Int): LayerMetrics =
        metrics.getOrPut(MetricsKey(index, widthAxis, heightAxis, tracking, weight)) {
            val layer = face.layers[index]
            if (layer.isHand) return@getOrPut LayerMetrics(1f, floatArrayOf(1f), 1f)
            if (layer.isShape && !layer.isHand) return@getOrPut LayerMetrics(1f / layer.aspect.coerceAtLeast(MIN_ASPECT), floatArrayOf(1f), 1f)
            val text = texts[index].orEmpty()
            measurePaint.typeface = FaceFont.typeface(fontOf(layer), weight, widthAxis, heightAxis)
            measurePaint.letterSpacing = 0f
            measurePaint.getTextBounds("0", 0, 1, bounds)
            val capRatio = if (isSymbol(layer) || bounds.isEmpty) ICON_CAP else bounds.height() / BASE_SIZE

            val advances = if (layer.isTime) {
                val digit = DIGITS.maxOf { measurePaint.measureText(it.toString()) } / BASE_SIZE
                FloatArray(text.length) { charIndex ->
                    val glyph = if (text[charIndex].isDigit()) {
                        digit
                    } else {
                        measurePaint.measureText(text[charIndex].toString()) / BASE_SIZE
                    }
                    if (charIndex < text.lastIndex) glyph + tracking else glyph
                }
            } else {
                measurePaint.letterSpacing = tracking
                floatArrayOf(measurePaint.measureText(text) / BASE_SIZE)
            }
            LayerMetrics(capRatio, advances, advances.sum())
        }

    private fun lerp(from: Float, to: Float, fraction: Float): Float = from + (to - from) * fraction

    companion object {
        private const val SHADOW_MIN_RADIUS = 0.03f
        private const val SHADOW_RADIUS_RANGE = 0.07f
        private const val SHADOW_MAX_ALPHA = 0.85f
        private const val SHADOW_DROP = 0.025f
        private const val BASE_SIZE = 200f
        private const val TEXT_FLAGS = Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG
        private const val MIN_ASPECT = 0.05f
        private const val ICON_CAP = 1f
        private const val GAUGE_TRACK = 0.28f
        private const val GAUGE_START = 135f
        private const val GAUGE_SWEEP = 270f
        private const val BAR_ASPECT = 1.5f
        private const val CARD_FALLBACK = 0.14f
        private const val FALLBACK_TINT = 0.7f
        private const val OPAQUE = 255f
        private const val MONO_STEPS = 32f
        private const val DEPTH_FADE = 0.25f
        private const val RIM_WIDTH = 0.012f
        private const val RIM_TOP = 0.45f
        private const val RIM_BOTTOM = 0.06f
        private const val MAX_OUTLINE_PATHS = 64
        private const val DESCENT = 0.08f
        private const val TEXT_DESCENT = 0.24f
        private const val MORPH_DURATION = 650L
        private const val ONE_SIDED_FADE = 0.3f
        private const val EFFECT_SWITCH = 0.5f
        private const val EDGE_SLACK = 0.04f
        private const val ICON_FONT = "res:symbols"
        private const val TILE_OFF_ALPHA = 0.45f
        private const val TILE_TOUCH_PAD = 0.35f
        private const val HALF_PROGRESS = 0.5f
        private const val SWIPE_DISTANCE = 36f
        private val MEDIA_LAYERS = setOf(FaceLayerType.MEDIA_TITLE, FaceLayerType.MEDIA_ARTIST, FaceLayerType.MEDIA_ICON, FaceLayerType.MEDIA_ART)
        private const val ART_FADE_MS = 360f
        private const val ART_FILL_ALPHA = 0.16f
        private const val ART_GLYPH_ALPHA = 0.7f
        private const val ART_GLYPH_SIZE = 0.42f
        private const val ART_GLYPH_WEIGHT = 400
        private const val ART_GLYPH_WIDTH = 100
        private const val ART_GLYPH_HEIGHT = 700
        private const val ART_GLYPH_FONT = "res:symbols?FILL=1"
        private const val STEM_PER_WEIGHT = 0.0002f
        private const val STATIC_STEM = 0.2f
        private const val BADGE_STROKE = 0.06f
        private const val BADGE_PILL_SIDE = 1.6f
        private const val HOURS_ON_DIAL = 12
        private const val MINUTES_PER_HOUR = 60f
        private const val DEGREES_PER_HOUR = 30f
        private const val DEGREES_PER_MINUTE = 6f
        private const val HAND_THICKNESS = 0.14f
        private const val MAX_WEIGHT = 1000f
        private val MORPH_EASING = PathInterpolator(0.2f, 0f, 0f, 1f)
        private const val OVERLAP_ALPHA = 0.55f
        private const val BLEND_SATURATION = 1.8f
        private const val BLEND_BRIGHTNESS = 1.12f
        private const val GLASS_BEVEL = 0.05f
        private const val GLASS_BRIGHTNESS = 1.15f
        private const val NO_BACKDROP_TINT = 0.4f
        private const val WAKE_STAGGER = 0.06f
        private const val MAX_STAGGER_STEPS = 5f
        private const val CHANGE_DURATION = 650L
        private const val CHANGE_STAGGER = 60L
        private const val CHANGE_SHIFT = 0.18f
        private const val ROLL_TRAVEL = 1.15f
        private const val ROLL_CLIP = 1.08f
        private const val SCALE_DELTA = 0.35f
        private const val DIGITS = "0123456789"
        private const val DATE_FORMAT = "EEE d MMM"
        private val WAKE_EASING = PathInterpolator(0.3f, 0f, 0f, 1f)
        private val CHANGE_EASING = PathInterpolator(0.4f, 0f, 0.1f, 1f)
    }
}

private const val CLOCK_GROUP = "clock"
private const val ELLIPSIS = "…"
private const val DAY_PATTERN = "EEEEdMMMM"
private const val DESCRIPTION_SEPARATOR = ", "

private const val TILT_POINTS = 8
private const val TILT_DEPTH = 2.5f
private const val CAMERA_DPI = 72f
private const val MIN_TILT_DEPTH = 8f
