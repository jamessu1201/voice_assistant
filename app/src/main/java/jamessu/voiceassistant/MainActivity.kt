package jamessu.voiceassistant

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.content.pm.PackageManager
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.spotify.sdk.android.auth.AuthorizationClient
import com.spotify.sdk.android.auth.AuthorizationRequest
import com.spotify.sdk.android.auth.AuthorizationResponse
import jamessu.voiceassistant.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val SPOTIFY_REQUEST_CODE = 1337

        private const val SPOTIFY_CLIENT_ID = BuildConfig.CLIENT_ID
        private const val SPOTIFY_REDIRECT_URI = "voiceassistant://callback"
    }

    private lateinit var binding: ActivityMainBinding
    private var isServiceRunning = false

    private val PERMISSIONS_REQUEST_CODE = 100
    private val OVERLAY_PERMISSION_REQUEST_CODE = 101

    // 根據 Android 版本動態生成所需權限
    private val requiredPermissions: Array<String>
        get() = mutableListOf<String>().apply {
            // 基本權限
            add(Manifest.permission.RECORD_AUDIO)

            // Android 13+ 需要通知權限
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }

            // Android 12+ 需要藍牙權限
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_SCAN)
            }
        }.toTypedArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        updateUI(false)

        binding.btnVoice.setOnClickListener {
            if (checkAndRequestAllPermissions()) {
                toggleService()
            }
        }

        // Spotify 授權按鈕
        binding.btnSpotifyAuth.setOnClickListener {
            authorizeSpotify()
        }

        // 檢查權限
        checkAndRequestAllPermissions()
    }

    private fun authorizeSpotify() {
        val builder = AuthorizationRequest.Builder(
            SPOTIFY_CLIENT_ID,
            AuthorizationResponse.Type.TOKEN,
            SPOTIFY_REDIRECT_URI
        )

        builder.setScopes(arrayOf(
            "app-remote-control",
            "user-modify-playback-state",
            "user-read-playback-state"
        ))
        val request = builder.build()

        AuthorizationClient.openLoginActivity(this, SPOTIFY_REQUEST_CODE, request)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            SPOTIFY_REQUEST_CODE -> {
                val response = AuthorizationClient.getResponse(resultCode, data)
                when (response.type) {
                    AuthorizationResponse.Type.TOKEN -> {
                        Log.d(TAG, "Spotify authorized successfully")
                        Toast.makeText(this, "✓ Spotify 授權成功！", Toast.LENGTH_SHORT).show()

                        // 保存 token
                        val token = response.accessToken
                        getSharedPreferences("spotify", MODE_PRIVATE)
                            .edit()
                            .putString("access_token", token)
                            .apply()
                    }
                    AuthorizationResponse.Type.ERROR -> {
                        Log.e(TAG, "Spotify authorization error: ${response.error}")
                        Toast.makeText(
                            this,
                            "✗ Spotify 授權失敗：${response.error}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    else -> {
                        Log.d(TAG, "Spotify authorization cancelled")
                        Toast.makeText(this, "Spotify 授權已取消", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            OVERLAY_PERMISSION_REQUEST_CODE -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (Settings.canDrawOverlays(this)) {
                        Toast.makeText(this, "✓ 懸浮窗權限已授予", Toast.LENGTH_SHORT).show()
                        checkAndRequestAllPermissions()
                    } else {
                        Toast.makeText(
                            this,
                            "需要懸浮窗權限才能在背景開啟應用",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    private fun checkAndRequestAllPermissions(): Boolean {
        // 步驟 1: 檢查基本權限
        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            Log.d(TAG, "缺少權限：${missingPermissions.joinToString()}")
            ActivityCompat.requestPermissions(
                this,
                missingPermissions.toTypedArray(),
                PERMISSIONS_REQUEST_CODE
            )
            return false
        }

        // 步驟 2: 檢查懸浮窗權限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                showOverlayPermissionDialog()
                return false
            }
        }

        // 步驟 3: 檢查電池優化
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(POWER_SERVICE) as android.os.PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                showBatteryOptimizationDialog()
            }
        }

        Log.d(TAG, "所有權限已授予")
        return true
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            val allGranted = grantResults.isNotEmpty() &&
                    grantResults.all { it == PackageManager.PERMISSION_GRANTED }

            if (allGranted) {
                Toast.makeText(this, "✓ 權限已授予", Toast.LENGTH_SHORT).show()
                Log.d(TAG, "所有請求的權限已授予")
                checkAndRequestAllPermissions()
            } else {
                // 檢查哪些權限被拒絕
                val deniedPermissions = permissions.filterIndexed { index, _ ->
                    grantResults[index] != PackageManager.PERMISSION_GRANTED
                }

                Log.w(TAG, "權限被拒絕：${deniedPermissions.joinToString()}")

                Toast.makeText(this, "需要所有權限才能使用", Toast.LENGTH_LONG).show()

                showPermissionDeniedDialog(deniedPermissions.toList())
            }
        }
    }

    // 顯示權限被拒絕的詳細說明
    private fun showPermissionDeniedDialog(deniedPermissions: List<String>) {
        val permissionExplanations = mutableListOf<String>()

        deniedPermissions.forEach { permission ->
            when (permission) {
                Manifest.permission.RECORD_AUDIO -> {
                    permissionExplanations.add("• 麥克風：用於語音識別")
                }
                Manifest.permission.POST_NOTIFICATIONS -> {
                    permissionExplanations.add("• 通知：用於在背景顯示狀態")
                }
                Manifest.permission.BLUETOOTH_CONNECT -> {
                    permissionExplanations.add("• 藍牙連接：用於偵測耳機連接狀態")
                }
                Manifest.permission.BLUETOOTH_SCAN -> {
                    permissionExplanations.add("• 藍牙掃描：用於偵測藍牙音頻設備")
                }
            }
        }

        AlertDialog.Builder(this)
            .setTitle("需要權限")
            .setMessage("語音助手需要以下權限：\n\n" +
                    permissionExplanations.joinToString("\n") +
                    "\n\n藍牙權限用於偵測耳機/車載音響連接，\n自動切換省電/騎車模式。\n\n" +
                    "⚠️ 手錶等非音頻設備不會觸發騎車模式")
            .setPositiveButton("重新授權") { _, _ ->
                checkAndRequestAllPermissions()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showOverlayPermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle("需要懸浮窗權限")
            .setMessage("為了在背景顯示通知和開啟應用程式，需要懸浮窗權限。\n\n" +
                    "這不會顯示廣告或干擾您的使用。")
            .setPositiveButton("前往設定") { _, _ ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    try {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                        startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST_CODE)
                    } catch (e: Exception) {
                        Toast.makeText(this, "無法開啟設定", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("稍後", null)
            .setCancelable(false)
            .show()
    }

    private fun showBatteryOptimizationDialog() {
        AlertDialog.Builder(this)
            .setTitle("建議關閉電池優化")
            .setMessage("為了讓語音助手在背景持續運作不被系統終止，建議關閉電池優化。\n\n" +
                    "• 連接藍牙耳機時自動取消休眠\n" +
                    "• 斷開耳機時自動省電\n" +
                    "• 這不會顯著影響電池壽命\n\n" +
                    "ℹ️ 智慧手錶連接不會影響省電模式")
            .setPositiveButton("前往設定") { _, _ ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    try {
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        intent.data = Uri.parse("package:$packageName")
                        startActivity(intent)
                    } catch (e: Exception) {
                        Toast.makeText(this, "無法開啟設定", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("稍後", null)
            .show()
    }

    private fun toggleService() {
        if (isServiceRunning) {
            stopVoiceService()
        } else {
            startVoiceService()
        }
    }

    private fun startVoiceService() {
        val serviceIntent = Intent(this, VoiceAssistantService::class.java).apply {
            action = VoiceAssistantService.ACTION_START
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        isServiceRunning = true
        updateUI(true)
        Toast.makeText(this, "✓ 語音助手已啟動", Toast.LENGTH_SHORT).show()
        Log.d(TAG, "語音助手服務已啟動")
    }

    private fun stopVoiceService() {
        val serviceIntent = Intent(this, VoiceAssistantService::class.java).apply {
            action = VoiceAssistantService.ACTION_STOP
        }
        startService(serviceIntent)

        isServiceRunning = false
        updateUI(false)
        Toast.makeText(this, "語音助手已停止", Toast.LENGTH_SHORT).show()
        Log.d(TAG, "語音助手服務已停止")
    }

    private fun updateUI(isRunning: Boolean) {
        if (isRunning) {
            binding.btnVoice.text = "⏹️ 停止服務"
            binding.tvStatus.text = """
                🎤 服務運行中（可切換到背景）
                
                說「Porcupine」喚醒助手
                
                🎧 智能省電模式：
                ├─ 連接耳機/車載音響 → 持續監聽
                └─ 斷開音頻設備 → 5分鐘後休眠
                
                ⌚ 手錶/手環連接不會影響省電模式
            """.trimIndent()
        } else {
            binding.btnVoice.text = "🎤 啟動服務"
            binding.tvStatus.text = """
                點擊按鈕啟動語音助手
                
                功能：
                • 語音開啟 App
                • Spotify 音樂控制
                • 語音問答（含網路搜尋）
                • 智能省電（偵測藍牙耳機）
                
                🎧 僅偵測耳機/音響等音頻設備
                ⌚ 手錶連接不會觸發騎車模式
                
                首次使用 Spotify 請先授權 ↓
            """.trimIndent()
        }
    }

    override fun onResume() {
        super.onResume()
        // 檢查權限狀態
        logPermissionStatus()
    }

    // 記錄當前權限狀態（用於除錯）
    private fun logPermissionStatus() {
        Log.d(TAG, "=== 權限狀態檢查 ===")

        requiredPermissions.forEach { permission ->
            val granted = ContextCompat.checkSelfPermission(this, permission) ==
                    PackageManager.PERMISSION_GRANTED
            val permissionName = permission.split(".").last()
            Log.d(TAG, "$permissionName: ${if (granted) "✓ 已授予" else "✗ 未授予"}")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val overlayGranted = Settings.canDrawOverlays(this)
            Log.d(TAG, "懸浮窗權限: ${if (overlayGranted) "✓ 已授予" else "✗ 未授予"}")

            val powerManager = getSystemService(POWER_SERVICE) as android.os.PowerManager
            val batteryOptimized = powerManager.isIgnoringBatteryOptimizations(packageName)
            Log.d(TAG, "電池優化: ${if (batteryOptimized) "✓ 已忽略" else "✗ 未忽略"}")
        }

        Log.d(TAG, "==================")
    }
}