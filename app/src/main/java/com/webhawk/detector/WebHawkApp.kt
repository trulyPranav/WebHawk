package com.webhawk.detector

import android.app.Application
import com.google.firebase.FirebaseApp
import com.webhawk.detector.data.repository.AuthRepository
import com.webhawk.detector.data.repository.FlagRepository
import com.webhawk.detector.service.UrlChangeLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class WebHawkApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val authRepository by lazy { AuthRepository() }
    val flagRepository by lazy { FlagRepository() }
    val urlChangeLogger by lazy { UrlChangeLogger(appScope) }

    override fun onCreate() {
        super.onCreate()
        FirebaseApp.initializeApp(this)
    }
}
