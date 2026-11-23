"""Перед збіркою кладе в assets свіжі довідники вулиць ДТЕК (Київ і область),
щоб застосунок показував підказки адрес одразу, без завантаження з сайту."""
import json, os, shutil, sys, time, urllib.request

UA = "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36"
SITES = {"streets.json": ("https://www.dtek-kem.com.ua/ua/shutdowns", list),
         "streets-oblast.json": ("https://www.dtek-krem.com.ua/ua/shutdowns", dict),
         "streets-dnipro.json": ("https://www.dtek-dnem.com.ua/ua/shutdowns", dict),
         "streets-odesa.json": ("https://www.dtek-oem.com.ua/ua/shutdowns", dict),
         "streets-donetsk.json": ("https://www.dtek-dem.com.ua/ua/shutdowns", dict)}
out = sys.argv[1]
backup = sys.argv[2] if len(sys.argv) > 2 else None  # копія з гілки data (оновлюється щотижня)


def grab(url, kind):
    html = urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": UA}), timeout=60).read().decode("utf-8", "ignore")
    i = html.index("DisconSchedule.streets")
    v, _ = json.JSONDecoder().raw_decode(html[html.index("=", i) + 1:].lstrip())
    assert isinstance(v, kind) and len(v) > 100, "unexpected"
    return v


missing = []
for name, (url, kind) in SITES.items():
    for attempt in range(5):
        try:
            v = grab(url, kind)
            with open(f"{out}/{name}", "w", encoding="utf-8") as f:
                json.dump(v, f, ensure_ascii=False, separators=(",", ":"))
            print(name, len(v))
            break
        except Exception as e:  # Imperva іноді віддає перевірку замість сторінки — пробуємо ще
            print("retry", name, attempt + 1, e)
            time.sleep(10 * (attempt + 1))
    else:
        if backup and os.path.exists(f"{backup}/{name}"):
            shutil.copy(f"{backup}/{name}", f"{out}/{name}")
            print(name, "from backup")
        else:
            missing.append(name)
if missing:
    sys.exit(f"no street lists for {missing}")
