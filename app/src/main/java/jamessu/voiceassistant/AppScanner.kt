package jamessu.voiceassistant

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log

class AppScanner(private val context: Context) {

    companion object {
        private const val TAG = "AppScanner"
    }

    data class InstalledApp(
        val appName: String,
        val packageName: String,
        val isSystemApp: Boolean
    )

    /**
     * 掃描所有已安裝的應用程式
     */
    fun scanInstalledApps(): List<InstalledApp> {
        val packageManager = context.packageManager
        val apps = mutableListOf<InstalledApp>()

        // 獲取所有已安裝的應用程式
        val packages = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)

        for (packageInfo in packages) {
            // 檢查是否有啟動 Intent（可以啟動的 App）
            val launchIntent = packageManager.getLaunchIntentForPackage(packageInfo.packageName)

            if (launchIntent != null) {
                val appName = packageManager.getApplicationLabel(packageInfo).toString()
                val isSystemApp = (packageInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0

                apps.add(InstalledApp(
                    appName = appName,
                    packageName = packageInfo.packageName,
                    isSystemApp = isSystemApp
                ))
            }
        }

        // 按名稱排序
        return apps.sortedBy { it.appName }
    }

    /**
     * 只獲取用戶安裝的 App（排除系統 App）
     */
    fun scanUserApps(): List<InstalledApp> {
        return scanInstalledApps().filter { !it.isSystemApp }
    }

    /**
     * 根據應用程式名稱搜尋（模糊匹配）
     */
    fun searchAppByName(query: String): InstalledApp? {
        val apps = scanInstalledApps()
        val normalizedQuery = query.lowercase().trim()

        Log.d(TAG, "Searching for: $normalizedQuery")

        // 1. 精確匹配
        apps.find { it.appName.lowercase() == normalizedQuery }?.let {
            Log.d(TAG, "Exact match found: ${it.appName} -> ${it.packageName}")
            return it
        }

        // 2. 包含匹配
        apps.find { it.appName.lowercase().contains(normalizedQuery) }?.let {
            Log.d(TAG, "Contains match found: ${it.appName} -> ${it.packageName}")
            return it
        }

        // 3. 部分匹配（查詢包含在 App 名稱中）
        apps.find { normalizedQuery in it.appName.lowercase() }?.let {
            Log.d(TAG, "Partial match found: ${it.appName} -> ${it.packageName}")
            return it
        }

        // 4. 移除空格後再比對
        val queryNoSpace = normalizedQuery.replace(" ", "")
        apps.find { it.appName.lowercase().replace(" ", "") == queryNoSpace }?.let {
            Log.d(TAG, "No-space match found: ${it.appName} -> ${it.packageName}")
            return it
        }

        Log.d(TAG, "No match found for: $normalizedQuery")
        return null
    }

    /**
     * 將已安裝的 App 列表轉換為 JSON 格式（供 LLM 使用）
     */
    fun getAppsAsJson(): String {
        val userApps = scanUserApps()
        val appMap = userApps.associate { it.appName to it.packageName }
        return android.util.JsonWriter(java.io.StringWriter()).use { writer ->
            writer.beginObject()
            appMap.forEach { (name, pkg) ->
                writer.name(name).value(pkg)
            }
            writer.endObject()
            writer.toString()
        }
    }
}