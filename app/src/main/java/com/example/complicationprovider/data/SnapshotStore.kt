package com.example.complicationprovider.data

import android.content.Context
import android.util.Log
import com.example.complicationprovider.util.FileLogger
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Lagani SharedPreferences store s dvjema serijama (5-min, 30-min), open/min/max i "global-last".
 * Dodane su sanity zaštite na upis:
 *  - Rasponi (EUR_MIN..EUR_MAX)
 *  - Max skok (ABS_JUMP_EUR / ABS_JUMP_PCT)
 *  - Open sanity prozor (OPEN_ABS / OPEN_PCT)
 * Ako podatak ne prolazi: [SNAP][REJECT], ne upisujemo.
 * Ako je izvan prozora ali blizu: [SNAP][SKIP], također ne upisujemo.
 * Valjan podatak: [SNAP][OK].
 */
object SnapshotStore {
    private const val TAG = "SnapshotStore"

    // —————— granice / prozori ——————
    private const val EUR_MIN = 200.0
    private const val EUR_MAX = 10_000.0

    private const val ABS_JUMP_EUR = 80.0     // max apsolutni skok
    private const val ABS_JUMP_PCT = 2.5      // ili postotak

    private const val OPEN_ABS = 400.0        // koliko daleko od open-a dopuštamo
    private const val OPEN_PCT = 10.0         // ili postotno

    // serije: čuvamo CSV "slot,value\n"
    private const val KEY_DAY = "day"
    private const val KEY_OPEN = "open"
    private const val KEY_MIN = "min"
    private const val KEY_MAX = "max"
    private const val KEY_UPDATED = "updated"
    private const val KEY_SERIES5 = "series5"
    private const val KEY_SERIES30 = "series30"
    private const val KEY_GLOBAL_LAST = "gLast"
    private const val KEY_GLOBAL_LAST_TS = "gLastTs"

    // util
    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences("snapshot_store", Context.MODE_PRIVATE)

