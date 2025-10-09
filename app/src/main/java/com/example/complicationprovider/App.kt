// app/src/main/java/com/example/complicationprovider/App.kt
package com.example.complicationprovider

import android.app.Application
import com.example.complicationprovider.util.FileLogger

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        FileLogger.init(
            context = this,
            baseName = "net",
            maxBytes = 256_000L,
            maxFiles = 4,
            enabled = true
        )
        FileLogger.writeLine("BOOT OK • " + System.currentTimeMillis())
    }
}