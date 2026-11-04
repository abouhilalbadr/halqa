package com.saiesh.tele.app

import android.app.Application
import com.saiesh.tele.core.cache.TeleCache
import com.saiesh.tele.core.mac.MacSettings
import com.saiesh.tele.core.mac.MacShare

class TeleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        System.loadLibrary("tdjni")
        TeleCache.init(this)
        MacSettings.init(this)
        MacShare.init(this)
    }
}
