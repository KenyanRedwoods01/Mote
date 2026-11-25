package com.mote.app

import android.app.Application
import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.ai.ondevice.FirebaseAIOnDevice
import com.google.firebase.ai.type.PublicPreviewAPI
import com.google.firebase.appcheck.appCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import com.google.firebase.initialize
import com.google.firebase.remoteconfig.remoteConfig
import com.google.firebase.remoteconfig.remoteConfigSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@OptIn(PublicPreviewAPI::class)
class MoteApplication : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        Firebase.initialize(context = this)

        val factory = if (BuildConfig.DEBUG) {
            DebugAppCheckProviderFactory.getInstance()
        } else {
            PlayIntegrityAppCheckProviderFactory.getInstance()
        }
        Firebase.appCheck.installAppCheckProviderFactory(factory)

        val rc = Firebase.remoteConfig
        rc.setConfigSettingsAsync(remoteConfigSettings { minimumFetchIntervalInSeconds = 3600 })
        rc.setDefaultsAsync(
            mapOf(
                "cloud_model_name" to "gemini-2.5-flash",
                "max_local_tokens" to 3800L,
                "prefer_on_device" to true
            )
        )
        rc.fetchAndActivate()

        appScope.launch {
            runCatching {
                val status = FirebaseAIOnDevice.checkStatus()
                Log.i(TAG, "Gemini Nano on-device status: $status")
            }.onFailure {
                Log.w(TAG, "AICore status check unavailable: ${it.message}")
            }
        }
    }

    companion object {
        private const val TAG = "MoteApp"
    }
}
