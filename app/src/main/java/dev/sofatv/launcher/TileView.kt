package dev.sofatv.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.text.TextUtils
import android.view.View

/** App or TV input tile: a single View with no children, everything is drawn in onDraw. */
class TileView(context: Context) : View(context) {
    var app: AppEntry? = null

    /** A TV input tile: its icon and label are drawn instead of an app image. */
    private var input: TvInputs.Input? = null
    private var inputIcon: Drawable? = null

    var moving = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private var bitmap: Bitmap? = null
    private val radius = resources.getDimension(R.dimen.tile_radius)
    private val stroke = resources.getDimension(R.dimen.tile_focus_stroke)
    private val rect = RectF()

    /** Filtering only matters for the scaled-up focused tile; otherwise it is a 1:1 copy. */
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val placeholderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.tile)
    }
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.text_secondary)
        textSize = resources.getDimension(R.dimen.tile_label)
        textAlign = Paint.Align.CENTER
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
    }
    private val focusColor = context.getColor(R.color.focus)
    private val movingColor = context.getColor(R.color.moving)
    private val inputColor = context.getColor(R.color.text_primary)
    private val baseIconSize = resources.getDimensionPixelSize(R.dimen.tile_icon)
    private val baseHeight = resources.getDimensionPixelSize(R.dimen.tile_height)

    init {
        isFocusable = true
        isClickable = true
        isLongClickable = true
    }

    fun setInput(value: TvInputs.Input?) {
        if (input?.id == value?.id && input?.label == value?.label) return
        input = value
        inputIcon = value?.let { context.getDrawable(it.icon)?.mutate()?.apply { setTint(inputColor) } }
        invalidate()
    }

    fun setBitmap(value: Bitmap?) {
        if (bitmap === value) return
        bitmap = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        val bitmap = bitmap
        if (bitmap != null) {
            // Corners are already rounded in the bitmap itself (TileCache), so just copy the pixels.
            canvas.drawBitmap(bitmap, 0f, 0f, bitmapPaint)
        } else {
            canvas.drawRoundRect(rect, radius, radius, placeholderPaint)
            val input = input
            if (input != null) drawInput(canvas, input) else app?.let { drawLabel(canvas, it.label, height / 2f) }
        }
        if (isFocused || moving) {
            borderPaint.color = if (moving) movingColor else focusColor
            rect.inset(stroke / 2, stroke / 2)
            canvas.drawRoundRect(rect, radius, radius, borderPaint)
        }
    }

    /** Label centered on [centerY]. */
    private fun drawLabel(canvas: Canvas, label: String, centerY: Float) {
        val text = TextUtils.ellipsize(label, labelPaint, width * 0.85f, TextUtils.TruncateAt.END)
        val y = centerY - (labelPaint.ascent() + labelPaint.descent()) / 2
        canvas.drawText(text, 0, text.length, width / 2f, y, labelPaint)
    }

    /** Icon above the label, the pair centered vertically, like an app tile without a banner. */
    private fun drawInput(canvas: Canvas, input: TvInputs.Input) {
        val iconSize = (baseIconSize * minOf(1f, height.toFloat() / baseHeight)).toInt()
        val labelHeight = labelPaint.fontSpacing
        val top = ((height - iconSize - labelHeight) / 2).toInt()
        val left = (width - iconSize) / 2
        inputIcon?.apply {
            setBounds(left, top, left + iconSize, top + iconSize)
            draw(canvas)
        }
        drawLabel(canvas, input.label, top + iconSize + labelHeight / 2)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        val scale = if (gainFocus) FOCUS_SCALE else 1f
        animate().scaleX(scale).scaleY(scale).setDuration(FOCUS_ANIMATION_MS).start()
    }

    private companion object {
        const val FOCUS_SCALE = 1.08f
        const val FOCUS_ANIMATION_MS = 90L
    }
}
