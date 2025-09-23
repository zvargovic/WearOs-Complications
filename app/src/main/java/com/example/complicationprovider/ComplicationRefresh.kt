package com.example.complicationprovider

import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.example.complicationprovider.complications.RocComplicationService
import com.example.complicationprovider.complications.RsiComplicationService
import com.example.complicationprovider.complications.SpotComplicationService

private const val TAG = "CompRefresh"

/** Pozovi nakon što snapshot i history budu spremljeni. */
fun requestUpdateAllComplicationsForAll(ctx: Context) {
    val services = listOf(
        SpotComplicationService::class.java,
        RsiComplicationService::class.java,
        RocComplicationService::class.java
    )
    services.forEach { clazz ->
        try {
            ComplicationDataSourceUpdateRequester
                .create(ctx, ComponentName(ctx, clazz))
                .requestUpdateAll()
            Log.d(TAG, "Requested complication update for ${clazz.simpleName}.")
        } catch (t: Throwable) {
            Log.w(TAG, "Update request failed for ${clazz.simpleName}: ${t.message}")
        }
    }
}