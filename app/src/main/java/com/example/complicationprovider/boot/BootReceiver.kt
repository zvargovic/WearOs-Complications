package com.example.complicationprovider.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.complicationprovider.data.OneShotFetcher
import com.example.complicationprovider.orchestrator.AlignedFetchScheduler
import com.example.complicationprovider.tiles.TilePreRender
import com.example.complicationprovider.util.FileLogger
import kotlinx.coroutines.runBlocking
private const val TAG = "BootReceiver"

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val act = intent?.action.orEmpty()
        FileLogger.writeLine("[BOOT] onReceive action=$act")
        Log.d(TAG, "BOOT action=$act")

        try {
            // 1) Uvijek replaniraj aligned tick
            AlignedFetchScheduler.scheduleNext(context.applicationContext)

            // 2) Odmah prerender (koristi MarketSession → closed look odmah nakon boota)
            runCatching { TilePreRender.run(context.applicationContext) }

            // 3) I napravi odmah jedan ciklus fetch-a (ako je mreža OK)
            runBlocking { OneShotFetcher.runOnce(context.applicationContext,"boot") }        } catch (t: Throwable) {
            Log.w(TAG, "Boot flow failed: ${t.message}", t)
            FileLogger.writeLine("[BOOT][ERR] ${t::class.java.simpleName}: ${t.message}")
        }
    }
}