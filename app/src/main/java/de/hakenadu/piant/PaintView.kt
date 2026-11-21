package de.hakenadu.piant

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SweepGradient
import android.os.Build
import android.os.SystemClock
import android.util.SparseArray
import android.view.MotionEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** Brand and paint colors. Pink is "Pia pink", the main brand color. */
object PiantColors {
    const val PAPER = 0xFFFFF8F1.toInt()
    const val PIA_PINK = 0xFFFF4F8B.toInt()
    const val INK = 0xFF2B2140.toInt()

    /** Paint colors in the palette. The last palette slot is the rainbow brush. */
    val PAINTS = intArrayOf(
        PIA_PINK,
        0xFFFFC233.toInt(), // sunny yellow
        0xFF2FD18A.toInt(), // mint green
        0xFF3A9BFF.toInt(), // sky blue
        0xFF8E5CF7.toInt(), // violet
    )
    val RAINBOW_INDEX = PAINTS.size
    val SLOT_COUNT = PAINTS.size + 1
}

/**
 * The painting surface.
 *
 * Built for babies and toddlers: every finger (and palm) paints at the same time, the brush
 * width follows the size of the touch, every new touch sparkles, and nothing on screen can
 * end the app. The only way out is the parent gate in the top-left corner, which opens after
 * holding exactly one finger on it for [GATE_HOLD_MS] while nothing else touches the screen.
 */
@SuppressLint("ViewConstructor")
class PaintView(context: Context) : View(context) {

    interface Listener {
        /** A new finger started painting; fractions are the position on screen (0..1). */
        fun onPaintStart(xFraction: Float, yFraction: Float)

        /** A palette color was tapped. */
        fun onColorPicked(slot: Int)

        /** The parent gate was held long enough. */
        fun onParentGateOpened()

        /** Ask for a short vibration. */
        fun onHaptic()
    }

    var listener: Listener? = null

    var paletteVisible = true
        set(value) {
            field = value
            invalidate()
        }

    /** Selected palette slot; [PiantColors.RAINBOW_INDEX] is the rainbow brush. */
    var selectedSlot = PiantColors.RAINBOW_INDEX
        private set

    private val dp = resources.displayMetrics.density

    // ---- Canvas ---------------------------------------------------------------------------

    private var bitmap: Bitmap? = null
    private var bitmapCanvas: Canvas? = null
    private var pendingRestore: Bitmap? = null
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    private var clearSnapshot: Bitmap? = null
    private var clearStart = 0L
    private val clearPath = Path()

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val segmentPath = Path()
    private val hsv = floatArrayOf(0f, 0.72f, 1f)

    // ---- Fingers --------------------------------------------------------------------------

    private enum class Mode { PAINT, IGNORE, GATE }

    private class Finger(val mode: Mode) {
        var x = 0f
        var y = 0f
        var midX = 0f
        var midY = 0f
        var width = 0f
        var color = 0
        var hue = 0f
        var rainbow = false
        var travelled = 0f
    }

    private val fingers = SparseArray<Finger>()
    private var nextHue = Random.nextFloat() * 360f
    private var lastHaptic = 0L

    // ---- Sparkles -------------------------------------------------------------------------

    private class Particle(
        val x: Float,
        val y: Float,
        val vx: Float,
        val vy: Float,
        val radius: Float,
        val color: Int,
        val born: Long,
        val life: Long,
        val ring: Boolean,
    )

    private val particles = ArrayList<Particle>()
    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // ---- Palette --------------------------------------------------------------------------

    private val slotCx = FloatArray(PiantColors.SLOT_COUNT)
    private var slotCy = 0f
    private var slotWidth = 0f
    private var dropRadius = 0f
    private val paletteRect = RectF()
    private val slotScale = FloatArray(PiantColors.SLOT_COUNT) { 1f }
    private val slotPicked = LongArray(PiantColors.SLOT_COUNT)
    private var rainbowShader: SweepGradient? = null
    private val palettePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // ---- Parent gate ----------------------------------------------------------------------

