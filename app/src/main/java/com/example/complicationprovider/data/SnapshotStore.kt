package com.example.complicationprovider.data
import com.example.complicationprovider.R
import android.widget.Toast
import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.min

private val Context.ds by preferencesDataStore(name = "gold_series_store")

/**
 * Primarna pohrana: 5-min serija (288 slotova/dan).
 * Renderer i dalje može tražiti 48 slotova (30-min), a mi na letu komprimiramo 288→48.
 *
 * - reset u ponoć (novi dayKey) ili po potrebi pomoću PragmaticOpen v2
 * - append u najbliži 5-min slot (± tolerancija)
 *
 * PragmaticOpen v2:
 *  - Ako u novom danu NEMA 00:00/00:05... točaka, OPEN/MIN/MAX se privremeno postavljaju
 *    na "globalni zadnji fetch" (može biti i od jučer).
 *  - Kad stigne prva stvarna današnja točka, OPEN se zamijeni tom točkom,
 *    provisional flag se gasi, min/max se resetiraju od te prve točke.
 */
object SnapshotStore {

    private const val TAG = "SnapshotStore"
    private const val DEBUG_SERIES = true   // uključi/isključi detaljni ispis serije

    // ============ Slot konstante ============
    private const val SLOTS_5MIN = 288      // 24h * 60 / 5
    private const val SLOTS_30MIN = 48      // 24h * 2

    // ============ Preferences keys ============
    private val KEY_DAY = stringPreferencesKey("day")                     // yyyy-MM-dd
    private val KEY_OPEN = doublePreferencesKey("open")
    private val KEY_SERIES5 = stringPreferencesKey("series5_csv")         // 288 elemenata: "" za null, ili broj (PRIMARNO)
    private val KEY_MIN = doublePreferencesKey("min")
    private val KEY_MAX = doublePreferencesKey("max")
    private val KEY_UPDATED = longPreferencesKey("updated_ms")

    // Globalni "zadnji fetch" (može biti od jučer)
    private val KEY_LAST_GLOBAL = doublePreferencesKey("last_global")
    private val KEY_LAST_GLOBAL_TS = longPreferencesKey("last_global_ts")
    private val KEY_LAST_GLOBAL_DAY = stringPreferencesKey("last_global_day")

    // Flag da je OPEN postavljen privremeno (provisional) – drži ts kad je postavljen (0 = nema)
    private val KEY_OPEN_PROVISIONAL = longPreferencesKey("open_provisional_ts")

    // ============ Serialization helpers ============
    private fun encodeSeries(list: List<Double?>): String =
        list.joinToString(",") { it?.toString() ?: "" }

    private fun decodeSeries(csv: String, slots: Int): MutableList<Double?> {
        if (csv.isBlank()) return MutableList(slots) { null }
        val parts = csv.split(",")
        val out = MutableList(slots) { null as Double? }
        for (i in 0 until min(slots, parts.size)) {
            val s = parts[i]
            out[i] = s.toDoubleOrNull()
        }
        return out
    }

    /** Kompresija 5-min (288) → 30-min (48): po bloku od 6 uzoraka uzmi *zadnji non-null* */
    private fun compress5minTo30min(series5: List<Double?>): List<Double?> {
        val out = MutableList(SLOTS_30MIN) { null as Double? }
        for (block in 0 until SLOTS_30MIN) {
            val start = block * 6
            val end = start + 6
            val slice = series5.subList(start, end)
            val last = slice.indexOfLast { it != null }
            out[block] = if (last >= 0) slice[last] else null
        }
        return out
    }

    /** Generička projekcija 288 → N slotova (zadnji non-null u prozoru) */
    private fun project5minTo(series5: List<Double?>, targetSlots: Int): List<Double?> {
        if (targetSlots == SLOTS_5MIN) return series5
        if (targetSlots == SLOTS_30MIN) return compress5minTo30min(series5)
        val factor = SLOTS_5MIN.toFloat() / targetSlots.toFloat()
        return MutableList(targetSlots) { idx ->
            val start = (idx * factor).toInt()
            val end = ((idx + 1) * factor).toInt().coerceAtMost(SLOTS_5MIN).coerceAtLeast(start + 1)
            val slice = series5.subList(start, end)
            val last = slice.indexOfLast { it != null }
            if (last >= 0) slice[last] else null
        }
    }

    /** Kratki, čitljiv ispis serije: "Npts: 0=3310.23 1=... … ..." (samo prvih/posljednjih par) */
    private fun briefSeries(series: List<Double?>): String {
        val nonNull = series.mapIndexedNotNull { idx, v ->
            if (v != null) "$idx=${"%.2f".format(Locale.US, v)}" else null
        }
        if (nonNull.isEmpty()) return "0pts: (empty)"
        val head = nonNull.take(6).joinToString(" ")
        val tail = if (nonNull.size > 6) " … " + nonNull.takeLast(6).joinToString(" ") else ""
        return "${nonNull.size}pts: $head$tail"
    }

