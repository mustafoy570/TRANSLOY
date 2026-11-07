package com.dari.screentranslator.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.dari.screentranslator.model.ScreenTextItem
import com.dari.screentranslator.model.TextSource

/**
 * Fast path for reading the screen: pulls visible text nodes and their
 * bounds from the accessibility tree on demand, when the floating button
 * is tapped. No events are processed outside of that.
 *
 * PRIVACY RULES (enforced in [collectTexts]):
 *  - text is read ONLY at the moment the user taps the button — this
 *    service never logs, stores or forwards what it reads;
 *  - password fields and text the user is typing into an input field are
 *    NEVER read, even when the button sits right on top of them;
 *  - the keyboard (IME) window is skipped entirely so typed text can not
 *    echo back through the candidate view.
 */
class ScreenReaderAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Reading is done on demand when the floating button is tapped. Here
        // only scroll/change signals are forwarded so the overlay service can
        // hide the translations when the page moves under them.
        val e = event ?: return
        if (onScreenScrolled == null) return
        val pkg = e.packageName?.toString()
        if (pkg.isNullOrEmpty() || pkg == packageName) return
        val type = e.eventType
        if (type != AccessibilityEvent.TYPE_VIEW_SCROLLED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) {
            return
        }
        val now = SystemClock.uptimeMillis()
        if (now - lastScrollSignal < SCROLL_SIGNAL_DEBOUNCE_MS) return
        lastScrollSignal = now
        onScreenScrolled?.invoke()
    }

    override fun onInterrupt() {
        // Nothing to interrupt.
    }

    private fun collectTexts(maxNodes: Int): List<ScreenTextItem> {
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        val ownPackage = packageName?.toString()
        val seen = HashSet<String>()
        val out = ArrayList<ScreenTextItem>()
        var id = 0L

        fun visitRoot(root: AccessibilityNodeInfo?) {
            if (root == null) return
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            while (queue.isNotEmpty() && out.size < maxNodes) {
                val node = queue.removeFirst()
                val nodePackage = node.packageName?.toString()
                if (nodePackage == null || nodePackage == ownPackage) continue

                // PRIVACY: never read password fields or the text a user is
                // typing into an editable field. Children are still visited
                // so surrounding labels are not lost.
                val sensitive = node.isPassword || node.isEditable
                val text = if (sensitive) {
                    ""
                } else {
                    node.text?.toString()?.trim().orEmpty()
                        .ifEmpty { node.contentDescription?.toString()?.trim().orEmpty() }
                }
                if (text.length >= 2) {
                    val rect = Rect()
                    node.getBoundsInScreen(rect)
                    if (node.isVisibleToUser &&
                        rect.width() >= 4 && rect.height() >= 4 &&
                        rect.left < screenWidth && rect.right > 0 &&
                        rect.top < screenHeight && rect.bottom > 0
                    ) {
                        val key = "$text|${rect.left},${rect.top},${rect.right},${rect.bottom}"
                        if (seen.add(key)) {
                            out.add(ScreenTextItem(id++, text, rect, TextSource.ACCESSIBILITY))
                        }
                    }
                }

                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(it) }
                }
            }
        }

        val roots = ArrayList<AccessibilityNodeInfo>()
        try {
            for (window in windows) {
                // PRIVACY: skip the keyboard (IME) window — its candidate view
                // can echo what the user is typing.
                if (window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue
                val root = window.root ?: continue
                val windowPackage = root.packageName?.toString()
                if (windowPackage == null || windowPackage != ownPackage) {
                    roots.add(root)
                }
            }
        } catch (t: Throwable) {
            // Some windows are not readable; ignore.
        }
        if (roots.isEmpty()) {
            roots.add(rootInActiveWindow)
        }
        for (root in roots) {
            visitRoot(root)
        }
        return out
    }

    companion object {
        @Volatile
        private var instance: ScreenReaderAccessibilityService? = null

        /** Set by [com.dari.screentranslator.service.OverlayService] while translations are visible. */
        @Volatile
        var onScreenScrolled: (() -> Unit)? = null

        private const val SCROLL_SIGNAL_DEBOUNCE_MS = 250L

        @Volatile
        private var lastScrollSignal = 0L

        val isRunning: Boolean
            get() = instance != null

        /**
         * Reads the currently visible texts. Returns null when the
         * accessibility service is not enabled or reading failed.
         */
        fun collectCurrentTexts(maxNodes: Int = 220): List<ScreenTextItem>? {
            val service = instance ?: return null
            return try {
                service.collectTexts(maxNodes)
            } catch (t: Throwable) {
                null
            }
        }
    }
}
