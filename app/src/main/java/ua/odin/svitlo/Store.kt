package ua.odin.svitlo

import android.content.Context
import org.json.JSONObject

object Store {
    private fun p(ctx: Context) = ctx.getSharedPreferences("svitlo", Context.MODE_PRIVATE)

    fun street(ctx: Context): String = p(ctx).getString("street", null) ?: "вул. Хрещатик"
    fun house(ctx: Context): String = p(ctx).getString("house", null) ?: "1"
    fun group(ctx: Context): String = p(ctx).getString("group", null) ?: "GPV1.1"
    fun error(ctx: Context): String? = p(ctx).getString("error", null)

    fun saveAddress(ctx: Context, street: String, house: String) =
        p(ctx).edit().putString("street", street).putString("house", house).apply()

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
