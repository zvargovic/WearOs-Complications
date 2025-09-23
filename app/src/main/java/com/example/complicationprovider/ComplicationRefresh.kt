package com.example.complicationprovider

import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.example.complicationprovider.complications.SpotComplicationService
import com.example.complicationprovider.complications.RsiComplicationService
import com.example.complicationprovider.complications.RocComplicationService

private const val TAG = "ComplicationSvc"

fun requestUpdateAllComplications(ctx: Context) {
    fun bump(klass: Class<*>, label: String) {
        try {
            val cn = ComponentName(ctx, klass)
            ComplicationDataSourceUpdateRequester.create(ctx, cn).requestUpdateAll()
            Log.d(TAG, "requestUpdateAll() -> $label  |  $cn")
        } catch (t: Throwable) {
            Log.w(TAG, "requestUpdateAll($label) failed: ${t.message}")
        }
    }

    bump(SpotComplicationService::class.java, "Spot")
    bump(RsiComplicationService::class.java,  "RSI")
    bump(RocComplicationService::class.java,  "ROC")
}