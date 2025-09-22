package com.example.complicationprovider.data

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "DataStoreRepo"
private const val DS_NAME = "app_settings"

private val Context.dataStore by preferencesDataStore(name = DS_NAME)

/** Bazične postavke (trenutno samo API key). */
data class Settings(
    val apiKey: String?
)

/** Snapshot zadnjeg fetch-a koji UI čita. */
data class Snapshot(
    val apiKey: String? = null,
    val usdConsensus: Double = 0.0,
    val eurConsensus: Double = 0.0,
    val eurUsdRate: Double = 1.0,
    val updatedEpochMs: Long = 0L
)

/** Povijest (rolling buffer). */
data class HistoryRec(
    val ts: Long,
    val usd: Double,
    val eur: Double,
    val fx: Double
)

class SettingsRepo(private val context: Context) {

    private object Keys {
        val API_KEY = stringPreferencesKey("api_key")
        val SNAPSHOT_JSON = stringPreferencesKey("snapshot_json")
        val HISTORY_JSON = stringPreferencesKey("history_json")
    }

    /** Osnovne postavke. */
    val flow: Flow<Settings> = context.dataStore.data
        .catch { e ->
            Log.w(TAG, "dataStore.data error: ${e.message}")
            emit(emptyPreferences())
        }
        .map { pref -> Settings(apiKey = pref[Keys.API_KEY]) }
        .distinctUntilChanged()

    /** Zadnji snapshot kao StateFlow. */
    val snapshotFlow: StateFlow<Snapshot> = context.dataStore.data
        .catch { e ->
            Log.w(TAG, "snapshotFlow error: ${e.message}")
            emit(emptyPreferences())
        }
        .map { pref ->
            pref[Keys.SNAPSHOT_JSON]?.let { parseSnapshot(it) } ?: Snapshot(
                apiKey = pref[Keys.API_KEY],
                usdConsensus = 0.0,
                eurConsensus = 0.0,
                eurUsdRate = 1.0,
                updatedEpochMs = 0L
            )
        }
        .stateIn(
            scope = CoroutineScope(Dispatchers.IO),
            started = SharingStarted.Eagerly,
            initialValue = Snapshot(
                apiKey = null,
                usdConsensus = 0.0,
                eurConsensus = 0.0,
                eurUsdRate = 1.0,
                updatedEpochMs = 0L
            )
        )

    /** Povijest fetch-eva. */
    val historyFlow: Flow<List<HistoryRec>> = context.dataStore.data
        .catch { e ->
            Log.w(TAG, "historyFlow error: ${e.message}")
            emit(emptyPreferences())
        }
        .map { pref -> parseHistoryArray(pref[Keys.HISTORY_JSON]).first }

    // ---------- API ----------

    suspend fun saveApiKey(key: String) {
        context.dataStore.edit { pref -> pref[Keys.API_KEY] = key }
        Log.i(TAG, "[API-KEY] saved (${key.length} chars)")
    }

    suspend fun saveSnapshot(s: Snapshot) {
        val js = JSONObject().apply {
            put("usd", s.usdConsensus)
            put("eur", s.eurConsensus)
            put("fx", s.eurUsdRate)
            put("ts", s.updatedEpochMs)
        }
        context.dataStore.edit { pref ->
            pref[Keys.SNAPSHOT_JSON] = js.toString()
        }
        Log.i(
            TAG,
            "[SNAPSHOT] persisted → ts=${s.updatedEpochMs}  USD=${s.usdConsensus}  EUR=${s.eurConsensus}  FX=${s.eurUsdRate}"
        )
    }

    /** Dodaj jedan history zapis (rolling). */
    suspend fun appendHistory(rec: HistoryRec, maxKeep: Int = 720): Int {
        var newSize = 0
        context.dataStore.edit { pref ->
            val arr = parseHistoryArray(pref[Keys.HISTORY_JSON]).second
            arr.put(JSONObject().apply {
                put("ts", rec.ts)
                put("usd", rec.usd)
                put("eur", rec.eur)
                put("fx", rec.fx)
            })
            val overflow = arr.length() - maxKeep
            if (overflow > 0) {
                val trimmed = JSONArray()
                for (i in overflow until arr.length()) trimmed.put(arr.get(i))
                pref[Keys.HISTORY_JSON] = trimmed.toString()
                newSize = trimmed.length()
            } else {
                pref[Keys.HISTORY_JSON] = arr.toString()
                newSize = arr.length()
            }
        }
        Log.i(TAG, "[HISTORY] +1  size=$newSize")
        return newSize
    }

    // ---------- helpers ----------

    private fun parseSnapshot(json: String): Snapshot = try {
        val o = JSONObject(json)
        Snapshot(
            usdConsensus = o.optDouble("usd", 0.0),
            eurConsensus = o.optDouble("eur", 0.0),
            eurUsdRate = o.optDouble("fx", 1.0),
            updatedEpochMs = o.optLong("ts", 0L)
        )
    } catch (t: Throwable) {
        Log.w(TAG, "parseSnapshot failed: ${t.message}")
        Snapshot(
            usdConsensus = 0.0,
            eurConsensus = 0.0,
            eurUsdRate = 1.0,
            updatedEpochMs = 0L
        )
    }

    /** @return Pair(list, rawJsonArray) */
    private fun parseHistoryArray(json: String?): Pair<List<HistoryRec>, JSONArray> {
        val arr = try { if (json.isNullOrBlank()) JSONArray() else JSONArray(json) }
        catch (_: Throwable) { JSONArray() }

        val list = ArrayList<HistoryRec>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            list.add(
                HistoryRec(
                    ts = o.optLong("ts", 0L),
                    usd = o.optDouble("usd", 0.0),
                    eur = o.optDouble("eur", 0.0),
                    fx = o.optDouble("fx", 0.0)
                )
            )
        }
        return list to arr
    }
}