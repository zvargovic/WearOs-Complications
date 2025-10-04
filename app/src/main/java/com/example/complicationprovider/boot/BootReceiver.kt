package com.example.complicationprovider.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.complicationprovider.data.FetchOrchestratorWorker

/**
 * Nakon boota ili update-a, osiguraj da je orkestrator zakazan.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val a = intent.action.orEmpty()
        Log.d("BootReceiver", "onReceive: $a")
        when (a) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                FetchOrchestratorWorker.ensureScheduled(context.applicationContext)
            }
        }
    }
}