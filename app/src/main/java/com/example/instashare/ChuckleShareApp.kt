package com.example.instashare

import android.app.Application
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ChuckleShareApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // Warm up the engine so a shared link right after launch doesn't pay
        // the extraction cost. The download path calls the same idempotent
        // functions, so nothing races and nothing runs twice.
        appScope.launch {
            try {
                Engine.ensureReady(this@ChuckleShareApp)
                Engine.updateIfStale(this@ChuckleShareApp)
            } catch (e: Exception) {
                Log.e("ChuckleShareApp", "Engine warm-up failed", e)
            }
        }
    }
}
