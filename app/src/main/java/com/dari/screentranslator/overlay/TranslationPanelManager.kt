package com.dari.screentranslator.overlay

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.dari.screentranslator.R
import com.dari.screentranslator.model.TranslatedBox
import com.dari.screentranslator.util.FontCache

/**
 * The translation result panel (v1.5) — replaces the old per-box overlays.
 *
 * Why the change: the old white boxes covered the original content, showed
 * only the translation (the original disappeared below them) and cut every
 * translation to three lines, which broke long Dari sentences. User rating:
 * 20/100.
 *
 * The panel instead behaves like a bottom sheet, and v1.5.0 restyles it as
 * DARK FROSTED GLASS so it reads comfortably over both light and dark apps
 * (the chameleon complaint: white-on-white was invisible, black-on-black
 * glared). One charcoal card pinned to the bottom of the screen shows EACH
 * item as
 *
 *     original text   (small, blue-gray, script-aware direction)
 *     ─────────────
 *     Dari translation (bold, Vazirmatn, true RTL, never truncated)
 *
 * …scrollable inside, closes with its ✕ button, the quick menu's Close, a
 * long-press on the bubble, never covers more than ~55% of the screen,
 * TAPPING a row copies the Dari translation to the clipboard, and it slides
 * up with a soft spring instead of popping in.
 *
 * RTL correctness rules applied to every translation row:
 *  - layoutDirection = RTL on the TextView → gravity START means the RIGHT
 *    edge, ellipsize (not used on translations) would cut the LEFT side.
 *  - textDirection = FIRSTSTRONG → the paragraph direction follows the first
 *    strong character (Arabic script → RTL, Latin → LTR).
 *  - textAlignment = VIEW_START → text hugs the start (right) edge.
 *  - NO maxLines/ellipsize on the translation → long sentences wrap fully
 *    instead of being "combined or ruined".
 *
 * PRIVACY: FLAG_SECURE, like the bubble and the menu.
 */
