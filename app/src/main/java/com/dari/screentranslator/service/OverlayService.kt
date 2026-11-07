package com.dari.screentranslator.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.dari.screentranslator.MainActivity
import com.dari.screentranslator.R
import com.dari.screentranslator.TranslatorApp
import com.dari.screentranslator.ServiceLocator
import com.dari.screentranslator.data.AppSettings
import com.dari.screentranslator.engine.OcrEngine
import com.dari.screentranslator.engine.TranslationEngine
import com.dari.screentranslator.model.ScreenTextItem
import com.dari.screentranslator.model.ScrollBehavior
import com.dari.screentranslator.model.TranslateScope
import com.dari.screentranslator.overlay.TranslationOverlayManager
import com.dari.screentranslator.util.SystemUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Foreground service that shows the draggable semi-transparent floating
 * button over every app. A tap reads the current screen (accessibility
 * first, MediaProjection + OCR as fallback) and translates it to Persian.
 * Another tap hides the translation. A long-press forces OCR.
 */
class OverlayService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var windowManager: WindowManager
    private var overlayManager: TranslationOverlayManager? = null
    private var bubble: ImageView? = null

    private var currentSettings: AppSettings = AppSettings()
    private var isTranslating = false

    @Volatile
    private var pendingOcrTranslate = false

    private var bubbleSizePx = 0
    private var screenWidthPx = 0
    private var screenHeightPx = 0
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var lastShownAt = 0L

    /** One-shot: moves the bubble to its saved position once DataStore
     *  delivers it — replaces the main-thread runBlocking of earlier
     *  versions. */
    private var bubblePositionRestored = false

    // Bubble drag/tap state.
    private var moved = false
    private var longPressed = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var startParamsX = 0
    private var startParamsY = 0

    private val longPressRunnable = Runnable {
        longPressed = true
        bubble?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        startTranslation(forceOcr = true)
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)!!
        val metrics = resources.displayMetrics
        screenWidthPx = metrics.widthPixels
        screenHeightPx = metrics.heightPixels
        bubbleSizePx = (BUBBLE_SIZE_DP * metrics.density).toInt()
        overlayManager = TranslationOverlayManager(this, windowManager)

        serviceScope.launch {
            ServiceLocator.settingsRepository.settings.collect { settings ->
                currentSettings = settings
                if (!bubblePositionRestored) {
                    bubblePositionRestored = true
                    if (settings.bubbleX != Int.MIN_VALUE && settings.bubbleY != Int.MIN_VALUE) {
                        val savedX = settings.bubbleX
                        val savedY = settings.bubbleY
                        mainHandler.post {
                            bubbleParams?.let { params ->
                                params.x = savedX.clampX()
                                params.y = savedY.clampY()
                                try {
                                    bubble?.let { windowManager.updateViewLayout(it, params) }
                                } catch (t: Throwable) {
                                    // Window not added yet or already removed.
                                }
                            }
                        }
                    }
                }
            }
        }

        ScreenCaptureService.onProjectionReady = {
            if (pendingOcrTranslate) {
                pendingOcrTranslate = false
                startTranslation(forceOcr = true)
            }
        }
        ScreenCaptureService.onProjectionFailed = {
            pendingOcrTranslate = false
        }
        ScreenReaderAccessibilityService.onScreenScrolled = { onScreenContentChanged() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopEverything()
            return START_NOT_STICKY
        }
        startAsForeground()
        running = true
        if (bubble == null) {
            showBubble()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        ScreenCaptureService.onProjectionReady = null
        ScreenCaptureService.onProjectionFailed = null
        ScreenReaderAccessibilityService.onScreenScrolled = null
        mainHandler.removeCallbacksAndMessages(null)
        hideBubble()
        overlayManager?.release()
        overlayManager = null
        serviceScope.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- foreground

    private fun startAsForeground() {
        val serviceType = if (Build.VERSION.SDK_INT >= 34) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), serviceType)
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            REQUEST_CODE_OPEN,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            REQUEST_CODE_STOP,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, TranslatorApp.CHANNEL_OVERLAY)
            .setSmallIcon(R.drawable.ic_translate)
            .setContentTitle(getString(R.string.notif_overlay_title))
            .setContentText(getString(R.string.notif_overlay_text))
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.notif_action_turn_off), stopIntent)
            .build()
    }

    // ---------------------------------------------------------------- bubble

    private fun showBubble() {
        val view = ImageView(this)
        view.setImageResource(R.drawable.ic_translate)
        view.setBackgroundResource(R.drawable.btn_floating_bg)
        view.scaleType = ImageView.ScaleType.CENTER_INSIDE
        view.alpha = 0.85f
        val padding = (10 * resources.displayMetrics.density).toInt()
        view.setPadding(padding, padding, padding, padding)
        view.contentDescription = getString(R.string.btn_translate_desc)

        // Uses currentSettings (collected in the background) instead of a
        // main-thread runBlocking DataStore read. If the saved position has
        // not arrived yet, the one-time restore in the collector moves the
        // bubble right after.
        val margin = (16 * resources.displayMetrics.density).toInt()
        val params = WindowManager.LayoutParams(
            bubbleSizePx,
            bubbleSizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                // PRIVACY: keeps the floating button out of screenshots and
                // screen recordings made by other apps — and out of this
                // app's own OCR captures, so it is never re-translated.
                or WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (currentSettings.bubbleX != Int.MIN_VALUE) currentSettings.bubbleX.clampX() else screenWidthPx - bubbleSizePx - margin
            y = if (currentSettings.bubbleY != Int.MIN_VALUE) currentSettings.bubbleY.clampY() else screenHeightPx * 2 / 3
        }

        setupBubbleTouch(view, params)
        try {
            windowManager.addView(view, params)
        } catch (t: Throwable) {
            toast(R.string.toast_overlay_permission)
            stopEverything()
            return
        }
        bubble = view
        bubbleParams = params
    }

    private fun hideBubble() {
        bubble?.let { current ->
            try {
                windowManager.removeView(current)
            } catch (t: Throwable) {
                // Already detached.
            }
        }
        bubble = null
        bubbleParams = null
    }

    /**
     * Called from the accessibility service when the underlying page changes
     * or scrolls. Hides the visible translations so they never stay behind
     * text that has moved away (feeds like Telegram).
     */
    private fun onScreenContentChanged() {
        if (currentSettings.scrollBehavior != ScrollBehavior.HIDE_ON_SCROLL) return
        if (overlayManager?.isShowing != true) return
        if (SystemClock.uptimeMillis() - lastShownAt < SCROLL_GRACE_MS) return
        mainHandler.post {
            overlayManager?.hide()
        }
    }

    private fun Int.clampX(): Int = coerceIn(0, (screenWidthPx - bubbleSizePx).coerceAtLeast(0))

    private fun Int.clampY(): Int = coerceIn(0, (screenHeightPx - bubbleSizePx).coerceAtLeast(0))

    @SuppressLint("ClickableViewAccessibility")
    private fun setupBubbleTouch(view: ImageView, params: WindowManager.LayoutParams) {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    moved = false
                    longPressed = false
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startParamsX = params.x
                    startParamsY = params.y
                    mainHandler.postDelayed(longPressRunnable, LONG_PRESS_TIMEOUT_MS)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!moved && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        moved = true
                        mainHandler.removeCallbacks(longPressRunnable)
                    }
                    if (moved) {
                        params.x = (startParamsX + dx).toInt().clampX()
                        params.y = (startParamsY + dy).toInt().clampY()
                        try {
                            windowManager.updateViewLayout(view, params)
                        } catch (t: Throwable) {
                            // Window was removed.
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    if (!moved) view.performClick()
                    if (moved) {
                        persistBubblePosition(params)
                        translateAfterDrag(event.rawX, event.rawY)
                    } else if (!longPressed) {
                        startTranslation(forceOcr = false)
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    true
                }
                else -> false
            }
        }
    }

    private fun persistBubblePosition(params: WindowManager.LayoutParams) {
        serviceScope.launch {
            ServiceLocator.settingsRepository.saveBubblePosition(params.x, params.y)
        }
    }

    /**
     * Proximity translate: in NEAR_BUTTON mode, dropping the button next to
     * some text translates that text immediately — no extra tap needed.
     * Re-pointing the button somewhere else re-translates at the new spot.
     * Tiny accidental drags are ignored.
     */
    private fun translateAfterDrag(upRawX: Float, upRawY: Float) {
        if (currentSettings.scope != TranslateScope.NEAR_BUTTON) return
        val minDragPx = MIN_DRAG_FOR_PROXIMITY_DP * resources.displayMetrics.density
        val dx = (upRawX - downRawX).toDouble()
        val dy = (upRawY - downRawY).toDouble()
        if (kotlin.math.hypot(dx, dy) < minDragPx) return
        // Pointing somewhere else counts as a new request, not a toggle-off.
        overlayManager?.hide()
        startTranslation(forceOcr = false)
    }

    private fun setBubbleBusy(busy: Boolean) {
        mainHandler.post {
            val view = bubble ?: return@post
            if (busy) {
                view.setColorFilter(Color.argb(120, 255, 255, 255))
            } else {
                view.clearColorFilter()
            }
        }
    }

    // ---------------------------------------------------------------- translation

    private fun startTranslation(forceOcr: Boolean) {
        if (isTranslating) return
        if (overlayManager?.isShowing == true) {
            overlayManager?.hide()
            return
        }
        isTranslating = true
        setBubbleBusy(true)
        serviceScope.launch {
            try {
                val items = collectTexts(forceOcr)
                if (items != null) {
                    val selected = filterNearButton(items)
                    when {
                        items.isNotEmpty() && selected.isEmpty() -> {
                            toast(R.string.toast_no_text_near)
                        }
                        selected.isEmpty() -> {
                            toast(R.string.toast_no_text)
                        }
                        else -> {
                            val boxes = TranslationEngine.translate(
                                selected,
                                SystemUtils.isOnline(this@OverlayService)
                            ) { event ->
                                handleEngineEvent(event)
                            }
                            if (boxes.isNotEmpty()) {
                                mainHandler.post {
                                    lastShownAt = SystemClock.uptimeMillis()
                                    overlayManager?.show(boxes, currentSettings.autoHideMs)
                                }
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                toast(getString(R.string.toast_translation_error, t.message ?: t.javaClass.simpleName))
            } finally {
                isTranslating = false
                setBubbleBusy(false)
            }
        }
    }

    /**
     * Reads the current screen. Accessibility text is preferred; when it is
     * not available (images, games) the screen is captured and OCRed.
     * Returns null when screen-capture consent is still pending.
     */
    private suspend fun collectTexts(forceOcr: Boolean): List<ScreenTextItem>? {
        if (!forceOcr) {
            val accessibilityTexts = ScreenReaderAccessibilityService.collectCurrentTexts()
            if (accessibilityTexts != null &&
                accessibilityTexts.sumOf { it.text.length } >= MIN_ACCESSIBILITY_CHARS
            ) {
                return accessibilityTexts
            }
        }

        val captureService = ScreenCaptureService.activeService()
        if (captureService == null) {
            pendingOcrTranslate = true
            toast(R.string.toast_consent_needed)
            try {
                startActivity(
                    Intent(this, ProjectionConsentActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (t: Throwable) {
                toast(R.string.toast_capture_failed)
            }
            return null
        }

        val bitmap = withContext(Dispatchers.IO) {
            // Hide the bubble for the capture so it never covers the text
            // being OCRed; the hide itself also forces a fresh frame.
            mainHandler.post { bubble?.visibility = View.INVISIBLE }
            try {
                captureService.capture()
            } finally {
                mainHandler.post { bubble?.visibility = View.VISIBLE }
            }
        }
        if (bitmap == null) {
            toast(R.string.toast_capture_failed)
            return emptyList()
        }
        val items = try {
            OcrEngine.recognize(bitmap, currentSettings.ocrScript)
        } finally {
            bitmap.recycle()
        }
        return items
    }

    /**
     * In NEAR_BUTTON scope, keeps only the text whose center is close to the
     * floating button, so the user can point the button at one sentence or
     * paragraph at a time. In WHOLE_SCREEN scope the list is returned as is.
     */
    private fun filterNearButton(items: List<ScreenTextItem>): List<ScreenTextItem> {
        if (currentSettings.scope != TranslateScope.NEAR_BUTTON) return items
        val bubbleView = bubble ?: return items
        val density = resources.displayMetrics.density
        val radius = NEAR_RADIUS_DP * density
        // Real screen coordinates (not window coordinates) so proximity
        // matches the accessibility/OCR bounds exactly, whatever the status
        // bar did to the bubble window's origin.
        val loc = IntArray(2)
        bubbleView.getLocationOnScreen(loc)
        val centerX = loc[0] + bubbleView.width / 2f
        val centerY = loc[1] + bubbleView.height / 2f
        return items.filter { item ->
            val dx = item.bounds.exactCenterX() - centerX
            val dy = item.bounds.exactCenterY() - centerY
            dx * dx + dy * dy <= radius * radius
        }
    }

    private fun handleEngineEvent(event: TranslationEngine.Event) {
        when (event) {
            is TranslationEngine.Event.ModelDownloadStarted ->
                toast(getString(R.string.toast_model_downloading, event.languageName))
            TranslationEngine.Event.NothingToTranslate ->
                toast(R.string.toast_nothing_to_translate)
            TranslationEngine.Event.AllTextAlreadyPersian ->
                toast(R.string.toast_already_persian)
            TranslationEngine.Event.PashtoNotSupported ->
                toast(R.string.toast_pashto_unsupported, Toast.LENGTH_LONG)
            is TranslationEngine.Event.ModelsMissingOffline ->
                toast(
                    getString(
                        R.string.toast_models_missing_offline,
                        event.languageNames.joinToString(", ")
                    ),
                    Toast.LENGTH_LONG
                )
            is TranslationEngine.Event.Failed ->
                toast(getString(R.string.toast_translation_error, event.message))
        }
    }

    // ---------------------------------------------------------------- misc

    private fun toast(resId: Int) = toast(getString(resId))

    private fun toast(message: String, duration: Int = Toast.LENGTH_SHORT) {
        mainHandler.post {
            Toast.makeText(this, message, duration).show()
        }
    }

    private fun stopEverything() {
        running = false
        hideBubble()
        overlayManager?.hide()
        if (ScreenCaptureService.isRunning) {
            ScreenCaptureService.stop(this)
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        const val ACTION_START = "com.dari.screentranslator.action.START"
        const val ACTION_STOP = "com.dari.screentranslator.action.STOP"
        private const val NOTIFICATION_ID = 1
        private const val REQUEST_CODE_OPEN = 10
        private const val REQUEST_CODE_STOP = 11
        private const val BUBBLE_SIZE_DP = 52
        private const val LONG_PRESS_TIMEOUT_MS = 550L
        private const val MIN_ACCESSIBILITY_CHARS = 12
        private const val NEAR_RADIUS_DP = 180
        private const val SCROLL_GRACE_MS = 800L
        private const val MIN_DRAG_FOR_PROXIMITY_DP = 24

        @Volatile
        private var running = false

        val isRunning: Boolean
            get() = running
    }
}
