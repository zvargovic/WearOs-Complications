package com.example.complicationprovider.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.ds by preferencesDataStore(name = "gold_snapshot")

object SnapshotStore {
    // Ključevi (Preferences DataStore – bez Proto)
    private val KEY_XAU_EUR   = doublePreferencesKey("xau_eur")
    private val KEY_XAU_USD   = doublePreferencesKey("xau_usd")
    private val KEY_EUR_USD   = doublePreferencesKey("eur_usd")
    private val KEY_CONF      = floatPreferencesKey("cons_conf")       // 0f..1f
    private val KEY_MARKET_ON = booleanPreferencesKey("market_open")   // true=open
    private val KEY_UPDATED   = longPreferencesKey("updated_epoch_ms")

    data class Snapshot(
        val xauEur: Double?,
        val xauUsd: Double?,
        val eurUsd: Double?,
        val confidence: Float?,
        val marketOpen: Boolean?,
        val updatedMs: Long?,
    )

    suspend fun read(context: Context): Snapshot {
        val p = context.ds.data.map { it }.first()
        return Snapshot(
            xauEur     = p[KEY_XAU_EUR],
            xauUsd     = p[KEY_XAU_USD],
            eurUsd     = p[KEY_EUR_USD],
            confidence = p[KEY_CONF],
            marketOpen = p[KEY_MARKET_ON],
            updatedMs  = p[KEY_UPDATED],
        )
    }

    // Ovo TI NE TREBA mijenjati ako već GoldFetcher sprema – ostavljam helper za slučaj potrebe:
    suspend fun write(
        context: Context,
        xauEur: Double?,
        xauUsd: Double?,
        eurUsd: Double?,
        confidence: Float?,
        marketOpen: Boolean,
        updatedMs: Long,
    ) {
        context.ds.edit {
            if (xauEur != null) it[KEY_XAU_EUR] = xauEur
            if (xauUsd != null) it[KEY_XAU_USD] = xauUsd
            if (eurUsd != null) it[KEY_EUR_USD] = eurUsd
            if (confidence != null) it[KEY_CONF] = confidence
            it[KEY_MARKET_ON] = marketOpen
            it[KEY_UPDATED] = updatedMs
        }
    }
}