    private var gateCx = 0f
    private var gateCy = 0f
    private val gateRadius = 24 * dp
    private var gateActive = false
    private var gateStart = 0L
    private var gatePointerId = -1
    private val gatePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gateArc = RectF()
    private val heartPath = Path()
    private val gateRunnable = Runnable {
        if (gateActive) {
            gateActive = false
            stopAllFingers()
            listener?.onHaptic()
            listener?.onParentGateOpened()
            invalidate()
        }
    }

    init {
        isHapticFeedbackEnabled = false
        // Paint with all fingers at once, even when the view sits inside other layouts.
        isClickable = true
    }

    // ---- Public API -----------------------------------------------------------------------

    /** Restores a previously saved picture (e.g. after the app was closed). */
    fun restore(saved: Bitmap) {
        if (bitmap == null) {
            pendingRestore = saved
        } else {
            drawFitted(saved)
            saved.recycle()
            invalidate()
        }
    }

    /** Copy of the current picture, or null before the view has a size. */
    fun snapshot(): Bitmap? = bitmap?.copy(Bitmap.Config.ARGB_8888, false)

    /** Starts a fresh picture with a round "wipe" animation from the center. */
    fun clear() {
        val current = bitmap ?: return
        clearSnapshot?.recycle()
        clearSnapshot = current.copy(Bitmap.Config.ARGB_8888, false)
        current.eraseColor(PiantColors.PAPER)
        clearStart = SystemClock.uptimeMillis()
        invalidate()
    }

    fun stopAllFingers() {
        for (i in 0 until fingers.size()) {
            val f = fingers.valueAt(i)
            if (f.mode == Mode.PAINT) finishStroke(f)
        }
        fingers.clear()
        cancelGate()
    }

    // ---- Layout ---------------------------------------------------------------------------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return

        val old = bitmap
        val fresh = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        fresh.eraseColor(PiantColors.PAPER)
        bitmap = fresh
        bitmapCanvas = Canvas(fresh)
        if (old != null) {
            drawFitted(old)
            old.recycle()
        }
        pendingRestore?.let {
            drawFitted(it)
            it.recycle()
            pendingRestore = null
        }

        // Palette: one big drop per slot along the bottom edge.
        val margin = 14 * dp
        slotWidth = min((w - 2 * margin) / PiantColors.SLOT_COUNT, 92 * dp)
        dropRadius = min(slotWidth * 0.40f, 34 * dp)
        val totalWidth = slotWidth * PiantColors.SLOT_COUNT
        val left = (w - totalWidth) / 2f
        slotCy = h - margin - 12 * dp - dropRadius
        for (i in 0 until PiantColors.SLOT_COUNT) slotCx[i] = left + slotWidth * (i + 0.5f)
        paletteRect.set(
            left,
            slotCy - dropRadius - 12 * dp,
            left + totalWidth,
            slotCy + dropRadius + 12 * dp,
        )
        rainbowShader = SweepGradient(
            0f, 0f,
            intArrayOf(
                0xFFFF4F8B.toInt(), 0xFFFFC233.toInt(), 0xFF2FD18A.toInt(),
                0xFF3A9BFF.toInt(), 0xFF8E5CF7.toInt(), 0xFFFF4F8B.toInt(),
            ),
            null,
        )

        // Parent gate: small, quiet button in the top-left corner.
        gateCx = margin + gateRadius + 6 * dp
        gateCy = margin + gateRadius + 6 * dp
        buildHeart(heartPath, gateCx, gateCy, gateRadius * 0.95f)

