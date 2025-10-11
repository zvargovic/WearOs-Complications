package com.example.complicationprovider.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.example.complicationprovider.data.OneShotFetcher
import com.example.complicationprovider.util.FileLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class FetchAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        FileLogger.writeLine("[ALARM] tick → fetch start")

        // goAsync-like: koristimo kratki background scope da završimo posao
        val appCtx = context.applicationContext
        CoroutineScope(Dispatchers.Default).launch {
            val ok = runCatching {
                OneShotFetcher.run(appCtx, reason = "alarm")
            }.onFailure {
                FileLogger.writeLine("[ALARM] fetch exception: ${it.message}")
            }.getOrDefault(false)

            FileLogger.writeLine("[ALARM] fetch done ok=$ok")

            // Uvijek ponovno armiraj glavni termin
            AlarmScheduler.scheduleNext(appCtx)
            // Ako nije uspjelo, stavi i kratki retry
            if (!ok) AlarmScheduler.scheduleRetry(appCtx)
        }
    }
}