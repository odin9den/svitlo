package ua.odin.svitlo

import android.content.Context
import org.json.JSONObject
import org.json.JSONTokener
import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Бере дані з https://www.dtek-kem.com.ua/ua/shutdowns:
 *  - погодинний графік усіх черг лежить у HTML як `DisconSchedule.fact = {...}`;
 *  - черга й поточне відключення для адреси — POST /ua/ajax (method=getHomeNum).
 */
object DtekClient {
    private const val BASE = "https://www.dtek-kem.com.ua"
    private const val UA =
        "Mozilla/5.0 (Linux; Android 15; SM-S921B) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/130.0.0.0 Mobile Safari/537.36"

    private val cookies = CookieManager(null, CookiePolicy.ACCEPT_ALL)

    private const val MARKER = "DisconSchedule.fact = "

    /** Спершу прямий запит; якщо Imperva не пустила — невидимий WebView. */
    suspend fun fetch(ctx: Context, street: String, house: String, fallbackGroup: String): Snapshot {
        val direct = runCatching { httpFetch(street) }.getOrNull()
        val (page, ajax) = if (direct != null && direct.first.contains(MARKER)) {
            direct
        } else {
            WebFetcher.fetch(ctx, street)
        }
        return build(page, ajax, house, fallbackGroup)
    }

    private fun httpFetch(street: String): Pair<String, String?> {
        CookieHandler.setDefault(cookies)
        val html = get("$BASE/ua/shutdowns")
        if (!html.contains(MARKER)) return html to null
        val ajax = runCatching {
            post(
                "$BASE/ua/ajax",
                listOf(
                    "method" to "getHomeNum",
                    "data[0][name]" to "street",
                    "data[0][value]" to street,
                ),
                csrfToken(html),
            )
        }.getOrNull()
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

    private fun post(url: String, form: List<Pair<String, String>>, csrf: String?): String = open(url).run {
        requestMethod = "POST"
        doOutput = true
        setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
        setRequestProperty("X-Requested-With", "XMLHttpRequest")
        if (csrf != null) setRequestProperty("X-CSRF-Token", csrf)
        setRequestProperty("Origin", BASE)
        setRequestProperty("Referer", "$BASE/ua/shutdowns")
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
