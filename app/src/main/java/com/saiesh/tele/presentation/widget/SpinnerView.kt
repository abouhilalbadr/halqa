package com.saiesh.tele.presentation.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import com.saiesh.tele.R
import kotlin.math.min
import kotlin.math.sin

/**
 * A loading spinner that turns even with the TV's animations switched off. Android's own ProgressBar
 * follows the system animation scale, which is 0 on this TV, so it freezes into a still arc.
 * This one works out its angle from the clock on every frame instead.
 */
class SpinnerView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : View(context, attrs) {
        private val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
            }
        private val bounds = RectF()

        init {
            val values = context.obtainStyledAttributes(attrs, R.styleable.SpinnerView)
            paint.color = values.getColor(R.styleable.SpinnerView_spinnerColor, DEFAULT_COLOR)
            values.recycle()
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val size = min(width - paddingLeft - paddingRight, height - paddingTop - paddingBottom).toFloat()
            if (size <= 0f) return
            val stroke = size * STROKE_RATIO
            paint.strokeWidth = stroke
            val left = paddingLeft + (width - paddingLeft - paddingRight - size) / 2f + stroke / 2f
            val top = paddingTop + (height - paddingTop - paddingBottom - size) / 2f + stroke / 2f
            bounds.set(left, top, left + size - stroke, top + size - stroke)

            val now = SystemClock.uptimeMillis()
            val turn = (now % TURN_MS) / TURN_MS.toFloat() * 360f
            // The arc grows and shrinks a little as it turns, like the system spinner.
            val sweep = MIN_SWEEP + (MAX_SWEEP - MIN_SWEEP) * (0.5f + 0.5f * sin(now / BREATH_MS.toDouble() * 2 * Math.PI).toFloat())
            canvas.drawArc(bounds, turn, sweep, false, paint)
            if (isShown) postInvalidateOnAnimation()
        }

        override fun onVisibilityChanged(
            changedView: View,
            visibility: Int,
        ) {
            super.onVisibilityChanged(changedView, visibility)
            if (isShown) postInvalidateOnAnimation()
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            postInvalidateOnAnimation()
        }

        private companion object {
            const val DEFAULT_COLOR = 0xFFF26419.toInt()
            const val STROKE_RATIO = 0.1f
            const val TURN_MS = 900L
            const val BREATH_MS = 1400L
            const val MIN_SWEEP = 40f
            const val MAX_SWEEP = 270f
        }
    }
