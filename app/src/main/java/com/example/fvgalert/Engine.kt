package com.example.fvgalert

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

data class Candle(val t: Long, val o: Double, val h: Double, val l: Double, val c: Double)

enum class AlertKind { TOUCH, NEW }

class Fvg(val key: Long, val bull: Boolean, val top: Double, val bot: Double, val at: Long) {
    /** آیا قیمت الان داخل ناحیه است؟ هر بار که از بیرون وارد شود یک هشدار جدید می‌دهیم */
    var inside = false
    /** شروع کندلی که آخرین هشدار این FVG در آن داده شد؛ در هر کندل فقط یک هشدار */
    var alertCandle = 0L
    var active = true
}

/** همون منطق اندیکاتور FVG MTF */
class Engine(
    private val token: String,
    private val account: String,
    private val symbol: String,
    private val tfMin: Int,
    private val minGap: Double,
    private val alertTouch: Boolean,
    private val alertNew: Boolean,
    private val onAlert: (AlertKind, Boolean) -> Unit
) {
    companion object {
        /** قیمت باید به‌اندازه‌ی این درصد از قیمت از ناحیه دور بشه تا ورود بعدی «برخورد جدید» حساب بشه (برای هر نماد خودکار تنظیم می‌شود) */
        const val HYST_PCT = 0.00008
    }

    /** هر FVG در هر کندل ۱ دقیقه‌ای حداکثر یک هشدار می‌دهد */
    private val alertCandleMs = 60000L
    /** شروع و قیمت اوپن کندل ۱ دقیقه‌ای جاری (اولین قیمت همان دقیقه) */
    private var candleStart = 0L
    private var candleOpen = Double.NaN

    private val lock = Any()
    val fvgs = LinkedHashMap<Long, Fvg>()
    @Volatile var price = Double.NaN
    /** آخرین قیمت زنده (bid) از استریم OANDA */
    @Volatile var src = Double.NaN
    private var lastTs = -1L
    var used = 0

    private fun gran(m: Int) = when (m) {
        1 -> "M1"; 5 -> "M5"; 10 -> "M10"; 15 -> "M15"; 30 -> "M30"
        60 -> "H1"; 120 -> "H2"; 240 -> "H4"
        else -> "M15"
    }

    /** کندل‌های XAU/USD از OANDA (قیمت bid، مثل چارت تریدینگ‌ویو) */
    private fun fetch(minutes: Int, n: Int): List<Candle> {
        val u = Oanda.rest(account) + "/v3/instruments/" + symbol + "/candles?granularity=" + gran(minutes) +
                "&count=" + n + "&price=B"
        val conn = URL(u).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.setRequestProperty("Authorization", "Bearer " + token)
        conn.setRequestProperty("Accept-Datetime-Format", "UNIX")
        val body = try {
            (if (conn.responseCode < 400) conn.inputStream else conn.errorStream).bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
        val j = JSONObject(body)
        val arr = j.optJSONArray("candles") ?: throw RuntimeException(j.optString("errorMessage", "no data"))
        val out = ArrayList<Candle>()
        for (i in 0 until arr.length()) {
            val v = arr.getJSONObject(i)
            val b = v.getJSONObject("bid")
            out.add(Candle((v.getString("time").toDouble() * 1000).toLong(),
                b.getString("o").toDouble(), b.getString("h").toDouble(),
                b.getString("l").toDouble(), b.getString("c").toDouble()))
        }
        out.sortBy { it.t }
        return out
    }

    private fun replay(f: Fvg, d: List<Candle>, i: Int) {
        for (j in i + 1 until d.size) {
            val c = d[j]
            if ((f.bull && c.c < f.bot) || (!f.bull && c.c > f.top)) { f.active = false; return }
        }
    }

    /** true یعنی آخرین کندل بسته‌شده‌ی تایم‌فریم هم دریافت شد (دیتا به‌روز است) */
    fun refresh(first: Boolean): Boolean {
        val tf = tfMin * 60000L
        val raw = fetch(tfMin, 300)
        val now = System.currentTimeMillis()
        val d = raw.filter { it.t + tf <= now }.takeLast(200)
        synchronized(lock) {
            if (d.size < 3) return false
            for (i in 2 until d.size) {
                val a = d[i - 2]; val m = d[i - 1]; val c = d[i]
                val f = when {
                    c.l > a.h && c.l - a.h >= minGap -> Fvg(m.t, true, c.l, a.h, c.t + tf)
                    c.h < a.l && a.l - c.h >= minGap -> Fvg(m.t, false, a.l, c.h, c.t + tf)
                    else -> null
                } ?: continue
                if (fvgs.containsKey(f.key)) continue
                replay(f, d, i)
                if (f.active && !price.isNaN()) f.inside = price >= f.bot && price <= f.top
                fvgs[f.key] = f
                if (!first && f.active && i == d.size - 1 && alertNew) onAlert(AlertKind.NEW, f.bull)
            }
            // ابطال FVG فقط با «کلوز کندل تایم‌فریم (مثلاً ۱۵ دقیقه)» بیرون از باکس — مثل اندیکاتور
            for (f in fvgs.values) {
                if (!f.active) continue
                for (cd in d) {
                    if (cd.t < f.at) continue
                    if ((f.bull && cd.c < f.bot) || (!f.bull && cd.c > f.top)) {
                        f.active = false; f.inside = false; break
                    }
                }
            }
            val oldest = d[0].t
            fvgs.keys.removeAll { it < oldest }
        }
        return d.last().t + tf >= (now / tf) * tf
    }

    /** قیمت زنده (OANDA). live=false یعنی بازار طلا بسته است. */
    fun onTick(rawPrice: Double, live: Boolean) {
        synchronized(lock) {
            src = rawPrice
            if (!live) { price = Double.NaN; candleStart = 0L; return }
            val p = rawPrice
            val cs0 = System.currentTimeMillis() / alertCandleMs * alertCandleMs
            if (cs0 != candleStart) { candleStart = cs0; candleOpen = p }
            val prev = price
            price = p
            if (prev.isNaN()) {
                for (f in fvgs.values) if (f.active) f.inside = p >= f.bot && p <= f.top
                return
            }
            val lo = minOf(prev, p)
            val hi = maxOf(prev, p)
            val h = p * HYST_PCT
            val cs = System.currentTimeMillis() / alertCandleMs * alertCandleMs
            for (f in fvgs.values) {
                if (!f.active) continue
                if (!f.inside) {
                    if (lo <= f.top && hi >= f.bot) {
                        f.inside = true
                        // هر کندل فقط یک هشدار برای هر FVG؛ کندل بعدی دوباره مجاز است
                        // کندلی که اوپنش داخل همین FVG بوده هشدار نمی‌دهد، حتی اگر وسطش خارج و دوباره وارد شود
                        val openInside = candleOpen >= f.bot && candleOpen <= f.top
                        if (alertTouch && f.alertCandle != cs && !openInside) { f.alertCandle = cs; onAlert(AlertKind.TOUCH, f.bull) }
                    }
                } else if (p < f.bot - h || p > f.top + h) f.inside = false
            }
        }
    }

    /** حالت پشتیبان وقتی استریم زنده قطع است: کندل ۱ دقیقه‌ای */
    fun check(first: Boolean) {
        val d = fetch(1, 120)
        val w = if (lastTs < 0 || first) d.takeLast(2) else d.filter { it.t >= lastTs }
        synchronized(lock) {
            for (x in w) for (f in fvgs.values) {
                if (!f.active || x.t < f.at) continue
                // ابطال اینجا انجام نمی‌شود؛ فقط کلوز کندل تایم‌فریم در refresh() ناحیه را غیرفعال می‌کند
                val now = x.l <= f.top && x.h >= f.bot
                // هر ورود تازه به ناحیه = یک هشدار (نه فقط اولین برخورد)
                if (now && !f.inside && !first && alertTouch) {
                    val cs = x.t / alertCandleMs * alertCandleMs
                    val openInside = x.o >= f.bot && x.o <= f.top
                    if (f.alertCandle != cs && !openInside) { f.alertCandle = cs; onAlert(AlertKind.TOUCH, f.bull) }
                }
                f.inside = now
            }
            if (d.isNotEmpty()) { lastTs = d.last().t; price = d.last().c }
        }
    }

    /** فاصله قیمت تا نزدیک‌ترین FVG فعال (برای تنظیم سرعت چک در حالت پشتیبان) */
    fun nearestDistance(): Double = synchronized(lock) {
        var best = Double.MAX_VALUE
        if (price.isNaN()) return@synchronized best
        for (f in fvgs.values) {
            if (!f.active) continue
            val d = if (price >= f.bot && price <= f.top) 0.0
                    else minOf(Math.abs(price - f.top), Math.abs(price - f.bot))
            if (d < best) best = d
        }
        best
    }

    fun activeCount() = synchronized(lock) { fvgs.values.count { it.active } }
}
