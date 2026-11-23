package ua.odin.svitlo

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Бере дані з сайтів ДТЕК (Київ — dtek-kem, область — dtek-krem), сторінка /ua/shutdowns:
 *  - погодинний графік усіх черг лежить у HTML як `DisconSchedule.fact = {...}`;
 *  - список вулиць — `DisconSchedule.streets` (в області — згрупований за населеними пунктами);
 *  - черга й поточне відключення для адреси — POST /ua/ajax (method=getHomeNum).
 */
object DtekClient {
    private const val UA =
        "Mozilla/5.0 (Linux; Android 15; SM-S921B) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/130.0.0.0 Mobile Safari/537.36"

    private val cookies = CookieManager(null, CookiePolicy.ACCEPT_ALL)

    private const val MARKER = "DisconSchedule.fact = "

    /** Запити до ДТЕК — суворо по одному: паралельні ламають сесію й CSRF-токен. */
    private val lock = Mutex()

    /** Сторінка ДТЕК і відповідь getHomeNum (спершу прямий запит, потім WebView). */
    /** Остання відповідь: графік і будинки беруться з тієї самої сторінки, двічі її не качаємо. */
    private var memo: Triple<String, Long, Pair<String, String?>>? = null
    private const val MEMO_MS = 2 * 60_000L

    suspend fun load(ctx: Context, region: Region, city: String, street: String): Pair<String, String?> =
        lock.withLock {
            val key = "${region.key}|$city|$street"
            val now = System.currentTimeMillis()
            memo?.let { (k, t, v) -> if (k == key && now - t < MEMO_MS) return@withLock v }
            // Для довідника вулиць підійде й сторінка, завантажена разом з адресою
            if (street.isBlank()) memo?.let { (k, t, v) ->
                if (k.startsWith("${region.key}|") && now - t < MEMO_MS) return@withLock v.first to null
            }
            loadNow(ctx, region, city, street).also { memo = Triple(key, now, it) }
        }

    /** Примусове оновлення (жест «потягнути вниз») — без кешу. */
    fun forget() { memo = null }

    private suspend fun loadNow(ctx: Context, region: Region, city: String, street: String): Pair<String, String?> {
        val query = if (street.isBlank()) emptyList() else region.homeQuery(city, street)
        val direct = runCatching { httpFetch(region, query) }
            .onFailure { log("http failed: ${it.message}") }.getOrNull()
        val pageOk = direct != null && direct.first.contains(MARKER)
        val ajaxOk = query.isEmpty() || hasData(direct?.second)
        log("http ${region.key}: page=$pageOk ajax=$ajaxOk street='$street' ajaxLen=${direct?.second?.length}")
        if (pageOk && ajaxOk) return direct!!
        val web = WebFetcher.fetch(ctx, region, query)
        log("webview: ajax=${hasData(web.second)} ajaxLen=${web.second?.length}")
        return web
    }

    private fun hasData(ajax: String?) =
        ajax != null && runCatching { JSONObject(ajax).optJSONObject("data")?.length() ?: 0 }.getOrDefault(0) > 0

    private fun log(msg: String) = android.util.Log.i("Svitlo", msg)

    suspend fun fetch(ctx: Context, a: Address, fallbackGroup: String): Snapshot {
        val (page, ajax) = load(ctx, a.region, a.city, a.street)
        runCatching { saveStreets(ctx, a.region, page) }
        return build(page, ajax, a.house, fallbackGroup)
    }

    /** Лише оновити довідник вулиць регіону. */
    suspend fun refreshStreets(ctx: Context, region: Region) {
        val (page, _) = load(ctx, region, "", "")
        saveStreets(ctx, region, page)
    }

    /** Будинки вулиці у тому вигляді, як їх показує сайт ДТЕК («4/к.15»). */
    suspend fun houses(ctx: Context, region: Region, city: String, street: String): List<String> {
        val (_, ajax) = load(ctx, region, city, street)
        val data = ajax?.let { runCatching { JSONObject(it).optJSONObject("data") }.getOrNull() }
            ?: error("ДТЕК не віддав список будинків")
        return data.keys().asSequence().toList().sortedWith(houseOrder)
    }

    private val houseOrder = Comparator<String> { a, b ->
        val na = a.takeWhile { it.isDigit() }.toIntOrNull() ?: Int.MAX_VALUE
        val nb = b.takeWhile { it.isDigit() }.toIntOrNull() ?: Int.MAX_VALUE
        if (na != nb) na.compareTo(nb) else a.compareTo(b)
    }

