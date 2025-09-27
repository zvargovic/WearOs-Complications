package com.example.complicationprovider.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map

private val Context.ds by preferencesDataStore(name = "gold_snapshot")

/**
 * Jednostavan Preferences DataStore za snimku cijena.
 * Držimo samo zadnju snimku; history vodi SettingsRepo.
 */
object SnapshotStore {

    // Ključevi
    private val KEY_XAU_EUR   = doublePreferencesKey("xau_eur")
    private val KEY_XAU_USD   = doublePreferencesKey("xau_usd")
    private val KEY_EUR_USD   = doublePreferencesKey("eur_usd")
    private val KEY_CONF      = floatPreferencesKey("cons_conf")        // 0f..1f
    private val KEY_MARKET_ON = booleanPreferencesKey("market_open")
    private val KEY_UPDATED   = longPreferencesKey("updated_ms")

    data class Snapshot(
        val xauEur: Double?,
        val xauUsd: Double?,
        val eurUsd: Double?,
        val confidence: Float?,
        val marketOpen: Boolean,
        val updatedMs: Long
    )

    /** Flow za čitanje snimke (ako ti ikad zatreba) */
    val snapshotFlow = { context: Context ->
        context.ds.data.map { p ->
            Snapshot(
                xauEur     = p[KEY_XAU_EUR],
                xauUsd     = p[KEY_XAU_USD],
                eurUsd     = p[KEY_EUR_USD],
                confidence = p[KEY_CONF],
                marketOpen = p[KEY_MARKET_ON] ?: false,
                updatedMs  = p[KEY_UPDATED] ?: 0L
            )
        }
    }

    /** Upis nove snimke */
    suspend fun write(
        context: Context,
        xauEur: Double?,
        xauUsd: Double?,
        eurUsd: Double?,
        confidence: Float?,
        marketOpen: Boolean,
        updatedMs: Long
    ) {
        context.ds.edit { p ->
            if (xauEur != null) p[KEY_XAU_EUR] = xauEur
            if (xauUsd != null) p[KEY_XAU_USD] = xauUsd
            if (eurUsd != null) p[KEY_EUR_USD] = eurUsd
            if (confidence != null) p[KEY_CONF] = confidence
            p[KEY_MARKET_ON] = marketOpen
            p[KEY_UPDATED] = updatedMs
        }
    }
}