    /** Detaljan dump SAMO današnje domene (friendly) */
    private fun dumpTodayFriendly(
        savedDay: String?, today: String,
        open: Double?, min: Double?, max: Double?, updated: Long, provisionalTs: Long,
        series: List<Double?>, gLast: Double?, gLastTs: Long, gLastDay: String?
    ) {
        Log.w(TAG, "——— TODAY DUMP ———")
        Log.w(TAG, "day(saved)=$savedDay  today=$today")
        Log.w(TAG, "open=$open  min=$min  max=$max  updated=$updated  provisionalTs=$provisionalTs")
        Log.w(TAG, "globalLast=$gLast  globalLastTs=$gLastTs  globalLastDay=$gLastDay")
        if (DEBUG_SERIES) Log.w(TAG, "series ${briefSeries(series)}")
        Log.w(TAG, "———————————————")
    }

    // ============ Public API ============
    data class DayData(
        val dayKey: String,
        val open: Double?,
        val series: List<Double?>, // size = traženi slots
        val min: Double?,
        val max: Double?,
        val updatedMs: Long
    )

    private fun dayKeyFrom(ts: Long): String {
        val cal = Calendar.getInstance()
        cal.timeInMillis = ts
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        return fmt.format(Date(cal.timeInMillis))
    }

    /**
     * Dobavi trenutni zapis; default vraća 48 slotova (30-min) za renderer.
     * Ako želiš 5-min gran. proslijedi slots = 288.
     */
    suspend fun get(context: Context, slots: Int = SLOTS_30MIN): DayData {
        val now = System.currentTimeMillis()
        val today = dayKeyFrom(now)
        val prefs = context.ds.data.first()

        val savedDay = prefs[KEY_DAY] ?: today
        val open = prefs[KEY_OPEN]
        val min = prefs[KEY_MIN]
        val max = prefs[KEY_MAX]
        val updated = prefs[KEY_UPDATED] ?: 0L

        val provisionalTs = prefs[KEY_OPEN_PROVISIONAL] ?: 0L
        val gLast = prefs[KEY_LAST_GLOBAL]
        val gLastTs = prefs[KEY_LAST_GLOBAL_TS] ?: 0L
        val gLastDay = prefs[KEY_LAST_GLOBAL_DAY]

        // Primarna pohrana je 5-min serija
        val series5 = decodeSeries(prefs[KEY_SERIES5] ?: "", SLOTS_5MIN)
        val series = project5minTo(series5, slots)

        Log.d(
            TAG,
            "get() → savedDay=$savedDay today=$today open=$open min=$min max=$max updated=$updated " +
                    "provisionalTs=$provisionalTs seriesNonNull=${series.count { it != null }} src5NonNull=${series5.count { it != null }} " +
                    "gLast=$gLast gLastTs=$gLastTs gLastDay=$gLastDay"
        )
        if (DEBUG_SERIES) Log.d(TAG, "get() → series ${briefSeries(series)}")

        // Nova domena dana → vrati prazno za traženi broj slotova
        if (savedDay != today) {
            Log.d(TAG, "get() → different day, return empty DayData for today")
            return DayData(today, null, MutableList(slots) { null }, null, null, 0L)
        }

        return DayData(savedDay, open, series, min, max, updated)
    }

    /** Ponoćni reset + postavi OPEN i praznu 5-min seriju. */
    suspend fun resetForNewDay(context: Context, openPrice: Double, ts: Long) {
        val day = dayKeyFrom(ts)
        context.ds.edit { p ->
            p[KEY_DAY] = day
            p[KEY_OPEN] = openPrice
            p[KEY_SERIES5] = encodeSeries(MutableList(SLOTS_5MIN) { null })
            p[KEY_MIN] = openPrice
            p[KEY_MAX] = openPrice
            p[KEY_UPDATED] = ts
            p[KEY_OPEN_PROVISIONAL] = 0L
        }
        Log.i(TAG, "resetForNewDay() → day=$day open=$openPrice ts=$ts (provisional=OFF)")
        // nakon reseta prikaži kratki dump (30-min pogled radi lakšeg čitanja)
        val d = get(context, SLOTS_30MIN)
        dumpTodayFriendly(day, day, d.open, d.min, d.max, d.updatedMs, 0L, d.series, null, 0L, null)
    }

