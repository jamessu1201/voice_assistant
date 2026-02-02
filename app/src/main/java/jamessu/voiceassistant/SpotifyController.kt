package jamessu.voiceassistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote

class SpotifyController(private val context: Context) {

    companion object {
        private const val TAG = "SpotifyController"
        private const val SPOTIFY_PACKAGE = "com.spotify.music"

        // 👇 改成你的 Client ID
        private const val CLIENT_ID = BuildConfig.CLIENT_ID
        private const val REDIRECT_URI = "voiceassistant://callback"
    }

    private var spotifyAppRemote: SpotifyAppRemote? = null

    interface SpotifyCallback {
        fun onSuccess(message: String)
        fun onError(error: String)
    }

    fun isSpotifyInstalled(): Boolean {
        return try {
            context.packageManager.getPackageInfo(SPOTIFY_PACKAGE, 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun isConnected(): Boolean {
        return spotifyAppRemote != null
    }

    fun connect(callback: SpotifyCallback) {
        if (isConnected()) {
            callback.onSuccess("Already connected")
            return
        }

        // 確保 Spotify 已登入
        if (!isSpotifyLoggedIn()) {
            Log.w(TAG, "Spotify not logged in, opening app...")
            openSpotifyApp()
            callback.onError("請先開啟並登入 Spotify")
            return
        }

        val connectionParams = ConnectionParams.Builder(CLIENT_ID)
            .setRedirectUri(REDIRECT_URI)
            .showAuthView(true)  // 自動顯示授權畫面
            .build()

        Log.d(TAG, "Attempting to connect to Spotify...")

        SpotifyAppRemote.connect(context, connectionParams, object : Connector.ConnectionListener {
            override fun onConnected(appRemote: SpotifyAppRemote) {
                spotifyAppRemote = appRemote
                Log.d(TAG, "Connected to Spotify")
                callback.onSuccess("Connected to Spotify")
            }

            override fun onFailure(throwable: Throwable) {
                Log.e(TAG, "Failed to connect", throwable)

                // 根據錯誤類型給出不同提示
                val errorMessage = when {
                    throwable.message?.contains("UserNotAuthorizedException") == true ->
                        "需要授權。請重試，會彈出授權畫面"
                    throwable.message?.contains("AUTHENTICATION_SERVICE_UNAVAILABLE") == true ->
                        "Spotify 服務不可用。請確認已登入 Spotify"
                    else ->
                        "連接失敗：${throwable.message}"
                }

                callback.onError(errorMessage)
            }
        })
    }

    private fun isSpotifyLoggedIn(): Boolean {
        // 檢查 Spotify 是否已登入（簡單檢查）
        return try {
            val pm = context.packageManager
            val intent = pm.getLaunchIntentForPackage(SPOTIFY_PACKAGE)
            intent != null
        } catch (e: Exception) {
            false
        }
    }

    private fun openSpotifyApp() {
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(SPOTIFY_PACKAGE)
            intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open Spotify", e)
        }
    }

    fun disconnect() {
        spotifyAppRemote?.let {
            SpotifyAppRemote.disconnect(it)
        }
        spotifyAppRemote = null
        Log.d(TAG, "Disconnected from Spotify")
    }

    fun play(callback: SpotifyCallback) {
        if (!checkConnection(callback)) return

        spotifyAppRemote?.playerApi?.resume()?.setResultCallback {
            Log.d(TAG, "Playback resumed")
            callback.onSuccess("播放中")
        }?.setErrorCallback { throwable ->
            Log.e(TAG, "Failed to resume", throwable)
            callback.onError("播放失敗：${throwable.message}")
        }
    }

    fun pause(callback: SpotifyCallback) {
        if (!checkConnection(callback)) return

        spotifyAppRemote?.playerApi?.pause()?.setResultCallback {
            Log.d(TAG, "Playback paused")
            callback.onSuccess("已暫停")
        }?.setErrorCallback { throwable ->
            Log.e(TAG, "Failed to pause", throwable)
            callback.onError("暫停失敗：${throwable.message}")
        }
    }

    fun skipNext(callback: SpotifyCallback) {
        if (!checkConnection(callback)) return

        spotifyAppRemote?.playerApi?.skipNext()?.setResultCallback {
            Log.d(TAG, "Skipped to next track")
            callback.onSuccess("下一首")
        }?.setErrorCallback { throwable ->
            Log.e(TAG, "Failed to skip next", throwable)
            callback.onError("切換失敗：${throwable.message}")
        }
    }

    fun skipPrevious(callback: SpotifyCallback) {
        if (!checkConnection(callback)) return

        spotifyAppRemote?.playerApi?.skipPrevious()?.setResultCallback {
            Log.d(TAG, "Skipped to previous track")
            callback.onSuccess("上一首")
        }?.setErrorCallback { throwable ->
            Log.e(TAG, "Failed to skip previous", throwable)
            callback.onError("切換失敗：${throwable.message}")
        }
    }

    fun playSong(query: String, callback: SpotifyCallback) {
        if (!checkConnection(callback)) return

        // 使用 Spotify search URI
        val searchUri = "spotify:search:$query"

        spotifyAppRemote?.playerApi?.play(searchUri)?.setResultCallback {
            Log.d(TAG, "Playing: $query")
            callback.onSuccess("正在播放：$query")
        }?.setErrorCallback { throwable ->
            Log.e(TAG, "Failed to play", throwable)
            callback.onError("播放失敗：${throwable.message}")
        }
    }

    private fun checkConnection(callback: SpotifyCallback): Boolean {
        if (spotifyAppRemote == null) {
            callback.onError("未連接到 Spotify")
            return false
        }
        return true
    }
}