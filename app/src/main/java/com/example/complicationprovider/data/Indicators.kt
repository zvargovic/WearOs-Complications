package com.example.complicationprovider.data

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

object Indicators {

    /** Jednostavni pokretni prosjek (SMA) zadnjih [period] vrijednosti. */
    fun sma(series: List<Double>, period: Int): Double? {
        if (period <= 0 || series.size < period) return null
        val window = series.takeLast(period)
        return window.average()
    }

    /** Eksponencijalni pokretni prosjek (EMA) s α = 2/(period+1). */
    fun ema(series: List<Double>, period: Int): Double? {
        if (period <= 0 || series.isEmpty()) return null
        val alpha = 2.0 / (period + 1.0)
        var ema = series.first()
        for (i in 1 until series.size) {
            ema = alpha * series[i] + (1 - alpha) * ema
        }
        return ema
    }

    /**
     * RSI po Wilderu (pojednostavljena varijanta: prosjeci dobitaka/gubitaka
     * preko zadnjih [period] promjena). Treba barem period+1 elemenata.
     */
    fun rsi(series: List<Double>, period: Int = 14): Double? {
        if (series.size < period + 1) return null
        var gains = 0.0
        var losses = 0.0
        // računamo promjene za zadnjih [period] koraka
        for (i in series.size - period until series.size) {
            val diff = series[i] - series[i - 1]
            if (diff >= 0) gains += diff else losses += abs(diff)
        }
        val avgGain = gains / period
        val avgLoss = losses / period
        if (avgLoss == 0.0) return 100.0
        val rs = avgGain / avgLoss
        return 100.0 - (100.0 / (1.0 + rs))
    }

    /** Standardna devijacija zadnjih [period] vrijednosti. */
    fun stddev(series: List<Double>, period: Int): Double? {
        if (period <= 1 || series.size < period) return null
        val w = series.takeLast(period)
        val mean = w.average()
        val varSum = w.sumOf { (it - mean).pow(2.0) }
        return sqrt(varSum / (w.size - 1))
    }

    /** Rate of Change: postotna promjena vs. vrijednost prije [period] koraka. */
    fun roc(series: List<Double>, period: Int): Double? {
        if (period <= 0 || series.size <= period) return null
        val last = series.last()
        val prev = series[series.size - 1 - period]
        if (prev == 0.0) return null
        return (last - prev) / prev * 100.0
    }

    /**
     * Dnevni min/max od 00:00 UTC po [valueSelector] (npr. { it.eur } ili { it.usd }).
     * Vraća Pair(min, max) ili (null, null) ako nema zapisa za danas.
     */
    fun dayMinMax(
        records: List<HistoryRec>,
        dayStartUtcMs: Long,
        valueSelector: (HistoryRec) -> Double
    ): Pair<Double?, Double?> {
        val todays = records.filter { it.ts >= dayStartUtcMs }
            .map { valueSelector(it) }
            .filter { it > 0.0 }
        if (todays.isEmpty()) return null to null
        return todays.minOrNull() to todays.maxOrNull()
    }
}