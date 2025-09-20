package com.example.complicationprovider.data

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private const val TAG = "SettingsRepo"
private const val DS_NAME = "settings"

val Context.dataStore by preferencesDataStore(name = DS_NAME)

data class Settings(
    val apiKey: String = "",
    val alarmOn: Boolean = false
)

data class Snapshot(
    val usdConsensus: Double? = null,
    val eurConsensus: Double? = null,
    val eurUsdRate: Double? = null,
    val updatedEpochMs: Long = 0L
)

class SettingsRepo(private val context: Context) {

    private object Keys {
        val API_KEY = stringPreferencesKey("api_key")
        val ALARM_ON = booleanPreferencesKey("alarm_on")

        // Alerts: spremamo kao jedan string (separator)
        val ALERTS = stringPreferencesKey("alerts_json")

        // Snapshot polja
        val LAST_USD = doublePreferencesKey("last_usd")
        val LAST_EUR = doublePreferencesKey("last_eur")
        val LAST_FX  = doublePreferencesKey("last_fx")
        val LAST_UPDATED = longPreferencesKey("last_updated")
    }

    // --------- Settings ----------
    val flow: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(
            apiKey = p[Keys.API_KEY] ?: "",
            alarmOn = p[Keys.ALARM_ON] ?: false
        )
    }

    suspend fun save(apiKey: String, alarmOn: Boolean) {
        context.dataStore.edit { p ->
            p[Keys.API_KEY] = apiKey
            p[Keys.ALARM_ON] = alarmOn
        }
        Log.d(TAG, "save(): apiKey set, alarmOn=$alarmOn")
    }

    // --------- Alerts ----------
    val alertsFlow: Flow<List<String>> = context.dataStore.data.map { p ->
        val raw = p[Keys.ALERTS].orEmpty()
        if (raw.isBlank()) emptyList() else raw.split("\u0001").filter { it.isNotBlank() }
    }

    suspend fun saveAlerts(list: List<String>) {
        context.dataStore.edit { p ->
            p[Keys.ALERTS] = list.joinToString("\u0001")
        }
        Log.d(TAG, "saveAlerts(): ${list.size} items")
    }

    // --------- Snapshot ----------
    val snapshotFlow: Flow<Snapshot> = context.dataStore.data.map { p ->
        Snapshot(
            usdConsensus   = p[Keys.LAST_USD],
            eurConsensus   = p[Keys.LAST_EUR],
            eurUsdRate     = p[Keys.LAST_FX],
            updatedEpochMs = p[Keys.LAST_UPDATED] ?: 0L
        )
    }

    suspend fun saveSnapshot(s: Snapshot) {
        context.dataStore.edit { p ->
            s.usdConsensus?.let { p[Keys.LAST_USD] = it }
            s.eurConsensus?.let { p[Keys.LAST_EUR] = it }
            s.eurUsdRate?.let   { p[Keys.LAST_FX]  = it }
            p[Keys.LAST_UPDATED] = s.updatedEpochMs
        }
        Log.d(
            TAG,
            "saveSnapshot(): usd=${s.usdConsensus} eur=${s.eurConsensus} fx=${s.eurUsdRate} t=${s.updatedEpochMs}"
        )
    }
}