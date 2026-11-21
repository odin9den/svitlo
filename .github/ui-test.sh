#!/usr/bin/env bash
# Сценарій перевірки «Світла» на емуляторі. Скріншоти й журнали — у папку shots/.
set -x
P=ua.odin.svitlo
mkdir -p shots
shot() { adb exec-out screencap -p > "shots/$1.png"; }

adb shell settings put system screen_off_timeout 1800000
adb install -r svitlo.apk
adb shell pm grant $P android.permission.POST_NOTIFICATIONS
adb logcat -c

# 1. Перший запуск: застосунок сам завантажує графік з сайту ДТЕК
adb shell am start -W -n $P/.MainActivity
sleep 45
shot 01-main

# 2. Прокрутка: сьогодні, завтра, статистика, налаштування
for i in 2 3 4; do
  adb shell input swipe 540 1900 540 700 400
  sleep 2
  shot 0$i-scroll
done
adb shell input swipe 540 1900 540 300 400; sleep 2; shot 05-bottom

# 3. Шторка сповіщень із постійним сповіщенням
adb shell cmd statusbar expand-notifications; sleep 3; shot 06-shade
adb shell cmd statusbar collapse; sleep 1

# 4. Екран блокування
adb shell input keyevent KEYCODE_SLEEP; sleep 3
adb shell input keyevent KEYCODE_WAKEUP; sleep 4
shot 07-lockscreen
adb shell input keyevent KEYCODE_MENU; sleep 1

# 5. Змахнути всі сповіщення — постійне має повернутись само
adb shell cmd notification cancel_all 2>/dev/null || true
adb shell service call notification 1 >/dev/null 2>&1 || true
sleep 5
adb shell dumpsys notification --noredact | grep -A3 "pkg=$P" > shots/notifications.txt

# 6. Журнали
adb logcat -d -b crash > shots/crash.txt
adb logcat -d | grep -iE "svitlo|AndroidRuntime|WebView" | tail -300 > shots/logcat.txt
exit 0
