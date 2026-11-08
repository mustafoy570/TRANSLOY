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
import android.view.animation.DecelerateInterpolator
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
import com.dari.screentranslator.overlay.QuickMenuManager
import com.dari.screentranslator.overlay.ScanAnimationOverlay
import com.dari.screentranslator.overlay.TranslationPanelManager
import com.dari.screentranslator.util.SystemUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Foreground service that shows the single draggable floating button over
 * every app (v1.5 one-button UX):
 *
 *  - TAP           → opens the circular quick menu (Translate / OCR /
 *                    Auto-on-scroll / Close / Turn off). The menu closes
 *                    itself after a choice or on a tap on the dimmed
 *                    background — the bubble needs no second "close"
 *                    button any more.
 *  - LONG-PRESS    → smart: translations or the menu are open → close them;
 *                    nothing open → translate the text NEAR the button
 *                    (point-and-translate, no dragging needed).
 *  - DRAG          → only moves the button. The old "translate on drop"
 *                    behaviour is gone (user rating: 20/100).
 *
 * v1.5.0 polish:
 *  - Every translation starts with the teal LASER SCAN sweep (the effect
 *    users loved), with a soft haptic tick — the wait feels instant.
 *  - After 3 idle seconds the button docks itself against the nearest
 *    screen edge, tucks halfway off-screen and fades to 40% — it stops
 *    disturbing the user. Any touch wakes it up instantly.
 *
 * Results are shown in the bottom TranslationPanel (original + Dari,
 * RTL-correct) instead of boxes that covered the screen.
 */
