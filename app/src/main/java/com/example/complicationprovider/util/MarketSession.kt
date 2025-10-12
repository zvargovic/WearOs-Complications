package com.example.complicationprovider.util

import android.content.Context
import com.example.complicationprovider.R
import java.time.*

object MarketSession {

    enum class Mode { AUTO, FORCE_OPEN, FORCE_CLOSED }

    @Volatile private var mode: Mode = Mode.AUTO
    fun setMode(m: Mode) { mode = m }
    fun getMode(): Mode = mode

    data class Hours(
        val openHour: Int,
        val openMinute: Int,
        val closeHour: Int,
        val closeMinute: Int,
        val tzId: String,
        val weekdaysOnly: Boolean = true
    )

    // ======= PUBLIC API tražen =======
    /** Centralizirani odgovor: je li tržište sada otvoreno. */
    fun isOpenNow(context: Context): Boolean =
        isOpenLocal(defaultHours(context))

    /** Centralizirani tekst ETA za "closed" stanje (npr. "12h 30m"). */
    fun closedEtaText(context: Context): String =
        closedStatusText(context, defaultHours(context))
    // =================================

    // Ako želiš kasnije povlačiti satnicu iz nekog repo-a / prefs-a, promijeni samo ovu funkciju.
    private fun defaultHours(@Suppress("UNUSED_PARAMETER") context: Context) = Hours(
        openHour = 2, openMinute = 0,
        closeHour = 2, closeMinute = 0,
        tzId = "Europe/Zagreb",
        weekdaysOnly = true
    )

    private fun isWeekday(d: LocalDate) = d.dayOfWeek.value in 1..5
    private fun start(d: LocalDate, z: ZoneId, h: Int, m: Int) =
        ZonedDateTime.of(d, LocalTime.of(h, m), z)
    private fun end(d: LocalDate, z: ZoneId, h: Int, m: Int) =
        ZonedDateTime.of(d.plusDays(1), LocalTime.of(h, m), z)

    /** Je li tržište sada otvoreno (uz poštovanje Mode)? */
    fun isOpenLocal(
        hours: Hours,
        now: ZonedDateTime = ZonedDateTime.now(ZoneId.of(hours.tzId))
    ): Boolean {
        return when (mode) {
            Mode.FORCE_OPEN -> true
            Mode.FORCE_CLOSED -> false
            Mode.AUTO -> {
                val zone = ZoneId.of(hours.tzId)
                val t = now.withZoneSameInstant(zone)
                val today = t.toLocalDate()
                val yday = today.minusDays(1)

                // vikend short-circuit (ako je uključeno)
                if (hours.weekdaysOnly && !isWeekday(today) && !isWeekday(yday)) return false

                val sY = start(yday, zone, hours.openHour, hours.openMinute)
                val eY = end(yday, zone, hours.closeHour, hours.closeMinute)
                if (!t.isBefore(sY) && t.isBefore(eY)) return true

                if (hours.weekdaysOnly && !isWeekday(today)) return false

                val sT = start(today, zone, hours.openHour, hours.openMinute)
                val eT = end(today, zone, hours.closeHour, hours.closeMinute)
                !t.isBefore(sT) && t.isBefore(eT)
            }
        }
    }

    /** Sljedeće otvaranje (poštuje Mode). */
    fun nextOpenLocalMs(
        hours: Hours,
        now: ZonedDateTime = ZonedDateTime.now(ZoneId.of(hours.tzId))
    ): Long {
        val zone = ZoneId.of(hours.tzId)
        val t = now.withZoneSameInstant(zone)

        if (mode == Mode.FORCE_OPEN) return t.toInstant().toEpochMilli()

        var d = t.toLocalDate()
        while (true) {
            if (!hours.weekdaysOnly || isWeekday(d)) {
                val s = start(d, zone, hours.openHour, hours.openMinute)
                if (t.isBefore(s)) return s.toInstant().toEpochMilli()
            }
            d = d.plusDays(1)
        }
    }

    /** Zadnje zatvaranje (poštuje Mode). */
    fun lastCloseLocalMs(
        hours: Hours,
        now: ZonedDateTime = ZonedDateTime.now(ZoneId.of(hours.tzId))
    ): Long {
        val zone = ZoneId.of(hours.tzId)
        val t = now.withZoneSameInstant(zone)

        if (mode == Mode.FORCE_OPEN) {
            // “ako je sve uvijek open”, vrati kraj današnjeg sessiona zbog countdowna
            val eT = end(t.toLocalDate(), zone, hours.closeHour, hours.closeMinute)
            return eT.toInstant().toEpochMilli()
        }
        if (mode == Mode.FORCE_CLOSED) {
            // “ako je sve closed”, uzmi zadnji radni dan
            var d = t.toLocalDate().minusDays(1)
            while (hours.weekdaysOnly && !isWeekday(d)) d = d.minusDays(1)
            return end(d, zone, hours.closeHour, hours.closeMinute).toInstant().toEpochMilli()
        }

        var d = t.toLocalDate()
        // ako smo prije današnjeg starta, gledaj jučer
        val sT = start(d, zone, hours.openHour, hours.openMinute)
        if (t.isBefore(sT)) d = d.minusDays(1)
        while (hours.weekdaysOnly && !isWeekday(d)) d = d.minusDays(1)
        return end(d, zone, hours.closeHour, hours.closeMinute).toInstant().toEpochMilli()
    }

    /** Tekst “Market closed — opens in Xh Ym”. */
    fun closedStatusText(context: Context, hours: Hours): String {
        val nextOpen = nextOpenLocalMs(hours)
        val delta = (nextOpen - System.currentTimeMillis()).coerceAtLeast(0)
        val days = delta / (24 * 60 * 60 * 1000)
        val hoursLeft = (delta / (60 * 60 * 1000)) % 24
        val minLeft = (delta / (60 * 1000)) % 60
        val human = when {
            days > 0 -> "${days}d ${hoursLeft}h"
            hoursLeft > 0 -> "${hoursLeft}h ${minLeft}m"
            else -> "${minLeft}m"
        }
        return try {
            context.getString(R.string.market_closed_open_in, human)
        } catch (_: Throwable) {
            "Market closed — opens in $human"
        }
    }
}