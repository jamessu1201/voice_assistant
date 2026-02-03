package jamessu.voiceassistant

import android.Manifest
import android.app.*
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineManager
import ai.picovoice.porcupine.PorcupineManagerCallback
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

class VoiceAssistantService : Service() {

    companion object {
        private const val TAG = "VoiceAssistantService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "VoiceAssistantChannel"
        const val ACTION_START = "START_SERVICE"
        const val ACTION_STOP = "STOP_SERVICE"

        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private var porcupineManager: PorcupineManager? = null

    // 音頻錄製
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordingJob: Job? = null

    private var apiService: ApiService? = null
    private var spotifyController: SpotifyController? = null

    // TTS
    private var textToSpeech: TextToSpeech? = null
    private var isTtsReady = false
    private var isSpeaking = false

    // ==================== 藍牙音頻設備檢測 ====================
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var isBluetoothEnabled = false

    // 🆕 改為追蹤「音頻設備」連接狀態，而非所有藍牙設備
    private var isAudioDeviceConnected = false

    // 🆕 A2DP 和 Headset Profile 代理
    private var a2dpProxy: BluetoothA2dp? = null
    private var headsetProxy: BluetoothHeadset? = null

    // 🆕 已連接的音頻設備列表
    private val connectedAudioDevices = mutableSetOf<String>()

    // 休眠管理
    private var isSleeping = false
    private var sleepTimer: Job? = null
    private val sleepAfterInactivity = 5 * 60 * 1000L  // 5 分鐘

    private val accessKey = BuildConfig.PORCUPINE_ACCESS_KEY
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // ==================== A2DP Profile 監聽器 ====================
    private val a2dpServiceListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == BluetoothProfile.A2DP) {
                a2dpProxy = proxy as BluetoothA2dp
                Log.d(TAG, "A2DP Profile 已連接")

                // 檢查當前已連接的 A2DP 設備
                checkConnectedA2dpDevices()
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.A2DP) {
                a2dpProxy = null
                Log.d(TAG, "A2DP Profile 已斷開")
            }
        }
    }

    // ==================== Headset Profile 監聽器 ====================
    private val headsetServiceListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == BluetoothProfile.HEADSET) {
                headsetProxy = proxy as BluetoothHeadset
                Log.d(TAG, "Headset Profile 已連接")

                // 檢查當前已連接的 Headset 設備
                checkConnectedHeadsetDevices()
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HEADSET) {
                headsetProxy = null
                Log.d(TAG, "Headset Profile 已斷開")
            }
        }
    }

    // ==================== 藍牙廣播接收器（改進版）====================
    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent?.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent?.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }

            when (intent?.action) {
                // 🆕 A2DP 連接狀態變化（高品質音頻，如耳機播放音樂）
                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED)
                    handleA2dpStateChange(device, state)
                }

                // 🆕 Headset 連接狀態變化（通話耳機）
                BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED)
                    handleHeadsetStateChange(device, state)
                }

                // 藍牙開關狀態
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    when (state) {
                        BluetoothAdapter.STATE_ON -> {
                            Log.d(TAG, "藍牙已開啟")
                            isBluetoothEnabled = true
                            // 重新獲取 Profile 代理
                            initializeBluetoothProfiles()
                        }
                        BluetoothAdapter.STATE_OFF -> {
                            Log.d(TAG, "藍牙已關閉")
                            isBluetoothEnabled = false
                            connectedAudioDevices.clear()
                            onAudioDeviceStateChanged(false)
                        }
                    }
                }

                // 🆕 通用 ACL 連接（作為備用檢測）
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    if (device != null && isAudioDevice(device)) {
                        Log.d(TAG, "🎧 音頻設備 ACL 連接：${getDeviceName(device)}")
                        // A2DP/Headset 事件會更準確，這裡只做備用
                    } else {
                        Log.d(TAG, "⌚ 非音頻設備連接：${getDeviceName(device)}（忽略）")
                    }
                }

                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    if (device != null && isAudioDevice(device)) {
                        Log.d(TAG, "🎧 音頻設備 ACL 斷開：${getDeviceName(device)}")
                    } else {
                        Log.d(TAG, "⌚ 非音頻設備斷開：${getDeviceName(device)}（忽略）")
                    }
                }
            }
        }
    }

    // ==================== A2DP 狀態處理 ====================
    private fun handleA2dpStateChange(device: BluetoothDevice?, state: Int) {
        val deviceName = getDeviceName(device)
        val deviceAddress = device?.address ?: return

        when (state) {
            BluetoothProfile.STATE_CONNECTED -> {
                // 🆕 檢查是否為真正的音頻設備（排除手錶等）
                if (device != null && !isWearableDevice(device) && isAudioDevice(device)) {
                    Log.d(TAG, "🎧 A2DP 音頻設備已連接：$deviceName")
                    connectedAudioDevices.add(deviceAddress)
                    onAudioDeviceStateChanged(true)
                } else {
                    Log.d(TAG, "⌚ A2DP 非音頻設備已連接（忽略）：$deviceName")
                }
            }
            BluetoothProfile.STATE_DISCONNECTED -> {
                if (connectedAudioDevices.contains(deviceAddress)) {
                    Log.d(TAG, "🎧 A2DP 音頻設備已斷開：$deviceName")
                    connectedAudioDevices.remove(deviceAddress)

                    // 只有當沒有任何音頻設備連接時才觸發斷開
                    if (connectedAudioDevices.isEmpty()) {
                        onAudioDeviceStateChanged(false)
                    } else {
                        Log.d(TAG, "仍有 ${connectedAudioDevices.size} 個音頻設備連接中")
                    }
                } else {
                    Log.d(TAG, "⌚ A2DP 非音頻設備已斷開（忽略）：$deviceName")
                }
            }
            BluetoothProfile.STATE_CONNECTING -> {
                Log.d(TAG, "🔄 A2DP 連接中：$deviceName")
            }
            BluetoothProfile.STATE_DISCONNECTING -> {
                Log.d(TAG, "🔄 A2DP 斷開中：$deviceName")
            }
        }
    }

    // ==================== Headset 狀態處理 ====================
    private fun handleHeadsetStateChange(device: BluetoothDevice?, state: Int) {
        val deviceName = getDeviceName(device)
        val deviceAddress = device?.address ?: return

        when (state) {
            BluetoothProfile.STATE_CONNECTED -> {
                // 🆕 檢查是否為真正的音頻設備（排除手錶等）
                if (device != null && !isWearableDevice(device) && isAudioDevice(device)) {
                    Log.d(TAG, "🎧 Headset 音頻設備已連接：$deviceName")
                    connectedAudioDevices.add(deviceAddress)
                    onAudioDeviceStateChanged(true)
                } else {
                    Log.d(TAG, "⌚ Headset 非音頻設備已連接（忽略）：$deviceName")
                }
            }
            BluetoothProfile.STATE_DISCONNECTED -> {
                if (connectedAudioDevices.contains(deviceAddress)) {
                    Log.d(TAG, "🎧 Headset 音頻設備已斷開：$deviceName")
                    connectedAudioDevices.remove(deviceAddress)

                    if (connectedAudioDevices.isEmpty()) {
                        onAudioDeviceStateChanged(false)
                    }
                } else {
                    Log.d(TAG, "⌚ Headset 非音頻設備已斷開（忽略）：$deviceName")
                }
            }
        }
    }

    // ==================== 判斷是否為穿戴設備（手錶/手環）====================
    private fun isWearableDevice(device: BluetoothDevice): Boolean {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                    return false
                }
            }

            val bluetoothClass = device.bluetoothClass
            val majorClass = bluetoothClass?.majorDeviceClass
            val deviceClass = bluetoothClass?.deviceClass

            // 主要類別：穿戴設備
            if (majorClass == BluetoothClass.Device.Major.WEARABLE) {
                Log.d(TAG, "設備類型：WEARABLE (${getDeviceName(device)})")
                return true
            }

            // 特定穿戴設備類別
            val wearableClasses = listOf(
                BluetoothClass.Device.WEARABLE_WRIST_WATCH,      // 手錶
                BluetoothClass.Device.WEARABLE_PAGER,            // 傳呼機
                BluetoothClass.Device.WEARABLE_JACKET,           // 夾克
                BluetoothClass.Device.WEARABLE_HELMET,           // 頭盔
                BluetoothClass.Device.WEARABLE_GLASSES,          // 眼鏡
                BluetoothClass.Device.WEARABLE_UNCATEGORIZED     // 未分類穿戴
            )

            if (deviceClass != null && deviceClass in wearableClasses) {
                Log.d(TAG, "設備類型：Wearable Class (${getDeviceName(device)})")
                return true
            }

            // 檢查設備名稱（某些設備可能沒有正確設置 Class）
            val deviceName = getDeviceName(device).lowercase()
            val wearableKeywords = listOf(
                "watch", "band", "fit", "mi band", "miband",
                "galaxy watch", "apple watch", "garmin", "fitbit",
                "amazfit", "huawei watch", "honor band", "xiaomi band",
                "vivoactive", "forerunner", "fenix", "vivosmart"
            )

            if (wearableKeywords.any { deviceName.contains(it) }) {
                Log.d(TAG, "設備名稱匹配穿戴關鍵字：${getDeviceName(device)}")
                return true
            }

            return false

        } catch (e: SecurityException) {
            Log.e(TAG, "檢查穿戴設備失敗：缺少權限", e)
            return false
        }
    }

    // ==================== 判斷是否為音頻設備 ====================
    private fun isAudioDevice(device: BluetoothDevice): Boolean {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                    return false
                }
            }

            val bluetoothClass = device.bluetoothClass ?: return false
            val majorClass = bluetoothClass.majorDeviceClass
            val deviceClass = bluetoothClass.deviceClass

            // 主要類別：音頻/視頻設備
            if (majorClass == BluetoothClass.Device.Major.AUDIO_VIDEO) {
                Log.d(TAG, "設備類型：AUDIO_VIDEO (${getDeviceName(device)})")
                return true
            }

            // 特定設備類別
            val audioDeviceClasses = listOf(
                BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES,           // 耳機
                BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET,     // 耳麥
                BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE,            // 免持裝置
                BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO,            // 車載音響
                BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER,          // 揚聲器
                BluetoothClass.Device.AUDIO_VIDEO_HIFI_AUDIO,           // HiFi 音響
                BluetoothClass.Device.AUDIO_VIDEO_PORTABLE_AUDIO,       // 便攜音頻
                BluetoothClass.Device.AUDIO_VIDEO_UNCATEGORIZED         // 未分類音頻設備
            )

            if (deviceClass in audioDeviceClasses) {
                Log.d(TAG, "設備類型：Audio Device Class (${getDeviceName(device)})")
                return true
            }

            // 檢查設備名稱（某些設備可能沒有正確設置 Class）
            val deviceName = getDeviceName(device).lowercase()
            val audioKeywords = listOf(
                "airpods", "buds", "earphone", "earbuds", "headphone", "headset",
                "speaker", "soundbar", "audio", "car", "jbl", "sony", "bose",
                "beats", "jabra", "sennheiser", "anker", "soundcore"
            )

            if (audioKeywords.any { deviceName.contains(it) }) {
                Log.d(TAG, "設備名稱匹配音頻關鍵字：${getDeviceName(device)}")
                return true
            }

            return false

        } catch (e: SecurityException) {
            Log.e(TAG, "檢查設備類型失敗：缺少權限", e)
            return false
        }
    }

    // ==================== 取得設備名稱（安全版本）====================
    private fun getDeviceName(device: BluetoothDevice?): String {
        if (device == null) return "未知設備"

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                    return "未知設備（無權限）"
                }
            }
            device.name ?: device.address ?: "未知設備"
        } catch (e: SecurityException) {
            device.address ?: "未知設備"
        }
    }

    // ==================== 檢查已連接的 A2DP 設備 ====================
    private fun checkConnectedA2dpDevices() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                    Log.w(TAG, "缺少 BLUETOOTH_CONNECT 權限")
                    return
                }
            }

            val connectedDevices = a2dpProxy?.connectedDevices ?: emptyList()

            Log.d(TAG, "當前 A2DP 連接設備數：${connectedDevices.size}")

            for (device in connectedDevices) {
                val deviceName = getDeviceName(device)

                // 🆕 過濾穿戴設備
                if (isWearableDevice(device)) {
                    Log.d(TAG, "  ⌚ $deviceName (${device.address}) - 穿戴設備，忽略")
                    continue
                }

                if (isAudioDevice(device)) {
                    Log.d(TAG, "  🎧 $deviceName (${device.address}) - 音頻設備")
                    connectedAudioDevices.add(device.address)
                } else {
                    Log.d(TAG, "  ❓ $deviceName (${device.address}) - 未知類型，忽略")
                }
            }

            if (connectedAudioDevices.isNotEmpty()) {
                onAudioDeviceStateChanged(true)
            }

        } catch (e: SecurityException) {
            Log.e(TAG, "檢查 A2DP 設備失敗", e)
        }
    }

    // ==================== 檢查已連接的 Headset 設備 ====================
    private fun checkConnectedHeadsetDevices() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                    return
                }
            }

            val connectedDevices = headsetProxy?.connectedDevices ?: emptyList()

            Log.d(TAG, "當前 Headset 連接設備數：${connectedDevices.size}")

            for (device in connectedDevices) {
                val deviceName = getDeviceName(device)

                // 🆕 過濾穿戴設備
                if (isWearableDevice(device)) {
                    Log.d(TAG, "  ⌚ $deviceName (${device.address}) - 穿戴設備，忽略")
                    continue
                }

                if (isAudioDevice(device)) {
                    Log.d(TAG, "  🎧 $deviceName (${device.address}) - 音頻設備")
                    connectedAudioDevices.add(device.address)
                } else {
                    Log.d(TAG, "  ❓ $deviceName (${device.address}) - 未知類型，忽略")
                }
            }

            if (connectedAudioDevices.isNotEmpty()) {
                onAudioDeviceStateChanged(true)
            }

        } catch (e: SecurityException) {
            Log.e(TAG, "檢查 Headset 設備失敗", e)
        }
    }

    // ==================== 音頻設備狀態改變處理 ====================
    private fun onAudioDeviceStateChanged(connected: Boolean) {
        // 避免重複觸發
        if (isAudioDeviceConnected == connected) {
            Log.d(TAG, "音頻設備狀態未變化，忽略")
            return
        }

        isAudioDeviceConnected = connected
        Log.d(TAG, "🎧 音頻設備狀態改變：${if (connected) "已連接" else "已斷開"}")

        if (connected) {
            // 連接音頻設備：進入騎車模式，取消休眠
            Log.d(TAG, "→ 進入騎車模式（持續監聽）")
            cancelSleepTimer()

            if (isSleeping) {
                wakeUp()
            }

            updateNotification("🏍️ 騎車模式（${connectedAudioDevices.size} 個音頻設備）")

        } else {
            // 斷開音頻設備：進入省電模式
            Log.d(TAG, "→ 進入省電模式（5分鐘後休眠）")
            updateNotification("🔋 省電模式（5分鐘後休眠）")
            startSleepTimer()
        }
    }

    // ==================== 初始化藍牙 Profile ====================
    private fun initializeBluetoothProfiles() {
        try {
            // 獲取 A2DP Profile 代理
            bluetoothAdapter?.getProfileProxy(this, a2dpServiceListener, BluetoothProfile.A2DP)

            // 獲取 Headset Profile 代理
            bluetoothAdapter?.getProfileProxy(this, headsetServiceListener, BluetoothProfile.HEADSET)

            Log.d(TAG, "正在獲取藍牙 Profile 代理...")

        } catch (e: Exception) {
            Log.e(TAG, "初始化藍牙 Profile 失敗", e)
        }
    }

    // ==================== 初始化藍牙檢測（改進版）====================
    private fun initializeBluetoothDetection() {
        try {
            val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            bluetoothAdapter = bluetoothManager?.adapter

            if (bluetoothAdapter == null) {
                Log.w(TAG, "此設備不支援藍牙")
                return
            }

            isBluetoothEnabled = bluetoothAdapter?.isEnabled == true
            Log.d(TAG, "藍牙已${if (isBluetoothEnabled) "開啟" else "關閉"}")

            // 🆕 註冊藍牙廣播接收器（包含 A2DP 和 Headset 事件）
            val filter = IntentFilter().apply {
                // A2DP 連接狀態（高品質音頻）
                addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
                // Headset 連接狀態（通話）
                addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
                // 藍牙開關
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                // ACL 連接（備用）
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(bluetoothReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(bluetoothReceiver, filter)
            }

            Log.d(TAG, "藍牙音頻設備監聽已啟用")

            // 🆕 初始化 Profile 代理以檢查當前連接的設備
            if (isBluetoothEnabled) {
                initializeBluetoothProfiles()
            }

        } catch (e: SecurityException) {
            Log.e(TAG, "缺少藍牙權限", e)
        } catch (e: Exception) {
            Log.e(TAG, "初始化藍牙檢測失敗", e)
        }
    }

    // ==================== 開始休眠計時器 ====================
    private fun startSleepTimer() {
        cancelSleepTimer()

        sleepTimer = serviceScope.launch {
            Log.d(TAG, "開始休眠倒數：5 分鐘")
            delay(sleepAfterInactivity)
            enterSleepMode()
        }
    }

    // ==================== 取消休眠計時器 ====================
    private fun cancelSleepTimer() {
        sleepTimer?.cancel()
        sleepTimer = null
    }

    // ==================== 進入休眠模式 ====================
    private fun enterSleepMode() {
        if (isSleeping) return

        Log.d(TAG, "💤 進入休眠模式")
        isSleeping = true

        try {
            porcupineManager?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "停止 Porcupine 失敗", e)
        }

        updateNotification("💤 休眠中（連接耳機自動喚醒）")

        showSleepNotification()
    }

    // ==================== 離開休眠模式 ====================
    private fun wakeUp() {
        if (!isSleeping) return

        Log.d(TAG, "⏰ 離開休眠模式")
        isSleeping = false

        try {
            porcupineManager?.start()
            updateNotification("🎤 正在監聽喚醒詞")
        } catch (e: Exception) {
            Log.e(TAG, "喚醒失敗", e)
        }
    }

    // ==================== 休眠提示通知 ====================
    private fun showSleepNotification() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("💤 語音助手已休眠")
            .setContentText("連接藍牙耳機將自動喚醒")  // 🆕 改為「耳機」
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setTimeoutAfter(3000)
            .build()

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(2005, notification)
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")

        apiService = ApiService(this)
        spotifyController = SpotifyController(this)

        initializeTextToSpeech()
        initializeBluetoothDetection()

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification("等待啟動..."))
    }

    private fun initializeTextToSpeech() {
        textToSpeech = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                Log.d(TAG, "TTS 初始化狀態：SUCCESS")

                val result = textToSpeech?.setLanguage(Locale.TRADITIONAL_CHINESE)

                Log.d(TAG, "設置繁體中文結果：$result")

                when (result) {
                    TextToSpeech.LANG_MISSING_DATA, TextToSpeech.LANG_NOT_SUPPORTED -> {
                        Log.w(TAG, "繁體中文不支援，嘗試簡體中文")
                        val zhCN = textToSpeech?.setLanguage(Locale.SIMPLIFIED_CHINESE)
                        if (zhCN == TextToSpeech.LANG_MISSING_DATA || zhCN == TextToSpeech.LANG_NOT_SUPPORTED) {
                            Log.w(TAG, "簡體中文也不支援，使用英文")
                            textToSpeech?.setLanguage(Locale.US)
                        }
                    }
                    else -> Log.d(TAG, "語言設定成功")
                }

                textToSpeech?.setSpeechRate(0.9f)
                textToSpeech?.setPitch(1.0f)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    val audioAttributes = android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()

                    textToSpeech?.setAudioAttributes(audioAttributes)
                    Log.d(TAG, "設置音頻屬性為 USAGE_ASSISTANT")
                }

                textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        isSpeaking = true
                        Log.d(TAG, "▶ TTS 開始說話")
                    }

                    override fun onDone(utteranceId: String?) {
                        isSpeaking = false
                        Log.d(TAG, "■ TTS 說完話")
                        resumeWakeWordDetection()
                    }

                    override fun onError(utteranceId: String?) {
                        isSpeaking = false
                        Log.e(TAG, "✗ TTS 錯誤")
                        resumeWakeWordDetection()
                    }
                })

                isTtsReady = true
                Log.d(TAG, "✓ TTS 初始化完成")
            } else {
                Log.e(TAG, "✗ TTS 初始化失敗，狀態碼：$status")
                isTtsReady = false
            }
        }
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

            porcupineManager = PorcupineManager.Builder()
                .setAccessKey(accessKey)
                .setKeyword(Porcupine.BuiltInKeyword.PORCUPINE)
                .setSensitivity(0.85f)
                .build(this, object : PorcupineManagerCallback {
                    override fun invoke(keywordIndex: Int) {
                        Log.d(TAG, "Wake word detected!")
                        onWakeWordDetected()
                    }
                })

            porcupineManager?.start()

            // 🆕 根據音頻設備狀態設定初始模式
            if (isAudioDeviceConnected) {
                updateNotification("🏍️ 騎車模式（${connectedAudioDevices.size} 個音頻設備）")
            } else {
                updateNotification("🎤 監聽中（5分鐘後休眠）")
                startSleepTimer()
            }

            Log.d(TAG, "Porcupine started successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start voice assistant", e)
            e.printStackTrace()
            Toast.makeText(this, "啟動失敗：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun onWakeWordDetected() {
        Log.d(TAG, "onWakeWordDetected called")

        playConfirmationSound()

        // 取消休眠計時器（有活動）
        if (!isAudioDeviceConnected) {
            cancelSleepTimer()
        }

        if (isSpeaking) {
            textToSpeech?.stop()
            isSpeaking = false
        }

        porcupineManager?.stop()
        updateNotification("偵測到喚醒詞，請說話...")

        showDetectionNotification()

        serviceScope.launch {
            delay(300)
            startAudioRecording()
        }
    }

    private fun playConfirmationSound() {
        try {
            val toneGen = android.media.ToneGenerator(
                android.media.AudioManager.STREAM_MUSIC,
                100
            )

            serviceScope.launch {
                toneGen.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 100)
                delay(150)
                toneGen.startTone(android.media.ToneGenerator.TONE_PROP_BEEP2, 100)
                delay(150)
                toneGen.release()
            }

            Log.d(TAG, "♪ 播放確認音效")
        } catch (e: Exception) {
            Log.e(TAG, "播放音效失敗", e)
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

    private fun startAudioRecording() {
        Log.d(TAG, "Starting audio recording")
        updateNotification("正在錄音...")

        serviceScope.launch(Dispatchers.IO) {
            try {
                val bufferSize = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT
                )

                if (ActivityCompat.checkSelfPermission(
                        this@VoiceAssistantService,
                        Manifest.permission.RECORD_AUDIO
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    Log.e(TAG, "沒有錄音權限")
                    withContext(Dispatchers.Main) {
                        showErrorNotification("沒有錄音權限")
                        resumeWakeWordDetection()
                    }
                    return@launch
                }

                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize
                )

                val audioFile = File(cacheDir, "recording_${System.currentTimeMillis()}.wav")
                val outputStream = FileOutputStream(audioFile)

                writeWavHeader(outputStream, SAMPLE_RATE, 1, 16)

                audioRecord?.startRecording()
                isRecording = true

                Log.d(TAG, "✓ 開始錄音")

                val buffer = ByteArray(bufferSize)
                var totalBytesRead = 0
                val maxDuration = 10000L
                val startTime = System.currentTimeMillis()

                // ==================== 🆕 動態閾值計算 ====================
                // 先收集 0.3 秒的背景噪音作為基準
                var baselineAmplitude = 0
                var baselineSamples = 0
                val baselineDuration = 300L  // 0.3 秒

                Log.d(TAG, "📊 收集背景噪音...")
                while ((System.currentTimeMillis() - startTime) < baselineDuration) {
                    val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (bytesRead > 0) {
                        outputStream.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead
                        baselineAmplitude += calculateAmplitude(buffer, bytesRead)
                        baselineSamples++
                    }
                }

                // 計算平均背景噪音
                val avgBaseline = if (baselineSamples > 0) baselineAmplitude / baselineSamples else 100

                // 動態設定閾值（根據環境自動調整）
                val silenceThreshold = maxOf(avgBaseline * 2, 100)      // 靜音 = 背景噪音 x2，最低 100
                val voiceThreshold = maxOf(avgBaseline * 5, 400)        // 語音 = 背景噪音 x5，最低 400
                val maxSilenceFrames = 30  // 約 1 秒靜音

                Log.d(TAG, "📊 背景噪音=$avgBaseline → 靜音閾值=$silenceThreshold, 語音閾值=$voiceThreshold")

                // 最小錄音時間保護（至少錄 1.5 秒才能因靜音停止）
                val minRecordingBytes = (SAMPLE_RATE * 2 * 1.5).toInt()

                var silenceCount = 0
                var hasDetectedVoice = false
                var maxAmplitude = avgBaseline

                // ==================== 主錄音循環 ====================
                while (isRecording && (System.currentTimeMillis() - startTime) < maxDuration) {
                    val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: 0

                    if (bytesRead > 0) {
                        outputStream.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead

                        val amplitude = calculateAmplitude(buffer, bytesRead)

                        if (amplitude > maxAmplitude) {
                            maxAmplitude = amplitude
                        }

                        if (amplitude > voiceThreshold) {
                            // 檢測到語音
                            hasDetectedVoice = true
                            silenceCount = 0
                        } else if (amplitude < silenceThreshold) {
                            // 低於靜音閾值
                            silenceCount++
                            if (hasDetectedVoice &&
                                silenceCount > maxSilenceFrames &&
                                totalBytesRead > minRecordingBytes) {
                                Log.d(TAG, "檢測到靜音，停止錄音 (amp=$amplitude < $silenceThreshold)")
                                break
                            }
                        } else {
                            // 介於兩者之間（可能是尾音或輕聲）
                            if (hasDetectedVoice) {
                                silenceCount++
                                if (silenceCount > maxSilenceFrames * 2 && totalBytesRead > minRecordingBytes) {
                                    Log.d(TAG, "檢測到低音量，停止錄音 (amp=$amplitude)")
                                    break
                                }
                            } else {
                                silenceCount = 0
                            }
                        }
                    }
                }

                val duration = (System.currentTimeMillis() - startTime) / 1000.0
                Log.d(TAG, "錄音統計：時長=${duration}s, baseline=$avgBaseline, maxAmp=$maxAmplitude, hasVoice=$hasDetectedVoice")

                isRecording = false
                audioRecord?.stop()
                audioRecord?.release()
                audioRecord = null

                updateWavHeader(audioFile, totalBytesRead)
                outputStream.close()

                Log.d(TAG, "✓ 錄音完成：${audioFile.absolutePath} (${totalBytesRead} bytes)")

                withContext(Dispatchers.Main) {
                    updateNotification("正在識別...")
                }

                transcribeWithWhisper(audioFile)

            } catch (e: Exception) {
                Log.e(TAG, "錄音失敗", e)
                withContext(Dispatchers.Main) {
                    showErrorNotification("錄音失敗")
                    resumeWakeWordDetection()
                }
            }
        }
    }

    private fun calculateAmplitude(buffer: ByteArray, length: Int): Int {
        var sum = 0L
        for (i in 0 until length step 2) {
            val sample = (buffer[i].toInt() or (buffer[i + 1].toInt() shl 8)).toShort()
            sum += Math.abs(sample.toInt())
        }
        return (sum / (length / 2)).toInt()
    }

    private fun writeWavHeader(
        out: FileOutputStream,
        sampleRate: Int,
        channels: Int,
        bitDepth: Int
    ) {
        val header = ByteArray(44)
        val byteRate = sampleRate * channels * bitDepth / 8

        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()

        header[4] = 0
        header[5] = 0
        header[6] = 0
        header[7] = 0

        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()

        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()

        header[16] = 16
        header[17] = 0
        header[18] = 0
        header[19] = 0

        header[20] = 1
        header[21] = 0

        header[22] = channels.toByte()
        header[23] = 0

        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte()
        header[27] = ((sampleRate shr 24) and 0xff).toByte()

        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()

        val blockAlign = channels * bitDepth / 8
        header[32] = blockAlign.toByte()
        header[33] = 0

        header[34] = bitDepth.toByte()
        header[35] = 0

        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()

        header[40] = 0
        header[41] = 0
        header[42] = 0
        header[43] = 0

        out.write(header)
    }

    private fun updateWavHeader(file: File, dataSize: Int) {
        val randomAccessFile = java.io.RandomAccessFile(file, "rw")

        randomAccessFile.seek(4)
        val fileSize = dataSize + 36
        randomAccessFile.write(fileSize and 0xff)
        randomAccessFile.write((fileSize shr 8) and 0xff)
        randomAccessFile.write((fileSize shr 16) and 0xff)
        randomAccessFile.write((fileSize shr 24) and 0xff)

        randomAccessFile.seek(40)
        randomAccessFile.write(dataSize and 0xff)
        randomAccessFile.write((dataSize shr 8) and 0xff)
        randomAccessFile.write((dataSize shr 16) and 0xff)
        randomAccessFile.write((dataSize shr 24) and 0xff)

        randomAccessFile.close()
    }

    private suspend fun transcribeWithWhisper(audioFile: File) {
        try {
            Log.d(TAG, "發送音頻到 Whisper...")

            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "audio",
                    audioFile.name,
                    audioFile.asRequestBody("audio/wav".toMediaTypeOrNull())
                )
                .build()

            val request = okhttp3.Request.Builder()
                .url("http://100.86.123.49:8080/transcribe")
                .post(requestBody)
                .build()

            withContext(Dispatchers.IO) {
                val response = client.newCall(request).execute()
                val responseBody = response.body?.string()

                Log.d(TAG, "Whisper 回應：$responseBody")

                if (response.isSuccessful && responseBody != null) {
                    val jsonObj = org.json.JSONObject(responseBody)
                    val text = jsonObj.getString("text")

                    if (text.isNotEmpty()) {
                        Log.d(TAG, "✓ Whisper 識別：$text")

                        withContext(Dispatchers.Main) {
                            processVoiceCommand(text)
                        }
                    } else {
                        Log.w(TAG, "Whisper 沒有識別到內容")
                        withContext(Dispatchers.Main) {
                            showErrorNotification("聽不清楚")
                            resumeWakeWordDetection()
                        }
                    }
                } else {
                    Log.e(TAG, "Whisper 請求失敗：${response.code}")
                    withContext(Dispatchers.Main) {
                        showErrorNotification("識別失敗")
                        resumeWakeWordDetection()
                    }
                }

                audioFile.delete()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Whisper 識別錯誤", e)
            withContext(Dispatchers.Main) {
                showErrorNotification("識別錯誤")
                resumeWakeWordDetection()
            }
            audioFile.delete()
        }
    }

    private fun processVoiceCommand(spokenText: String) {
        Log.d(TAG, "Processing command: $spokenText")
        updateNotification("處理指令：$spokenText")

        serviceScope.launch {
            try {
                val command = apiService?.processCommand(spokenText)
                Log.d(TAG, "Command result: $command")

                when (command) {
                    is AppCommand.OpenApp -> {
                        Log.d(TAG, "Executing OpenApp")
                        openApp(command)
                        showSuccessNotification("執行：$spokenText → ${command.appName}")
                    }
                    is AppCommand.SpotifyControl -> {
                        Log.d(TAG, "Handling Spotify control: ${command.command}, song: ${command.song}")
                        handleSpotifyControl(command)
                        delay(1000)
                        resumeWakeWordDetection()
                    }
                    is AppCommand.Speak -> {
                        Log.d(TAG, "Speaking: ${command.message}")
                        speak(command.message)
                    }
                    null -> {
                        Log.w(TAG, "Command is null")
                        showErrorNotification("無法識別：$spokenText")
                        delay(1000)
                        resumeWakeWordDetection()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to process command", e)
                e.printStackTrace()
                showErrorNotification("處理失敗：${e.message}")
                delay(1000)
                resumeWakeWordDetection()
            }
        }
    }

    private fun speak(text: String) {
        if (!isTtsReady) {
            Log.e(TAG, "TTS 尚未準備好")
            showErrorNotification("語音功能尚未準備好")
            resumeWakeWordDetection()
            return
        }

        if (isSpeaking) {
            textToSpeech?.stop()
        }

        Log.d(TAG, "TTS 說話：$text")
        updateNotification("回答：$text")

        val utteranceId = "utterance_${System.currentTimeMillis()}"
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, android.media.AudioManager.STREAM_MUSIC)
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }

        val result = textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)

        Log.d(TAG, "TTS speak() 返回值：$result")
        when (result) {
            TextToSpeech.SUCCESS -> Log.d(TAG, "TTS 呼叫成功")
            TextToSpeech.ERROR -> {
                Log.e(TAG, "TTS 呼叫失敗")
                showErrorNotification("語音播放失敗")
                resumeWakeWordDetection()
            }
        }
    }

    private fun openApp(command: AppCommand.OpenApp) {
        try {
            Log.d(TAG, "Attempting to open: ${command.packageName}")

            val launchIntent = packageManager.getLaunchIntentForPackage(command.packageName)

            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    try {
                        startActivity(launchIntent)
                        Log.d(TAG, "Direct launch attempted")
                    } catch (e: Exception) {
                        Log.e(TAG, "Direct launch failed: ${e.message}")
                    }

                    val pendingIntent = PendingIntent.getActivity(
                        this,
                        command.packageName.hashCode(),
                        launchIntent,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )

                    val notificationBuilder = NotificationCompat.Builder(this, "LAUNCH_CHANNEL")
                        .setContentTitle("開啟 ${command.appName}")
                        .setContentText("正在啟動應用程式...")
                        .setSmallIcon(android.R.drawable.ic_menu_send)
                        .setPriority(NotificationCompat.PRIORITY_MAX)
                        .setCategory(NotificationCompat.CATEGORY_ALARM)
                        .setFullScreenIntent(pendingIntent, true)
                        .setContentIntent(pendingIntent)
                        .setAutoCancel(true)
                        .setOngoing(false)
                        .setTimeoutAfter(3000)

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        notificationBuilder.setCategory(NotificationCompat.CATEGORY_CALL)
                    }

                    val notification = notificationBuilder.build()

                    val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    val notificationId = 3000 + command.packageName.hashCode()
                    notificationManager.notify(notificationId, notification)

                    Log.d(TAG, "Notification sent with full screen intent")

                    serviceScope.launch {
                        delay(200)
                        try {
                            pendingIntent.send()
                            Log.d(TAG, "PendingIntent.send() executed")
                        } catch (e: Exception) {
                            Log.e(TAG, "PendingIntent.send() failed: ${e.message}")
                        }

                        delay(3000)
                        notificationManager.cancel(notificationId)

                        resumeWakeWordDetection()
                    }

                    showSuccessNotification("嘗試開啟 ${command.appName}")
                } else {
                    startActivity(launchIntent)
                    showSuccessNotification("已開啟 ${command.appName}")

                    serviceScope.launch {
                        delay(1000)
                        resumeWakeWordDetection()
                    }
                }

                Log.d(TAG, "Launch sequence completed for: ${command.appName}")
            } else {
                Log.e(TAG, "App not found: ${command.packageName}")
                showErrorNotification("找不到應用程式：${command.appName}")
                resumeWakeWordDetection()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open app", e)
            e.printStackTrace()
            showErrorNotification("開啟失敗：${e.message}")
            resumeWakeWordDetection()
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
        if (isSpeaking) {
            Log.d(TAG, "Still speaking, will resume after TTS finishes")
            return
        }

        if (isSleeping) {
            Log.d(TAG, "Service is sleeping, not resuming")
            return
        }

        Log.d(TAG, "Resuming wake word detection")
        serviceScope.launch {
            delay(500)
            try {
                porcupineManager?.start()

                // 🆕 根據音頻設備狀態決定是否重新啟動休眠計時器
                if (isAudioDeviceConnected) {
                    updateNotification("🏍️ 騎車模式（持續監聽）")
                } else {
                    updateNotification("🎤 監聽中")
                    startSleepTimer()
                }

                Log.d(TAG, "Wake word detection resumed")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to resume wake word detection", e)
            }
        }
    }

    private fun stopVoiceAssistant() {
        Log.d(TAG, "Stopping voice assistant")

        cancelSleepTimer()

        if (isSpeaking) {
            textToSpeech?.stop()
        }
        textToSpeech?.shutdown()

        porcupineManager?.stop()
        porcupineManager?.delete()
        serviceScope.cancel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "語音助手服務",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "語音助手背景運行通知"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(serviceChannel)

            val launchChannel = NotificationChannel(
                "LAUNCH_CHANNEL",
                "應用程式啟動",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "用於啟動應用程式的通知"
                setShowBadge(true)
                enableVibration(true)
                setSound(null, null)
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

    private fun handleSpotifyControl(command: AppCommand.SpotifyControl) {
        val controller = spotifyController ?: run {
            showErrorNotification("Spotify 控制器未初始化")
            return
        }

        if (!controller.isSpotifyInstalled()) {
            showErrorNotification("未安裝 Spotify")
            return
        }

        if (!controller.isConnected()) {
            Log.d(TAG, "Spotify 未連接，嘗試連接...")
            updateNotification("正在連接 Spotify...")

            controller.connect(object : SpotifyController.SpotifyCallback {
                override fun onSuccess(message: String) {
                    Log.d(TAG, "✓ Spotify 已連接")
                    executeSpotifyCommand(controller, command)
                }

                override fun onError(error: String) {
                    Log.e(TAG, "✗ Spotify 連接失敗：$error")
                    showErrorNotification("Spotify 連接失敗")

                    serviceScope.launch {
                        delay(1000)
                        resumeWakeWordDetection()
                    }
                }
            })
        } else {
            executeSpotifyCommand(controller, command)
        }
    }

    private fun executeSpotifyCommand(
        controller: SpotifyController,
        command: AppCommand.SpotifyControl
    ) {
        val callback = object : SpotifyController.SpotifyCallback {
            override fun onSuccess(message: String) {
                showSuccessNotification(message)
            }

            override fun onError(error: String) {
                showErrorNotification(error)
            }
        }

        when (command.command) {
            "play" -> {
                if (command.song != null) {
                    controller.playSong(command.song, callback)
                } else {
                    controller.play(callback)
                }
            }
            "pause" -> controller.pause(callback)
            "next" -> controller.skipNext(callback)
            "previous" -> controller.skipPrevious(callback)
            else -> showErrorNotification("未知的 Spotify 指令")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service onDestroy")

        // 取消註冊藍牙監聽
        try {
            unregisterReceiver(bluetoothReceiver)
            Log.d(TAG, "藍牙監聽已取消")
        } catch (e: Exception) {
            Log.e(TAG, "取消藍牙監聽失敗", e)
        }

        // 🆕 關閉 Profile 代理
        try {
            a2dpProxy?.let { bluetoothAdapter?.closeProfileProxy(BluetoothProfile.A2DP, it) }
            headsetProxy?.let { bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HEADSET, it) }
            Log.d(TAG, "藍牙 Profile 代理已關閉")
        } catch (e: Exception) {
            Log.e(TAG, "關閉 Profile 代理失敗", e)
        }

        spotifyController?.disconnect()
        stopVoiceAssistant()
    }
}