    /** Вирізає `DisconSchedule.streets = ...` зі сторінки й кладе в Store як є (масив або об'єкт). */
    private fun saveStreets(ctx: Context, region: Region, page: String) {
        val m = "DisconSchedule.streets = "
        val i = page.indexOf(m)
        if (i < 0) return
        val v = JSONTokener(page.substring(i + m.length)).nextValue()
        when {
            region == Region.KYIV && v is JSONArray -> Store.saveStreets(ctx, region, v.toString())
            region != Region.KYIV && v is JSONObject -> Store.saveStreets(ctx, region, v.toString())
        }
    }

    private fun httpFetch(region: Region, query: List<Pair<String, String>>): Pair<String, String?> {
        CookieHandler.setDefault(cookies)
        val base = region.base
        val html = get("$base/ua/shutdowns")
        if (!html.contains(MARKER) || query.isEmpty()) return html to null
        val form = listOf("method" to "getHomeNum") + query.flatMapIndexed { n, (k, v) ->
            listOf("data[$n][name]" to k, "data[$n][value]" to v)
        }
        val ajax = runCatching { post(base, form, csrfToken(html)) }.getOrNull()
        return html to ajax
    }

    private fun build(page: String, ajax: String?, house: String, fallbackGroup: String): Snapshot {
        val fact = extractFact(page)

        var group = fallbackGroup
        var outage: Outage? = null
        if (ajax != null) {
            runCatching {
                val h = JSONObject(ajax).optJSONObject("data")?.optJSONObject(house)
                if (h != null) {
                    val reason = h.optJSONArray("sub_type_reason")?.optString(0).orEmpty()
                    if (reason.startsWith("GPV")) group = reason
                    outage = Outage(h.str("sub_type"), h.str("start_date"), h.str("end_date"))
                }
            }
        }

        val data = fact.getJSONObject("data")
        val days = mutableMapOf<Long, String>()
        for (key in data.keys()) {
            val g = data.getJSONObject(key).optJSONObject(group) ?: continue
            days[key.toLong()] = (1..24).joinToString(",") { g.optString(it.toString(), "?") }
        }
        if (group.isBlank()) error("Будинок не знайдено в базі ДТЕК — оберіть адресу зі списку")
        if (days.isEmpty()) error("Черги $group немає в графіку ДТЕК")

        return Snapshot(group, days, fact.str("update"), System.currentTimeMillis(), outage)
    }

    private fun extractFact(html: String): JSONObject {
        val i = html.indexOf(MARKER)
        require(i >= 0) { "На сторінці ДТЕК не знайдено графік" }
        // JSONTokener читає рівно один JSON-об'єкт і ігнорує решту (</script> тощо).
        return JSONTokener(html.substring(i + MARKER.length)).nextValue() as JSONObject
    }

    private fun csrfToken(html: String): String? =
        Regex("""<meta[^>]+name="csrf-token"[^>]+content="([^"]+)"""").find(html)?.groupValues?.get(1)
            ?: Regex("""<meta[^>]+content="([^"]+)"[^>]+name="csrf-token"""").find(html)?.groupValues?.get(1)

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept-Language", "uk-UA,uk;q=0.9")
        }

    private fun HttpURLConnection.readBody(): String {
        val code = responseCode
        if (code !in 200..299) error("ДТЕК відповів $code")
        return inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private fun get(url: String): String = open(url).run {
        setRequestProperty("Accept", "text/html")
        try { readBody() } finally { disconnect() }
    }

    private fun post(base: String, form: List<Pair<String, String>>, csrf: String?): String = open("$base/ua/ajax").run {
        requestMethod = "POST"
        doOutput = true
        setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
        setRequestProperty("X-Requested-With", "XMLHttpRequest")
        if (csrf != null) setRequestProperty("X-CSRF-Token", csrf)
        setRequestProperty("Origin", base)
        setRequestProperty("Referer", "$base/ua/shutdowns")
        val body = form.joinToString("&") { (k, v) ->
            URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8")
        }
        try {
            outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            readBody()
        } finally {
            disconnect()
        }
    }
}

/** optString повертає "null" для JSON null — тут повертаємо порожній рядок. */
fun JSONObject.str(key: String): String = if (isNull(key)) "" else optString(key, "")
