package com.example.complicationprovider.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Jednostavan file logger s dnevnom rotacijom.
 *  - Piše u:  /data/data/<pkg>/files/logs/app-YYYY-MM-DD.txt
 *  - writeLine() je bezbacan (swallowa iznimke)
 *  - Automatski ubaci "BOOT ..." marker na prvo pozivanje u procesu
 */
object FileLogger {
    private const val TAG = "FileLogger"
    @Volatile private var inited = false
    @Volatile private var dir: File? = null
    @Volatile private var lastDay: String? = null
    @Volatile private var wroteBoot = false

    fun init(context: Context) {
        if (inited) return
        runCatching {
            val base = File(context.filesDir, "logs")
            if (!base.exists()) base.mkdirs()
            dir = base
            inited = true
        }.onFailure {
            Log.w(TAG, "init() failed: ${it.message}")
        }
    }

    fun writeLine(message: String) {
        val d = dir ?: return
        val now = System.currentTimeMillis()
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now)
        val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(now)

        if (!wroteBoot || lastDay != day) {
            // nova datoteka ili prvi put u procesu → upiši BOOT marker
            runCatching {
                val f = File(d, "app-$day.txt")
                f.appendText("BOOT $day $ts\n")
            }.onFailure { /* ignore */ }
            wroteBoot = true
            lastDay = day
        }

        runCatching {
            val f = File(d, "app-$day.txt")
            f.appendText("$ts  $message\n")
        }.onFailure {
            // ne prekidati app radi logiranja
        }
    }

    /** Korisno za ADB pull. */
    fun currentLogFilePath(): String? {
        val d = dir ?: return null
        val day = lastDay ?: return null
        return File(d, "app-$day.txt").absolutePath
    }
    /** Forsira brzi upis na disk ako OS bufferira log.
     *  Sigurno je pozivati i odmah nakon writeLine().
     */
    fun flush() {
        try {
            val path = currentLogFilePath() ?: return
            // “Takni” fajl kako bi se OS-ov buffer ispraznio
            File(path).appendText("")
        } catch (_: Throwable) {
            // namjerno ignoriramo — log nikad ne smije rušiti app
        }
    }
}