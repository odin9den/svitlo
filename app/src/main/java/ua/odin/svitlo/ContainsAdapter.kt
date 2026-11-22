package ua.odin.svitlo

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.Filter

/**
 * Підказки, що шукають збіг будь-де в назві: «15» знаходить «4/к.15»,
 * «регенер» — «вул. Регенераторна». Спершу ті, де збіг на початку слова.
 */
class ContainsAdapter(ctx: Context, private val all: List<String>) :
    ArrayAdapter<String>(ctx, R.layout.item_dropdown, ArrayList(all)) {

    private val lower = all.map { it.lowercase() }

    private val filter = object : Filter() {
        override fun performFiltering(q: CharSequence?): FilterResults {
            val s = q?.toString()?.trim()?.lowercase().orEmpty()
            val res = if (s.isEmpty()) {
                all
            } else {
                val first = ArrayList<String>()
                val rest = ArrayList<String>()
                for (i in all.indices) {
                    val l = lower[i]
                    if (!l.contains(s)) continue
                    val wordStart = l.startsWith(s) ||
                        l.split(' ', '/', '.', '-', ',', '(').any { it.startsWith(s) }
                    if (wordStart) first += all[i] else rest += all[i]
                }
                first + rest
            }
            return FilterResults().apply { values = res; count = res.size }
        }

        @Suppress("UNCHECKED_CAST")
        override fun publishResults(q: CharSequence?, r: FilterResults) {
            clear()
            addAll(r.values as List<String>)
            notifyDataSetChanged()
        }
    }

    override fun getFilter(): Filter = filter
}
