package jamessu.voiceassistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ApiService {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // 👇 改用 BuildConfig
    private val serverUrl = BuildConfig.SERVER_URL

    suspend fun processCommand(spokenText: String): AppCommand? {
        return withContext(Dispatchers.IO) {
            try {
                val json = JSONObject().apply {
                    put("text", spokenText)
                }

                val requestBody = json.toString()
                    .toRequestBody("application/json".toMediaType())

                val request = Request.Builder()
                    .url("$serverUrl/process_command")
                    .post(requestBody)
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string()

                if (response.isSuccessful && responseBody != null) {
                    parseAppCommand(responseBody)
                } else {
                    null
                }
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    private fun parseAppCommand(json: String): AppCommand? {
        return try {
            val jsonObj = JSONObject(json)
            val action = jsonObj.getString("action")

            when (action) {
                "open_app" -> {
                    AppCommand.OpenApp(
                        packageName = jsonObj.getString("package"),
                        appName = jsonObj.getString("app_name")
                    )
                }
                else -> null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}

sealed class AppCommand {
    data class OpenApp(
        val packageName: String,
        val appName: String
    ) : AppCommand()
}