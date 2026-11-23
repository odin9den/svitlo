"""Перед збіркою кладе в assets свіжі довідники вулиць ДТЕК (Київ і область),
щоб застосунок показував підказки адрес одразу, без завантаження з сайту."""
import json, sys, urllib.request

UA = "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36"
SITES = {"streets.json": ("https://www.dtek-kem.com.ua/ua/shutdowns", list),
         "streets-oblast.json": ("https://www.dtek-krem.com.ua/ua/shutdowns", dict)}
out = sys.argv[1]
for name, (url, kind) in SITES.items():
    try:
        html = urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": UA}), timeout=60).read().decode("utf-8", "ignore")
        i = html.index("DisconSchedule.streets")
        v, _ = json.JSONDecoder().raw_decode(html[html.index("=", i) + 1:].lstrip())
        assert isinstance(v, kind) and len(v) > 100, "unexpected"
        with open(f"{out}/{name}", "w", encoding="utf-8") as f:
            json.dump(v, f, ensure_ascii=False, separators=(",", ":"))
        print(name, len(v))
    except Exception as e:  # збірка не повинна падати через сайт ДТЕК
        print("skip", name, e)