    /**
     * Append u najbliži 5-min slot (± toleranceSec).
     * Ako je OPEN bio privremen, prva stvarna točka ga zamjenjuje (OPEN=min=max=price) i provisional se gasi.
     */
    suspend fun appendIfOnSlot(context: Context, tsMs: Long, price: Double, toleranceSec: Int = 90) {
        val day = dayKeyFrom(tsMs)
        val data5 = get(context, SLOTS_5MIN)  // učitaj 5-min seriju

        // Novi dan bez reseta → inicijaliziraj na prvi uzorak
        if (data5.dayKey != day) {
            Log.d(TAG, "appendIfOnSlot() → new day detected (saved=${data5.dayKey}, now=$day) → resetForNewDay(price=$price)")
            resetForNewDay(context, price, tsMs)
            return
        }

        val cal = Calendar.getInstance().apply { timeInMillis = tsMs }
        val minute = cal.get(Calendar.MINUTE)
        val second = cal.get(Calendar.SECOND)
        val totalSec = minute * 60 + second

        // 5-min mreža: slot svake 300s
        val stepSec = 5 * 60
        val nearest = (totalSec / stepSec) * stepSec
        val distanceToNearest = abs(totalSec - nearest)
        if (distanceToNearest > toleranceSec) {
            Log.d(TAG, "appendIfOnSlot() → not on 5-min slot (min=$minute sec=$second, distance=$distanceToNearest > tol=$toleranceSec) → ignore")
            return
        }

        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val slotIndex = (hour * 60 + minute) / 5   // 0..287
        val series5 = data5.series.toMutableList()
        val prev = series5[slotIndex]
        series5[slotIndex] = price

        // Čitaj provisional flag iz prefs
        val prefs = context.ds.data.first()
        val wasProvisional = (prefs[KEY_OPEN_PROVISIONAL] ?: 0L) != 0L

        Log.d(
            TAG,
            "appendIfOnSlot() → day=$day slot5=$slotIndex (H=$hour M=$minute) prev=$prev new=$price " +
                    "wasProvisional=$wasProvisional open=${data5.open} min=${data5.min} max=${data5.max}"
        )

        context.ds.edit { p ->
            p[KEY_DAY] = day

            if (data5.open == null || wasProvisional) {
                // Ako prvi uzorak ispadne 0.0, a prije smo imali valjane min/max, koristi prethodnu nenultu
                val prevMin = data5.min
                val prevMax = data5.max
                val candidate = price

                val fallbackNonZero = when {
                    (prevMin ?: 0.0) > 0.0 -> prevMin
                    (prevMax ?: 0.0) > 0.0 -> prevMax
                    else -> null
                }

                val openSafe = if (candidate == 0.0 && fallbackNonZero != null) fallbackNonZero else candidate

                p[KEY_OPEN] = openSafe
                p[KEY_MIN]  = openSafe
                p[KEY_MAX]  = openSafe
                p[KEY_OPEN_PROVISIONAL] = 0L

                Log.d(TAG, "appendIfOnSlot() → FIRST real point today → OPEN=min=max=$openSafe (rawFirst=$price, fallback=$fallbackNonZero, provisional=OFF)")
            } else {
                // (ovdje ide "normalni" blok iz točke 1)
                val newMin = listOfNotNull(data5.min, price).minOrNull() ?: price
                val newMax = listOfNotNull(data5.max, price).maxOrNull() ?: price
                val safeMin = if (newMin == 0.0 && (data5.min ?: 0.0) > 0.0) data5.min else newMin
                val safeMax = if (newMax == 0.0 && (data5.max ?: 0.0) > 0.0) data5.max else newMax
                p[KEY_MIN] = safeMin ?: (data5.min ?: price)
                p[KEY_MAX] = safeMax ?: (data5.max ?: price)
                Log.d(TAG, "appendIfOnSlot() → min/max updated → min=$safeMin max=$safeMax (rawMin=$newMin rawMax=$newMax)")
            }

            p[KEY_SERIES5] = encodeSeries(series5)
            p[KEY_UPDATED] = tsMs
        }
        Log.i(TAG, "appendIfOnSlot() → saved slot5=$slotIndex price=$price ts=$tsMs")
        if (DEBUG_SERIES) {
            val series30 = compress5minTo30min(series5)
            Log.d(TAG, "appendIfOnSlot() → series5 ${briefSeries(series5)}")
            Log.d(TAG, "appendIfOnSlot() → series30 ${briefSeries(series30)}")
        }
    }

    // ============ PragmaticOpen v2 helpers ============

