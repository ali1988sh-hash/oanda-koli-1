package com.example.fvgalert

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*

class MainActivity : Activity() {
    private companion object {
        const val REQ_SOUND = 11
        const val REQ_SOUND2 = 12
        val DEFAULT_SYMBOLS = listOf(
            "XAU_USD", "XAG_USD",
            "NAS100_USD", "US30_USD", "SPX500_USD", "US2000_USD", "DE30_EUR", "UK100_GBP", "JP225_USD", "EU50_EUR", "FR40_EUR", "AU200_AUD", "HK33_HKD",
            "EUR_USD", "GBP_USD", "USD_JPY", "USD_CHF", "USD_CAD", "AUD_USD", "NZD_USD",
            "EUR_GBP", "EUR_JPY", "GBP_JPY", "AUD_JPY", "EUR_AUD", "EUR_CHF", "GBP_CHF", "CAD_JPY", "CHF_JPY", "NZD_JPY",
            "WTICO_USD", "BCO_USD", "NATGAS_USD"
        )
        val BG = Color.parseColor("#0D1117")
        val CARD = Color.parseColor("#161B22")
        val LINE = Color.parseColor("#30363D")
        val FIELD = Color.parseColor("#0D1117")
        val GOLD = Color.parseColor("#F5B800")
        val TEXT = Color.parseColor("#E6EDF3")
        val MUTED = Color.parseColor("#8B949E")
    }

    private lateinit var key: EditText
    private lateinit var tf: EditText
    private lateinit var sym: AutoCompleteTextView
    private lateinit var paList: LinearLayout
    private lateinit var acct: EditText
    private lateinit var touch: Switch
    private lateinit var nw: Switch
    private lateinit var loop: Switch
    private lateinit var vibe: Switch
    private lateinit var btn: Button
    private lateinit var chip: TextView
    private lateinit var sp1: SoundPicker
    private lateinit var sp2: SoundPicker
    private var previewing: SoundPicker? = null
    private lateinit var root: LinearLayout

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun prefs() = getSharedPreferences("cfg", MODE_PRIVATE)

