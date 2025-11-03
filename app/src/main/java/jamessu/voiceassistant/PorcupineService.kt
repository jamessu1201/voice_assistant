package jamessu.voiceassistant

import android.content.Context
import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineManager
import ai.picovoice.porcupine.PorcupineManagerCallback

class PorcupineService(
    private val context: Context,
    private val onWakeWordDetected: () -> Unit
) {
    private var porcupineManager: PorcupineManager? = null

    // 👇 改用 BuildConfig
    private val accessKey = BuildConfig.PORCUPINE_ACCESS_KEY

    fun start() {
        try {
            porcupineManager = PorcupineManager.Builder()
                .setAccessKey(accessKey)
                .setKeyword(Porcupine.BuiltInKeyword.TERMINATOR)
                .setSensitivity(0.5f)
                .build(context, object : PorcupineManagerCallback {
                    override fun invoke(keywordIndex: Int) {
                        onWakeWordDetected()
                    }
                })

            porcupineManager?.start()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stop() {
        porcupineManager?.stop()
    }

    fun release() {
        porcupineManager?.delete()
    }
}