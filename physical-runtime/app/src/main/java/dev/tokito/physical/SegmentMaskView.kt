package dev.tokito.physical

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.view.View
import dev.tokito.physical.dialogue.LocalSegmentationRuntime

/** Image-frame raw class predictions, never registered design guidance. */
class SegmentMaskView(context: Context) : View(context) {
    private var result: LocalSegmentationRuntime.Result? = null
    private var transform = Matrix()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clearExpired = Runnable { clear() }
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO; isClickable = false }
    fun show(next: LocalSegmentationRuntime.Result, imageToPreview: Matrix, sourceTimestampNanos: Long) {
        if (next.count == 0 && result != null) {
            // A single detector miss must not flash the overlay off. Do not refresh
            // the old result's expiry: it still disappears at its original deadline.
            next.bitmap.recycle()
            return
        }
        removeCallbacks(clearExpired)
        val previous = result
        result = next
        transform.setConcat(imageToPreview, next.maskToImage)
        previous?.bitmap?.recycle()
        invalidate()
        // Replace atomically, retaining presentation between admitted predictions.
        // Expiry is bounded by source age, not a fresh lifetime for delayed inference.
        val ageMillis = ((System.nanoTime() - sourceTimestampNanos) / 1_000_000).coerceAtLeast(0)
        postDelayed(clearExpired, (3000L - ageMillis).coerceAtLeast(0))
    }
    fun clear() {
        removeCallbacks(clearExpired)
        result?.bitmap?.recycle()
        result = null
        invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val prediction = result ?: return
        paint.style = Paint.Style.FILL
        paint.alpha = 255
        canvas.drawBitmap(prediction.bitmap, transform, paint)
        val density = resources.displayMetrics.density
        val font = 12f * resources.displayMetrics.scaledDensity
        val placedTags = mutableListOf<android.graphics.RectF>()
        prediction.boxes.forEach { box ->
            val points = floatArrayOf(box.left,box.top,box.right,box.top,box.right,box.bottom,box.left,box.bottom)
            transform.mapPoints(points)
            val path = android.graphics.Path().apply {
                moveTo(points[0],points[1]); for (i in 1..3) lineTo(points[i*2],points[i*2+1]); close()
            }
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f*density
            paint.color = context.getColor(R.color.tokito_primary)
            canvas.drawPath(path,paint)
            val text = "${box.label} ${"%.0f".format(java.util.Locale.ROOT,box.score*100)}%"
            paint.textSize = font
            val padding = 4f*density
            val measured = paint.measureText(text) + padding*2
            val tagWidth = measured.coerceAtMost(width.toFloat())
            val left = points.filterIndexed { i,_ -> i%2 == 0 }.min().coerceIn(0f,(width-tagWidth).coerceAtLeast(0f))
            val topY = points.filterIndexed { i,_ -> i%2 == 1 }.min()
            val bottomY = points.filterIndexed { i,_ -> i%2 == 1 }.max()
            val tagHeight = font + padding*2
            val candidates = (0..prediction.boxes.size).flatMap { row ->
                listOf(topY-tagHeight-row*tagHeight, bottomY+row*tagHeight)
            }.map { top -> android.graphics.RectF(left,top.coerceIn(0f,(height-tagHeight).coerceAtLeast(0f)),
                left+tagWidth,top.coerceIn(0f,(height-tagHeight).coerceAtLeast(0f))+tagHeight) }
            val tag = candidates.firstOrNull { candidate -> placedTags.none { android.graphics.RectF.intersects(candidate,it) } }
                ?: candidates.minBy { candidate -> placedTags.sumOf { prior ->
                    (kotlin.math.min(candidate.right,prior.right)-kotlin.math.max(candidate.left,prior.left)).coerceAtLeast(0f).toDouble() *
                        (kotlin.math.min(candidate.bottom,prior.bottom)-kotlin.math.max(candidate.top,prior.top)).coerceAtLeast(0f)
                } }
            placedTags += tag
            val baseline = tag.top + font + padding
            paint.style = Paint.Style.FILL; paint.color = context.getColor(R.color.tokito_background)
            canvas.drawRect(tag,paint)
            paint.color = context.getColor(R.color.tokito_text)
            canvas.save(); canvas.clipRect(left,baseline-font-padding,left+tagWidth,baseline+padding)
            canvas.drawText(text,left+padding,baseline,paint); canvas.restore()
        }
    }
    override fun onDetachedFromWindow() { clear(); super.onDetachedFromWindow() }
}
