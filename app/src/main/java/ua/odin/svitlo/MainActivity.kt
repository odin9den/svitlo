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
import android.view.inputmethod.InputMethodManager
import android.app.AlertDialog
import android.widget.Button
import android.widget.EditText
import android.widget.AutoCompleteTextView
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private lateinit var street: AutoCompleteTextView
    private lateinit var house: AutoCompleteTextView
    private lateinit var city: AutoCompleteTextView
    private lateinit var addrName: EditText
    private lateinit var regionGroup: RadioGroup
    private lateinit var chips: LinearLayout
    private var housesFor: String? = null
    /** Регіон у формі адреси. */
    private var formRegion = Region.KYIV
    /** Форма додає нову адресу (а не редагує активну). */
    private var adding = false
    private var filling = false
    private lateinit var battery: Button
    private lateinit var settings: View
    private lateinit var settingsToggle: TextView

    private val dayFmt = DateTimeFormatter.ofPattern("EE, d MMMM", Locale.forLanguageTag("uk"))
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
        city = findViewById(R.id.city)
        addrName = findViewById(R.id.addr_name)
        regionGroup = findViewById(R.id.region)
        chips = findViewById(R.id.addr_chips)
        battery = findViewById(R.id.battery)
        settings = findViewById(R.id.settings)
        settingsToggle = findViewById(R.id.settings_toggle)

        tint(findViewById(R.id.legend_on), R.color.on)
        tint(findViewById(R.id.legend_off), R.color.off)
        tint(findViewById(R.id.legend_maybe), R.color.maybe)

        swipe.setColorSchemeColors(getColor(R.color.on))
        swipe.setProgressBackgroundColorSchemeColor(getColor(R.color.card))
        swipe.setOnRefreshListener { refresh() }

        // Адреса з інтенту (використовує автотест на емуляторі)
        val xs = intent.getStringExtra("street")
        val xh = intent.getStringExtra("house")
        if (!xs.isNullOrBlank() && !xh.isNullOrBlank()) {
            Store.addAddress(
                this,
                Address(
                    "", Region.of(intent.getStringExtra("region")), intent.getStringExtra("city").orEmpty(),
                    xs, xh, intent.getStringExtra("name").orEmpty(),
                ),
            )
        }
        setupAddress()
        settingsToggle.setOnClickListener {
            if (settings.visibility == View.VISIBLE) closeSettings() else openSettings(adding = false)
        }
        findViewById<Button>(R.id.save).setOnClickListener {
            val c = city.text.toString().trim()
            val st = street.text.toString().trim()
            val h = house.text.toString().trim()
            val known = Store.streets(this, formRegion, c)
            when {
                formRegion == Region.OBLAST && c !in Store.cities(this) ->
                    toast("Оберіть населений пункт зі списку")
                st.isBlank() || h.isBlank() -> toast("Оберіть вулицю й будинок зі списку")
                known.isNotEmpty() && st !in known -> toast("Такої вулиці немає в базі ДТЕК — оберіть зі списку")
                else -> applyAddress(st, h)
            }
        }
        findViewById<View>(R.id.delete_addr).setOnClickListener { confirmDelete() }
        battery.setOnClickListener { askBatteryExemption() }
        findViewById<View>(R.id.perm_notif).setOnClickListener { askNotificationPermission() }
        findViewById<View>(R.id.perm_battery).setOnClickListener { askBatteryExemption() }
        findViewById<View>(R.id.perm_later).setOnClickListener {
            prefs.edit().putBoolean("perm_later", true).apply()
            show()
        }

        findViewById<View>(R.id.share).setOnClickListener {
            val snap = Store.load(this)
            if (snap == null) Toast.makeText(this, "Графік ще не завантажено", Toast.LENGTH_SHORT).show()
            else Share.open(this, snap)
        }
        bindReminderSettings()
        // Дозвіл на сповіщення просимо з картки-пояснення, а не одразу при запуску
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
            if (street.adapter == null) bindStreets()
            if (formRegion == Region.OBLAST && city.adapter == null) bindCities()
            show()
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    // ---------- Адреси ----------

    /** Чипи над графіком: усі адреси, активна підсвічена, у кінці «+ Адреса». */
    private fun renderChips() {
        chips.removeAllViews()
        val list = Store.addresses(this)
        val active = Store.active(this)
        val dp = resources.displayMetrics.density
        fun chip(text: String, selected: Boolean, onClick: () -> Unit) = TextView(this).apply {
            this.text = text
            textSize = 14f
            setTypeface(typeface, if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            setTextColor(getColor(if (selected) R.color.badge_text else R.color.text))
            setBackgroundResource(R.drawable.pill)
            backgroundTintList = ColorStateList.valueOf(getColor(if (selected) R.color.badge_bg else R.color.card))
            setPadding((16 * dp).toInt(), (9 * dp).toInt(), (16 * dp).toInt(), (9 * dp).toInt())
            maxLines = 1
            setOnClickListener { onClick() }
            chips.addView(this, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = (8 * dp).toInt() })
        }
        for (a in list) {
            chip(a.label, a.id == active?.id) { switchTo(a) }
        }
        chip("+ Адреса", false) { openSettings(adding = true) }
    }

    private fun switchTo(a: Address) {
        if (a.id == Store.active(this)?.id) return
        Store.setActive(this, a.id)
        Renderer.renderAll(this)
        closeSettings()
        show()
        refresh()
    }

    private fun openSettings(adding: Boolean) {
        this.adding = adding
        fillForm(if (adding) null else Store.active(this))
        settings.visibility = View.VISIBLE
        settingsToggle.text = "Адреса й налаштування  ▴"
        if (adding) {
            // У режимі додавання налаштування нагадувань не заважають
            findViewById<View>(R.id.extra_settings).visibility = View.GONE
            settings.post { findViewById<android.widget.ScrollView>(R.id.scroll)?.smoothScrollTo(0, settingsToggle.top) }
        }
    }

    private fun closeSettings() {
        adding = false
        settings.visibility = View.GONE
        settingsToggle.text = "Адреса й налаштування  ▾"
        hideKeyboard()
    }

    private fun setupAddress() {
        for (f in listOf(street, house, city)) f.setDropDownBackgroundResource(R.drawable.dropdown_bg)

        regionGroup.setOnCheckedChangeListener { _, id ->
            if (filling) return@setOnCheckedChangeListener
            formRegion = if (id == R.id.region_oblast) Region.OBLAST else Region.KYIV
            city.setText("", false)
            street.setText("", false)
            house.setText("", false)
            housesFor = null
            house.setAdapter(null)
            house.isEnabled = false
            house.hint = "Спершу оберіть вулицю"
            applyRegionUi()
        }
        city.setOnItemClickListener { parent, _, pos, _ ->
            city.setText(parent.getItemAtPosition(pos) as String, false)
            street.setText("", false)
            house.setText("", false)
            housesFor = null
            bindStreets()
            street.requestFocus()
        }
        // Порожнє поле — показуємо весь список, щоб можна було гортати
        city.setOnClickListener { if (city.text.isBlank() && city.adapter != null) city.showDropDown() }
        city.setOnFocusChangeListener { _, f -> if (f && city.text.isBlank() && city.adapter != null) city.showDropDown() }
        street.setOnItemClickListener { parent, _, pos, _ ->
            val s = parent.getItemAtPosition(pos) as String
            house.setText("", false)
            loadHouses(s, open = true)
        }
        house.setOnItemClickListener { parent, _, pos, _ ->
            hideKeyboard()
            // Нову адресу з назвою зберігаємо кнопкою — щоб встигли дописати назву
            if (adding && addrName.visibility == View.VISIBLE) return@setOnItemClickListener
            applyAddress(street.text.toString().trim(), parent.getItemAtPosition(pos) as String)
        }
        house.setOnFocusChangeListener { _, focused -> if (focused) openHouses() }
        house.setOnClickListener { openHouses() }

        adding = !Store.hasAddress(this)
        fillForm(Store.active(this))
    }

    /** Заповнити форму адресою (або очистити для нової). */
    private fun fillForm(a: Address?) {
        filling = true
        formRegion = a?.region ?: Region.KYIV
        regionGroup.check(if (formRegion == Region.OBLAST) R.id.region_oblast else R.id.region_kyiv)
        filling = false
        city.setText(a?.city.orEmpty(), false)
        street.setText(a?.street.orEmpty(), false)
        house.setText(a?.house.orEmpty(), false)
        addrName.setText(a?.name.orEmpty())
        housesFor = null
        house.setAdapter(null)
        house.isEnabled = a != null
        house.hint = if (a != null) "Номер будинку" else "Спершу оберіть вулицю"
        applyRegionUi()
        if (a != null) loadHouses(a.street, open = false)

        val hasAny = Store.hasAddress(this)
        // Назва потрібна, лише коли адрес кілька; на першому екрані — тільки вулиця й будинок
        addrName.visibility = if (hasAny) View.VISIBLE else View.GONE
        findViewById<View>(R.id.delete_addr).visibility = if (!adding && a != null) View.VISIBLE else View.GONE
        findViewById<Button>(R.id.save).text = if (adding && hasAny) "Додати адресу" else "Зберегти й оновити"
        findViewById<View>(R.id.extra_settings).visibility = if (hasAny && !adding) View.VISIBLE else View.GONE
    }

    private fun applyRegionUi() {
        city.visibility = if (formRegion == Region.OBLAST) View.VISIBLE else View.GONE
        street.hint = if (formRegion == Region.OBLAST) "Вулиця" else "Почніть вводити назву вулиці"
        if (formRegion == Region.OBLAST) bindCities()
        bindStreets()
    }

    private fun openHouses() {
        val st = street.text.toString().trim()
        when {
            house.adapter != null && housesFor == houseKey(st) -> house.showDropDown()
            st.isNotBlank() -> loadHouses(st, open = true)
        }
    }

    private fun bindStreets() {
        val list = Store.streets(this, formRegion, city.text.toString().trim())
        street.setAdapter(if (list.isNotEmpty()) ContainsAdapter(this, list) else null)
    }

    /** Населені пункти області; якщо довідника ще немає — завантажуємо його з сайту ДТЕК. */
    private fun bindCities() {
        val list = Store.cities(this)
        if (list.isNotEmpty()) {
            city.setAdapter(ContainsAdapter(this, list))
            return
        }
        city.hint = "Завантажую населені пункти…"
        lifecycleScope.launch {
            val r = runCatching { withContext(Dispatchers.IO) { DtekClient.refreshStreets(this@MainActivity, Region.OBLAST) } }
            city.hint = "Населений пункт, напр. Бровари"
            val loaded = Store.cities(this@MainActivity)
            if (loaded.isNotEmpty()) {
                city.setAdapter(ContainsAdapter(this@MainActivity, loaded))
                bindStreets()
            } else {
                toast("Не вдалося завантажити населені пункти: ${r.exceptionOrNull()?.message ?: "порожній список"}")
            }
        }
    }

    private fun houseKey(st: String) = "${formRegion.key}|${city.text.toString().trim()}|$st"

    private fun loadHouses(s: String, open: Boolean) {
        val key = houseKey(s)
        if (housesFor == key && house.adapter != null) {
            if (open) house.showDropDown()
            return
        }
        val region = formRegion
        val c = city.text.toString().trim()
        house.isEnabled = false
        house.hint = "Завантажую будинки…"
        lifecycleScope.launch {
            val r = runCatching { withContext(Dispatchers.IO) { DtekClient.houses(this@MainActivity, region, c, s) } }
            house.isEnabled = true
            house.hint = "Номер будинку"
            if (houseKey(s) != key) return@launch // поки чекали, форму змінили
            r.onSuccess { list ->
                housesFor = key
                house.setAdapter(ContainsAdapter(this@MainActivity, list))
                if (open) {
                    house.requestFocus()
                    house.showDropDown()
                }
            }.onFailure { toast("Не вдалося завантажити будинки: ${it.message}") }
        }
    }

    private fun applyAddress(s: String, h: String) {
        val a = Address(
            "", formRegion, if (formRegion == Region.OBLAST) city.text.toString().trim() else "",
            s, h, addrName.text.toString().trim(),
        )
        if (adding || !Store.hasAddress(this)) Store.addAddress(this, a) else Store.updateActive(this, a)
        closeSettings()
        Renderer.renderAll(this)
        show()
        refresh()
    }

    private fun confirmDelete() {
        val a = Store.active(this) ?: return
        AlertDialog.Builder(this)
            .setMessage("Видалити адресу «${a.label}»?")
            .setPositiveButton("Видалити") { _, _ ->
                Store.removeAddress(this, a.id)
                closeSettings()
                adding = !Store.hasAddress(this)
                fillForm(Store.active(this))
                Renderer.renderAll(this)
                show()
                if (Store.hasAddress(this)) refresh()
            }
            .setNegativeButton("Скасувати", null)
            .show()
    }

    private fun hideKeyboard() {
        val v = currentFocus ?: house
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(v.windowToken, 0)
        v.clearFocus()
    }

    // ---------- Дозволи ----------

    private fun notificationsOk() = NotificationManagerCompat.from(this).areNotificationsEnabled()

    private fun batteryOk() = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    private fun askNotificationPermission() {
        val prefs = getSharedPreferences("svitlo", MODE_PRIVATE)
        val canAsk = Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            (!prefs.getBoolean("notif_asked", false) || shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS))
        if (canAsk) {
            prefs.edit().putBoolean("notif_asked", true).apply()
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            // Система більше не показує запит — відкриваємо налаштування сповіщень застосунку
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }
    }

    private fun bindPermissionCard(hasAddress: Boolean) {
        val n = notificationsOk()
        val b = batteryOk()
        val later = getSharedPreferences("svitlo", MODE_PRIVATE).getBoolean("perm_later", false)
        findViewById<View>(R.id.perm_card).visibility =
            if (hasAddress && !later && (!n || !b)) View.VISIBLE else View.GONE
        for (id in intArrayOf(R.id.perm_notif_text, R.id.perm_notif)) findViewById<View>(id).visibility = if (n) View.GONE else View.VISIBLE
        for (id in intArrayOf(R.id.perm_battery_text, R.id.perm_battery)) findViewById<View>(id).visibility = if (b) View.GONE else View.VISIBLE
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
        sw(R.id.reminder_sound, "reminder_sound", Store.reminderSound(this))

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
        val hasAddress = Store.hasAddress(this)
        findViewById<View>(R.id.addr_scroll).visibility = if (hasAddress) View.VISIBLE else View.GONE
        if (hasAddress) renderChips()
        bindPermissionCard(hasAddress)
        for (id in intArrayOf(R.id.today_card, R.id.tomorrow_card, R.id.stats_card, R.id.share, R.id.address)) {
            findViewById<View>(id).visibility = if (hasAddress) View.VISIBLE else View.GONE
        }
        for (id in intArrayOf(R.id.hero_card, R.id.settings_toggle, R.id.legend)) {
            findViewById<View>(id).visibility = if (hasAddress) View.VISIBLE else View.GONE
        }
        if (!hasAddress) {
            // Новий користувач: лише поля вулиці й будинку
            settings.visibility = View.VISIBLE
            info.text = "Неофіційний застосунок. Дані з сайтів ДТЕК."
            return
        }

        val snap = Store.load(this)
        val now = Instant.now()
        val st = StatusCalc.of(snap, now)
        val sched = snap?.let { Schedule.from(it) } ?: Schedule(emptyMap())

        val active = Store.active(this)
        address.text = buildString {
            append(active?.full.orEmpty())
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
            append("Неофіційний застосунок. Дані з сайту ${active?.region?.company ?: "ДТЕК"}.")
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
