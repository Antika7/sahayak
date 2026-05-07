package com.sahayak

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SahayakApp : Application() {
    lateinit var gemmaEngine: LocalGemmaEngine

    override fun onCreate() {
        super.onCreate()
        gemmaEngine = LocalGemmaEngine(this)
        CoroutineScope(Dispatchers.IO).launch {
            gemmaEngine.initialize()
        }
    }
}
