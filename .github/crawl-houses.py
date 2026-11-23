"""Раз на тиждень збирає номери будинків для всіх вулиць Києва з сайту ДТЕК
(той самий запит getHomeNum, що й на сайті). Повільно, по одному запиту,
щоб не навантажувати сайт. Результат: {"вул. Хрещатик": ["1", "2", ...], ...}."""
import http.cookiejar, json, re, sys, time, urllib.parse, urllib.request

BASE = "https://www.dtek-kem.com.ua"
UA = "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36"
out_path = sys.argv[1]


def session():
    jar = http.cookiejar.CookieJar()
    op = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
    html = op.open(urllib.request.Request(BASE + "/ua/shutdowns", headers={"User-Agent": UA}), timeout=60).read().decode("utf-8", "ignore")
    tok = re.search(r'name="csrf-token" content="([^"]+)"', html).group(1)
    i = html.index("DisconSchedule.streets")
    streets, _ = json.JSONDecoder().raw_decode(html[html.index("=", i) + 1:].lstrip())
    return op, tok, streets


def houses(op, tok, street):
    body = urllib.parse.urlencode({"method": "getHomeNum", "data[0][name]": "street", "data[0][value]": street}).encode()
    req = urllib.request.Request(BASE + "/ua/ajax", data=body, headers={
        "User-Agent": UA, "X-Requested-With": "XMLHttpRequest", "X-CSRF-Token": tok,
        "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8", "Referer": BASE + "/ua/shutdowns"})
    data = json.loads(op.open(req, timeout=30).read().decode()).get("data") or {}
    return list(data.keys())


op, tok, streets = session()
result, failed = {}, []
for n, st in enumerate(streets):
    for attempt in range(3):
        try:
            h = houses(op, tok, st)
            if h:
                result[st] = h
            break
        except Exception as e:
            print("retry", st, e)
            time.sleep(5 * (attempt + 1))
            try:
                op, tok, _ = session()
            except Exception:
                pass
    else:
        failed.append(st)
    if n % 200 == 0:
        print(n, len(streets), len(result))
    time.sleep(0.3)

print("streets", len(streets), "with houses", len(result), "failed", len(failed))
if len(result) < len(streets) * 0.8:
    sys.exit("too many gaps, keeping the previous file")
with open(out_path, "w", encoding="utf-8") as f:
    json.dump(result, f, ensure_ascii=False, separators=(",", ":"))