class OverlayService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var windowManager: WindowManager
    private var panelManager: TranslationPanelManager? = null
    private var menuManager: QuickMenuManager? = null
    private var scanOverlay: ScanAnimationOverlay? = null
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

    /** True while the bubble is tucked halfway off-screen (idle). */
    private var isTucked = false

    /** The edge-docking animation, cancelled the moment the user touches. */
    private var snapAnimator: android.animation.ValueAnimator? = null

    /** 3 s of no interaction → dock to the edge, tuck and fade. */
    private var idleRunnable: Runnable? = null

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

    /** Debounced auto re-translation after the page scrolls (auto mode). */
    private var autoTranslateRunnable: Runnable? = null

    private val longPressRunnable = Runnable {
        longPressed = true
        bubble?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        onLongPressed()
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)!!
        val metrics = resources.displayMetrics
        screenWidthPx = metrics.widthPixels
        screenHeightPx = metrics.heightPixels
        bubbleSizePx = (BUBBLE_SIZE_DP * metrics.density).toInt()
        panelManager = TranslationPanelManager(this, windowManager)
        menuManager = QuickMenuManager(this, windowManager)
        scanOverlay = ScanAnimationOverlay(this, windowManager)

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
        snapAnimator?.cancel()
        snapAnimator = null
        scanOverlay?.stop()
        scanOverlay = null
        hideBubble()
        menuManager?.release()
        menuManager = null
        panelManager?.release()
        panelManager = null
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
        view.alpha = WAKE_ALPHA
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
     * or scrolls. Two behaviours (v1.5):
     *  - Auto-translate ON (and the panel is open): after a short debounce the
     *    panel re-translates the new screen content by itself — the user no
     *    longer "should always scan for any scroll".
     *  - Auto OFF: the old behaviour — hide the panel when the text scrolls
     *    away, unless KEEP was chosen in settings.
     *
     * Everything here runs on the main thread in one block, so cancel + re-
     * schedule can never race (a posted cancel could otherwise kill the NEW
     * debounce instead of the old one).
     */
    private fun onScreenContentChanged() {
        mainHandler.post {
            cancelPendingAutoTranslate()
            if (currentSettings.autoTranslateOnScroll) {
                if (panelManager?.isShowing != true) return@post
                if (SystemClock.uptimeMillis() - lastShownAt < SCROLL_GRACE_MS) return@post
                val runnable = Runnable {
                    autoTranslateRunnable = null
                    if (currentSettings.autoTranslateOnScroll) {
                        startTranslation(forceOcr = false, silent = true)
                    }
                }
                autoTranslateRunnable = runnable
                mainHandler.postDelayed(runnable, AUTO_TRANSLATE_DEBOUNCE_MS)
                return@post
            }
            if (currentSettings.scrollBehavior != ScrollBehavior.HIDE_ON_SCROLL) return@post
            if (panelManager?.isShowing != true) return@post
            if (SystemClock.uptimeMillis() - lastShownAt < SCROLL_GRACE_MS) return@post
            panelManager?.hide()
        }
    }

    private fun cancelPendingAutoTranslate() {
        autoTranslateRunnable?.let { mainHandler.removeCallbacks(it) }
        autoTranslateRunnable = null
    }

    private fun Int.clampX(): Int = coerceIn(0, (screenWidthPx - bubbleSizePx).coerceAtLeast(0))

    private fun Int.clampY(): Int = coerceIn(0, (screenHeightPx - bubbleSizePx).coerceAtLeast(0))

    @SuppressLint("ClickableViewAccessibility")
    private fun setupBubbleTouch(view: ImageView, params: WindowManager.LayoutParams) {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    wakeUpBubble()
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
                        // v1.5: dragging ONLY moves the button. The old
                        // "translate on drop" is what made pointing feel bad.
                        persistBubblePosition(params)
                    } else if (!longPressed) {
                        toggleQuickMenu()
                    }
                    armIdleTimer()
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

    // -------------------------------------------------- idle docking (v1.5.0)

    /**
     * After [IDLE_TUCK_DELAY_MS] without interaction the button glides to
     * the nearest screen edge, tucks itself halfway off-screen and fades to
     * 40% — out of the user's way, but still one tap away. Any touch on it
     * pops it fully back.
     */
    private fun armIdleTimer() {
        idleRunnable?.let { mainHandler.removeCallbacks(it) }
        if (menuManager?.isShowing == true) return // menu open: stay awake
        val runnable = Runnable {
            idleRunnable = null
            val params = bubbleParams ?: return@Runnable
            if (isTucked) return@Runnable
            snapBubbleToNearestEdge(params) {
                if (!isTucked) {
                    tuckBubble()
                    persistBubblePosition(params)
                }
            }
        }
        idleRunnable = runnable
        mainHandler.postDelayed(runnable, IDLE_TUCK_DELAY_MS)
    }

    private fun snapBubbleToNearestEdge(
        params: WindowManager.LayoutParams,
        onDone: () -> Unit
    ) {
        val targetX =
            if (params.x + bubbleSizePx / 2 < screenWidthPx / 2) 0
            else (screenWidthPx - bubbleSizePx).coerceAtLeast(0)
        val startX = params.x
        val animator = android.animation.ValueAnimator.ofInt(startX, targetX).apply {
            duration = SNAP_DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { animation ->
                params.x = animation.animatedValue as Int
                try {
                    bubble?.let { windowManager.updateViewLayout(it, params) }
                } catch (t: Throwable) {
                    // Window removed mid-animation.
                }
            }
            addListener(object : android.animation.Animator.AnimatorListener {
                override fun onAnimationStart(animation: android.animation.Animator) = Unit
                override fun onAnimationRepeat(animation: android.animation.Animator) = Unit
                override fun onAnimationCancel(animation: android.animation.Animator) = Unit
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    snapAnimator = null
                    params.x = targetX
                    onDone()
                }
            })
        }
        snapAnimator?.cancel()
        snapAnimator = animator
        animator.start()
    }

    private fun tuckBubble() {
        val params = bubbleParams ?: return
        val view = bubble ?: return
        isTucked = true
        val direction = if (params.x <= screenWidthPx / 2) -1f else 1f
        view.animate()
            .translationX(direction * bubbleSizePx * 0.45f)
            .alpha(TUCKED_ALPHA)
            .setDuration(SNAP_DURATION_MS)
            .start()
    }

    private fun wakeUpBubble() {
        idleRunnable?.let { mainHandler.removeCallbacks(it) }
        idleRunnable = null
        snapAnimator?.cancel()
        snapAnimator = null
        if (!isTucked) return
        isTucked = false
        bubble?.let { view ->
            view.animate()
                .translationX(0f)
                .alpha(WAKE_ALPHA)
                .setDuration(180L)
                .start()
        }
    }

    private fun persistBubblePosition(params: WindowManager.LayoutParams) {
        serviceScope.launch {
            ServiceLocator.settingsRepository.saveBubblePosition(params.x, params.y)
        }
    }

    // ---------------------------------------------------------------- quick menu

    /**
     * Tap on the bubble → the circular options menu. Tapping while it is
     * already open simply closes it (one button, zero clutter).
     */
    private fun toggleQuickMenu() {
        val menu = menuManager ?: return
        if (menu.isShowing) {
            menu.hide()
            armIdleTimer()
            return
        }
        wakeUpBubble()
        val bubbleView = bubble ?: return
        val loc = IntArray(2)
        bubbleView.getLocationOnScreen(loc)
        val cx = loc[0] + bubbleView.width / 2
        val cy = loc[1] + bubbleView.height / 2
        menu.show(cx, cy, currentSettings.autoTranslateOnScroll) { option ->
            when (option) {
                QuickMenuManager.Option.TRANSLATE -> startTranslation(forceOcr = false)
                QuickMenuManager.Option.OCR -> startTranslation(forceOcr = true)
                QuickMenuManager.Option.AUTO -> toggleAutoTranslate()
                QuickMenuManager.Option.CLOSE -> {
                    panelManager?.hide()
                    menu.hide()
                }
                QuickMenuManager.Option.POWER_OFF -> powerOff()
            }
            // The menu is gone → restart the idle timer so the bubble can
            // dock away again after the user finishes interacting.
            if (option != QuickMenuManager.Option.POWER_OFF) armIdleTimer()
        }
    }

    /**
     * POWER OFF from the circular panel: writes `enabled = false` so the app
     * does not resurrect itself, stops every overlay, confirms with a short
     * vibration. The DataStore write runs on an ephemeral scope because
     * [stopEverything] tears the service scope down immediately after.
     */
    private fun powerOff() {
        bubble?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { ServiceLocator.settingsRepository.setEnabled(false) }
        }
        stopEverything()
    }

    private fun toggleAutoTranslate() {
        val newValue = !currentSettings.autoTranslateOnScroll
        serviceScope.launch {
            ServiceLocator.settingsRepository.setAutoTranslateOnScroll(newValue)
        }
        menuManager?.refreshAutoLabel(newValue)
        toast(
            if (newValue) R.string.toast_auto_on else R.string.toast_auto_off
        )
    }

    // ---------------------------------------------------------------- long-press

    /**
     * One button does the closing too: a long-press closes whatever is open
     * (menu, panel, both). When nothing is open it becomes point-and-
     * translate — the texts closest to the button are translated, so the
     * user just parks the button next to a paragraph and holds it.
     */
    private fun onLongPressed() {
        var closedSomething = false
        if (menuManager?.isShowing == true) {
            menuManager?.hide()
            closedSomething = true
        }
        if (panelManager?.isShowing == true) {
            panelManager?.hide()
            closedSomething = true
        }
        if (!closedSomething) {
            startTranslation(forceOcr = false, nearButtonOnly = true)
        }
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

    /**
     * Reads and translates the current screen into the bottom panel.
     *
     * @param forceOcr       skip accessibility text and use image OCR.
     * @param nearButtonOnly translate only the texts close to the button
     *                       (long-press pointing; also forced when the
     *                       scope setting is NEAR_BUTTON).
     * @param silent         auto-refresh mode: no "no text" / status toasts,
     *                       so an empty screen after scrolling does not nag.
     */
    private fun startTranslation(
        forceOcr: Boolean,
        nearButtonOnly: Boolean = false,
        silent: Boolean = false
    ) {
        if (isTranslating) return
        isTranslating = true
        setBubbleBusy(true)
        if (!silent) {
            // The beloved scan animation: a teal laser sweeps the screen once,
            // with a soft haptic tick — the wait feels instant.
            mainHandler.post {
                scanOverlay?.playScanAnimation()
                bubble?.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
        }
        serviceScope.launch {
            try {
                val items = collectTexts(forceOcr, silent)
                if (items != null) {
                    val selected = filterNearButton(items, nearButtonOnly)
                    when {
                        items.isNotEmpty() && selected.isEmpty() && !silent ->
                            toast(R.string.toast_no_text_near)
                        selected.isEmpty() && !silent ->
                            toast(R.string.toast_no_text)
                        else -> {
                            val boxes = TranslationEngine.translate(
                                selected,
                                SystemUtils.isOnline(this@OverlayService)
                            ) { event ->
                                handleEngineEvent(event, silent)
                            }
                            if (boxes.isNotEmpty()) {
                                mainHandler.post {
                                    lastShownAt = SystemClock.uptimeMillis()
                                    panelManager?.show(boxes, currentSettings.autoHideMs)
                                }
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                if (!silent) {
                    toast(getString(R.string.toast_translation_error, t.message ?: t.javaClass.simpleName))
                }
            } finally {
                isTranslating = false
                setBubbleBusy(false)
                mainHandler.post { armIdleTimer() }
            }
        }
    }

    /**
     * Reads the current screen. Accessibility text is preferred; when it is
     * not available (images, games) the screen is captured and OCRed.
     * Returns null when screen-capture consent is still pending.
     *
     * When [silent] is true (auto-refresh after scrolling), nothing user-
     * facing happens on failure — in particular the consent activity is
     * never launched from a background scroll event.
     */
    private suspend fun collectTexts(forceOcr: Boolean, silent: Boolean): List<ScreenTextItem>? {
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
            if (!silent) {
                toast(R.string.toast_consent_needed)
                try {
                    startActivity(
                        Intent(this, ProjectionConsentActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (t: Throwable) {
                    toast(R.string.toast_capture_failed)
                }
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
            if (!silent) toast(R.string.toast_capture_failed)
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
     * Keeps only the text whose center is close to the floating button, so
     * the user can point the button at one sentence or paragraph at a time.
     * Used by the long-press pointing gesture, and always active when the
     * scope setting is NEAR_BUTTON.
     */
    private fun filterNearButton(
        items: List<ScreenTextItem>,
        force: Boolean
    ): List<ScreenTextItem> {
        if (!force && currentSettings.scope != TranslateScope.NEAR_BUTTON) return items
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

    private fun handleEngineEvent(event: TranslationEngine.Event, silent: Boolean) {
        if (silent) return
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
        snapAnimator?.cancel()
        snapAnimator = null
        idleRunnable?.let { mainHandler.removeCallbacks(it) }
        idleRunnable = null
        scanOverlay?.stop()
        hideBubble()
        menuManager?.hide()
        panelManager?.hide()
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

        /** How long the page must be still after a scroll before the panel
         *  refreshes itself (auto-translate mode). */
        private const val AUTO_TRANSLATE_DEBOUNCE_MS = 700L

        /** Idle time before the bubble docks to the edge and tucks away. */
        private const val IDLE_TUCK_DELAY_MS = 3000L
        private const val SNAP_DURATION_MS = 220L
        private const val TUCKED_ALPHA = 0.40f
        private const val WAKE_ALPHA = 0.92f

        @Volatile
        private var running = false

        val isRunning: Boolean
            get() = running
    }
}
