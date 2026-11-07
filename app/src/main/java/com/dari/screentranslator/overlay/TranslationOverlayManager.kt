package com.dari.screentranslator.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.dari.screentranslator.R
import com.dari.screentranslator.model.TranslatedBox
import com.dari.screentranslator.util.FontCache

/**
 * Draws white RTL boxes with the Persian translation directly over the
 * original text. The layer is not touchable, so the app below keeps working.
 * All child views are removed on hide to release their memory.
 */
class TranslationOverlayManager(
    private val context: Context,
    private val windowManager: WindowManager
) {

    private val handler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { hide() }
    private var layer: FrameLayout? = null

    val isShowing: Boolean
        get() = layer != null

    fun show(boxes: List<TranslatedBox>, autoHideMs: Long) {
        handler.removeCallbacks(hideRunnable)
        hide()

        val metrics = context.resources.displayMetrics
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels
        val density = metrics.density
        val paddingH = (6 * density).toInt()
        val paddingV = (3 * density).toInt()
        val minBoxWidth = (56 * density).toInt()
        val font: Typeface? = FontCache.regular(context)

        val newLayer = FrameLayout(context).apply {
            // Force LTR on the container so left/top margins are physical
            // coordinates matching the screen bitmap / accessibility bounds.
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            clipChildren = true
        }

        var added = 0
        for (box in boxes) {
            if (added >= MAX_BOXES) break
            if (box.translatedText.isBlank()) continue

            val width = box.bounds.width().coerceIn(minBoxWidth, screenWidth)
            val left = box.bounds.left.coerceIn(0, (screenWidth - width).coerceAtLeast(0))
            val top = box.bounds.top.coerceIn(0, (screenHeight - (12 * density).toInt()).coerceAtLeast(0))

            val textView = TextView(context).apply {
                text = box.translatedText
                setTextColor(TEXT_COLOR)
                background = ContextCompat.getDrawable(context, R.drawable.overlay_box_bg)
                gravity = Gravity.CENTER_VERTICAL
                layoutDirection = View.LAYOUT_DIRECTION_RTL
                textDirection = View.TEXT_DIRECTION_FIRSTSTRONG
                textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
                includeFontPadding = false
                setPadding(paddingH, paddingV, paddingH, paddingV)
                setAutoSizeTextTypeUniformWithConfiguration(9, 18, 1, TypedValue.COMPLEX_UNIT_SP)
                if (font != null) typeface = font
            }

            val childParams = FrameLayout.LayoutParams(
                width,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START
            ).apply {
                leftMargin = left
                topMargin = top
            }
            newLayer.addView(textView, childParams)
            added++
        }
        if (newLayer.childCount == 0) return

        val windowParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                // PRIVACY: translated text is excluded from screenshots and
                // screen recordings made by any other app, and from this
                // app's own OCR captures (no re-translating our own boxes).
                or WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Draw across the camera cutout so window coordinates equal
            // physical screen coordinates (API 28+).
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }

        try {
            windowManager.addView(newLayer, windowParams)
            layer = newLayer
            // Accessibility/OCR bounds are in physical screen space. If the
            // window manager still placed this layer below the status bar,
            // measure the real offset and shift every box by it.
            newLayer.post {
                val offset = IntArray(2)
                newLayer.getLocationOnScreen(offset)
                if (offset[0] != 0 || offset[1] != 0) {
                    for (i in 0 until newLayer.childCount) {
                        val child = newLayer.getChildAt(i)
                        val lp = child.layoutParams as FrameLayout.LayoutParams
                        lp.leftMargin -= offset[0]
                        lp.topMargin -= offset[1]
                        child.layoutParams = lp
                    }
                }
            }
        } catch (t: Throwable) {
            // Overlay permission revoked or the window could not be added.
            newLayer.removeAllViews()
        }

        if (autoHideMs > 0 && layer != null) {
            handler.postDelayed(hideRunnable, autoHideMs)
        }
    }

    fun hide() {
        handler.removeCallbacks(hideRunnable)
        layer?.let { current ->
            try {
                windowManager.removeView(current)
            } catch (t: Throwable) {
                // Already detached.
            }
            current.removeAllViews()
        }
        layer = null
    }

    fun release() {
        hide()
    }

    private companion object {
        const val MAX_BOXES = 80
        val TEXT_COLOR = Color.parseColor("#1A1A1A")
    }
}
