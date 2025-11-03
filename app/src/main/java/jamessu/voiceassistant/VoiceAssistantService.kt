package jamessu.voiceassistant

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineManager
import ai.picovoice.porcupine.PorcupineManagerCallback
import kotlinx.coroutines.*

class VoiceAssistantService : Service() {

    companion object {
        private const val TAG = "VoiceAssistantService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "VoiceAssistantChannel"
        const val ACTION_START = "START_SERVICE"
        const val ACTION_STOP = "STOP_SERVICE"
    }

    private var porcupineManager: PorcupineManager? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var apiService: ApiService? = null

    // 👇 改用 BuildConfig
    private val accessKey = BuildConfig.PORCUPINE_ACCESS_KEY

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")
        apiService = ApiService()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification("等待啟動..."))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: ${intent?.action}")
        when (intent?.action) {
            ACTION_START -> {
                startVoiceAssistant()
            }
            ACTION_STOP -> {
                stopVoiceAssistant()
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startVoiceAssistant() {
        try {
            Log.d(TAG, "Starting voice assistant")

            // 初始化 Porcupine
            porcupineManager = PorcupineManager.Builder()
                .setAccessKey(accessKey)
                .setKeyword(Porcupine.BuiltInKeyword.PORCUPINE)
                .setSensitivity(0.5f)
                .build(this, object : PorcupineManagerCallback {
                    override fun invoke(keywordIndex: Int) {
                        Log.d(TAG, "Wake word detected!")
                        onWakeWordDetected()
                    }
                })

            porcupineManager?.start()
            updateNotification("正在監聽喚醒詞「Porcupine」...")
            Log.d(TAG, "Porcupine started successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start voice assistant", e)
            e.printStackTrace()
            Toast.makeText(this, "啟動失敗：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun onWakeWordDetected() {
        Log.d(TAG, "onWakeWordDetected called")

        // 暫停 Porcupine
        porcupineManager?.stop()
        updateNotification("偵測到喚醒詞，請說話...")

        // 顯示通知（背景時用戶看不到 Toast）
        showDetectionNotification()

        // 等待後啟動語音識別
        serviceScope.launch {
            delay(300)
            startSpeechRecognition()
        }
    }

    private fun showDetectionNotification() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("偵測到喚醒詞！")
            .setContentText("請說話...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(2002, notification)
    }

    private fun startSpeechRecognition() {
        Log.d(TAG, "Starting speech recognition")

        // 銷毀舊的 recognizer
        speechRecognizer?.destroy()

        // 創建新的 recognizer
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "onReadyForSpeech")
                updateNotification("請說話...")
            }

            override fun onBeginningOfSpeech() {
                Log.d(TAG, "onBeginningOfSpeech")
                updateNotification("正在聆聽...")
            }

            override fun onRmsChanged(rmsdB: Float) {}

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                Log.d(TAG, "onEndOfSpeech")
                updateNotification("處理中...")
            }

            override fun onError(error: Int) {
                val message = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "聽不清楚"
                    SpeechRecognizer.ERROR_NETWORK -> "網路問題"
                    SpeechRecognizer.ERROR_AUDIO -> "麥克風問題"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "缺少權限"
                    else -> "識別失敗 (錯誤代碼: $error)"
                }
                Log.e(TAG, "Speech recognition error: $error - $message")

                showErrorNotification(message)

                // 恢復監聽
                resumeWakeWordDetection()
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(
                    SpeechRecognizer.RESULTS_RECOGNITION
                )

                Log.d(TAG, "onResults: $matches")

                matches?.firstOrNull()?.let { spokenText ->
                    Log.d(TAG, "Recognized text: $spokenText")
                    processVoiceCommand(spokenText)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                Log.d(TAG, "onPartialResults")
            }

            override fun onEvent(eventType: Int, params: Bundle?) {
                Log.d(TAG, "onEvent: $eventType")
            }
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-TW")
            putExtra(
                "android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES",
                arrayOf("en-US")
            )
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }

        try {
            speechRecognizer?.startListening(intent)
            Log.d(TAG, "Speech recognizer started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start speech recognition", e)
            showErrorNotification("啟動語音識別失敗")
            resumeWakeWordDetection()
        }
    }

    private fun processVoiceCommand(spokenText: String) {
        Log.d(TAG, "Processing command: $spokenText")
        updateNotification("處理指令：$spokenText")

        serviceScope.launch {
            try {
                val command = apiService?.processCommand(spokenText)
                Log.d(TAG, "Command result: $command")

                if (command is AppCommand.OpenApp) {
                    openApp(command)
                    showSuccessNotification("執行：$spokenText → ${command.appName}")
                } else {
                    showErrorNotification("無法識別：$spokenText")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to process command", e)
                e.printStackTrace()
                showErrorNotification("處理失敗：${e.message}")
            } finally {
                delay(1000)
                resumeWakeWordDetection()
            }
        }
    }

    private fun openApp(command: AppCommand.OpenApp) {
        try {
            Log.d(TAG, "Attempting to open: ${command.packageName}")

            val launchIntent = packageManager.getLaunchIntentForPackage(command.packageName)
            val notificationBuilder = NotificationCompat.Builder(this, "LAUNCH_CHANNEL")  // 👈 改這裡
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Android 10+ 使用多重策略

                    // 策略 1: 嘗試直接啟動（通常會失敗，但試試看）
                    try {
                        startActivity(launchIntent)
                        Log.d(TAG, "Direct launch attempted")
                    } catch (e: Exception) {
                        Log.e(TAG, "Direct launch failed: ${e.message}")
                    }

                    // 策略 2: 使用 PendingIntent + 通知（主要方法）
                    val pendingIntent = PendingIntent.getActivity(
                        this,
                        command.packageName.hashCode(),
                        launchIntent,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )

                    // 創建一個高優先級、全屏的通知
                    val notificationBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
                        .setContentTitle("開啟 ${command.appName}")
                        .setContentText("正在啟動應用程式...")
                        .setSmallIcon(android.R.drawable.ic_menu_send)
                        .setPriority(NotificationCompat.PRIORITY_MAX)
                        .setCategory(NotificationCompat.CATEGORY_ALARM)  // 使用鬧鐘類別
                        .setFullScreenIntent(pendingIntent, true)
                        .setContentIntent(pendingIntent)
                        .setAutoCancel(true)
                        .setOngoing(false)
                        .setTimeoutAfter(3000)

                    // 如果支援，設置為時間敏感通知
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        notificationBuilder.setCategory(NotificationCompat.CATEGORY_CALL)
                    }

                    val notification = notificationBuilder.build()

                    val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    val notificationId = 3000 + command.packageName.hashCode()
                    notificationManager.notify(notificationId, notification)

                    Log.d(TAG, "Notification sent with full screen intent")

                    // 策略 3: 延遲後嘗試透過 PendingIntent.send()
                    serviceScope.launch {
                        delay(200)
                        try {
                            pendingIntent.send()
                            Log.d(TAG, "PendingIntent.send() executed")
                        } catch (e: Exception) {
                            Log.e(TAG, "PendingIntent.send() failed: ${e.message}")
                        }

                        // 3 秒後取消通知（如果還在）
                        delay(3000)
                        notificationManager.cancel(notificationId)
                    }

                    showSuccessNotification("嘗試開啟 ${command.appName}")
                } else {
                    // Android 9 及以下直接啟動
                    startActivity(launchIntent)
                    showSuccessNotification("已開啟 ${command.appName}")
                }

                Log.d(TAG, "Launch sequence completed for: ${command.appName}")
            } else {
                Log.e(TAG, "App not found: ${command.packageName}")
                showErrorNotification("找不到應用程式：${command.appName}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open app", e)
            e.printStackTrace()
            showErrorNotification("開啟失敗：${e.message}")
        }
    }

    private fun showSuccessNotification(message: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("✓ 成功")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_menu_send)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(2003, notification)
    }

    private fun showErrorNotification(message: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("✗ 錯誤")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(2004, notification)
    }

    private fun resumeWakeWordDetection() {
        Log.d(TAG, "Resuming wake word detection")
        serviceScope.launch {
            delay(500)
            speechRecognizer?.destroy()
            speechRecognizer = null
            try {
                porcupineManager?.start()
                updateNotification("正在監聽喚醒詞「Porcupine」...")
                Log.d(TAG, "Wake word detection resumed")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to resume wake word detection", e)
            }
        }
    }

    private fun stopVoiceAssistant() {
        Log.d(TAG, "Stopping voice assistant")
        porcupineManager?.stop()
        porcupineManager?.delete()
        speechRecognizer?.destroy()
        serviceScope.cancel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // 主要的服務通知 Channel
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "語音助手服務",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "語音助手背景運行通知"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(serviceChannel)

            // 高優先級的啟動通知 Channel
            val launchChannel = NotificationChannel(
                "LAUNCH_CHANNEL",
                "應用程式啟動",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "用於啟動應用程式的通知"
                setShowBadge(true)
                enableVibration(true)
                setSound(null, null)  // 不要聲音
            }
            notificationManager.createNotificationChannel(launchChannel)
        }
    }

    private fun createNotification(content: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, VoiceAssistantService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("語音助手運行中")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "停止",
                stopPendingIntent
            )
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(content: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE)
                as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, createNotification(content))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service onDestroy")
        stopVoiceAssistant()
    }
}