    private fun box(color: Int, radius: Int, stroke: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
        if (stroke != 0) setStroke(dp(1), stroke)
    }

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top) }

    private fun card(title: String, icon: String): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = box(CARD, 18, LINE)
            setPadding(dp(18), dp(16), dp(18), dp(18))
        }
        c.addView(TextView(this).apply {
            text = "$icon  $title"; textSize = 15f; setTextColor(GOLD); typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(c, lp(14))
        return c
    }

    private fun label(parent: LinearLayout, t: String) = TextView(this).apply {
        text = t; textSize = 12.5f; setTextColor(MUTED); setPadding(dp(2), dp(14), 0, dp(6))
    }.also { parent.addView(it) }

    private fun field(v: String, type: Int) = EditText(this).apply {
        setText(v); inputType = type; setTextColor(TEXT); textSize = 15f
        setHintTextColor(MUTED)
        background = box(FIELD, 12, LINE)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        textDirection = View.TEXT_DIRECTION_LTR
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        isSingleLine = true
    }

    private fun switchRow(parent: LinearLayout, text: String, sub: String, on: Boolean): Switch {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply { this.text = text; textSize = 15f; setTextColor(TEXT) })
        col.addView(TextView(this).apply { this.text = sub; textSize = 11.5f; setTextColor(MUTED) })
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val sw = Switch(this).apply {
            isChecked = on
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = ColorStateList(states, intArrayOf(GOLD, Color.parseColor("#6E7681")))
            trackTintList = ColorStateList(states, intArrayOf(Color.parseColor("#66F5B800"), LINE))
        }
        row.addView(sw)
        parent.addView(row, lp(14))
        return sw
    }

    private fun smallBtn(t: String, bg: Int, fg: Int = Color.WHITE) = Button(this).apply {
        text = t; textSize = 13f; isAllCaps = false; setTextColor(fg)
        background = box(bg, 12); stateListAnimator = null
        minHeight = 0; minimumHeight = 0; setPadding(dp(8), dp(10), dp(8), dp(10))
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        val p = prefs()
        // یک‌بار: مقدار قدیمی ۱۵۰ یا ۳۰ ثانیه ذخیره‌شده را به ۳ ثانیه ببر
        if (!p.getBoolean("poll_migrated2", false)) {
            val e = p.edit().putBoolean("poll_migrated2", true)
            if (p.getString("poll", null) in listOf("150", "30")) e.putString("poll", "3")
            e.apply()
        }

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(16), dp(28), dp(16), dp(32))
        }

        // ---------- هدر ----------
        val head = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        head.addView(ImageView(this).apply {
            setImageResource(R.drawable.app_logo)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, LinearLayout.LayoutParams(dp(104), dp(104)))
        head.addView(TextView(this).apply {
            text = "FVG Alert"; textSize = 26f; setTextColor(TEXT); typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(12), 0, 0)
        })
        head.addView(TextView(this).apply {
            text = "هشدار شکاف ارزش منصفانه"; textSize = 13f; setTextColor(MUTED)
        })
        chip = TextView(this).apply {
            textSize = 12.5f; typeface = Typeface.DEFAULT_BOLD; setPadding(dp(14), dp(6), dp(14), dp(6))
        }
        head.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(12) })
        root.addView(head, lp())

        btn = Button(this).apply {
            textSize = 17f; typeface = Typeface.DEFAULT_BOLD; isAllCaps = false; stateListAnimator = null
            setOnClickListener { if (FvgService.running) stopSvc() else startSvc() }
        }
        root.addView(btn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)).apply { topMargin = dp(20) })

        // ---------- اتصال ----------
        val c1 = card("اتصال", "🔑")
        label(c1, "OANDA API token")
        key = field(p.getString("key", "") ?: "", InputType.TYPE_CLASS_TEXT).also { c1.addView(it, lp()) }
        label(c1, "OANDA Account ID (مثل 101-001-1234567-001)")
        acct = field(p.getString("acct", "") ?: "", InputType.TYPE_CLASS_TEXT).also { c1.addView(it, lp()) }

        // ---------- تنظیمات ----------
        val c2 = card("تنظیمات FVG", "⚙️")
        label(c2, "نماد (طلا XAU_USD · نزدک NAS100_USD · داوجونز US30_USD · S&P500 SPX500_USD)")
        sym = AutoCompleteTextView(this).apply {
            setText(p.getString("sym", "XAU_USD") ?: "XAU_USD")
            setTextColor(TEXT); textSize = 15f; setHintTextColor(MUTED)
            background = box(FIELD, 12, LINE)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            textDirection = View.TEXT_DIRECTION_LTR
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            threshold = 1
            setDropDownBackgroundDrawable(box(CARD, 12, LINE))
            setOnClickListener { showDropDown() }
        }
        setSymbols(DEFAULT_SYMBOLS)
        c2.addView(sym, lp())
        val loadAll = smallBtn("📥  دریافت همه‌ی نمادهای حساب از OANDA", Color.parseColor("#1F6FEB"))
        c2.addView(loadAll, lp(10))
        loadAll.setOnClickListener {
            val k = key.text.toString().trim()
            val a2 = acct.text.toString().trim()
            if (k.isEmpty() || a2.isEmpty()) {
                Toast.makeText(this, "اول توکن و Account ID رو وارد کن", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            loadAll.isEnabled = false
            Thread {
                var msg = ""
                var names: List<String> = emptyList()
                try {
                    val conn = java.net.URL(Oanda.rest(a2) + "/v3/accounts/" + a2 + "/instruments")
                        .openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 15000
                    conn.readTimeout = 15000
                    conn.setRequestProperty("Authorization", "Bearer " + k)
                    val body = try {
                        (if (conn.responseCode < 400) conn.inputStream else conn.errorStream).bufferedReader().readText()
                    } finally { conn.disconnect() }
                    val arr = org.json.JSONObject(body).getJSONArray("instruments")
                    names = (0 until arr.length()).map { arr.getJSONObject(it).getString("name") }.sorted()
                    msg = names.size.toString() + " نماد دریافت شد"
                } catch (e: Exception) {
                    msg = "خطا در دریافت فهرست (توکن، Account ID و VPN رو چک کن)"
                }
                runOnUiThread {
                    loadAll.isEnabled = true
                    if (names.isNotEmpty()) setSymbols(names)
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                }
            }.start()
        }
        label(c2, "تایم‌فریم FVG به دقیقه (5 / 15 / 30 / 60)")
        tf = field(p.getString("tf", "15") ?: "15", InputType.TYPE_CLASS_NUMBER).also { c2.addView(it, lp()) }

        // ---------- هشدارها ----------
        val c3 = card("هشدارها", "🔔")
        touch = switchRow(c3, "رسیدن قیمت به FVG", "برای هر کندل ۱ دقیقه‌ای فقط یک‌بار؛ کندلی که داخل FVG باز شود هشدار نمی‌دهد", p.getBoolean("touch", true))
        nw = switchRow(c3, "تشکیل FVG جدید", "وقتی یک FVG تازه ساخته شود", p.getBoolean("new", true))
        loop = switchRow(c3, "زنگ پی‌درپی", "تا وقتی خودت قطع نکنی ادامه دارد", p.getBoolean("loop", true))
        vibe = switchRow(c3, "لرزش", "همراه با زنگ", p.getBoolean("vibe", true))

        // ---------- آهنگ هشدار FVG ----------
        sp1 = SoundPicker("sound", REQ_SOUND, card("آهنگ هشدار FVG", "🎵"), Sounds.DEFAULT_ID)

        // ---------- هشدار قیمت دستی ----------
        val c5 = card("هشدار قیمت دستی", "🎯")
        c5.addView(TextView(this).apply {
            text = "وقتی قیمت به این سطح برسد (از هر طرف) یک‌بار هشدار می‌دهد و بعد خودش حذف می‌شود."
            textSize = 11.5f; setTextColor(MUTED); setPadding(dp(2), dp(10), 0, 0)
        })
        paList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        c5.addView(paList, lp())
        val addPrice = smallBtn("＋  افزودن قیمت", Color.parseColor("#1F6FEB"))
        c5.addView(addPrice, lp(12))
        addPrice.setOnClickListener {
            val id = System.currentTimeMillis().toString()
            paPrefs().edit().putString(id, "").apply()
            addPriceRow(id, "")
        }
        reloadPriceAlerts()

        // ---------- آهنگ هشدار قیمت ----------
        sp2 = SoundPicker("psound", REQ_SOUND2, card("آهنگ هشدار قیمت", "🔔"), "raw:alarm_chime")

        // ---------- پایین ----------
        root.addView(Button(this).apply {
            text = "🔋  غیرفعال کردن بهینه‌سازی باتری (پیشنهادی)"
            textSize = 13f; isAllCaps = false; setTextColor(TEXT); stateListAnimator = null
            background = box(Color.TRANSPARENT, 14, LINE)
            setOnClickListener { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }, lp(18))
        root.addView(TextView(this).apply {
            text = "بعد از شروع، سرویس تو پس‌زمینه با یه اعلان دائمی کار می‌کنه."
            textSize = 11.5f; setTextColor(MUTED); gravity = Gravity.CENTER; setPadding(0, dp(12), 0, 0)
        })

        setContentView(ScrollView(this).apply { setBackgroundColor(BG); addView(root) })
    }

    private fun paPrefs() = getSharedPreferences("pa", MODE_PRIVATE)

    private fun setSymbols(list: List<String>) {
        sym.setAdapter(object : ArrayAdapter<String>(this, android.R.layout.simple_dropdown_item_1line, list) {
            override fun getView(pos: Int, cv: View?, parent: ViewGroup): View =
                (super.getView(pos, cv, parent) as TextView).apply { setTextColor(TEXT); setBackgroundColor(CARD) }
        })
    }

    private fun reloadPriceAlerts() {
        paList.removeAllViews()
        paPrefs().all.entries.sortedBy { it.key }.forEach { addPriceRow(it.key, (it.value as? String) ?: "") }
    }

    private fun addPriceRow(id: String, value: String) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val f = field(value, InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        f.hint = "مثلاً 3900.50"
        f.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                // اگر سرویس این هشدار را فعال‌شده و حذف کرده، دوباره نمی‌نویسیمش
                val pa = paPrefs()
                if (pa.contains(id)) pa.edit().putString(id, s.toString()).apply()
            }
        })
        val x = smallBtn("✕", Color.parseColor("#6E1B1B"))
        x.setOnClickListener { paPrefs().edit().remove(id).apply(); paList.removeView(row) }
        row.addView(f, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(x, LinearLayout.LayoutParams(dp(52), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
        paList.addView(row, lp(8))
    }

    private fun refreshPreviews() {
        if (::sp1.isInitialized) sp1.refreshBtn()
        if (::sp2.isInitialized) sp2.refreshBtn()
    }

    /** انتخاب آهنگ: فهرست، پخش آزمایشی، افزودن و حذف آهنگ دلخواه */
    private inner class SoundPicker(val prefKey: String, val reqCode: Int, card: LinearLayout, val default: String) {
        var sounds: List<SoundItem> = emptyList()
        val spinner = Spinner(this@MainActivity).apply { background = box(FIELD, 12, LINE); setPadding(dp(10), dp(4), dp(10), dp(4)) }
        val preview = smallBtn("▶  پخش", Color.parseColor("#238636"))
        val del = smallBtn("🗑  حذف", Color.parseColor("#6E1B1B"))

        init {
            card.addView(spinner, lp(12))
            val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
            val add = smallBtn("＋  افزودن آهنگ", Color.parseColor("#1F6FEB"))
            val w = { m: Int -> LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(m) } }
            row.addView(preview, w(0)); row.addView(add, w(8)); row.addView(del, w(8))
            card.addView(row, lp(12))
            card.addView(TextView(this@MainActivity).apply {
                text = "🔊  میزان بلندی صدا"; textSize = 12.5f; setTextColor(MUTED); setPadding(dp(2), dp(14), 0, dp(4))
            })
            val bar = SeekBar(this@MainActivity).apply {
                max = 95
                progress = prefs().getInt(prefKey + "_vol", 100).coerceIn(5, 100) - 5
                progressTintList = ColorStateList.valueOf(GOLD)
                thumbTintList = ColorStateList.valueOf(GOLD)
            }
            bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    prefs().edit().putInt(prefKey + "_vol", p + 5).apply()
                    if (previewing === this@SoundPicker) AlarmCenter.setVolume((p + 5) / 100f)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
            card.addView(bar, lp())
            preview.setOnClickListener {
                if (AlarmCenter.isPlaying()) { AlarmCenter.stopSound(); previewing = null }
                else { AlarmCenter.playSound(this@MainActivity, selected().id, false, volume()); previewing = this }
                refreshPreviews()
            }
            add.setOnClickListener {
                this@MainActivity.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    type = "audio/*"; addCategory(Intent.CATEGORY_OPENABLE)
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                }, reqCode)
            }
            del.setOnClickListener {
                val s = selected()
                if (s.custom) {
                    AlarmCenter.stopSound()
                    Sounds.removeCustom(this@MainActivity, s.id)
                    prefs().edit().putString(prefKey, default).apply()
                    load(default)
                }
            }
            load(prefs().getString(prefKey, default) ?: default)
        }

        fun volume(): Float = prefs().getInt(prefKey + "_vol", 100).coerceIn(5, 100) / 100f

        fun selected(): SoundItem = sounds.getOrNull(spinner.selectedItemPosition) ?: sounds.first()

        fun refreshBtn() {
            preview.text = if (AlarmCenter.isPlaying() && previewing === this) "⏹  توقف" else "▶  پخش"
        }

        fun load(selectId: String) {
            sounds = Sounds.all(this@MainActivity)
            spinner.adapter = object : ArrayAdapter<SoundItem>(this@MainActivity, android.R.layout.simple_spinner_item, sounds) {
                override fun getView(pos: Int, cv: View?, parent: ViewGroup): View = bind(pos, super.getView(pos, cv, parent), false)
                override fun getDropDownView(pos: Int, cv: View?, parent: ViewGroup): View = bind(pos, super.getDropDownView(pos, cv, parent), true)
                private fun bind(pos: Int, v: View, dropdown: Boolean): View {
                    (v as TextView).apply {
                        text = sounds[pos].name; setTextColor(TEXT); textSize = 15f
                        layoutDirection = View.LAYOUT_DIRECTION_RTL
                        setPadding(dp(12), dp(12), dp(12), dp(12))
                        if (dropdown) setBackgroundColor(CARD)
                    }
                    return v
                }
            }
            val idx = sounds.indexOfFirst { it.id == selectId }.let { if (it < 0) 0 else it }
            spinner.setSelection(idx)
            spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(a: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    prefs().edit().putString(prefKey, sounds[pos].id).apply()
                    del.alpha = if (sounds[pos].custom) 1f else 0.35f
                    del.isEnabled = sounds[pos].custom
                }
                override fun onNothingSelected(a: AdapterView<*>?) {}
            }
            del.alpha = if (sounds[idx].custom) 1f else 0.35f
            del.isEnabled = sounds[idx].custom
        }

        fun handle(data: Intent) {
            val flags = data.flags
            var last: SoundItem? = null
            val clip = data.clipData
            if (clip != null) {
                for (i in 0 until clip.itemCount) last = Sounds.addCustom(this@MainActivity, clip.getItemAt(i).uri, flags) ?: last
            } else data.data?.let { last = Sounds.addCustom(this@MainActivity, it, flags) }
            last?.let {
                prefs().edit().putString(prefKey, it.id).apply()
                load(it.id)
                Toast.makeText(this@MainActivity, "آهنگ اضافه شد", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (res != RESULT_OK || data == null) return
        when (req) {
            REQ_SOUND -> sp1.handle(data)
            REQ_SOUND2 -> sp2.handle(data)
        }
    }

    private fun setRunning(on: Boolean) {
        btn.text = if (on) "⏹  توقف" else "▶  شروع"
        btn.setTextColor(if (on) Color.WHITE else Color.parseColor("#1A1300"))
        btn.background = box(if (on) Color.parseColor("#DA3633") else GOLD, 16)
        chip.text = if (on) "● فعال" else "● متوقف"
        chip.setTextColor(if (on) Notif.GREEN else Notif.RED)
        chip.background = box(if (on) Color.parseColor("#1A22C55E") else Color.parseColor("#1AEF4444"), 20)
    }

    override fun onResume() {
        super.onResume()
        setRunning(FvgService.running)
        reloadPriceAlerts()
        refreshPreviews()
    }

    override fun onPause() {
        saveSettings()
        if (AlarmCenter.isPlaying() && !AlarmCenter.ringing) AlarmCenter.stopSound()
        super.onPause()
    }

    private fun saveSettings() {
        prefs().edit()
            .putString("key", key.text.toString().trim())
            .putString("tf", tf.text.toString())
            .putString("sym", sym.text.toString().trim().uppercase().replace('/', '_').ifEmpty { "XAU_USD" })
            .putString("acct", acct.text.toString().trim())
            .putBoolean("touch", touch.isChecked)
            .putBoolean("new", nw.isChecked)
            .putBoolean("loop", loop.isChecked)
            .putBoolean("vibe", vibe.isChecked)
            .apply()
    }

    private fun startSvc() {
        if (key.text.isBlank() || acct.text.isBlank()) { Toast.makeText(this, "توکن و Account ID رو وارد کن", Toast.LENGTH_SHORT).show(); return }
        saveSettings()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        startForegroundService(Intent(this, FvgService::class.java))
        setRunning(true)
    }

    private fun stopSvc() {
        stopService(Intent(this, FvgService::class.java))
        setRunning(false)
    }
}