        // Keep edge swipes ("back" gesture) from leaving the app where possible.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val edge = (40 * dp).toInt()
            val half = (100 * dp).toInt()
            val top = h / 2 - half
            val bottom = h / 2 + half
            systemGestureExclusionRects = listOf(
                Rect(0, top, edge, bottom),
                Rect(w - edge, top, w, bottom),
            )
        }
    }

    private fun drawFitted(src: Bitmap) {
        val canvas = bitmapCanvas ?: return
        val dst = bitmap ?: return
        val scale = min(dst.width.toFloat() / src.width, dst.height.toFloat() / src.height)
        val dw = src.width * scale
        val dh = src.height * scale
        val left = (dst.width - dw) / 2f
        val top = (dst.height - dh) / 2f
        canvas.drawBitmap(src, null, RectF(left, top, left + dw, top + dh), bitmapPaint)
    }

    // ---- Touch ----------------------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) cancelGate()
                val i = event.actionIndex
                fingerDown(
                    id = event.getPointerId(i),
                    x = event.getX(i),
                    y = event.getY(i),
                    size = event.getTouchMajor(i),
                    first = event.actionMasked == MotionEvent.ACTION_DOWN,
                )
            }

            MotionEvent.ACTION_MOVE -> {
                for (h in 0 until event.historySize) {
                    for (i in 0 until event.pointerCount) {
                        fingerMove(
                            event.getPointerId(i),
                            event.getHistoricalX(i, h),
                            event.getHistoricalY(i, h),
                            event.getHistoricalTouchMajor(i, h),
                        )
                    }
                }
                for (i in 0 until event.pointerCount) {
                    fingerMove(event.getPointerId(i), event.getX(i), event.getY(i), event.getTouchMajor(i))
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val id = event.getPointerId(event.actionIndex)
                fingers[id]?.let { if (it.mode == Mode.PAINT) finishStroke(it) }
                fingers.remove(id)
                if (id == gatePointerId) cancelGate()
            }

            MotionEvent.ACTION_CANCEL -> stopAllFingers()
        }
        invalidate()
        return true
    }

    private fun fingerDown(id: Int, x: Float, y: Float, size: Float, first: Boolean) {
        // Parent gate: only a single, finger-sized touch can start it.
        val touchSize = if (size > 0f) size else 0f
        if (first && hypot(x - gateCx, y - gateCy) < gateRadius + 14 * dp && touchSize < 48 * dp) {
            fingers.put(id, Finger(Mode.GATE))
            gateActive = true
            gatePointerId = id
            gateStart = SystemClock.uptimeMillis()
            removeCallbacks(gateRunnable)
            postDelayed(gateRunnable, GATE_HOLD_MS)
            return
        }

        // Palette: tapping a drop picks the color.
        if (paletteVisible && paletteRect.contains(x, y)) {
            fingers.put(id, Finger(Mode.IGNORE))
            val slot = ((x - paletteRect.left) / slotWidth).toInt()
                .coerceIn(0, PiantColors.SLOT_COUNT - 1)
            selectedSlot = slot
            slotPicked[slot] = SystemClock.uptimeMillis()
            val color = if (slot == PiantColors.RAINBOW_INDEX) PiantColors.PIA_PINK else PiantColors.PAINTS[slot]
            burst(slotCx[slot], slotCy, color, rainbow = slot == PiantColors.RAINBOW_INDEX, width = dropRadius * 2)
            listener?.onColorPicked(slot)
            haptic()
            return
        }

        val f = Finger(Mode.PAINT)
        f.x = x
        f.y = y
        f.midX = x
        f.midY = y
        f.width = brushWidth(size)
        f.rainbow = selectedSlot == PiantColors.RAINBOW_INDEX
        if (f.rainbow) {
            f.hue = nextHue
            nextHue = (nextHue + 67f) % 360f
            f.color = rainbowColor(f.hue)
        } else {
            f.color = PiantColors.PAINTS[selectedSlot]
        }
        fingers.put(id, f)

        fillPaint.color = f.color
        bitmapCanvas?.drawCircle(x, y, f.width / 2f, fillPaint)
        burst(x, y, f.color, f.rainbow, f.width)
        listener?.onPaintStart(x / max(1, width), y / max(1, height))
        haptic()
    }

    private fun fingerMove(id: Int, x: Float, y: Float, size: Float) {
        val f = fingers[id] ?: return
        when (f.mode) {
            Mode.IGNORE -> return
            Mode.GATE -> {
                if (hypot(x - gateCx, y - gateCy) > gateRadius + 36 * dp) cancelGate()
                return
            }
            Mode.PAINT -> Unit
        }

        val distance = hypot(x - f.x, y - f.y)
        if (distance < 1.5f) return

        val newWidth = f.width + (brushWidth(size) - f.width) * 0.2f
        val newMidX = (f.x + x) / 2f
        val newMidY = (f.y + y) / 2f

        segmentPath.rewind()
        segmentPath.moveTo(f.midX, f.midY)
        segmentPath.quadTo(f.x, f.y, newMidX, newMidY)
        strokePaint.color = f.color
        strokePaint.strokeWidth = (f.width + newWidth) / 2f
        bitmapCanvas?.drawPath(segmentPath, strokePaint)

        if (f.rainbow) {
            f.hue = (f.hue + distance * 0.3f / dp) % 360f
            f.color = rainbowColor(f.hue)
        }
        f.x = x
        f.y = y
        f.midX = newMidX
        f.midY = newMidY
        f.width = newWidth

        // A few little sparkles along longer strokes.
        f.travelled += distance
        if (f.travelled > 110 * dp) {
            f.travelled = 0f
            trailSparkle(x, y, f.color)
        }
    }

    private fun finishStroke(f: Finger) {
        strokePaint.color = f.color
        strokePaint.strokeWidth = f.width
        bitmapCanvas?.drawLine(f.midX, f.midY, f.x, f.y, strokePaint)
    }

    private fun cancelGate() {
        if (gateActive) invalidate()
        gateActive = false
        gatePointerId = -1
        removeCallbacks(gateRunnable)
    }

    private fun brushWidth(touchMajor: Float): Float {
        // Bigger contact (whole finger, palm) = fatter brush. Many devices report 0, then
        // fall back to a chunky default.
        val base = if (touchMajor > 2f) touchMajor * 0.9f else 30 * dp
        return base.coerceIn(22 * dp, 150 * dp)
    }

    private fun rainbowColor(hue: Float): Int {
        hsv[0] = hue
        return Color.HSVToColor(hsv)
    }

    private fun haptic() {
        val now = SystemClock.uptimeMillis()
        if (now - lastHaptic < 70) return
        lastHaptic = now
        listener?.onHaptic()
    }

    // ---- Sparkles -------------------------------------------------------------------------

    private fun burst(x: Float, y: Float, color: Int, rainbow: Boolean, width: Float) {
        val now = SystemClock.uptimeMillis()
        particles += Particle(x, y, 0f, 0f, width / 2f, color, now, 520, ring = true)
        val count = 8
        val offset = Random.nextFloat() * 2f * PI.toFloat()
        for (i in 0 until count) {
            val angle = offset + i * (2f * PI.toFloat() / count)
            val speed = (140 + Random.nextInt(200)) * dp
            val c = if (rainbow) rainbowColor((i * 360f / count + Random.nextFloat() * 30f) % 360f) else color
            particles += Particle(
                x + cos(angle) * width * 0.4f,
                y + sin(angle) * width * 0.4f,
                cos(angle) * speed,
                sin(angle) * speed,
                (4 + Random.nextInt(6)) * dp,
                c,
                now,
                (520 + Random.nextInt(260)).toLong(),
                ring = false,
            )
        }
        trimParticles()
    }

    private fun trailSparkle(x: Float, y: Float, color: Int) {
        val now = SystemClock.uptimeMillis()
        repeat(3) {
            val angle = Random.nextFloat() * 2f * PI.toFloat()
            val speed = (60 + Random.nextInt(90)) * dp
            particles += Particle(x, y, cos(angle) * speed, sin(angle) * speed, (3 + Random.nextInt(4)) * dp, color, now, 480, ring = false)
        }
        trimParticles()
    }

    private fun trimParticles() {
        val excess = particles.size - MAX_PARTICLES
        if (excess > 0) particles.subList(0, excess).clear()
    }

    // ---- Drawing --------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        var animating = false

        canvas.drawColor(PiantColors.PAPER)
        val bmp = bitmap
        val snapshot = clearSnapshot
        if (bmp != null && snapshot != null) {
            val p = ((now - clearStart) / CLEAR_MS.toFloat()).coerceIn(0f, 1f)
            val eased = 1f - (1f - p) * (1f - p) * (1f - p)
            canvas.drawBitmap(snapshot, 0f, 0f, bitmapPaint)
            clearPath.rewind()
            clearPath.addCircle(width / 2f, height / 2f, eased * hypot(width.toFloat(), height.toFloat()) / 2f + 1f, Path.Direction.CW)
            canvas.save()
            canvas.clipPath(clearPath)
            canvas.drawBitmap(bmp, 0f, 0f, bitmapPaint)
            canvas.restore()
            if (p >= 1f) {
                snapshot.recycle()
                clearSnapshot = null
            } else {
                animating = true
            }
        } else if (bmp != null) {
            canvas.drawBitmap(bmp, 0f, 0f, bitmapPaint)
        }

        if (drawParticles(canvas, now)) animating = true
        if (paletteVisible && drawPalette(canvas, now)) animating = true
        drawGate(canvas, now)
        if (gateActive) animating = true

        if (animating) postInvalidateOnAnimation()
    }

    private fun drawParticles(canvas: Canvas, now: Long): Boolean {
        val it = particles.iterator()
        while (it.hasNext()) {
            val p = it.next()
            val t = (now - p.born).toFloat() / p.life
            if (t >= 1f) {
                it.remove()
                continue
            }
            val alpha = ((1f - t) * 255).toInt()
            if (p.ring) {
                val eased = 1f - (1f - t) * (1f - t)
                particlePaint.style = Paint.Style.STROKE
                particlePaint.strokeWidth = 7 * dp * (1f - t)
                particlePaint.color = p.color
                particlePaint.alpha = (alpha * 0.8f).toInt()
                canvas.drawCircle(p.x, p.y, p.radius + 46 * dp * eased, particlePaint)
            } else {
                val sec = (now - p.born) / 1000f
                val drag = 1f - t * 0.55f
                particlePaint.style = Paint.Style.FILL
                particlePaint.color = p.color
                particlePaint.alpha = alpha
                canvas.drawCircle(p.x + p.vx * sec * drag, p.y + p.vy * sec * drag, p.radius * (1f - t * 0.6f), particlePaint)
            }
        }
        return particles.isNotEmpty()
    }

    private fun drawPalette(canvas: Canvas, now: Long): Boolean {
        var animating = false
        val corner = paletteRect.height() / 2f

        // Soft shadow + frosted pill
        palettePaint.shader = null
        palettePaint.style = Paint.Style.FILL
        palettePaint.color = 0x14000000
        canvas.drawRoundRect(
            paletteRect.left, paletteRect.top + 4 * dp, paletteRect.right, paletteRect.bottom + 4 * dp,
            corner, corner, palettePaint,
        )
        palettePaint.color = 0xE6FFFFFF.toInt()
        canvas.drawRoundRect(paletteRect, corner, corner, palettePaint)

        for (i in 0 until PiantColors.SLOT_COUNT) {
            val target = if (i == selectedSlot) 1.18f else 1f
            slotScale[i] += (target - slotScale[i]) * 0.22f
            if (kotlin.math.abs(target - slotScale[i]) > 0.005f) animating = true
            val sincePick = now - slotPicked[i]
            val bounce = if (sincePick in 0 until BOUNCE_MS) {
                animating = true
                sin(sincePick / BOUNCE_MS.toFloat() * PI.toFloat()) * 0.22f
            } else {
                0f
            }
            val scale = slotScale[i] + bounce

            canvas.save()
            canvas.translate(slotCx[i], slotCy)
            canvas.scale(scale, scale)

            val color = if (i == PiantColors.RAINBOW_INDEX) PiantColors.PIA_PINK else PiantColors.PAINTS[i]
            if (i == selectedSlot) {
                palettePaint.shader = null
                palettePaint.color = color
                palettePaint.alpha = 70
                canvas.drawCircle(0f, 0f, dropRadius + 7 * dp, palettePaint)
            }

            if (i == PiantColors.RAINBOW_INDEX) {
                palettePaint.shader = rainbowShader
                palettePaint.alpha = 255
                canvas.save()
                canvas.rotate((now / 12L % 360L).toFloat())
                canvas.drawCircle(0f, 0f, dropRadius, palettePaint)
                canvas.restore()
                palettePaint.shader = null
                animating = true
            } else {
                palettePaint.color = color
                canvas.drawCircle(0f, 0f, dropRadius, palettePaint)
            }

            // Glossy highlight makes the drops look like little paint blobs.
            palettePaint.color = 0x59FFFFFF
            canvas.drawCircle(-dropRadius * 0.34f, -dropRadius * 0.34f, dropRadius * 0.26f, palettePaint)
            canvas.restore()
        }
        return animating
    }

    private fun drawGate(canvas: Canvas, now: Long) {
        gatePaint.style = Paint.Style.FILL
        gatePaint.color = 0x8CFFFFFF.toInt()
        canvas.drawCircle(gateCx, gateCy, gateRadius, gatePaint)
        gatePaint.color = PiantColors.PIA_PINK
        gatePaint.alpha = if (gateActive) 230 else 90
        canvas.drawPath(heartPath, gatePaint)

        if (gateActive) {
            val p = ((now - gateStart) / GATE_HOLD_MS.toFloat()).coerceIn(0f, 1f)
            gatePaint.style = Paint.Style.STROKE
            gatePaint.strokeCap = Paint.Cap.ROUND
            gatePaint.strokeWidth = 5 * dp
            gatePaint.color = PiantColors.PIA_PINK
            val r = gateRadius + 8 * dp
            gateArc.set(gateCx - r, gateCy - r, gateCx + r, gateCy + r)
            canvas.drawArc(gateArc, -90f, 360f * p, false, gatePaint)
        }
    }

    /** Heart centered on (cx, cy), roughly [size] wide. Same shape as in the app icon. */
    private fun buildHeart(path: Path, cx: Float, cy: Float, size: Float) {
        val s = size / 22f
        fun px(v: Float) = cx + (v - 54f) * s
        fun py(v: Float) = cy + (v - 55f) * s
        path.rewind()
        path.moveTo(px(54f), py(65f))
        path.cubicTo(px(46f), py(59.5f), px(43f), py(55.5f), px(43f), py(51.2f))
        path.cubicTo(px(43f), py(47.9f), px(45.5f), py(45.5f), px(48.6f), py(45.5f))
        path.cubicTo(px(50.8f), py(45.5f), px(52.8f), py(46.8f), px(54f), py(48.8f))
        path.cubicTo(px(55.2f), py(46.8f), px(57.2f), py(45.5f), px(59.4f), py(45.5f))
        path.cubicTo(px(62.5f), py(45.5f), px(65f), py(47.9f), px(65f), py(51.2f))
        path.cubicTo(px(65f), py(55.5f), px(62f), py(59.5f), px(54f), py(65f))
        path.close()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(gateRunnable)
        super.onDetachedFromWindow()
    }

    companion object {
        const val GATE_HOLD_MS = 2000L
        private const val CLEAR_MS = 550L
        private const val BOUNCE_MS = 380L
        private const val MAX_PARTICLES = 400
    }
}
