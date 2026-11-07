package com.dari.screentranslator.service

import android.app.Activity
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Display
import android.view.Surface
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.dari.screentranslator.R
import com.dari.screentranslator.TranslatorApp

/**
 * Foreground service of type mediaProjection that owns the MediaProjection,
 * a VirtualDisplay mirror and an ImageReader.
 *
 * ANDROID 14+ RULE (the reason this class looks the way it does):
 * a MediaProjection may create its VirtualDisplay exactly ONCE. A second
 * [MediaProjection.createVirtualDisplay] call on the same consent throws
 * SecurityException on API 34+. Therefore:
 *  - [createCaptureSurface] is called exactly once per projection;
 *  - rotation is handled with [VirtualDisplay.resize] + [VirtualDisplay.setSurface]
 *    instead of recreating anything;
 *  - there is no "force a fresh frame" path that touches the VirtualDisplay.
 *
 * Frames are cached continuously by [frameListener] into [lastBitmap], so a
 * static screen (which pushes no new frames) can still be captured —
 * [capture] simply returns the cached frame.
 */
class ScreenCaptureService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val captureLock = Any()

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var displayWidth = 0
    private var displayHeight = 0
    private lateinit var display: Display

    /**
     * Latest screen frame, kept current by [frameListener].
     * Volatile reference; replaced bitmaps are never recycled manually
     * (heap bitmaps since API 26, GC reclaims them) because the OCR thread
     * may still be reading the previous one.
     */
    @Volatile
    private var lastBitmap: Bitmap? = null

    @Volatile
    private var lastFrameAt = 0L

    private val frameThread = HandlerThread("capture-frames").apply { start() }
    private val frameHandler = Handler(frameThread.looper)

    private val frameListener = ImageReader.OnImageAvailableListener { reader ->
        val image = try {
            reader.acquireLatestImage()
        } catch (t: Throwable) {
            null // Reader closed concurrently (rotation); keep previous frame.
        } ?: return@OnImageAvailableListener
        try {
            lastBitmap = imageToBitmap(image)
            lastFrameAt = SystemClock.uptimeMillis()
        } catch (t: Throwable) {
            // Conversion failed; the previous frame stays current.
        } finally {
            image.close()
        }
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            mainHandler.post { stopSelf() }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        val displayManager = getSystemService(DisplayManager::class.java)!!
        display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
            ?: displayManager.getDisplays().first()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION")
        val resultData: Intent? = intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        if (resultCode != Activity.RESULT_OK || resultData == null) {
            postFailed()
            stopSelf()
            return START_NOT_STICKY
        }

        val serviceType = if (Build.VERSION.SDK_INT >= 29) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), serviceType)

        // On Android 14+ the foreground service must already be running with
        // the mediaProjection type before getMediaProjection is called.
        startProjection(resultCode, resultData)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        release()
        frameThread.quitSafely()
        instance = null
        super.onDestroy()
    }

    private fun startProjection(resultCode: Int, resultData: Intent) {
        try {
            val projectionManager = getSystemService(MediaProjectionManager::class.java)!!
            val mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)
            mediaProjection.registerCallback(projectionCallback, mainHandler)
            projection = mediaProjection
            createCaptureSurface()
            if (imageReader != null && virtualDisplay != null) {
                mainHandler.post { onProjectionReady?.invoke() }
            } else {
                postFailed()
                stopSelf()
            }
        } catch (t: Throwable) {
            postFailed()
            stopSelf()
        }
    }

    /**
     * Creates the ImageReader and the single VirtualDisplay. Called exactly
     * ONCE per MediaProjection consent (see class doc). Rotation never calls
     * this again — see [ensureDisplayFresh].
     */
    private fun createCaptureSurface() {
        synchronized(captureLock) {
            releaseCaptureSurface()
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)
            displayWidth = metrics.widthPixels
            displayHeight = metrics.heightPixels
            val reader = ImageReader.newInstance(displayWidth, displayHeight, PixelFormat.RGBA_8888, 2)
            reader.setOnImageAvailableListener(frameListener, frameHandler)
            imageReader = reader
            // Android 14+ allows this exactly once per MediaProjection.
            virtualDisplay = projection?.createVirtualDisplay(
                "dari-screen-capture",
                displayWidth,
                displayHeight,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                null
            )
        }
    }

    /**
     * Captures the current screen as an upright ARGB bitmap. Blocking; must
     * be called off the main thread.
     *
     * Prefers a frame that arrived after the call started ("fresh"); when
     * the screen is static and no new frame arrives, falls back to the last
     * cached frame — which is by definition what the screen still shows.
     */
    fun capture(timeoutMs: Long = 2500L): Bitmap? {
        ensureDisplayFresh()
        val startMs = SystemClock.uptimeMillis()
        val freshDeadline = startMs + FRESH_FRAME_WAIT_MS
        val hardDeadline = startMs + timeoutMs
        var result: Bitmap? = null
        while (SystemClock.uptimeMillis() < hardDeadline) {
            val candidate = lastBitmap
            if (candidate != null) {
                result = candidate
                val now = SystemClock.uptimeMillis()
                if (lastFrameAt >= startMs || now >= freshDeadline) break
            }
            Thread.sleep(30)
        }
        // Return a COPY: the caller recycles the returned bitmap after OCR,
        // but lastBitmap must stay valid for the next capture.
        val source = result ?: return null
        val copy = try {
            source.copy(source.config ?: Bitmap.Config.ARGB_8888, false)
        } catch (t: Throwable) {
            null
        } ?: return null
        return rotateToCurrentOrientation(copy)
    }

    /**
     * Rotation handling WITHOUT recreating the VirtualDisplay (Android 14+
     * forbids a second createVirtualDisplay): swap in a new correctly sized
     * ImageReader surface and resize the existing mirror.
     */
    @Suppress("DEPRECATION")
    private fun ensureDisplayFresh() {
        synchronized(captureLock) {
            val metrics = DisplayMetrics()
            display.getRealMetrics(metrics)
            if (metrics.widthPixels == displayWidth && metrics.heightPixels == displayHeight) {
                return
            }
            displayWidth = metrics.widthPixels
            displayHeight = metrics.heightPixels
            val oldReader = imageReader
            val reader = ImageReader.newInstance(displayWidth, displayHeight, PixelFormat.RGBA_8888, 2)
            reader.setOnImageAvailableListener(frameListener, frameHandler)
            imageReader = reader
            try {
                virtualDisplay?.resize(displayWidth, displayHeight, metrics.densityDpi)
                virtualDisplay?.surface = reader.surface
            } catch (t: Throwable) {
                // Resize unsupported: the cached frame is still returned.
            }
            try {
                oldReader?.close()
            } catch (t: Throwable) {
                // Ignore.
            }
        }
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val width = image.width
        val height = image.height
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width
        val paddedWidth = width + rowPadding / pixelStride
        val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(buffer)
        return if (paddedWidth == width) {
            padded
        } else {
            val cropped = Bitmap.createBitmap(padded, 0, 0, width, height)
            padded.recycle()
            cropped
        }
    }

    private fun rotateToCurrentOrientation(bitmap: Bitmap): Bitmap {
        val rotation = display.rotation
        val needsRotation = when (rotation) {
            Surface.ROTATION_90 -> bitmap.width == displayHeight && bitmap.height == displayWidth
            Surface.ROTATION_270 -> bitmap.width == displayHeight && bitmap.height == displayWidth
            Surface.ROTATION_180 -> true
            else -> false
        }
        if (!needsRotation) return bitmap
        val degrees = when (rotation) {
            Surface.ROTATION_90 -> 90f
            Surface.ROTATION_180 -> 180f
            Surface.ROTATION_270 -> 270f
            else -> 0f
        }
        val matrix = Matrix().apply { postRotate(degrees) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated != bitmap) bitmap.recycle()
        return rotated
    }

    private fun release() {
        synchronized(captureLock) {
            try {
                virtualDisplay?.release()
            } catch (t: Throwable) {
                // Ignore.
            }
            virtualDisplay = null
            try {
                imageReader?.close()
            } catch (t: Throwable) {
                // Ignore.
            }
            imageReader = null
            lastBitmap = null
            try {
                projection?.stop()
            } catch (t: Throwable) {
                // Ignore.
            }
            projection = null
        }
    }

    private fun releaseCaptureSurface() {
        try {
            virtualDisplay?.release()
        } catch (t: Throwable) {
            // Ignore.
        }
        virtualDisplay = null
        try {
            imageReader?.close()
        } catch (t: Throwable) {
            // Ignore.
        }
        imageReader = null
    }

    private fun postFailed() {
        mainHandler.post { onProjectionFailed?.invoke() }
    }

    private fun buildNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            REQUEST_CODE_STOP,
            Intent(this, ScreenCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, TranslatorApp.CHANNEL_CAPTURE)
            .setSmallIcon(R.drawable.ic_translate)
            .setContentTitle(getString(R.string.notif_capture_title))
            .setContentText(getString(R.string.notif_capture_text))
            .setOngoing(true)
            .addAction(0, getString(R.string.notif_action_stop), stopIntent)
            .build()
    }

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val ACTION_STOP = "com.dari.screentranslator.action.STOP_CAPTURE"
        private const val NOTIFICATION_ID = 2
        private const val REQUEST_CODE_STOP = 21

        /** How long to wait for a frame newer than the capture() call before
         *  accepting the cached (static screen) frame. */
        private const val FRESH_FRAME_WAIT_MS = 450L

        @Volatile
        private var instance: ScreenCaptureService? = null

        val isRunning: Boolean
            get() = instance != null

        fun activeService(): ScreenCaptureService? = instance

        @Volatile
        var onProjectionReady: (() -> Unit)? = null

        @Volatile
        var onProjectionFailed: (() -> Unit)? = null

        fun start(context: Context, resultCode: Int, resultData: Intent) {
            val intent = Intent(context, ScreenCaptureService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, resultData)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ScreenCaptureService::class.java))
        }
    }
}
