package com.example.complicationprovider.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.complicationprovider.data.OneShotFetcher
import com.example.complicationprovider.util.FileLogger
import kotlinx.coroutines.runBlocking
private const val TAG = "FetchAlarmReceiver"

/** Ako koristiš vlastiti alarm tick za fetch. */
class FetchAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        FileLogger.writeLine("[ALARM] onReceive → fetch-alarm")
        Log.d(TAG, "Alarm tick → OneShotFetcher.run")

        try {
            runBlocking { OneShotFetcher.runOnce(context.applicationContext,"fetch-alarm") }
        } catch (t: Throwable) {
            Log.w(TAG, "Alarm run failed: ${t.message}", t)
            FileLogger.writeLine("[ALARM][ERR] ${t::class.java.simpleName}: ${t.message}")
        }
    }
}