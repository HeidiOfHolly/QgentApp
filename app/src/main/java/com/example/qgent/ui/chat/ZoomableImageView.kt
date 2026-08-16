package com.example.qgent.ui.chat

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Matrix
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.min

/**
 * 支持捏合缩放、双击放大/还原、缩放后平移的图片预览 View。
 * scaleType 固定 MATRIX，图片先按 fitCenter 铺满，再允许放大到 base 的 5 倍。
 */
class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatImageView(context, attrs) {

    var onSingleTap: (() -> Unit)? = null

    private val matrix = Matrix()
    private val startMatrix = Matrix()
    private val values = FloatArray(9)

    private var minScale = 1f
    private var maxScale = 5f

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                startMatrix.set(matrix)
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val cur = currentScale()
                val target = (cur * detector.scaleFactor).coerceIn(minScale, maxScale)
                matrix.set(startMatrix)
                matrix.postScale(target / cur, target / cur, detector.focusX, detector.focusY)
                clampTranslation()
                imageMatrix = matrix
                return true
            }
        }
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val target = if (currentScale() > minScale * 1.01f) minScale else minScale * 2.5f
                animateScale(target, e.x, e.y)
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                onSingleTap?.invoke()
                return true
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                if (currentScale() > minScale * 1.01f) {
                    matrix.postTranslate(-distanceX, -distanceY)
                    clampTranslation()
                    imageMatrix = matrix
                    return true
                }
                return false
            }
        }
    )

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (!scaleDetector.isInProgress) {
            gestureDetector.onTouchEvent(event)
        }
        return true
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        matrix.reset()
        post { applyBaseMatrix() }
    }

    private fun applyBaseMatrix() {
        val d = drawable ?: return
        val dw = d.intrinsicWidth.toFloat()
        val dh = d.intrinsicHeight.toFloat()
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (dw <= 0f || dh <= 0f || vw <= 0f || vh <= 0f) return
        val scale = min(vw / dw, vh / dh)
        matrix.setScale(scale, scale)
        matrix.postTranslate((vw - dw * scale) / 2f, (vh - dh * scale) / 2f)
        minScale = scale
        maxScale = scale * 5f
        imageMatrix = matrix
    }

    private fun currentScale(): Float {
        matrix.getValues(values)
        return values[Matrix.MSCALE_X]
    }

    private fun animateScale(target: Float, fx: Float, fy: Float) {
        val from = currentScale()
        if (from == target) return
        val start = Matrix(matrix)
        ValueAnimator.ofFloat(from, target).apply {
            duration = 200
            addUpdateListener { va ->
                val s = va.animatedValue as Float
                matrix.set(start)
                matrix.postScale(s / from, s / from, fx, fy)
                clampTranslation()
                imageMatrix = matrix
            }
            start()
        }
    }

    private fun clampTranslation() {
        val s = currentScale()
        if (s <= minScale * 1.01f) {
            applyBaseMatrix()
            return
        }
        val d = drawable ?: return
        val dw = d.intrinsicWidth * s
        val dh = d.intrinsicHeight * s
        val vw = width.toFloat()
        val vh = height.toFloat()
        matrix.getValues(values)
        val minTx = if (dw > vw) vw - dw else (vw - dw) / 2f
        val maxTx = if (dw > vw) 0f else (vw - dw) / 2f
        val minTy = if (dh > vh) vh - dh else (vh - dh) / 2f
        val maxTy = if (dh > vh) 0f else (vh - dh) / 2f
        values[Matrix.MTRANS_X] = values[Matrix.MTRANS_X].coerceIn(minTx, maxTx)
        values[Matrix.MTRANS_Y] = values[Matrix.MTRANS_Y].coerceIn(minTy, maxTy)
        matrix.setValues(values)
    }
}
