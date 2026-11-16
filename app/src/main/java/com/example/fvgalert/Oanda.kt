package com.example.fvgalert

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object Oanda {
    /** حساب لایو با 001- شروع می‌شود، دمو با 101- */
    private fun live(account: String) = account.startsWith("001-")
    fun rest(account: String) = if (live(account)) "https://api-fxtrade.oanda.com" else "https://api-fxpractice.oanda.com"
    fun stream(account: String) = if (live(account)) "https://stream-fxtrade.oanda.com" else "https://stream-fxpractice.oanda.com"
}

/** قیمت زنده‌ی نماد انتخاب‌شده (bid) از استریم OANDA؛ تا ۴ قیمت در ثانیه. با هر قطعی خودش دوباره وصل می‌شود. */
class OandaStream(
    private val token: String,
    private val account: String,
    private val symbol: String,
    private val onPrice: (Double) -> Unit
) {
    @Volatile var connected = false
    @Volatile var lastError = ""
    @Volatile private var running = false
    @Volatile private var conn: HttpURLConnection? = null
    private var thread: Thread? = null

    fun start() {
        if (thread != null) return
        running = true
        thread = Thread { loop() }.also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        running = false
        connected = false
        try { conn?.disconnect() } catch (e: Exception) { }
        thread?.interrupt()
        thread = null
    }

    private fun loop() {
        while (running) {
            try {
                readStream()
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                lastError = e.message ?: "error"
            }
            connected = false
            try { Thread.sleep(3000) } catch (e: InterruptedException) { break }
        }
    }

    private fun readStream() {
        val u = Oanda.stream(account) + "/v3/accounts/" + account + "/pricing/stream?instruments=" + symbol
        val c = URL(u).openConnection() as HttpURLConnection
        conn = c
        c.connectTimeout = 10000
        c.readTimeout = 20000 // هر ~۵ ثانیه heartbeat می‌آید؛ اگر نیامد یعنی اتصال مرده
        c.setRequestProperty("Authorization", "Bearer " + token)
        try {
            if (c.responseCode >= 400) throw RuntimeException("HTTP " + c.responseCode)
            val r = c.inputStream.bufferedReader()
            while (running) {
                val line = r.readLine() ?: break
                if (line.isBlank()) continue
                val j = JSONObject(line)
                connected = true
                if (j.optString("type") == "PRICE") {
                    val bids = j.optJSONArray("bids")
                    if (bids != null && bids.length() > 0) {
                        onPrice(bids.getJSONObject(0).getString("price").toDouble())
                    }
                }
            }
        } finally {
            c.disconnect()
        }
    }
}
