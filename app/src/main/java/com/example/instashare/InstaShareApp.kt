package com.example.instashare

import android.app.Application
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class InstaShareApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialize YoutubeDL and FFmpeg natively in the background
        CoroutineScope(Dispatchers.IO).launch {
            try {
                com.yausername.youtubedl_android.YoutubeDL.getInstance().init(this@InstaShareApp)
                com.yausername.ffmpeg.FFmpeg.getInstance().init(this@InstaShareApp)
                Log.d("InstaShareApp", "YoutubeDL and FFmpeg initialized successfully")
                
                // Update to the latest yt-dlp binary to support current scrape targets (like Instagram updates)
                com.yausername.youtubedl_android.YoutubeDL.getInstance().updateYoutubeDL(this@InstaShareApp)
                Log.d("InstaShareApp", "YoutubeDL updated successfully")
            } catch (e: Exception) {
                Log.e("InstaShareApp", "Failed to initialize or update YoutubeDL", e)
            }
        }
    }
}
