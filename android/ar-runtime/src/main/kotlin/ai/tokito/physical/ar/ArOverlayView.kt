package ai.tokito.physical.ar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.text.TextUtils
import android.text.TextPaint
import android.view.View
import android.view.MotionEvent
import kotlin.math.max
import kotlin.math.hypot

class ArOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(14f)
        typeface = android.graphics.Typeface.create("sans", android.graphics.Typeface.BOLD)
    }
    private var renderScene: RenderScene? = null
    private var boardTapListener: ((Point2) -> Unit)? = null
    private var pendingTap: Point2? = null

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
        contentDescription = "Tokito augmented guidance overlay"
    }

    fun render(scene: RenderScene?) {
        renderScene = scene
        contentDescription = scene?.guidance?.let { guidance ->
            buildString {
                append(guidance.title)
                if (guidance.detail.isNotBlank()) append(". ${guidance.detail}")
                guidance.step?.let { append(". Step $it of ${guidance.stepCount}") }
                guidance.abstentionReason?.let { append(". Guidance unavailable: $it") }
            }
        } ?: "Tokito augmented guidance unavailable"
        invalidate()
    }

    fun setOnBoardTapListener(listener: ((Point2) -> Unit)?) {
        boardTapListener = listener
        isClickable = listener != null
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (boardTapListener == null) return false
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> true
            MotionEvent.ACTION_UP -> {
                pendingTap = Point2(event.x.toDouble(), event.y.toDouble())
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> {
                pendingTap = null
                false
            }
            else -> true
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        val point = pendingTap ?: return false
        pendingTap = null
        boardTapListener?.invoke(point)
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scene = renderScene ?: return
        scene.primitives.forEach { drawPrimitive(canvas, it) }
    }

    private fun drawPrimitive(canvas: Canvas, rendered: RenderPrimitive) {
        val primitive = rendered.source
        val color = primitive.tone.color()
        linePaint.color = color.withAlpha(85)
        fillPaint.color = color.withAlpha(14)
        when (primitive) {
            is OverlayPrimitive.Outline, is OverlayPrimitive.Region -> {
                val path = Path()
                rendered.points.forEachIndexed { index, point ->
                    if (index == 0) path.moveTo(point.x.toFloat(), point.y.toFloat())
                    else path.lineTo(point.x.toFloat(), point.y.toFloat())
                }
                if (primitive is OverlayPrimitive.Region || (primitive as OverlayPrimitive.Outline).closed) path.close()
                canvas.drawPath(path, fillPaint)
                canvas.drawPath(path, linePaint)
                if (primitive is OverlayPrimitive.Region) {
                    rendered.points.firstOrNull()?.let { drawLabel(canvas, primitive.label, it, color) }
                }
            }
            is OverlayPrimitive.Target -> {
                val center = rendered.points[0]
                val radiusPoint = rendered.points[1]
                val radius = max(dp(18f).toDouble(), hypot(radiusPoint.x - center.x, radiusPoint.y - center.y))
                canvas.drawCircle(center.x.toFloat(), center.y.toFloat(), radius.toFloat(), fillPaint)
                canvas.drawCircle(center.x.toFloat(), center.y.toFloat(), radius.toFloat(), linePaint)
                canvas.drawCircle(center.x.toFloat(), center.y.toFloat(), dp(4f), linePaint)
                rendered.points.getOrNull(2)?.let { direction ->
                    val dx = direction.x - center.x
                    val dy = direction.y - center.y
                    val magnitude = hypot(dx, dy)
                    if (magnitude > 1e-6) {
                        canvas.drawLine(
                            center.x.toFloat(), center.y.toFloat(),
                            (center.x + dx / magnitude * radius * 1.7).toFloat(),
                            (center.y + dy / magnitude * radius * 1.7).toFloat(),
                            linePaint,
                        )
                    }
                }
                drawLabel(canvas, primitive.label, Point2(center.x + radius, center.y - radius), color)
            }
            is OverlayPrimitive.Anchor -> {
                val point = rendered.points.single()
                canvas.drawCircle(point.x.toFloat(), point.y.toFloat(), dp(7f), fillPaint)
                canvas.drawCircle(point.x.toFloat(), point.y.toFloat(), dp(7f), linePaint)
                drawLabel(canvas, primitive.label, point, color)
            }
        }
        if (rendered.uncertaintyPx >= 12.0) {
            rendered.points.firstOrNull()?.let { drawLabel(canvas, "±${rendered.uncertaintyPx.toInt()} px", Point2(it.x, it.y + dp(28f)), Color.rgb(255, 184, 77)) }
        }
    }

    private fun drawLabel(canvas: Canvas, label: String?, point: Point2, color: Int) {
        if (label.isNullOrBlank()) return
        val padding = dp(8f)
        val maxTextWidth = max(dp(72f), width - dp(32f) - padding * 2)
        val display = TextUtils.ellipsize(label, textPaint, maxTextWidth, TextUtils.TruncateAt.END).toString()
        val labelWidth = textPaint.measureText(display) + padding * 2
        val left = point.x.toFloat().coerceIn(dp(8f), max(dp(8f), width - labelWidth - dp(8f)))
        val bottom = point.y.toFloat().coerceIn(textPaint.textSize + padding * 2, height - dp(8f))
        val top = bottom - textPaint.textSize - padding * 2
        val rect = RectF(left, top, left + labelWidth, bottom)
        fillPaint.color = Color.argb(224, 9, 12, 18)
        canvas.drawRoundRect(rect, dp(7f), dp(7f), fillPaint)
        linePaint.color = color
        linePaint.strokeWidth = dp(1f)
        canvas.drawRoundRect(rect, dp(7f), dp(7f), linePaint)
        linePaint.strokeWidth = dp(3f)
        canvas.drawText(display, rect.left + padding, rect.bottom - padding, textPaint)
    }

    private fun OverlayTone.color(): Int = when (this) {
        OverlayTone.Primary -> Color.rgb(25, 211, 174)
        OverlayTone.Success -> Color.rgb(80, 220, 132)
        OverlayTone.Warning -> Color.rgb(255, 184, 77)
        OverlayTone.Danger -> Color.rgb(255, 92, 112)
        OverlayTone.Muted -> Color.rgb(156, 168, 189)
    }

    private fun Int.withAlpha(alpha: Int): Int = Color.argb(alpha, Color.red(this), Color.green(this), Color.blue(this))
    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
