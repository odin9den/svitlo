#!/usr/bin/env bash
# Сценарій перевірки «Світла» на емуляторі. Скріншоти й журнали — у папку shots/.
set -x
P=ua.odin.svitlo
mkdir -p shots
shot() { adb exec-out screencap -p > "shots/$1.png"; }
# Натиснути на елемент із текстом (через дамп інтерфейсу)
tap_text() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null
  b=$(adb shell cat /sdcard/ui.xml | tr '>' '\n' | grep -F "text=\"$1" | head -1 | sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/')
  [ -n "$b" ] && set -- $b && adb shell input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 ))
}
# Натиснути на поле за його id
tap_id() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null
  b=$(adb shell cat /sdcard/ui.xml | tr '>' '\n' | grep -F "resource-id=\"$P:id/$1\"" | head -1 | sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/')
  [ -n "$b" ] && set -- $b && adb shell input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 ))
}

adb shell settings put system screen_off_timeout 1800000
adb install -r svitlo.apk
adb shell pm grant $P android.permission.POST_NOTIFICATIONS
adb logcat -c

# 0. Перший запуск без адреси: порожній стан і підказки вулиць
adb shell am start -W -n $P/.MainActivity
sleep 30
shot 00-empty
tap_id street; sleep 1
adb shell input text "1-"; sleep 3
shot 00b-street-dropdown
adb shell input keyevent KEYCODE_BACK; sleep 1

# 1. Задаємо адресу і чекаємо графік з сайту ДТЕК
adb shell am force-stop $P
adb shell "am start -W -n $P/.MainActivity --es street 'вул. Хрещатик' --es house 1"
sleep 45
shot 01-main

# 2. Прокрутка: сьогодні, завтра, статистика, налаштування
for i in 2 3 4; do
  adb shell input swipe 540 1900 540 700 400
  sleep 2
  shot 0$i-scroll
done
adb shell input swipe 540 1900 540 300 400; sleep 2; shot 05-bottom
tap_text "Адреса й налаштування"; sleep 2
adb shell input swipe 540 1900 540 600 400; sleep 2; shot 05b-settings
tap_id house; sleep 4
adb shell input keyevent KEYCODE_MOVE_END; for i in 1 2 3 4 5; do adb shell input keyevent KEYCODE_DEL; done
adb shell input text "2"; sleep 3; shot 05d-house-dropdown
adb shell input keyevent KEYCODE_BACK; sleep 1
adb shell input swipe 540 1900 540 600 400; sleep 2; shot 05c-reminders

adb shell input keyevent KEYCODE_BACK; sleep 1

# 2b. Друга адреса — Київська область (інший сайт ДТЕК)
adb shell am force-stop $P
adb shell "am start -W -n $P/.MainActivity --es region oblast --es city 'м. Бровари' --es street 'вул. Незалежності' --es house 34 --es name 'Робота'"
sleep 50
shot 08-oblast
adb shell input swipe 540 1700 540 900 400; sleep 2; shot 08b-oblast-scroll
adb shell input swipe 540 900 540 1900 300; sleep 1
# Перемикання на київську адресу чипом
tap_text "Хрещатик"; sleep 25; shot 09-switched-kyiv
# Форма нової адреси: регіон «Київська область», підказки населених пунктів
tap_text "+ Адреса"; sleep 2
tap_text "Київська область"; sleep 15
shot 10-add-form
tap_id city; sleep 1
sleep 3; shot 10b-city-dropdown
adb shell input keyevent KEYCODE_BACK; sleep 1
tap_text "Адреса й налаштування"; sleep 2
adb shell input swipe 540 600 540 1900 300; sleep 1; adb shell input swipe 540 600 540 1900 300; sleep 1
# Повертаємо активну адресу в області, щоб у шторці був підпис «Робота»
tap_text "Робота"; sleep 25

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
# лише збої нашого застосунку (системні сервіси емулятора інколи падають самі)
adb logcat -d -b crash > shots/crash-all.txt
grep -q "Process: $P" shots/crash-all.txt && cp shots/crash-all.txt shots/crash.txt || : > shots/crash.txt
adb logcat -d | grep -iE "svitlo|AndroidRuntime|WebView" | tail -300 > shots/logcat.txt
adb logcat -d -s Svitlo > shots/svitlo-log.txt; cp shots/svitlo-log.txt shots/promotion.txt
adb shell getprop ro.build.version.release_or_preview_display >> shots/promotion.txt
adb shell getprop ro.build.version.sdk_full >> shots/promotion.txt
exit 0
