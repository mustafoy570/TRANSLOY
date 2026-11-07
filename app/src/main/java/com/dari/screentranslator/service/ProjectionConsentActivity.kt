package com.dari.screentranslator.service

import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.dari.screentranslator.R

/**
 * Invisible trampoline activity: shows the system MediaProjection consent
 * dialog and forwards the result to [ScreenCaptureService]. It is launched
 * from the overlay service (which is exempt from background activity start
 * restrictions because the app holds the overlay permission).
 */
class ProjectionConsentActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val projectionManager = getSystemService(MediaProjectionManager::class.java)!!
        val launcher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                ScreenCaptureService.start(this, result.resultCode, data)
            } else {
                // Reset the pending OCR request in the overlay service, or it
                // stays "waiting for projection" forever after a denial.
                ScreenCaptureService.onProjectionFailed?.invoke()
                Toast.makeText(this, R.string.toast_consent_denied, Toast.LENGTH_LONG).show()
            }
            finish()
        }
        launcher.launch(projectionManager.createScreenCaptureIntent())
    }
}
