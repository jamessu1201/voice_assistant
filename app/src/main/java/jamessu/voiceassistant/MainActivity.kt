package jamessu.voiceassistant

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import jamessu.voiceassistant.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var isServiceRunning = false

    private val PERMISSIONS_REQUEST_CODE = 100
    private val OVERLAY_PERMISSION_REQUEST_CODE = 101

    private val requiredPermissions = mutableListOf<String>().apply {
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 初始狀態
        updateUI(false)

        binding.btnVoice.setOnClickListener {
            if (checkAndRequestAllPermissions()) {
                toggleService()
            }
        }

        // 啟動時檢查權限
        checkAndRequestAllPermissions()
    }

    private fun checkAndRequestAllPermissions(): Boolean {
        // 步驟 1: 檢查基本權限（麥克風、通知）
        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                missingPermissions.toTypedArray(),
                PERMISSIONS_REQUEST_CODE
            )
            return false
        }

        // 步驟 2: 檢查懸浮窗權限（Android 6+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                showOverlayPermissionDialog()
                return false
            }
        }

        // 步驟 3: 檢查電池優化（建議但非必須）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(POWER_SERVICE) as android.os.PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                showBatteryOptimizationDialog()
            }
        }

        // 注意：Android 12 的 USE_FULL_SCREEN_INTENT 權限是預設授予的
        // 只有 Android 14+ 才需要額外檢查

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
                Toast.makeText(this, "權限已授予", Toast.LENGTH_SHORT).show()
                // 繼續檢查其他權限
                checkAndRequestAllPermissions()
            } else {
                Toast.makeText(this, "需要所有權限才能使用", Toast.LENGTH_LONG).show()

                // 顯示為什麼需要權限
                AlertDialog.Builder(this)
                    .setTitle("需要權限")
                    .setMessage("語音助手需要以下權限：\n\n" +
                            "• 麥克風：用於語音識別\n" +
                            "• 通知：用於在背景顯示狀態")
                    .setPositiveButton("重新授權") { _, _ ->
                        checkAndRequestAllPermissions()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }
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
                    "這不會顯著影響電池壽命。")
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

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            OVERLAY_PERMISSION_REQUEST_CODE -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (Settings.canDrawOverlays(this)) {
                        Toast.makeText(this, "懸浮窗權限已授予", Toast.LENGTH_SHORT).show()
                        // 繼續檢查其他權限
                        checkAndRequestAllPermissions()
                    } else {
                        Toast.makeText(this, "需要懸浮窗權限才能在背景開啟應用", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
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
        Toast.makeText(this, "語音助手已啟動", Toast.LENGTH_SHORT).show()
    }

    private fun stopVoiceService() {
        val serviceIntent = Intent(this, VoiceAssistantService::class.java).apply {
            action = VoiceAssistantService.ACTION_STOP
        }
        startService(serviceIntent)

        isServiceRunning = false
        updateUI(false)
        Toast.makeText(this, "語音助手已停止", Toast.LENGTH_SHORT).show()
    }

    private fun updateUI(isRunning: Boolean) {
        if (isRunning) {
            binding.btnVoice.text = "⏹️ 停止服務"
            binding.tvStatus.text = "服務運行中（可切換到背景）\n\n說「Porcupine」喚醒"
        } else {
            binding.btnVoice.text = "🎤 啟動服務"
            binding.tvStatus.text = "點擊按鈕啟動語音助手"
        }
    }

    override fun onResume() {
        super.onResume()
        // 回到 App 時不自動彈窗，避免干擾
    }
}