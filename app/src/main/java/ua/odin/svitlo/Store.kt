package ua.odin.svitlo

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object Store {
    private fun p(ctx: Context) = ctx.getSharedPreferences("svitlo", Context.MODE_PRIVATE)

    // ---------- Адреси ----------
    // Список адрес у "addresses", активна — "active". Графік, черга, історія й помилка
    // під звичними ключами завжди належать активній адресі; інші адреси тримають свої
    // копії під ключами "<ключ>@<id>", тож перемикання миттєве і без зайвих запитів.

    private val CACHE = listOf("snapshot", "group", "history", "error")

    fun addresses(ctx: Context): List<Address> {
        val p = p(ctx)
        val raw = p.getString("addresses", null)
        if (raw == null) {
            // Перехід зі старої версії з однією адресою
            val st = p.getString("street", null).orEmpty()
            val h = p.getString("house", null).orEmpty()
            val list = if (st.isNotBlank() && h.isNotBlank()) listOf(Address("a1", Region.KYIV, "", st, h)) else emptyList()
            p.edit().putString("addresses", JSONArray(list.map { it.toJson() }).toString())
                .apply { if (list.isNotEmpty()) putString("active", "a1") }
                .remove("street").remove("house").commit()
            return list
        }
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).map { Address.from(a.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    private fun saveList(e: android.content.SharedPreferences.Editor, list: List<Address>) =
        e.putString("addresses", JSONArray(list.map { it.toJson() }).toString())

    fun active(ctx: Context): Address? {
        val list = addresses(ctx)
        val id = p(ctx).getString("active", null)
        return list.firstOrNull { it.id == id } ?: list.firstOrNull()
    }

    fun street(ctx: Context): String = active(ctx)?.street.orEmpty()
    fun house(ctx: Context): String = active(ctx)?.house.orEmpty()
    fun group(ctx: Context): String = p(ctx).getString("group", null).orEmpty()
    fun hasAddress(ctx: Context) = active(ctx) != null
    fun error(ctx: Context): String? = p(ctx).getString("error", null)

    /** Перемкнути активну адресу: зберегти кеш поточної й підставити кеш нової. */
    fun setActive(ctx: Context, id: String) {
        val p = p(ctx)
        val cur = active(ctx)
        if (cur?.id == id) return
        val e = p.edit()
        if (cur != null) for (k in CACHE) {
            val v = p.getString(k, null)
            if (v != null) e.putString("$k@${cur.id}", v) else e.remove("$k@${cur.id}")
        }
        for (k in CACHE) {
            val v = p.getString("$k@$id", null)
            if (v != null) e.putString(k, v) else e.remove(k)
            e.remove("$k@$id")
        }
        e.putString("active", id).commit()
    }

    /** Нова адреса стає активною. Якщо така вже є — просто перемикаємось на неї. */
    fun addAddress(ctx: Context, a: Address): Address {
        val list = addresses(ctx)
        list.firstOrNull { it.sameAs(a) }?.let { old ->
            if (a.name.isNotBlank() && a.name != old.name) {
                saveList(p(ctx).edit(), list.map { if (it.id == old.id) it.copy(name = a.name) else it }).commit()
            }
            setActive(ctx, old.id)
            return old
        }
        val added = a.copy(id = "a" + System.currentTimeMillis())
        saveList(p(ctx).edit(), list + added).commit()
        setActive(ctx, added.id)
        return added
    }

    /** Змінити активну адресу. Інший будинок — інший графік, тож кеш скидаємо. */
    fun updateActive(ctx: Context, a: Address) {
        val cur = active(ctx) ?: run { addAddress(ctx, a); return }
        val upd = a.copy(id = cur.id)
        if (upd == cur) return
        val e = p(ctx).edit()
        if (!upd.sameAs(cur)) CACHE.forEach { e.remove(it) }
        saveList(e, addresses(ctx).map { if (it.id == cur.id) upd else it }).commit()
    }

    fun removeAddress(ctx: Context, id: String) {
        val list = addresses(ctx).filter { it.id != id }
        val wasActive = active(ctx)?.id == id
        val e = p(ctx).edit()
        CACHE.forEach { e.remove("$it@$id") }
        if (wasActive) {
            CACHE.forEach { e.remove(it) }
            e.remove("active")
        }
        saveList(e, list).commit()
        if (wasActive) list.firstOrNull()?.let { setActive(ctx, it.id) }
    }

    // ---------- Довідники ДТЕК ----------
    // Київ: масив вулиць. Область: {"м. Бровари": [вулиці], ...}. Лежать у файлах — це тисячі назв.

    private fun streetsFile(ctx: Context, r: Region) =
        java.io.File(ctx.filesDir, if (r == Region.KYIV) "streets.json" else "streets-${r.key}.json")

    @Volatile private var oblastCache: JSONObject? = null

    fun saveStreets(ctx: Context, r: Region, json: String) {
        if (json.length < 2000) return
        streetsFile(ctx, r).writeText(json)
        if (r != Region.KYIV) oblastCache = null
    }

    /** Довідник: свіжий із сайту, а якщо його ще немає — вкладений у застосунок під час збірки. */
    private fun streetsText(ctx: Context, r: Region): String? {
        val f = streetsFile(ctx, r)
        if (f.exists()) return runCatching { f.readText() }.getOrNull()
        return runCatching { ctx.assets.open(f.name).bufferedReader().use { it.readText() } }.getOrNull()
    }

    private fun oblast(ctx: Context): JSONObject? = oblastCache ?: runCatching {
        JSONObject(streetsText(ctx, Region.OBLAST)!!)
    }.getOrNull()?.also { oblastCache = it }

    private val ukCollator = java.text.Collator.getInstance(java.util.Locale.forLanguageTag("uk"))

    @Volatile private var housesCache: JSONObject? = null

    /** Будинки вулиці Києва з вкладеного довідника (null — довідника або вулиці немає). */
    fun bundledHouses(ctx: Context, street: String): List<String>? {
        val all = housesCache ?: runCatching {
            JSONObject(ctx.assets.open("houses-kyiv.json").bufferedReader().use { it.readText() })
        }.getOrNull()?.also { housesCache = it } ?: return null
        val a = all.optJSONArray(street) ?: return null
        return (0 until a.length()).map { a.getString(it) }.takeIf { it.isNotEmpty() }
    }

    /** «м. Бровари», «с. Гатне», «Броварська ТГ» — сортуємо за самою назвою, за українською абеткою. */
    fun cities(ctx: Context): List<String> {
        val prefix = Regex("^(м|с|смт|сел|с-ще)\\.\\s*")
        return oblast(ctx)?.keys()?.asSequence()?.filter { it.isNotBlank() }?.toList().orEmpty()
            .sortedWith(compareBy(ukCollator) { it.replace(prefix, "") })
    }

    fun streets(ctx: Context, r: Region, city: String = ""): List<String> = runCatching {
        val a = if (r == Region.KYIV) JSONArray(streetsText(ctx, r)!!)
        else oblast(ctx)?.optJSONArray(city) ?: JSONArray()
        (0 until a.length()).map { a.getString(it) }.filter { it.isNotBlank() }
    }.getOrDefault(emptyList())

    // Нагадування
    fun remindOff(ctx: Context) = p(ctx).getBoolean("remind_off", true)
    fun remindOn(ctx: Context) = p(ctx).getBoolean("remind_on", false)
    fun leadMinutes(ctx: Context) = p(ctx).getInt("lead_min", 30)
    fun notifyChanges(ctx: Context) = p(ctx).getBoolean("notify_changes", true)
    fun quietNight(ctx: Context) = p(ctx).getBoolean("quiet_night", true)
    fun reminderSound(ctx: Context) = p(ctx).getBoolean("reminder_sound", true)
    fun setBool(ctx: Context, key: String, v: Boolean) = p(ctx).edit().putBoolean(key, v).apply()
    fun setLead(ctx: Context, min: Int) = p(ctx).edit().putInt("lead_min", min).apply()

    /** Сайт ДТЕК блокує прямі запити з цього телефона — кілька годин одразу йдемо через WebView. */
    fun directBlocked(ctx: Context) = p(ctx).getLong("direct_blocked_until", 0) > System.currentTimeMillis()
    fun setDirectBlocked(ctx: Context, blocked: Boolean) = p(ctx).edit()
        .putLong("direct_blocked_until", if (blocked) System.currentTimeMillis() + 6 * 3600_000L else 0).apply()

    fun setError(ctx: Context, msg: String?) = p(ctx).edit().putString("error", msg).apply()

    fun save(ctx: Context, s: Snapshot) {
        val days = JSONObject()
        s.days.forEach { (k, v) -> days.put(k.toString(), v) }
        val json = JSONObject()
            .put("group", s.group)
            .put("days", days)
            .put("update", s.update)
            .put("fetchedAt", s.fetchedAt)
        s.outage?.let {
            json.put("outage", JSONObject().put("sub", it.subType).put("start", it.start).put("end", it.end))
        }
        p(ctx).edit().putString("snapshot", json.toString()).putString("group", s.group).apply()
    }

    /** Історія денних підсумків: останній відомий графік для кожної доби, до 60 днів. */
    fun saveHistory(ctx: Context, totals: Map<java.time.LocalDate, DayTotal>) {
        val all = history(ctx).toMutableMap()
        all.putAll(totals)
        val keep = all.keys.sortedDescending().take(60).toSet()
        val j = JSONObject()
        all.filterKeys { it in keep }.forEach { (d, t) ->
            j.put(d.toString(), JSONObject().put("on", t.on).put("off", t.off))
        }
        p(ctx).edit().putString("history", j.toString()).apply()
    }

    fun history(ctx: Context): Map<java.time.LocalDate, DayTotal> {
        val raw = p(ctx).getString("history", null) ?: return emptyMap()
        return runCatching {
            val j = JSONObject(raw)
            j.keys().asSequence().associate { k ->
                val o = j.getJSONObject(k)
                java.time.LocalDate.parse(k) to DayTotal(o.optLong("on"), o.optLong("off"))
            }
        }.getOrDefault(emptyMap())
    }

    fun load(ctx: Context): Snapshot? {
        val raw = p(ctx).getString("snapshot", null) ?: return null
        return runCatching {
            val j = JSONObject(raw)
            val d = j.getJSONObject("days")
            val days = d.keys().asSequence().associate { it.toLong() to d.getString(it) }
            val o = j.optJSONObject("outage")?.let { Outage(it.str("sub"), it.str("start"), it.str("end")) }
            Snapshot(j.getString("group"), days, j.str("update"), j.optLong("fetchedAt"), o)
        }.getOrNull()
    }
}
