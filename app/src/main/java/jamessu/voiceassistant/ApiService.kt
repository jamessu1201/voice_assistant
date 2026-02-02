package jamessu.voiceassistant

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ApiService(private val context: Context) {
    companion object {
        private const val TAG = "ApiService"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val serverUrl = "http://100.86.123.49:8080"
    private val appScanner = AppScanner(context)

    suspend fun processCommand(spokenText: String): AppCommand? {
        return withContext(Dispatchers.IO) {
            try {
                // 掃描已安裝的應用程式
                val installedApps = appScanner.scanUserApps()
                val appsMap = installedApps.associate { it.appName to it.packageName }

                Log.d(TAG, "Found ${appsMap.size} installed apps")

                val json = JSONObject().apply {
                    put("text", spokenText)
                    put("installed_apps", JSONObject(appsMap))
                }

                val requestBody = json.toString()
                    .toRequestBody("application/json".toMediaType())

                val request = Request.Builder()
                    .url("$serverUrl/process_command")
                    .post(requestBody)
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string()

                Log.d(TAG, "Response code: ${response.code}")
                Log.d(TAG, "Response body: $responseBody")

                if (response.isSuccessful && responseBody != null) {
                    parseAppCommand(responseBody)
                } else {
                    Log.e(TAG, "Request failed: ${response.code}")
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing command", e)
                e.printStackTrace()
                null
            }
        }
    }

    private fun parseAppCommand(json: String): AppCommand? {
        return try {
            Log.d(TAG, "Parsing JSON: $json")

            val jsonObj = JSONObject(json)
            val action = jsonObj.getString("action")

            Log.d(TAG, "Action: $action")

            when (action) {
                "open_app" -> {
                    val packageName = jsonObj.getString("package")
                    val appName = jsonObj.getString("app_name")
                    Log.d(TAG, "OpenApp: $packageName - $appName")
                    AppCommand.OpenApp(packageName, appName)
                }
                "spotify_control" -> {
                    val command = jsonObj.getString("command")
                    val song = if (jsonObj.has("song") && !jsonObj.isNull("song")) {
                        jsonObj.getString("song")
                    } else {
                        null
                    }
                    Log.d(TAG, "SpotifyControl: command=$command, song=$song")
                    AppCommand.SpotifyControl(command, song)
                }
                // 🆕 處理語音回答
                "speak" -> {
                    val message = jsonObj.optString("message", "抱歉，我無法回答")
                    Log.d(TAG, "Speak: $message")
                    AppCommand.Speak(message)
                }
                "error" -> {
                    val message = jsonObj.optString("message", "Unknown error")
                    Log.e(TAG, "Server returned error: $message")
                    // 🆕 錯誤訊息也用語音回答
                    AppCommand.Speak(message)
                }
                "unknown" -> {
                    val message = jsonObj.optString("message", "抱歉，我不太明白你的意思")
                    Log.w(TAG, "Unknown command: $message")
                    // 🆕 未知指令也用語音回答
                    AppCommand.Speak(message)
                }
                else -> {
                    Log.w(TAG, "Unhandled action: $action")
                    AppCommand.Speak("抱歉，我無法處理這個指令")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse command", e)
            e.printStackTrace()
            // 🆕 解析錯誤也用語音回答
            AppCommand.Speak("抱歉，處理指令時發生錯誤")
        }
    }
}

sealed class AppCommand {
    data class OpenApp(
        val packageName: String,
        val appName: String
    ) : AppCommand()

    data class SpotifyControl(
        val command: String,
        val song: String?
    ) : AppCommand()

    // 🆕 新增語音回答指令
    data class Speak(
        val message: String
    ) : AppCommand()
}