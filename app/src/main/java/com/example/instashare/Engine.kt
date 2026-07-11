package com.example.instashare

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDL.UpdateChannel
import com.yausername.youtubedl_android.YoutubeDL.UpdateStatus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

/**
 * Single owner of yt-dlp/FFmpeg initialization and updates.
 *
 * Everything used to be split between the Application class (stable-channel
 * update on every launch) and the ViewModel (nightly-channel update before
 * every download), which meant two full engine downloads per cold start and
 * silently-swallowed failures. This funnels all of it through one throttled,
 * single-channel path and exposes the installed yt-dlp version so the UI can
 * prove updates actually happened.
 */
object Engine {
    private const val TAG = "Engine"
    private const val PREFS = "engine"
    private const val KEY_LAST_UPDATE_CHECK = "last_successful_update_check"
    private val UPDATE_CHECK_INTERVAL_MS = TimeUnit.HOURS.toMillis(6)

    // Nightly picks up extractor fixes for site layout changes within a day.
    private val CHANNEL = UpdateChannel.NIGHTLY

    private val mutex = Mutex()

    var ytdlpVersion by mutableStateOf<String?>(null)
        private set

    /** Human-readable result of the last update attempt, for surfacing in the UI. */
    var lastUpdateError by mutableStateOf<String?>(null)
        private set

    /**
     * Idempotent init of both YoutubeDL and FFmpeg. Must succeed before any
     * download; the library extracts Python/FFmpeg on first call only.
     */
    suspend fun ensureReady(context: Context) {
        val app = context.applicationContext
        mutex.withLock {
            YoutubeDL.getInstance().init(app)
            com.yausername.ffmpeg.FFmpeg.getInstance().init(app)
            refreshVersion(app)
        }
    }

    /**
     * Checks for a yt-dlp update if the last successful check is older than
     * [UPDATE_CHECK_INTERVAL_MS]. Failures are recorded (not thrown) so a dead
     * GitHub API or offline device never blocks a download attempt.
     */
    suspend fun updateIfStale(context: Context) {
        val app = context.applicationContext
        mutex.withLock {
            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val last = prefs.getLong(KEY_LAST_UPDATE_CHECK, 0L)
            if (System.currentTimeMillis() - last < UPDATE_CHECK_INTERVAL_MS) {
                return
            }
            try {
                val status = YoutubeDL.getInstance().updateYoutubeDL(app, CHANNEL)
                Log.d(TAG, "yt-dlp update check finished: $status")
                prefs.edit().putLong(KEY_LAST_UPDATE_CHECK, System.currentTimeMillis()).apply()
                lastUpdateError = null
                refreshVersion(app)
            } catch (e: Exception) {
                Log.e(TAG, "yt-dlp update check failed", e)
                lastUpdateError = e.localizedMessage ?: "Update check failed"
            }
        }
    }

    /** Forced update for the settings dialog button. Throws on failure. */
    suspend fun updateNow(context: Context): UpdateStatus? {
        val app = context.applicationContext
        mutex.withLock {
            try {
                val status = YoutubeDL.getInstance().updateYoutubeDL(app, CHANNEL)
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putLong(KEY_LAST_UPDATE_CHECK, System.currentTimeMillis()).apply()
                lastUpdateError = null
                refreshVersion(app)
                return status
            } catch (e: Exception) {
                lastUpdateError = e.localizedMessage ?: "Update check failed"
                throw e
            }
        }
    }

    private fun refreshVersion(context: Context) {
        try {
            ytdlpVersion = YoutubeDL.getInstance().version(context)
        } catch (e: Exception) {
            Log.e(TAG, "Could not read yt-dlp version", e)
        }
    }
}
