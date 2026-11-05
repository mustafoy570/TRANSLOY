package com.dari.screentranslator.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.dari.screentranslator.model.ScreenTextItem
import com.dari.screentranslator.model.TextSource

/**
 * Fast path for reading the screen: pulls visible text nodes and their
 * bounds from the accessibility tree on demand, when the floating button
 * is tapped. No events are processed outside of that.
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
        // Text is pulled on demand when the floating button is tapped.
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

                val text = node.text?.toString()?.trim().orEmpty()
                    .ifEmpty { node.contentDescription?.toString()?.trim().orEmpty() }
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
