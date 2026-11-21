package ua.odin.svitlo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var address: TextView
    private lateinit var statusDot: View
    private lateinit var statusLabel: TextView
    private lateinit var bigTime: TextView
    private lateinit var caption: TextView
    private lateinit var info: TextView
    private lateinit var street: EditText
    private lateinit var house: EditText
    private lateinit var battery: Button
    private lateinit var settings: View
    private lateinit var settingsToggle: TextView

    private val dayFmt = DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.forLanguageTag("uk"))
    private val ticker = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            show()
            ticker.postDelayed(this, 30_000)
        }
    }

    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { Renderer.renderAll(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // Показуємо минулий збій лише один раз
        val prefs = getSharedPreferences("svitlo", MODE_PRIVATE)
        crashText = prefs.getString("crash", null)
        prefs.edit().remove("crash").apply()

        swipe = findViewById(R.id.swipe)
        address = findViewById(R.id.address)
        statusDot = findViewById(R.id.status_dot)
        statusLabel = findViewById(R.id.status_label)
        bigTime = findViewById(R.id.big_time)
        caption = findViewById(R.id.caption)
        info = findViewById(R.id.info)
        street = findViewById(R.id.street)
        house = findViewById(R.id.house)
        battery = findViewById(R.id.battery)
        settings = findViewById(R.id.settings)
        settingsToggle = findViewById(R.id.settings_toggle)

        tint(findViewById(R.id.legend_on), R.color.on)
        tint(findViewById(R.id.legend_off), R.color.off)
        tint(findViewById(R.id.legend_maybe), R.color.maybe)

        swipe.setColorSchemeColors(getColor(R.color.on))
        swipe.setProgressBackgroundColorSchemeColor(getColor(R.color.card))
        swipe.setOnRefreshListener { refresh() }

        street.setText(Store.street(this))
        house.setText(Store.house(this))
        settingsToggle.setOnClickListener {
            val open = settings.visibility != View.VISIBLE
            settings.visibility = if (open) View.VISIBLE else View.GONE
            settingsToggle.text = if (open) "Адреса й налаштування  ▴" else "Адреса й налаштування  ▾"
        }
        findViewById<Button>(R.id.save).setOnClickListener {
            Store.saveAddress(this, street.text.toString().trim(), house.text.toString().trim())
            refresh()
        }
        battery.setOnClickListener { askBatteryExemption() }

        findViewById<View>(R.id.share).setOnClickListener {
            val snap = Store.load(this)
            if (snap == null) Toast.makeText(this, "Графік ще не завантажено", Toast.LENGTH_SHORT).show()
            else Share.open(this, snap)
        }
        bindReminderSettings()

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        val pm = getSystemService(PowerManager::class.java)
        val exempt = pm.isIgnoringBatteryOptimizations(packageName)
        battery.isEnabled = !exempt
        battery.text = if (exempt) "Робота у фоні дозволена ✓" else "Дозволити роботу у фоні"
        ticker.post(tick)
    }

    override fun onPause() {
        super.onPause()
        ticker.removeCallbacks(tick)
    }

    private fun refresh() {
        swipe.isRefreshing = true
        lifecycleScope.launch {
            Refresher.refresh(this@MainActivity)
            swipe.isRefreshing = false
            show()
        }
    }

    private fun bindReminderSettings() {
        fun sw(id: Int, key: String, value: Boolean) {
            val v = findViewById<Switch>(id)
            v.isChecked = value
            v.setOnCheckedChangeListener { _, on ->
                Store.setBool(this, key, on)
                Renderer.renderAll(this)
            }
        }
        sw(R.id.remind_off, "remind_off", Store.remindOff(this))
        sw(R.id.remind_on, "remind_on", Store.remindOn(this))
        sw(R.id.notify_changes, "notify_changes", Store.notifyChanges(this))
        sw(R.id.quiet_night, "quiet_night", Store.quietNight(this))

        val group = findViewById<RadioGroup>(R.id.lead)
        group.check(
            when (Store.leadMinutes(this)) {
                15 -> R.id.lead15
                60 -> R.id.lead60
                else -> R.id.lead30
            },
        )
        group.setOnCheckedChangeListener { _, id ->
            Store.setLead(this, if (id == R.id.lead15) 15 else if (id == R.id.lead60) 60 else 30)
            Renderer.renderAll(this)
        }
    }

    private fun tint(v: View, colorRes: Int) {
        v.backgroundTintList = ColorStateList.valueOf(getColor(colorRes))
    }

    private fun colorRes(h: Half) = when (h) {
        Half.ON -> R.color.on
        Half.OFF -> R.color.off
        Half.MAYBE -> R.color.maybe
        Half.UNKNOWN -> R.color.unknown
    }

    private fun show() {
        val snap = Store.load(this)
        val now = Instant.now()
        val st = StatusCalc.of(snap, now)
        val sched = snap?.let { Schedule.from(it) } ?: Schedule(emptyMap())

        address.text = buildString {
            append(Store.street(this@MainActivity)).append(", ").append(Store.house(this@MainActivity))
            snap?.let { append("  ·  черга ").append(it.group.removePrefix("GPV")) }
        }

        // Головна картка: час наступної зміни великими цифрами
        tint(statusDot, colorRes(st.current))
        statusLabel.text = st.title.uppercase(Locale.forLanguageTag("uk"))
        val until = st.until
        if (until != null) {
            bigTime.text = StatusCalc.hm(until)
            val verb = when (st.current) {
                Half.ON -> "вимкнуть"
                Half.OFF -> if (st.title.startsWith("Аварійне")) "увімкнуть орієнтовно" else "увімкнуть"
                else -> "до зміни"
            }
            val sameDay = until.atZone(KYIV).toLocalDate() == now.atZone(KYIV).toLocalDate()
            caption.text = "$verb ${Intervals.relative(now, until)}" + if (sameDay) "" else " · завтра"
        } else {
            bigTime.text = "—"
            caption.text = st.text
        }

        // Сьогодні / завтра
        val today = now.atZone(KYIV).toLocalDate()
        bindDay(
            sched, today, now, "Сьогодні", R.id.today_title, R.id.today_total,
            R.id.today_timeline, R.id.today_list, null,
        )
        bindDay(
            sched, today.plusDays(1), now, "Завтра", R.id.tomorrow_title, R.id.tomorrow_total,
            R.id.tomorrow_timeline, R.id.tomorrow_list, R.id.tomorrow_empty,
        )

        bindStats(snap, today)

        // Підвал
        val err = Store.error(this)
        info.text = buildString {
            if (snap != null) {
                append("Графік ДТЕК від ${snap.update}")
                append("  ·  оновлено о ${StatusCalc.hm(Instant.ofEpochMilli(snap.fetchedAt))}")
            }
            if (err != null) {
                if (isNotEmpty()) append("\n")
                append("Не вдалося оновити: $err")
            }
            crashText?.let {
                if (isNotEmpty()) append("\n")
                append("Попередній збій: ").append(it.lineSequence().take(4).joinToString(" | "))
            }
            if (isNotEmpty()) append("\n")
            append("Неофіційний застосунок. Дані з сайту ДТЕК Київські електромережі.")
            val v = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()
            if (v != null) append("\nверсія $v")
        }
    }

    private fun bindDay(
        sched: Schedule, day: LocalDate, now: Instant, name: String,
        titleId: Int, totalId: Int, timelineId: Int, listId: Int, emptyId: Int?,
    ) {
        val intervals = Intervals.forDay(sched, day)
        val has = Intervals.hasData(intervals)
        val dateText = dayFmt.format(day)

        findViewById<TextView>(titleId).text = "$name  ·  $dateText"
        val on = Intervals.total(intervals, Half.ON)
        findViewById<TextView>(totalId).text = if (has) "світло ${Intervals.dur(on)}" else ""

        val timeline = findViewById<DayTimelineView>(timelineId)
        timeline.bind(sched, day, now)
        timeline.visibility = if (has) View.VISIBLE else View.GONE
        emptyId?.let { findViewById<View>(it).visibility = if (has) View.GONE else View.VISIBLE }

        val list = findViewById<LinearLayout>(listId)
        list.removeAllViews()
        if (!has) return
        val inflater = LayoutInflater.from(this)
        for (iv in intervals) {
            if (iv.half == Half.UNKNOWN) continue
            val row = inflater.inflate(R.layout.item_interval, list, false)
            tint(row.findViewById(R.id.swatch), colorRes(iv.half))
            row.findViewById<TextView>(R.id.time).text =
                "${StatusCalc.hm(iv.start)} – ${Intervals.hmEnd(iv.end, day)}"
            val what = when (iv.half) {
                Half.ON -> "Світло є"
                Half.OFF -> "Світла немає"
                else -> "Можливе відключення"
            }
            row.findViewById<TextView>(R.id.sub).text = "$what  ·  ${Intervals.dur(iv.minutes)}"
            val current = iv.contains(now)
            row.findViewById<View>(R.id.badge).visibility = if (current) View.VISIBLE else View.GONE
            row.alpha = if (iv.end.isBefore(now) || iv.end == now) 0.4f else 1f
            list.addView(row)
        }
    }

    private var crashText: String? = null

    private val shortDay = DateTimeFormatter.ofPattern("EE, d.MM", Locale.forLanguageTag("uk"))

    /** Картка «Скільки світла щодня»: 7 минулих днів + сьогодні + завтра. */
    private fun bindStats(snap: Snapshot?, today: LocalDate) {
        val all = Store.history(this).toMutableMap()
        snap?.let { all.putAll(Stats.dayTotals(it)) }
        val days = all.keys
            .filter { !it.isBefore(today.minusDays(7)) && !it.isAfter(today.plusDays(1)) }
            .sorted()

        val list = findViewById<LinearLayout>(R.id.stats_list)
        list.removeAllViews()
        val inflater = LayoutInflater.from(this)
        for (d in days) {
            val t = all.getValue(d)
            val row = inflater.inflate(R.layout.item_stat, list, false)
            val label = row.findViewById<TextView>(R.id.day)
            label.text = when (d) {
                today -> "Сьогодні"
                today.plusDays(1) -> "Завтра"
                today.minusDays(1) -> "Вчора"
                else -> shortDay.format(d)
            }
            if (d == today) label.setTypeface(label.typeface, android.graphics.Typeface.BOLD)

            val bar = row.findViewById<LinearLayout>(R.id.bar)
            bar.backgroundTintList = ColorStateList.valueOf(getColor(R.color.unknown))
            bar.clipToOutline = true
            val on = row.findViewById<View>(R.id.bar_on)
            on.setBackgroundColor(getColor(R.color.on))
            (on.layoutParams as LinearLayout.LayoutParams).weight = t.on.toFloat()
            val rest = row.findViewById<View>(R.id.bar_rest)
            rest.setBackgroundColor(getColor(R.color.off))
            (rest.layoutParams as LinearLayout.LayoutParams).weight = (1440 - t.on).coerceAtLeast(0).toFloat()

            row.findViewById<TextView>(R.id.value).text = Intervals.dur(t.on)
            if (d.isAfter(today)) row.alpha = 0.75f
            list.addView(row)
        }

        // Середнє за дні, що вже минули або тривають
        val past = days.filter { !it.isAfter(today) }.map { all.getValue(it).on }
        findViewById<TextView>(R.id.stats_summary).text = when {
            past.isEmpty() -> ""
            past.size == 1 -> "Сьогодні за графіком ${Intervals.dur(past[0])} світла з 24 год"
            else -> "У середньому ${Intervals.dur(past.average().toLong())} на добу за ${past.size} дн."
        }
        findViewById<View>(R.id.stats_hint).visibility = if (days.size <= 2) View.VISIBLE else View.GONE
    }

    /** Відкриває системний список оптимізації батареї (без обмеженого дозволу Google Play). */
    private fun askBatteryExemption() {
        Toast.makeText(this, "Знайдіть «Світло» у списку й вимкніть для нього оптимізацію", Toast.LENGTH_LONG).show()
        runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            .onFailure { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
    }
}