    private fun todayUtc(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(System.currentTimeMillis())

    private fun clampToRangeEUR(v: Double): Double =
        v.coerceIn(EUR_MIN, EUR_MAX)

    private fun fmt2(v: Double?): String =
        if (v == null || v.isNaN()) "n/a" else String.format(Locale.US, "%.2f", v)

    // ============= PUBLIC API =============

    /** Briše sve (koristi se na double-tap u MainActivity). */
    fun clearAll(context: Context) {
        prefs(context).edit().clear().apply()
        Log.w(TAG, "clearAll() done")
        FileLogger.writeLine("[SNAP] clearAll")
    }

    /**
     * upiši “global last” (s guardrails).
     */
    fun setGlobalLast(context: Context, ts: Long, priceEur: Double) {
        val p = prefs(context)
        if (!isInRange(priceEur)) {
            Log.w(TAG, "[GLAST][REJECT] out of range ${fmt2(priceEur)}")
            FileLogger.writeLine("[GLAST][REJECT] out-of-range price=${fmt2(priceEur)}")
            return
        }

        val prev = p.getString(KEY_GLOBAL_LAST, null)?.toDoubleOrNull()
        if (prev != null && isJumpTooBig(prev, priceEur)) {
            Log.w(TAG, "[GLAST][REJECT] jump prev=${fmt2(prev)} -> new=${fmt2(priceEur)}")
            FileLogger.writeLine("[GLAST][REJECT] jump prev=${fmt2(prev)} new=${fmt2(priceEur)}")
            return
        }

        p.edit()
            .putString(KEY_GLOBAL_LAST, fmt2(priceEur))
            .putLong(KEY_GLOBAL_LAST_TS, ts)
            .apply()

        Log.d(TAG, "setGlobalLast() → price=${fmt2(priceEur)} ts=$ts day=${todayUtc()}")
        FileLogger.writeLine("[GLAST][OK] price=${fmt2(priceEur)} ts=$ts")
    }

    /**
     * Pozovi na početku prikaza tile-a: puni open ako fali (midnight/yesterday heuristika).
     * Nema guardrails — samo se brine da open postoji i da prvi slotovi dana nisu prazni.
     */
    fun ensureOpenFromMidnightOrYesterday(context: Context, nowMs: Long) {
        val p = prefs(context)
        val savedDay = p.getString(KEY_DAY, null)
        val today = todayUtc()
        val open = p.getString(KEY_OPEN, null)?.toDoubleOrNull()

        val hasFirstSlots = firstSlotsPresent(p.getString(KEY_SERIES5, ""))

        Log.d(
            TAG,
            "ensureOpenFromMidnightOrYesterday() → today=$today savedDay=$savedDay open=${fmt2(open)} hasFirstSlots=$hasFirstSlots " +
                    "gLast=${p.getString(KEY_GLOBAL_LAST, "n/a")} gLastTs=${p.getLong(KEY_GLOBAL_LAST_TS, 0)}"
        )

        if (savedDay == today && open != null && hasFirstSlots) {
            // ništa
            Log.d(TAG, "ensureOpen… → nothing to do (open=${fmt2(open)}, hasFirstSlots=$hasFirstSlots)")
            return
        }

        // Ako nema open-a za danas: koristi globalLast kao polaznu točku (ako je valjan)
        val gLast = p.getString(KEY_GLOBAL_LAST, null)?.toDoubleOrNull()
        if (gLast != null && isInRange(gLast)) {
            p.edit()
                .putString(KEY_DAY, today)
                .putString(KEY_OPEN, fmt2(gLast))
                .putString(KEY_MIN, fmt2(gLast))
                .putString(KEY_MAX, fmt2(gLast))
                .apply()
            Log.i(TAG, "ensureOpen… → set open from gLast=${fmt2(gLast)}")
            FileLogger.writeLine("[SNAP][OPEN] set-from-gLast=${fmt2(gLast)} day=$today")
            // Početni slotovi: slot0 = open
            saveSeries5(context, mapOf(0 to gLast))
            saveSeries30(context, mapOf(0 to gLast))
        } else {
            // ako ni to nemamo, ne diramo ništa (bolje prazan nego kriv)
            Log.w(TAG, "ensureOpen… → no valid gLast to seed open; skipping")
            FileLogger.writeLine("[SNAP][OPEN][SKIP] no-valid-gLast")
        }
    }

    /**
     * Upiši točku u 5-min seriju ako je “on slot” (i price je valjan).
     * Također održava min/max. Ne zapisuje anomalije.
     */
    fun appendIfOnSlot(context: Context, tsMs: Long, priceEur: Double, toleranceSec: Int) {
        val p = prefs(context)

        // Provjera slota: 5-min “točkica” resetira se u 00:00 UTC
        val now = tsMs
        val minute = ((now / 60_000L) % 60).toInt()
        val hour = ((now / 3_600_000L) % 24).toInt()
        val totalMin = hour * 60 + minute

        // target slotovi npr. :00, :05, :10 ... ± tolerance
        val slot5 = if (isOnSlot(minute, 5, toleranceSec)) totalMin / 5 else null
        val day = todayUtc()

        val open = p.getString(KEY_OPEN, null)?.toDoubleOrNull()
        val oldMin = p.getString(KEY_MIN, null)?.toDoubleOrNull()
        val oldMax = p.getString(KEY_MAX, null)?.toDoubleOrNull()

        Log.d(
            TAG,
            "appendIfOnSlot() → day=$day slot5=$slot5 (H=$hour M=$minute) prev=${fmt2(lastNonNullFromSeries(p.getString(KEY_SERIES5, "")))} " +
                    "new=${fmt2(priceEur)} open=${fmt2(open)}"
        )

        if (slot5 == null) return

        // ——— SANITY GUARDS ———
        if (!isInRange(priceEur)) {
            Log.w(TAG, "[SNAP][REJECT] out-of-range=${fmt2(priceEur)}")
            FileLogger.writeLine("[SNAP][REJECT] out-of-range price=${fmt2(priceEur)}")
            return
        }

        val prev = lastNonNullFromSeries(p.getString(KEY_SERIES5, ""))
        if (prev != null && isJumpTooBig(prev, priceEur)) {
            Log.w(TAG, "[SNAP][REJECT] jump prev=${fmt2(prev)} -> new=${fmt2(priceEur)}")
            FileLogger.writeLine("[SNAP][REJECT] jump prev=${fmt2(prev)} new=${fmt2(priceEur)}")
            return
        }

        if (open != null && !isNearOpen(open, priceEur)) {
            Log.w(TAG, "[SNAP][SKIP] too-far-from-open open=${fmt2(open)} new=${fmt2(priceEur)}")
            FileLogger.writeLine("[SNAP][SKIP] far-from-open open=${fmt2(open)} new=${fmt2(priceEur)}")
            return
        }
        // ————————————————

        // upiši u serije
        val added5 = saveOrReplacePoint(context, KEY_SERIES5, slot5, priceEur)
        if (added5) {
            // 30-min slot(ovi)
            val slot30 = totalMin / 30
            saveOrReplacePoint(context, KEY_SERIES30, slot30, priceEur)

            // min/max
            val newMin = if (oldMin == null) priceEur else min(oldMin, priceEur)
            val newMax = if (oldMax == null) priceEur else max(oldMax, priceEur)
            p.edit()
                .putString(KEY_MIN, fmt2(newMin))
                .putString(KEY_MAX, fmt2(newMax))
                .putLong(KEY_UPDATED, tsMs)
                .apply()

            Log.i(TAG, "appendIfOnSlot() → saved slot5=$slot5 price=${fmt2(priceEur)} ts=$tsMs")
            FileLogger.writeLine("[SNAP][OK] slot5=$slot5 price=${fmt2(priceEur)}")
        }
    }

    /** Pomoćna čitanja za renderer / complicatione. */
    fun get(
        context: Context,
        slotsPerDay: Int = 288, // 5-min slotovi
    ): SnapshotData {
        val p = prefs(context)
        val day = p.getString(KEY_DAY, null)
        val open = p.getString(KEY_OPEN, null)?.toDoubleOrNull() ?: 0.0
        val minV = p.getString(KEY_MIN, null)?.toDoubleOrNull() ?: open
        val maxV = p.getString(KEY_MAX, null)?.toDoubleOrNull() ?: open
        val updated = p.getLong(KEY_UPDATED, 0)

        val series5 = parseSeries(p.getString(KEY_SERIES5, ""))
        val values = ArrayList<Double?>(slotsPerDay)
        // napuni do slotsPerDay
        for (i in 0 until slotsPerDay) values += series5[i] ?: null

        val nonNull = values.count { it != null }
        Log.d(
            TAG,
            "get() → savedDay=$day today=${todayUtc()} open=${fmt2(open)} min=${fmt2(minV)} max=${fmt2(maxV)} " +
                    "updated=$updated seriesNonNull=$nonNull gLast=${p.getString(KEY_GLOBAL_LAST, "n/a")} " +
                    "gLastTs=${p.getLong(KEY_GLOBAL_LAST_TS, 0)}"
        )

        return SnapshotData(
            open = open,
            min = minV,
            max = maxV,
            updated = updated,
            series = values
        )
    }

    // ============= PRIVATE =============

    private fun isInRange(v: Double): Boolean = v in EUR_MIN..EUR_MAX

    private fun isJumpTooBig(prev: Double, now: Double): Boolean {
        val absJump = abs(now - prev)
        val pct = if (prev != 0.0) absJump / prev * 100.0 else 100.0
        return absJump > ABS_JUMP_EUR || pct > ABS_JUMP_PCT
    }

    private fun isNearOpen(open: Double, now: Double): Boolean {
        val absJump = abs(now - open)
        val pct = if (open != 0.0) absJump / open * 100.0 else 100.0
        return absJump <= OPEN_ABS || pct <= OPEN_PCT
    }

    private fun isOnSlot(minute: Int, step: Int, toleranceSec: Int): Boolean {
        // npr. minute=30, step=5 → target točke: 0,5,10...
        val mod = (minute % step) * 60 // sekunde “odmaknute” od točno na slot
        return mod <= toleranceSec || mod >= (step * 60 - toleranceSec)
    }

    private fun firstSlotsPresent(csv: String?): Boolean {
        val map = parseSeries(csv)
        return map[0] != null // dovoljan je prvi slot (seed)
    }

    private fun lastNonNullFromSeries(csv: String?): Double? {
        val map = parseSeries(csv)
        if (map.isEmpty()) return null
        var last: Double? = null
        map.keys.sorted().forEach { k -> map[k]?.let { last = it } }
        return last
    }

    private fun saveOrReplacePoint(context: Context, key: String, slot: Int, value: Double): Boolean {
        val map = parseSeries(prefs(context).getString(key, ""))
        val prev = map[slot]
        if (prev != null && prev == value) return false
        map[slot] = value
        saveSeries(context, key, map)
        return true
    }

    private fun parseSeries(csv: String?): MutableMap<Int, Double> {
        val out = mutableMapOf<Int, Double>()
        if (csv.isNullOrEmpty()) return out
        csv.lineSequence().forEach { line ->
            val parts = line.split(',')
            if (parts.size == 2) {
                val s = parts[0].toIntOrNull()
                val v = parts[1].toDoubleOrNull()
                if (s != null && v != null) out[s] = v
            }
        }
        return out
    }

    private fun saveSeries(context: Context, key: String, map: Map<Int, Double>) {
        // serija kao CSV 'slot,value\n' (stabilno i čitljivo)
        val sb = StringBuilder((map.size * 12).coerceAtLeast(32))
        map.toSortedMap().forEach { (slot, v) ->
            sb.append(slot).append(',').append(fmt2(v)).append('\n')
        }
        prefs(context).edit().putString(key, sb.toString()).apply()
    }

    private fun saveSeries5(context: Context, map: Map<Int, Double>) = saveSeries(context, KEY_SERIES5, map)
    private fun saveSeries30(context: Context, map: Map<Int, Double>) = saveSeries(context, KEY_SERIES30, map)

    // ——— DTO za potrošače ———
    data class SnapshotData(
        val open: Double,
        val min: Double,
        val max: Double,
        val updated: Long,
        val series: List<Double?> // 5-min slotovi
    )
}