class TranslationPanelManager(
    private val context: Context,
    private val windowManager: WindowManager
) {

    private val handler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { hide() }
    private var panel: FrameLayout? = null

    val isShowing: Boolean
        get() = panel != null

    fun show(boxes: List<TranslatedBox>, autoHideMs: Long) {
        handler.removeCallbacks(hideRunnable)
        hide()

        val metrics = context.resources.displayMetrics
        val density = metrics.density
        val font: Typeface? = FontCache.bold(context)

        val root = FrameLayout(context).apply {
            val hPad = (10 * density).toInt()
            val bottomPad = (14 * density).toInt()
            setPadding(hPad, 0, hPad, bottomPad)
        }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                // Dark frosted glass: 94% charcoal + a faint teal rim.
                setColor(Color.parseColor("#F0141C24"))
                cornerRadius = 22 * density
                setStroke((1 * density).toInt(), Color.parseColor("#3826A69A"))
            }
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, 22 * density)
                }
            }
            // Slide-up entrance: starts below its final place and floats up.
            translationY = 120f * density
            alpha = 0f
        }

        // ---------------------------------------------------------------- header
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val hPad = (16 * density).toInt()
            val vPad = (10 * density).toInt()
            setPadding(hPad, vPad, hPad, vPad)
        }
        header.addView(
            TextView(context).apply {
                text = context.getString(R.string.panel_title)
                setTextColor(Color.parseColor("#FF26E6D0"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.defaultFromStyle(Typeface.BOLD)
                letterSpacing = 0.08f
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        val closeButton = ImageView(context).apply {
            setImageResource(R.drawable.ic_menu_close)
            setColorFilter(Color.parseColor("#FF9BA5B4"))
            val pad = (7 * density).toInt()
            setPadding(pad, pad, pad, pad)
            contentDescription = context.getString(R.string.panel_close_desc)
            isClickable = true
            isFocusable = true
            background = rippleCircle(density)
        }
        header.addView(
            closeButton,
            LinearLayout.LayoutParams(
                (36 * density).toInt(),
                (36 * density).toInt()
            )
        )
        card.addView(header)
        card.addView(divider(density, color = Color.parseColor("#14FFFFFF")))

        // ---------------------------------------------------------------- body
        val scroll = MaxHeightScrollView(context, (metrics.heightPixels * 0.55f).toInt())
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val hPad = (16 * density).toInt()
            val vPad = (12 * density).toInt()
            setPadding(hPad, vPad, hPad, vPad)
        }

        var added = 0
        for (box in boxes) {
            if (added >= MAX_ROWS) break
            if (box.translatedText.isBlank()) continue

            // One tappable row per item: tap anywhere on it to copy the Dari
            // translation to the clipboard (a top request — users want to
            // paste translations into messages).
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                isClickable = true
                isFocusable = true
                val hPad = (10 * density).toInt()
                val vPad = (8 * density).toInt()
                setPadding(hPad, vPad, hPad, vPad)
                background = GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                    cornerRadius = 14 * density
                }
                setOnLongClickListener {
                    copyToClipboard(box.translatedText)
                    true
                }
                setOnClickListener { copyToClipboard(box.translatedText) }
            }

            row.addView(
                TextView(context).apply {
                    // Original: small, blue-gray, script-aware alignment; kept
                    // to two lines because it is only a reminder of the source.
                    text = box.originalText
                    setTextColor(Color.parseColor("#FF9BA5B4"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    textDirection = View.TEXT_DIRECTION_FIRSTSTRONG
                    textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                }
            )
            row.addView(
                TextView(context).apply {
                    // The Dari translation — the star of the panel.
                    text = box.translatedText
                    setTextColor(Color.parseColor("#FFF5F8FA"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                    typeface = font ?: Typeface.defaultFromStyle(Typeface.BOLD)
                    // THE RTL FIX: full RTL layout + first-strong paragraph
                    // direction + start alignment, and no truncation at all —
                    // long translations wrap instead of being ruined.
                    layoutDirection = View.LAYOUT_DIRECTION_RTL
                    textDirection = View.TEXT_DIRECTION_FIRSTSTRONG
                    textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                    gravity = Gravity.START
                    setLineSpacing(3f, 1f)
                    setPadding(0, (2 * density).toInt(), 0, 0)
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (3 * density).toInt() }
            )
            list.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            if (added < MAX_ROWS - 1 && added < boxes.size - 1) {
                list.addView(
                    divider(density, color = Color.parseColor("#0FFFFFFF")).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            (1 * density).toInt()
                        ).apply { topMargin = (10 * density).toInt(); bottomMargin = (10 * density).toInt() }
                    }
                )
            }
            added++
        }
        if (list.childCount == 0) return
        scroll.addView(list)
        card.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        root.addView(
            card,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        )

        closeButton.setOnClickListener { hide() }

        val windowParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Touchable (so ✕ and scrolling work) but not focusable, so the
            // app below keeps its input focus.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                // PRIVACY: translations never appear in other apps'
                // screenshots or recordings, and our own OCR cannot
                // re-translate the panel.
                or WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
            }
        }

        try {
            windowManager.addView(root, windowParams)
            panel = root
        } catch (t: Throwable) {
            // Overlay permission revoked or window add failed.
            root.removeAllViews()
            return
        }

        // Slide-up entrance (220 ms, decelerating — soft, not bouncy).
        card.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(220L)
            .setInterpolator(DecelerateInterpolator())
            .start()

        if (autoHideMs > 0) {
            handler.postDelayed(hideRunnable, autoHideMs)
        }
    }

    fun hide() {
        handler.removeCallbacks(hideRunnable)
        panel?.let { current ->
            try {
                windowManager.removeView(current)
            } catch (t: Throwable) {
                // Already detached.
            }
            current.removeAllViews()
        }
        panel = null
    }

    fun release() = hide()

    // ---------------------------------------------------------------- helpers

    /** Copies [text] and confirms with a small Dari toast. */
    private fun copyToClipboard(text: String) {
        try {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            clipboard?.setPrimaryClip(
                ClipData.newPlainText("translation", text)
            )
            Toast.makeText(
                context,
                context.getString(R.string.panel_copy_toast),
                Toast.LENGTH_SHORT
            ).show()
        } catch (t: Throwable) {
            // Clipboard unavailable (rare vendor restrictions) — ignore.
        }
    }

    private fun divider(density: Float, color: Int): View =
        View(context).apply {
            setBackgroundColor(color)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (1 * density).toInt()
            )
        }

    private fun rippleCircle(density: Float): android.graphics.drawable.Drawable =
        // A plain translucent circle is enough feedback for an overlay
        // window (no themed ripple is available in a Service context).
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.argb(38, 0, 0, 0))
            setStroke((1 * density).toInt(), Color.argb(30, 0, 0, 0))
        }

    /** A ScrollView whose height never exceeds [maxHeightPx]. */
    private class MaxHeightScrollView(
        context: Context,
        private val maxHeightPx: Int
    ) : ScrollView(context) {
        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            super.onMeasure(
                widthSpec,
                MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST)
            )
        }
    }

    private companion object {
        const val MAX_ROWS = 60
    }
}
