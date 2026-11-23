package ua.odin.svitlo

import org.json.JSONObject

/** Регіон = окремий сайт ДТЕК. Сторінки влаштовані однаково, різниться лише пошук адреси. */
enum class Region(val key: String, val base: String, val title: String, val company: String) {
    KYIV("kyiv", "https://www.dtek-kem.com.ua", "Київ", "ДТЕК Київські електромережі"),
    OBLAST("oblast", "https://www.dtek-krem.com.ua", "Київська область", "ДТЕК Київські регіональні електромережі"),
    DNIPRO("dnipro", "https://www.dtek-dnem.com.ua", "Дніпропетровська область", "ДТЕК Дніпровські електромережі"),
    ODESA("odesa", "https://www.dtek-oem.com.ua", "Одеська область", "ДТЕК Одеські електромережі"),
    DONETSK("donetsk", "https://www.dtek-dem.com.ua", "Донецька область", "ДТЕК Донецькі електромережі");

    /** Усі регіони, крім Києва, шукають адресу через населений пункт. */
    val hasCities: Boolean get() = this != KYIV

    /** Поля запиту getHomeNum: у Києві лише вулиця, в області — населений пункт і вулиця. */
    fun homeQuery(city: String, street: String): List<Pair<String, String>> =
        if (!hasCities) listOf("street" to street) else listOf("city" to city, "street" to street)

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: KYIV
        fun byTitle(t: String) = entries.firstOrNull { it.title == t }
    }
}

data class Address(
    val id: String,
    val region: Region,
    val city: String,
    val street: String,
    val house: String,
    val name: String = "",
) {
    /** «вул. Хрещатик» → «Хрещатик» — для коротких підписів. */
    private val shortStreet: String
        get() = street.replace(Regex("^(вул|просп|пров|бульв|пл|наб|узвіз|шосе|тупик|проїзд)\\.?\\s+"), "")

    val label: String get() = name.ifBlank { "$shortStreet, $house" }

    val full: String
        get() = buildString {
            if (region.hasCities && city.isNotBlank()) append(city).append(", ")
            append(street).append(", ").append(house)
        }

    fun sameAs(o: Address) = region == o.region && city == o.city && street == o.street && house == o.house

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("region", region.key).put("city", city)
        .put("street", street).put("house", house).put("name", name)

    companion object {
        fun from(j: JSONObject) = Address(
            j.getString("id"), Region.of(j.optString("region")), j.str("city"),
            j.str("street"), j.str("house"), j.str("name"),
        )
    }
}
