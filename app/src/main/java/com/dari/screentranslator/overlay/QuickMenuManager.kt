package com.dari.screentranslator.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.dari.screentranslator.R

/**
 * The circular quick menu that opens around the floating button (v1.5).
 *
 * One button controls everything: a tap on the bubble fans out FIVE round
 * options arranged in a ring around the button — Translate, Image (OCR),
 * Auto-on-scroll, Close, and Turn off — and the menu dismisses itself as
 * soon as an option is picked or the user taps the dimmed background.
 *
 * v1.5.0 upgrades (field feedback):
 *  - POWER OFF lives in the ring ("a circular panel for turning it off"),
 *    so the notification does not have to be pulled down to stop the app.
 *  - EDGE-AWARE ANGLES: when the bubble sits near a screen edge the ring
 *    becomes a half-arc that fans AWAY from that edge, so no option is ever
 *    clipped off-screen or opens under the user's finger.
 *  - SPRING FAN-OUT: options pop out one after another (22 ms stagger) with
 *    an overshoot spring — the smooth, playful feel users asked for.
 *
 * PRIVACY: the menu window is FLAG_SECURE, exactly like the bubble and the
 * translation panel, so it never appears in other apps' screenshots or
 * recordings.
 */
class QuickMenuManager(
    private val context: Context,
    private val windowManager: WindowManager
) {

    enum class Option { TRANSLATE, OCR, AUTO, CLOSE, POWER_OFF }

    /** One ring option: icon, label, tint and angle around the bubble. */
    private data class Entry(
        val option: Option,
        val iconRes: Int,
        val labelRes: Int,
        val iconTint: Int,
        val angleDeg: Double
    )

    fun interface Listener {
        /** Called on the main thread when the user picks an option. */
        fun onOption(option: Option)
    }

    private var menuLayer: FrameLayout? = null
    private var autoLabel: TextView? = null

    val isShowing: Boolean
        get() = menuLayer != null

    /**
     * Opens the ring menu around the button whose center is at
     * ([anchorX], [anchorY]) in screen coordinates.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun show(anchorX: Int, anchorY: Int, isAutoOn: Boolean, listener: Listener) {
        if (menuLayer != null) return
        val density = context.resources.displayMetrics.density
        val screenWidth = context.resources.displayMetrics.widthPixels
        val screenHeight = context.resources.displayMetrics.heightPixels

        val root = FrameLayout(context).apply {
            // 25% black scrim; any touch outside an option dismisses.
            setBackgroundColor(Color.argb(64, 0, 0, 0))
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN ||
                    event.actionMasked == MotionEvent.ACTION_UP
                ) {
                    hide()
                    true
                } else {
                    false
                }
            }
        }

        fun pick(option: Option) {
            hide()
            listener.onOption(option)
        }

        val entries = buildEntries(anchorX, anchorY, screenWidth, screenHeight)

        val radius = RADIUS_DP * density
        val buttonSize = (OPTION_SIZE_DP * density).toInt()

        for ((index, entry) in entries.withIndex()) {
            val angle = Math.toRadians(entry.angleDeg)
            // Ideal ring position around the button…
            var cx = anchorX + (radius * Math.cos(angle)).toInt()
            var cy = anchorY + (radius * Math.sin(angle)).toInt()
            // …clamped so the whole option (icon + label) stays on screen.
            val margin = (16 * density).toInt()
            cx = cx.coerceIn(margin + buttonSize / 2, screenWidth - margin - buttonSize / 2)
            cy = cy.coerceIn(margin + buttonSize, screenHeight - margin - buttonSize)

            val item = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                val icon = ImageView(context).apply {
                    setImageResource(entry.iconRes)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    val pad = (13 * density).toInt()
                    setPadding(pad, pad, pad, pad)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.WHITE)
                        setStroke((1.5 * density).toInt(), Color.argb(60, 0, 0, 0))
                    }
                    setColorFilter(entry.iconTint)
                    isClickable = true
                    isFocusable = true
                    contentDescription = context.getString(entry.labelRes)
                }
                val label = TextView(context).apply {
                    text = context.getString(entry.labelRes)
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setShadowLayer(4f, 0f, 0f, Color.argb(200, 0, 0, 0))
                    gravity = Gravity.CENTER
                }
                if (entry.option == Option.AUTO) {
                    autoLabel = label
                    refreshAutoLabel(isAutoOn)
                }
                addView(icon, LinearLayout.LayoutParams(buttonSize, buttonSize))
                addView(
                    label,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = (6 * density).toInt() }
                )
                icon.setOnClickListener { pick(entry.option) }
                label.setOnClickListener { pick(entry.option) }
                // Spring fan-out start state.
                alpha = 0f
                scaleX = 0.55f
                scaleY = 0.55f
            }

            val itemWidth = (92 * density).toInt()
            val itemHeight = buttonSize + (30 * density).toInt()
            val params = FrameLayout.LayoutParams(itemWidth, itemHeight).apply {
                gravity = Gravity.TOP or Gravity.START
                leftMargin = cx - itemWidth / 2
                topMargin = cy - buttonSize / 2
            }
            root.addView(item, params)
            // The spring pop: one option after another, overshooting slightly.
            item.post {
                item.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setStartDelay(index * FAN_STAGGER_MS)
                    .setDuration(FAN_DURATION_MS)
                    .setInterpolator(OvershootInterpolator(FAN_OVERSHOOT))
                    .start()
            }
        }

        val windowParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                // PRIVACY: never visible in other apps' screenshots or
                // recordings, and never captured by our own OCR.
                or WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.TRANSLUCENT
        )

        try {
            windowManager.addView(root, windowParams)
            menuLayer = root
        } catch (t: Throwable) {
            // Overlay permission revoked mid-session.
            root.removeAllViews()
            autoLabel = null
        }
    }

    /**
     * Five options with edge-aware angles. Screen coordinates: 0° = right of
     * the bubble, -90° = above it, 90° = below it.
     */
    private fun buildEntries(
        anchorX: Int,
        anchorY: Int,
        screenWidth: Int,
        screenHeight: Int
    ): List<Entry> {
        val angles: DoubleArray = when {
            anchorX > screenWidth * 0.70 ->
                // Bubble near the RIGHT edge → fan to the LEFT half-arc.
                doubleArrayOf(110.0, 145.0, 180.0, 215.0, 250.0)
            anchorX < screenWidth * 0.30 ->
                // Bubble near the LEFT edge → fan to the RIGHT half-arc.
                doubleArrayOf(-70.0, -35.0, 0.0, 35.0, 70.0)
            anchorY < screenHeight * 0.30 ->
                // Bubble near the TOP edge → fan DOWNWARD.
                doubleArrayOf(30.0, 60.0, 90.0, 120.0, 150.0)
            else ->
                // Middle of the screen → full ring, evenly spaced.
                doubleArrayOf(-90.0, -18.0, 54.0, 126.0, 198.0)
        }
        val options = listOf(
            Triple(Option.TRANSLATE, R.drawable.ic_menu_translate, R.string.menu_translate to ICON_TEAL),
            Triple(Option.OCR, R.drawable.ic_menu_scan, R.string.menu_ocr to ICON_TEAL),
            Triple(Option.AUTO, R.drawable.ic_menu_auto, R.string.menu_auto_off to ICON_TEAL),
            Triple(Option.CLOSE, R.drawable.ic_menu_close, R.string.menu_close to ICON_RED),
            Triple(Option.POWER_OFF, R.drawable.ic_menu_power, R.string.menu_power_off to ICON_RED)
        )
        return options.mapIndexed { index, (option, iconRes, labelAndTint) ->
            val (labelRes, tint) = labelAndTint
            Entry(option, iconRes, labelRes, tint, angles[index])
        }
    }

    /** Flips the Auto option label without reopening the menu. */
    fun refreshAutoLabel(isOn: Boolean) {
        autoLabel?.setText(
            if (isOn) R.string.menu_auto_on else R.string.menu_auto_off
        )
    }

    fun hide() {
        menuLayer?.let { current ->
            try {
                windowManager.removeView(current)
            } catch (t: Throwable) {
                // Already detached.
            }
            current.removeAllViews()
        }
        menuLayer = null
        autoLabel = null
    }

    fun release() = hide()

    private companion object {
        const val RADIUS_DP = 104
        const val OPTION_SIZE_DP = 56
        const val FAN_STAGGER_MS = 22L
        const val FAN_DURATION_MS = 210L
        const val FAN_OVERSHOOT = 1.25f
        val ICON_TEAL = Color.parseColor("#FF0E7C86")
        val ICON_RED = Color.parseColor("#FFB3261E")
    }
}
