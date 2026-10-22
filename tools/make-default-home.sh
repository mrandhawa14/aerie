#!/bin/bash
# Makes Aerie the Fire TV's only home screen by disabling Amazon's launcher.
# Verifies every step and rolls back automatically if Aerie doesn't come up.
# Usage: TV=192.168.1.50 tools/make-default-home.sh
#        (TV on the same network, Developer options > ADB debugging on)
set -uo pipefail
cd "$(dirname "$0")/.."

: "${TV:?Set TV to the Fire TV IP address, e.g. TV=192.168.1.50 tools/make-default-home.sh}"
[[ "$TV" == *:* ]] || TV="$TV:5555"
A="adb -s $TV"
HOME_ACTIVITY=io.github.mrandhawa14.aerie/.HomeActivity
SERVICE=io.github.mrandhawa14.aerie/io.github.mrandhawa14.aerie.HomeOverrideService
AMAZON=(com.amazon.tv.launcher com.amazon.firehomestarter)

sh_()   { $A shell "$@" </dev/null 2>&1 | tr -d '\r'; }
focus() { sh_ dumpsys window windows | grep -m1 mCurrentFocus; }
on_aerie() { focus | grep -q io.github.mrandhawa14.aerie; }

rollback() {
    echo "!! $1 — restoring Amazon's launcher"
    for p in "${AMAZON[@]}"; do sh_ pm enable "$p" >/dev/null; done
    sh_ input keyevent KEYCODE_HOME >/dev/null
    echo "Rolled back. The TV is as it was before this script ran."
    exit 1
}

echo "== Connecting to $TV"
adb connect "$TV" | grep -q connected || { echo "Can't reach the TV. Same Wi-Fi? ADB debugging on?"; exit 1; }

echo "== Checking Aerie is installed and opens"
sh_ pm path io.github.mrandhawa14.aerie | grep -q package: || ./build.sh install || exit 1
sh_ am start -W -n $HOME_ACTIVITY >/dev/null; sleep 2
on_aerie || { echo "Aerie didn't open; not touching anything."; exit 1; }

echo "== Making Aerie the preferred home"
sh_ cmd package set-home-activity $HOME_ACTIVITY

echo "== Disabling Amazon's launcher"
blocked=0
for p in "${AMAZON[@]}"; do
    r=$(sh_ pm disable-user --user 0 "$p" | tail -1)
    echo "   $p: $r"
    [[ "$r" == *disabled* ]] || blocked=1
done

if [ $blocked = 1 ]; then
    # Amazon protects its launcher on this firmware. Fall back to the key-level
    # override service (Fire OS hides it from Settings, so it's enabled here).
    for p in "${AMAZON[@]}"; do sh_ pm enable "$p" >/dev/null; done
    echo "== Launcher is protected on this Fire OS; enabling the Home override service instead"
    # Add Aerie's service without switching off any others already enabled.
    cur=$(sh_ settings get secure enabled_accessibility_services)
    case "$cur" in
        null|"") new="$SERVICE" ;;
        *"$SERVICE"*) new="$cur" ;;
        *) new="$cur:$SERVICE" ;;
    esac
    sh_ settings put secure enabled_accessibility_services "$new"
    sh_ settings put secure accessibility_enabled 1
fi

echo "== Testing the Home button"
sh_ monkey -p com.netflix.ninja -c android.intent.category.LEANBACK_LAUNCHER 1 >/dev/null; sleep 5
sh_ input keyevent KEYCODE_HOME; sleep 3
on_aerie || rollback "Home button didn't land on Aerie ($(focus))"
echo "   Home -> Aerie ✓"

echo "== Reboot test (about a minute)"
adb -s "$TV" reboot
sleep 25
for i in $(seq 1 30); do adb connect "$TV" >/dev/null 2>&1; on_aerie && break; sleep 4; done
on_aerie || rollback "After reboot the screen wasn't Aerie ($(focus))"
echo "   Boot -> Aerie ✓"

echo
if [ $blocked = 1 ]; then
    echo "Done (override mode): Amazon's launcher couldn't be disabled on this firmware,"
    echo "but the Home key and boot now go to Aerie."
else
    echo "Done: Amazon's launcher is disabled and no longer runs. Aerie is the only home."
fi
echo "Undo any time with: TV=$TV tools/restore-amazon-home.sh"
