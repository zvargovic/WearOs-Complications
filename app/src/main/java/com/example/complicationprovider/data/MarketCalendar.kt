package com.example.complicationprovider.data

import java.time.*

/**
 * Jednostavan kalendar: tržište je OTVORENO pon–pet cijeli dan (00:00–24:00) u UTC.
 * Vikend je zatvoren. Ovo odgovara onome što ti GoldFetcher javlja u logu.
 */
object MarketCalendarUtc {

    /** Je li tržište trenutno otvoreno (prema UTC kalendaru)? */
    fun isMarketOpenUtc(nowUtc: ZonedDateTime): Boolean {
        val dow = nowUtc.dayOfWeek.value // Mon=1..Sun=7
        return dow in 1..5
    }

    /** Sljedeće otvaranje (prva iduća ponoć 00:00 UTC koja pada na pon–pet). */
    fun nextOpenUtcMs(nowUtc: ZonedDateTime): Long {
        var d = nowUtc.toLocalDate()
        // ako smo usred radnog dana → sljedeći open je sutra u 00:00, ali to je već otvoreno;
        // CLOSED nas tipično zanima vikendom → idemo do prvog pon–pet datuma
        while (d.dayOfWeek.value !in 1..5) d = d.plusDays(1)
        val openUtc = ZonedDateTime.of(d, LocalTime.MIDNIGHT, ZoneOffset.UTC)
        return openUtc.toInstant().toEpochMilli()
    }

    /** Sljedeće zatvaranje: ponoć (00:00 UTC) sljedećeg dana. */
    fun nextCloseUtcMs(nowUtc: ZonedDateTime): Long {
        val nextMidnight = nowUtc.toLocalDate().plusDays(1)
        return ZonedDateTime.of(nextMidnight, LocalTime.MIDNIGHT, ZoneOffset.UTC)
            .toInstant().toEpochMilli()
    }

    /** Zadnje zatvaranje: ponoć nakon zadnjeg radnog dana. */
    fun lastCloseUtcMs(nowUtc: ZonedDateTime): Long {
        var d = nowUtc.toLocalDate()
        // ako smo u sub/ned → vrati se na posljednji petak
        while (d.dayOfWeek.value !in 1..5) d = d.minusDays(1)
        val closeDate = d.plusDays(1) // zatvaranje tog radnog dana je na sljedećoj ponoći
        return ZonedDateTime.of(closeDate, LocalTime.MIDNIGHT, ZoneOffset.UTC)
            .toInstant().toEpochMilli()
    }

    /** Lokalno vrijeme otvaranja za UX (prikaz korisniku). */
    fun nextOpenLocalLabel(nowUtc: ZonedDateTime): String {
        val openMs = nextOpenUtcMs(nowUtc)
        val local = Instant.ofEpochMilli(openMs).atZone(ZoneId.systemDefault())
        // Primjer: "Pon 02:00"
        val dow = local.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault())
        val hh = local.hour.toString().padStart(2, '0')
        val mm = local.minute.toString().padStart(2, '0')
        return "$dow $hh:$mm"
    }
}