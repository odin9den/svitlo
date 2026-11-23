package ua.odin.svitlo

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/**
 * Запасний шлях: сайт ДТЕК стоїть за Imperva, яка може віддати застосунку
 * JS-перевірку замість сторінки. Невидимий WebView проходить її як звичайний
 * браузер, після чого ми забираємо скрипт з графіком і робимо запит черги.
 */
object WebFetcher {
    class Bridge {
        @Volatile var result: CompletableDeferred<Pair<String, String?>>? = null

        @JavascriptInterface
        fun done(script: String, ajax: String) {
            result?.complete(script to ajax.ifBlank { null })
        }
    }

    /**
     * «Теплий» WebView: сторінка ДТЕК лишається відкритою кілька хвилин, і наступні запити
     * (будинки, інша адреса) робляться в ній за секунду, без нового 30-секундного завантаження.
     */
    private const val WARM_MS = 3 * 60_000L
    private val handler = Handler(Looper.getMainLooper())
    private var warm: WebView? = null
    private var warmBridge: Bridge? = null
    private var warmRegion: Region? = null
    private var warmAt = 0L
    private val cool = Runnable { dropWarm() }

    private fun dropWarm() {
        warm?.run { stopLoading(); destroy() }
        warm = null
        warmBridge = null
        warmRegion = null
    }

    /**
     * @param reuse можна взяти вже відкриту сторінку (для будинків і довідників).
     *              Для графіка — завжди свіже завантаження.
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun fetch(
        ctx: Context, region: Region, query: List<Pair<String, String>>, reuse: Boolean = false,
    ): Pair<String, String?> = withContext(Dispatchers.Main) {
        val result = CompletableDeferred<Pair<String, String?>>()
        val reusing = reuse && warm != null && warmRegion == region &&
            System.currentTimeMillis() - warmAt < WARM_MS
        if (!reusing) dropWarm()
        handler.removeCallbacks(cool)
        val wv = if (reusing) warm!! else WebView(ctx.applicationContext)
        val bridge = if (reusing) warmBridge!! else Bridge()
        bridge.result = result
        var lastInfo = ""

        // Порожній запит — потрібна лише сторінка (довідник вулиць)
        val fields = query.mapIndexed { n, (k, v) ->
            "b.append('data[$n][name]', ${JSONObject.quote(k)}); b.append('data[$n][value]', ${JSONObject.quote(v)});"
        }.joinToString("\n")
        val skipAjax = if (query.isEmpty()) "SvitloBridge.done(s, ''); return 'OK';" else ""
        val probe = """
            (function(){
              var s = Array.prototype.map.call(document.scripts, function(x){ return x.text || ''; })
                .filter(function(t){ return t.indexOf('DisconSchedule.') >= 0; }).join('\n');
              if (s.indexOf('DisconSchedule.fact = ') < 0) return 'WAIT|' + document.title;
              $skipAjax
              var m = document.querySelector('meta[name="csrf-token"]');
              var tok = m ? m.content : '';
              var b = new URLSearchParams();
              b.append('method', 'getHomeNum');
              $fields
              fetch('/ua/ajax', {
                method: 'POST', credentials: 'same-origin',
                headers: {
                  'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8',
                  'X-Requested-With': 'XMLHttpRequest',
                  'X-CSRF-Token': tok
                },
                body: b.toString()
              }).then(function(r){ return r.text(); })
                .then(function(t){ SvitloBridge.done(s, t); },
                      function(){ SvitloBridge.done(s, ''); });
              return 'OK';
            })()
        """.trimIndent()

        lateinit var check: Runnable
        check = Runnable {
            if (result.isCompleted) return@Runnable
            wv.evaluateJavascript(probe) { raw ->
                val v = raw?.trim('"').orEmpty()
                if (v != "OK") {
                    lastInfo = v.removePrefix("WAIT|")
                    handler.postDelayed(check, 1500)
                }
            }
        }

        if (reusing) {
            handler.post(check)
        } else {
            CookieManager.getInstance().setAcceptCookie(true)
            wv.settings.javaScriptEnabled = true
            wv.settings.domStorageEnabled = true
            wv.settings.blockNetworkImage = true
            wv.addJavascriptInterface(bridge, "SvitloBridge")
            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    handler.removeCallbacks(check)
                    handler.postDelayed(check, 500)
                }
            }
            wv.loadUrl("${region.base}/ua/shutdowns")
        }

        var ok = false
        try {
            withTimeout(if (reusing) 20_000 else 45_000) { result.await() }.also { ok = true }
        } catch (e: TimeoutCancellationException) {
            error("ДТЕК не віддав графік (${lastInfo.ifBlank { "немає відповіді" }})")
        } finally {
            handler.removeCallbacks(check)
            bridge.result = null
            if (ok) {
                // Тримаємо сторінку відкритою ще кілька хвилин
                if (!reusing) {
                    warm = wv; warmBridge = bridge; warmRegion = region; warmAt = System.currentTimeMillis()
                }
                handler.postDelayed(cool, WARM_MS)
            } else {
                if (reusing) dropWarm() else { wv.stopLoading(); wv.destroy() }
            }
        }
    }
}
