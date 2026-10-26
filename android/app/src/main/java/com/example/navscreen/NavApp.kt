package com.example.navscreen

import android.app.Application

class NavApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }
}
