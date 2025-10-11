package com.example.complicationprovider.boot
import com.example.complicationprovider.requestUpdateAllComplications
import com.example.complicationprovider.scheduleComplicationRefresh
import com.example.complicationprovider.WorkFallback
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.complicationprovider.util.FileLogger
import com.example.complicationprovider.alarm.AlarmScheduler

class BootUpgradeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        FileLogger.init(context)
        val action = intent.action ?: "?"
        FileLogger.writeLine("[BOOTRX] action=$action")

        // Lagano poguraj komplikacije da se odmah repaintaju s posljednjim snapshotom
        runCatching { requestUpdateAllComplications(context) }

        // Armiraj “watchdog” alarm (backup buđenje)
        scheduleComplicationRefresh(context, 30 * 60_000L)  // ~30 min

        // (opcionalno) Periodični WorkManager fallback
        runCatching { WorkFallback.schedule(context) }
    }
}