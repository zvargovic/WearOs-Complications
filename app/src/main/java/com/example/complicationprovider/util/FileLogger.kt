package com.example.complicationprovider.util

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lagani file-logger s rotacijom:
 * - async upis (jedna writer korutina)
 * - rotacija po veličini (maxBytes) uz maxFiles povijesti
 * - thread-safe, pozivi su brzi (samo enqueue stringa)
 *
 * Korištenje:
 *   FileLogger.init(context, baseName = "net", maxBytes = 256_000, maxFiles = 4)
 *   FileLogger.writeLine("Hello")
 *   FileLogger.flush() // opcionalno
 *
 * Logovi su u: context.filesDir/logs/<baseName>.log , <baseName>.log.1 , ...
 */
object FileLogger {

    private const val TAG = "FileLogger"

    // Konfiguracija
    @Volatile private var appCtx: Context? = null
    @Volatile private var baseName: String = "app"
    @Volatile private var maxBytes: Long = 256_000L
    @Volatile private var maxFiles: Int = 4
    @Volatile private var enabled = true

    // Writer infrastruktura
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var writerJob: Job? = null
    private var writer: BufferedWriter? = null
    private val q = Channel<String>(capacity = 1024)
    private val started = AtomicBoolean(false)

    private val tsFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    /**
     * Inicijaliziraj logger. Zovi jednom (npr. u Application.onCreate()).
     */
    fun init(
        context: Context,
        baseName: String = "app",
        maxBytes: Long = 256_000L,
        maxFiles: Int = 4,
        enabled: Boolean = true,
    ) {
        this.appCtx = context.applicationContext
        this.baseName = baseName
        this.maxBytes = maxBytes.coerceAtLeast(64 * 1024) // min 64 KB
        this.maxFiles = maxFiles.coerceAtLeast(1)
        this.enabled = enabled

        if (started.compareAndSet(false, true)) {
            writerJob = scope.launch { runWriter() }
        }
    }

    /** Omogući / onemogući runtime. */
    fun setEnabled(v: Boolean) { enabled = v }

    /** Brzi helper za upis jedne linije. */
    fun writeLine(msg: String) {
        if (!enabled) return
        if (!started.get()) return
        val line = buildString(msg.length + 48) {
            append('[').append(tsFmt.format(Date())).append("] ")
            if (Build.FINGERPRINT.isNotEmpty()) append("T=").append(android.os.Process.myTid()).append(' ')
            append(msg)
            if (!msg.endsWith('\n')) append('\n')
        }
        q.trySend(line)
    }

    /** Forsiraj flush. Nije nužno. */
    suspend fun flush() {
        if (!enabled) return
        q.trySend("") // noop signal da probudi writer
        // kratki yield da writer pokupi red
        delay(10)
        writer?.flush()
    }

    /** Zatvori logger (rijetko potrebno). */
    suspend fun shutdown() {
        enabled = false
        writerJob?.cancelAndJoin()
        writer?.runCatching { flush(); close() }
        writer = null
        started.set(false)
    }

    // ------------------------ internals ------------------------

    private fun logsDir(): File {
        val dir = File(appCtx!!.filesDir, "logs")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun activeFile(): File = File(logsDir(), "$baseName.log")

    private suspend fun ensureWriterOpen() {
        if (writer != null) return
        val f = activeFile()
        if (!f.exists()) f.createNewFile()
        writer = BufferedWriter(FileWriter(f, /*append=*/true))
    }

    private fun rotateIfNeeded() {
        val f = activeFile()
        if (!f.exists()) return
        if (f.length() < maxBytes) return

        // Zatvori trenutan writer prije rotacije
        writer?.runCatching { flush(); close() }
        writer = null

        // Obrni .(maxFiles-1) → .maxFiles i .log → .1
        for (i in maxFiles downTo 1) {
            val src = if (i == 1) f else File(f.parentFile, "$baseName.log.${i - 1}")
            val dst = File(f.parentFile, "$baseName.log.$i")
            if (src.exists()) {
                // Ako destinacija postoji, obriši je
                if (dst.exists()) dst.delete()
                src.renameTo(dst)
            }
        }
        // Kreiraj svježi .log
        f.createNewFile()
    }

    private suspend fun runWriter() {
        try {
            ensureWriterOpen()
            for (msg in q) {
                rotateIfNeeded()
                ensureWriterOpen()
                writer!!.write(msg)
                // Mali batch flush za performanse
                if (q.isEmpty) writer!!.flush()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Writer loop crashed: ${t.message}", t)
            // Pokušaj reset (bez recursive loopa)
            runCatching { writer?.close() }
            writer = null
            started.set(false)
        }
    }
}