    /**
     * Zapiši globalni "zadnji fetch" (može biti od jučer). Zovi na SVAKI uspješan konsenzus u fetcheru.
     */
    suspend fun setGlobalLast(context: Context, ts: Long, price: Double) {
        val day = dayKeyFrom(ts)
        context.ds.edit { p ->
            p[KEY_LAST_GLOBAL] = price
            p[KEY_LAST_GLOBAL_TS] = ts
            p[KEY_LAST_GLOBAL_DAY] = day
        }
        Log.d(TAG, "setGlobalLast() → price=$price ts=$ts day=$day")
    }

    /**
     * Ako je danas bez prvih točaka i OPEN nedostaje → postavi privremeni OPEN=min=max
     * iz globalnog last-a. Ne dira seriju (ostaje prazna do stvarne prve točke).
     */
    suspend fun ensureOpenFromMidnightOrYesterday(context: Context, nowTs: Long) {
        val today = dayKeyFrom(nowTs)
        val prefs = context.ds.data.first()

        val savedDay = prefs[KEY_DAY]
        val open = prefs[KEY_OPEN]
        val series5Csv = prefs[KEY_SERIES5] ?: ""
        val series5 = decodeSeries(series5Csv, SLOTS_5MIN)
        val hasFirstSlots = (series5.getOrNull(0) != null) || (series5.getOrNull(1) != null)

        val gLast = prefs[KEY_LAST_GLOBAL]
        val gLastTs = prefs[KEY_LAST_GLOBAL_TS] ?: 0L
        val gLastDay = prefs[KEY_LAST_GLOBAL_DAY]

        Log.d(
            TAG,
            "ensureOpenFromMidnightOrYesterday() → today=$today savedDay=$savedDay open=$open " +
                    "hasFirstSlots=$hasFirstSlots gLast=$gLast gLastTs=$gLastTs gLastDay=$gLastDay"
        )
        if (DEBUG_SERIES) Log.d(TAG, "ensureOpen… → series5 ${briefSeries(series5)}")

        if (savedDay != today) {
            // Nema današnjeg zapisa → inicijaliziraj iz global last-a (ako postoji)
            if (gLast != null) {
                context.ds.edit { p ->
                    p[KEY_DAY] = today
                    p[KEY_OPEN] = gLast
                    p[KEY_MIN] = gLast
                    p[KEY_MAX] = gLast
                    p[KEY_SERIES5] = encodeSeries(MutableList(SLOTS_5MIN) { null })
                    p[KEY_UPDATED] = gLastTs
                    p[KEY_OPEN_PROVISIONAL] = gLastTs // označi da je privremeni
                }
                Log.i(TAG, "ensureOpen… → init TODAY from globalLast=$gLast (day=$gLastDay ts=$gLastTs) provisional=ON")
                val d = get(context, SLOTS_30MIN)
                dumpTodayFriendly(today, today, d.open, d.min, d.max, d.updatedMs, d.updatedMs, d.series, gLast, gLastTs, gLastDay)
            } else {
                Log.w(TAG, "ensureOpen… → no global last available; leaving empty")
            }
            return
        }

        // Isti dan, ali OPEN nije postavljen i nemamo prve slotove → privremeni OPEN iz global last-a
        if (open == null && !hasFirstSlots && gLast != null) {
            context.ds.edit { p ->
                p[KEY_OPEN] = gLast
                p[KEY_MIN] = gLast
                p[KEY_MAX] = gLast
                p[KEY_UPDATED] = gLastTs
                p[KEY_OPEN_PROVISIONAL] = gLastTs
            }
            Log.i(TAG, "ensureOpen… → set provisional OPEN=min=max=$gLast (from day=$gLastDay ts=$gLastTs)")
            val d = get(context, SLOTS_30MIN)
            dumpTodayFriendly(today, today, d.open, d.min, d.max, d.updatedMs, d.updatedMs, d.series, gLast, gLastTs, gLastDay)
        } else {
            Log.d(TAG, "ensureOpen… → nothing to do (open=$open, hasFirstSlots=$hasFirstSlots)")
        }
    }

    // ============ Debug helpers ============

    /**
     * Potpuni dump svih ključ/ vrijednost parova iz DataStore-a.
     * Pozovi npr. iz TileService.onResourcesRequest() unutar runBlocking { … }.
     */
    // ============ Debug helpers ============

    suspend fun clearAll(context: Context) {
        context.ds.edit { it.clear() }
        val msg = context.getString(R.string.datastore_cleared_warning)
        Log.w(TAG, msg)
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }
    suspend fun dumpAll(context: Context) {
        val prefs = context.ds.data.first()
        Log.w(TAG, "=== DUMP START ===")
        prefs.asMap().forEach { (k, v) -> Log.w(TAG, "${k.name} = $v") }
        Log.w(TAG, "=== DUMP END ===")
    }

}