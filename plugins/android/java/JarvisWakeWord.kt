package com.jarvis.newly

import android.content.Context
import com.rementia.openwakeword.lib.model.DetectionMode
import com.rementia.openwakeword.lib.WakeWordEngine
import com.rementia.openwakeword.lib.model.WakeWordModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Thin Java-callable wrapper around xyz.rementia:openwakeword so JarvisService.java
 * (plain Java) never has to deal with Kotlin Flows/coroutines directly.
 *
 * Listens for a single wake word, "Jarvis", using the ONNX model bundled at
 * assets/jarvis.onnx (plus the shared assets/melspectrogram.onnx and
 * assets/embedding_model.onnx). See plugins/android/assets/README.md for how
 * to obtain those three files — they are binary model weights, not something
 * code can generate, and must be supplied before building.
 *
 * If the model files are missing or invalid, [start] reports the failure via
 * [StartListener.onFailed] instead of crashing the service; the caller
 * (JarvisService) falls back to Idle and the app's push-to-talk button still
 * works regardless.
 */
class JarvisWakeWord(private val context: Context, private val onWake: Runnable) {

    interface StartListener {
        fun onFailed(reason: String)
    }

    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.Default + job)
    private var engine: WakeWordEngine? = null

    fun start(listener: StartListener?) {
        try {
            val models = listOf(WakeWordModel("Jarvis", "jarvis.onnx", 0.12f))
            val e = WakeWordEngine(
                context = context,
                models = models,
                detectionMode = DetectionMode.SINGLE_BEST,
                detectionCooldownMs = 1500L
            )
            engine = e
            scope.launch {
                e.detections.collect { _ -> onWake.run() }
            }
            e.start()
        } catch (ex: Exception) {
            listener?.onFailed(ex.message ?: "wake word engine failed to start")
        }
    }

    fun stop() {
        try {
            engine?.stop()
        } catch (ignored: Exception) {
        }
    }

    fun release() {
        try {
            engine?.release()
        } catch (ignored: Exception) {
        }
        try {
            job.cancel()
        } catch (ignored: Exception) {
        }
        engine = null
    }
}
