package com.mote.app

import android.app.Application
import com.google.firebase.Firebase
import com.google.firebase.initialize
import com.google.firebase.remoteconfig.remoteConfig
import com.google.firebase.remoteconfig.remoteConfigSettings

class MoteApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        Firebase.initialize(context = this)

        // Note: App Check is intentionally configured in MainActivity.onCreate(intent)
        // so launch Intent extras (FIREBASE_APPCHECK_DEBUG_TOKEN) are captured before any Firebase call.
        val rc = Firebase.remoteConfig
        rc.setConfigSettingsAsync(remoteConfigSettings { minimumFetchIntervalInSeconds = 3600 })
        rc.setDefaultsAsync(
            mapOf(
                "cloud_model_name" to "gemini-3.8-flash",
                "max_local_tokens" to 3800L,
                "prefer_on_device" to true
            )
        )
        rc.fetchAndActivate()
    }
}
