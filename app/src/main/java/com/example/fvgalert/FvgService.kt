package com.example.fvgalert

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.IBinder
import android.os.PowerManager
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class FvgService : Service() {
    companion object {
        @Volatile var running = false
        const val CH_ALERT = "alerts2"
    }

    private var thread: Thread? = null
    private var stream: OandaStream? = null
    private var wl: PowerManager.WakeLock? = null
    private var alertId = 100
    private var symLabel = "XAU/USD"
    private var prevRaw = Double.NaN

    /** بازار طلا: بسته از جمعه ۲۲:۰۰ تا یکشنبه ۲۲:۰۰ (UTC) */
    private fun marketClosed(): Boolean {
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        val dow = c.get(Calendar.DAY_OF_WEEK)
        val h = c.get(Calendar.HOUR_OF_DAY)
        return dow == Calendar.SATURDAY || (dow == Calendar.FRIDAY && h >= 22) || (dow == Calendar.SUNDAY && h < 22)
    }

    private fun nm() = getSystemService(NotificationManager::class.java)

    private fun pi() = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
    )

    // نوتیف وضعیت: بدون قیمت
    private fun statusNotif(text: String): Notification =
        Notification.Builder(this, "status")
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("FVG Alert — " + symLabel)
            .setContentText(text)
            .setContentIntent(pi())
            .setOngoing(true)
            .build()

    private fun alert(kind: AlertKind, bull: Boolean) {
        val p = getSharedPreferences("cfg", MODE_PRIVATE)
        val soundId = p.getString("sound", Sounds.DEFAULT_ID) ?: Sounds.DEFAULT_ID
        val dir = if (bull) "صعودی 🟢" else "نزولی 🔴"
        val msg = when (kind) {
            AlertKind.TOUCH -> "قیمت به FVG $dir رسید"
            AlertKind.NEW -> "FVG جدید $dir تشکیل شد"
        }
        fire(msg, bull, soundId, p.getInt("sound_vol", 100).coerceIn(5, 100) / 100f)
    }

    /** هشدار قیمت دستی: وقتی قیمت از سطح مورد نظر عبور کرد، فقط یک‌بار */
    @Synchronized
    private fun checkPriceAlerts(p: Double) {
        if (p.isNaN()) return
        val prev = prevRaw
        prevRaw = p
        if (prev.isNaN()) return
        val pa = getSharedPreferences("pa", MODE_PRIVATE)
        val all = pa.all
        if (all.isEmpty()) return
        for ((id, v) in all) {
            val txt = (v as? String)?.trim() ?: continue
            val lvl = txt.toDoubleOrNull() ?: continue
            val up = prev < lvl && p >= lvl
            val down = prev > lvl && p <= lvl
            if (up || down) {
                pa.edit().remove(id).apply()
                val sid = getSharedPreferences("cfg", MODE_PRIVATE).getString("psound", "raw:alarm_chime") ?: "raw:alarm_chime"
                val vol = getSharedPreferences("cfg", MODE_PRIVATE).getInt("psound_vol", 100).coerceIn(5, 100) / 100f
                fire("قیمت به $txt رسید", up, sid, vol)
            }
        }
    }

    private fun fire(msg: String, bull: Boolean, soundId: String, vol: Float) {
        val p = getSharedPreferences("cfg", MODE_PRIVATE)
        val loop = p.getBoolean("loop", true)
        val vibe = p.getBoolean("vibe", true)
        val id = alertId++
        val open = PendingIntent.getActivity(
            this, 2,
            Intent(this, AlarmActivity::class.java)
                .putExtra("msg", msg).putExtra("bull", bull).putExtra("sym", symLabel)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getBroadcast(
            this, 3, Intent(this, StopReceiver::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val b = Notification.Builder(this, CH_ALERT)
            .setSmallIcon(R.drawable.ic_stat)
            .setColor(if (bull) Notif.GREEN else Notif.RED)
            .setLargeIcon(Notif.dirIcon(bull))
            .setContentTitle("FVG Alert — " + symLabel)
            .setContentText(msg)
            .setStyle(Notification.BigTextStyle().bigText(msg))
            .setCategory(Notification.CATEGORY_ALARM)
            .setContentIntent(open)
        if (loop) {
            b.setOngoing(true)
                .setFullScreenIntent(open, true)
                .setDeleteIntent(stop)
                .addAction(
                    Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stop), "قطع هشدار", stop).build()
                )
            AlarmCenter.track(id)
        } else {
            b.setAutoCancel(true)
        }
        nm().notify(id, b.build())
        AlarmCenter.startAlarm(this, soundId, loop, vibe, vol)
    }

    override fun onCreate() {
        super.onCreate()
        nm().createNotificationChannel(NotificationChannel("status", "وضعیت", NotificationManager.IMPORTANCE_LOW))
        // صدا و لرزش رو خودمون پخش می‌کنیم (برای تکرار تا قطع دستی)، پس کانال بی‌صداست
        nm().createNotificationChannel(
            NotificationChannel(CH_ALERT, "هشدارها", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
                enableVibration(false)
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (thread != null) return START_STICKY
        startForeground(1, statusNotif("در حال راه‌اندازی…"))
        running = true
        wl = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fvgalert:poll").apply { acquire() }

        val p = getSharedPreferences("cfg", MODE_PRIVATE)
        val poll = 3L
        val tf = p.getString("tf", "15")?.toIntOrNull() ?: 15
        val sym = (p.getString("sym", "XAU_USD") ?: "XAU_USD").trim().uppercase().replace('/', '_').ifEmpty { "XAU_USD" }
        symLabel = sym.replace('_', '/')
        val eng = Engine(
            p.getString("key", "") ?: "", p.getString("acct", "") ?: "", sym, tf, 0.0,
            p.getBoolean("touch", true), p.getBoolean("new", true)
        ) { kind, bull -> alert(kind, bull) }

        // قیمت زنده و کندل‌ها هر دو از OANDA (همان فید OANDA:XAUUSD در تریدینگ‌ویو)
        val st = OandaStream(p.getString("key", "") ?: "", p.getString("acct", "") ?: "", sym) { raw ->
            val open = !marketClosed()
            eng.onTick(raw, open)
            if (open) checkPriceAlerts(raw) else prevRaw = Double.NaN
        }
        stream = st
        st.start()

        thread = Thread {
            var first = true
            var next = 0L
            var lastPoll = 0L
            var lastStatus = 0L
            val step = tf * 60000L
            val clock = SimpleDateFormat("HH:mm:ss", Locale.US)
            while (running) {
                var wait = 3L
                try {
                    val now = System.currentTimeMillis()
                    if (!first && marketClosed()) {
                        nm().notify(1, statusNotif("بازار بسته است"))
                        wait = 300
                    } else {
                        if (first || now >= next) {
                            val fresh = eng.refresh(first)
                            val t = System.currentTimeMillis()
                            // اگر کندل جدید هنوز روی سرور نیامده، زود دوباره تلاش کن (نه ۱۵ دقیقه بعد)
                            next = if (fresh) (t / step + 1) * step + 2000 else t + 5000L
                        }
                        val live = st.connected
                        // حالت پشتیبان: فقط وقتی استریم زنده نیست، با کندل ۱ دقیقه‌ای
                        if (!live && now - lastPoll >= poll * 1000L) {
                            lastPoll = now
                            eng.check(first)
                            if (!marketClosed()) checkPriceAlerts(eng.price)
                        }
                        first = false
                        if (now - lastStatus >= 10000L) {
                            lastStatus = now
                            nm().notify(1, statusNotif(
                                String.format(Locale.US, "%s — %s — FVG فعال: %d",
                                    clock.format(Date()),
                                    if (live) "زنده (OANDA)" else "پشتیبان" + (if (st.lastError.isNotEmpty()) " (" + st.lastError + ")" else ""),
                                    eng.activeCount())
                            ))
                        }
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    wait = 60
                    nm().notify(1, statusNotif("خطا: ${e.message}"))
                }
                try { Thread.sleep(wait * 1000) } catch (e: InterruptedException) { break }
            }
        }.also { it.start() }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        thread?.interrupt()
        thread = null
        stream?.stop()
        stream = null
        AlarmCenter.stopAlarm(this)
        wl?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    override fun onBind(i: Intent?): IBinder? = null
}
