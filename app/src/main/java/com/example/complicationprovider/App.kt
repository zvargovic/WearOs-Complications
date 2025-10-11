// app/src/main/java/com/example/complicationprovider/App.kt
package com.example.complicationprovider

import android.app.Application
import android.os.Process
import com.example.complicationprovider.util.FileLogger

class App : Application() {
    override fun onCreate() {
        super.onCreate()

        // --- inicijalizacija FileLoggera ---
        FileLogger.init(this)

        // --- verzija i PID ---
        val ver = try {
            val pi = packageManager.getPackageInfo(packageName, 0)
            "${pi.versionName} (${pi.longVersionCode})"
        } catch (_: Throwable) {
            "unknown"
        }

        // --- bilježi boot ---
        val ts = System.currentTimeMillis()
        FileLogger.writeLine("[BOOT] OK • $ts pid=${Process.myPid()} ver=$ver")

        // --- globalni handler za sve neočekivane iznimke ---
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            FileLogger.writeLine("[CRASH] ${e::class.java.simpleName}: ${e.message}")
            FileLogger.writeLine(e.stackTraceToString())
            FileLogger.flush() // forsirano zapisivanje u fajl
            prev?.uncaughtException(t, e) ?: kotlin.system.exitProcess(10)
        }

        FileLogger.flush()
    }

}