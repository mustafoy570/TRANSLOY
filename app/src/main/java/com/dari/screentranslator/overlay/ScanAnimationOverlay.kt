package com.dari.screentranslator.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator

/**
 * THE SCAN ANIMATION (v1.5.0) — the effect users loved most.
 *
 * When a translation starts, a teal laser line with a soft glow sweeps from
 * the top of the screen to the bottom in ~680 ms, like a photocopier reading
 * the page. It says "I am reading this screen right now" far better than a
 * toast, and it makes the wait feel instant.
 *
 * Behaviour contract:
 *  - FLAG_NOT_TOUCHABLE + FLAG_NOT_FOCUSABLE: the user can keep tapping the
 *    app underneath while the laser sweeps — the animation never blocks.
 *  - FLAG_SECURE: like every window of this app, invisible to other apps'
 *    screenshots and recordings, and ignored by our own OCR capture.
 *  - Removes itself automatically when the sweep ends (or on [stop]).
 */
class ScanAnimationOverlay(
    private val context: Context,
    private val windowManager: WindowManager
) {

    private var laserView: LaserView? = null
    private var animator: ValueAnimator? = null

    val isPlaying: Boolean
        get() = laserView != null

    /**
     * Plays one top→bottom laser sweep. Safe to call repeatedly: a sweep in
     * progress is cancelled and restarted.
     */
    fun playScanAnimation(
        durationMs: Long = DEFAULT_DURATION_MS,
        onFinished: (() -> Unit)? = null
    ) {
        stop()
        val view = LaserView(context)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                // PRIVACY: never visible in other apps' screenshots or
                // recordings, and never captured by our own OCR.
                or WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.TRANSLUCENT
        )
        try {
            windowManager.addView(view, params)
        } catch (t: Throwable) {
            // Overlay permission revoked — the animation is cosmetic, skip it.
            return
        }
        laserView = view

        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { valueAnimator ->
                val progress = valueAnimator.animatedValue as Float
                view.progress = progress
                view.invalidate()
            }
            addListener(object : android.animation.Animator.AnimatorListener {
                override fun onAnimationStart(animation: android.animation.Animator) = Unit
                override fun onAnimationRepeat(animation: android.animation.Animator) = Unit
                override fun onAnimationCancel(animation: android.animation.Animator) = Unit
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    stop()
                    onFinished?.invoke()
                }
            })
        }
        anim.start()
        animator = anim
    }

    /** Removes the animation window immediately (service stop, cleanup). */
    fun stop() {
        animator?.cancel()
        animator = null
        laserView?.let { current ->
            try {
                windowManager.removeView(current)
            } catch (t: Throwable) {
                // Already detached.
            }
        }
        laserView = null
    }

    /** The laser itself: a glowing teal line with a fading trail above it. */
    private class LaserView(context: Context) : View(context) {

        var progress: Float = 0f

        private val density = resources.displayMetrics.density
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_LINE
            strokeWidth = 3f * density
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            setShadowLayer(10f * density, 0f, 0f, COLOR_GLOW)
        }
        private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(canvas: Canvas) {
            if (progress <= 0f || progress >= 1f) return
            val width = width.toFloat()
            val y = progress * height.toFloat()

            // Fade out in the last 8% so the laser melts away, not stops.
            val fade = if (progress > 0.92f) 1f - (progress - 0.92f) / 0.08f else 1f
            val sideInset = 8f * density

            // Trail: gradient band above the line (already scanned area).
            val trailTop = (y - TRAIL_HEIGHT_DP * density).coerceAtLeast(0f)
            trailPaint.shader = LinearGradient(
                0f, trailTop, 0f, y,
                COLOR_TRAIL_START,
                COLOR_TRAIL_END,
                Shader.TileMode.CLAMP
            )
            trailPaint.alpha = (255 * 0.45f * fade).toInt().coerceIn(0, 255)
            canvas.drawRect(sideInset, trailTop, width - sideInset, y, trailPaint)

            // The laser line itself.
            linePaint.alpha = (255 * fade).toInt().coerceIn(0, 255)
            canvas.drawLine(sideInset, y, width - sideInset, y, linePaint)
        }
    }

    private companion object {
        const val DEFAULT_DURATION_MS = 680L
        const val TRAIL_HEIGHT_DP = 120f
        val COLOR_LINE = Color.parseColor("#FF26E6D0")
        val COLOR_GLOW = Color.parseColor("#B326E6D0")
        val COLOR_TRAIL_START = Color.parseColor("#0026E6D0")
        val COLOR_TRAIL_END = Color.parseColor("#6626E6D0")
    }
}
