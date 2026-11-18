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
    private const val PAGE = "https://www.dtek-kem.com.ua/ua/shutdowns"

    class Bridge(private val result: CompletableDeferred<Pair<String, String?>>) {
        @JavascriptInterface
        fun done(script: String, ajax: String) {
            result.complete(script to ajax.ifBlank { null })
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun fetch(ctx: Context, street: String): Pair<String, String?> = withContext(Dispatchers.Main) {
        val result = CompletableDeferred<Pair<String, String?>>()
        val handler = Handler(Looper.getMainLooper())
        val wv = WebView(ctx.applicationContext)
        var lastInfo = ""

        val streetJs = JSONObject.quote(street)
        val probe = """
            (function(){
              var s = Array.prototype.map.call(document.scripts, function(x){ return x.text || ''; })
                .filter(function(t){ return t.indexOf('DisconSchedule.fact = ') >= 0; })[0];
              if (!s) return 'WAIT|' + document.title;
              var m = document.querySelector('meta[name="csrf-token"]');
              var tok = m ? m.content : '';
              var b = new URLSearchParams();
              b.append('method', 'getHomeNum');
              b.append('data[0][name]', 'street');
              b.append('data[0][value]', $streetJs);
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

        CookieManager.getInstance().setAcceptCookie(true)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.blockNetworkImage = true
        wv.addJavascriptInterface(Bridge(result), "SvitloBridge")
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                handler.removeCallbacks(check)
                handler.postDelayed(check, 500)
            }
        }
        wv.loadUrl(PAGE)

        try {
            withTimeout(45_000) { result.await() }
        } catch (e: TimeoutCancellationException) {
            error("ДТЕК не віддав графік (${lastInfo.ifBlank { "немає відповіді" }})")
        } finally {
            handler.removeCallbacks(check)
            wv.stopLoading()
            wv.destroy()
        }
    }
}
