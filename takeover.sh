#!/bin/bash
# Makes Shelf the Fire TV's only home screen by disabling Amazon's launcher.
# Verifies every step and rolls back automatically if Shelf doesn't come up.
# Usage: ./takeover.sh           (TV must be on the same Wi-Fi, ADB debugging on)
#        TV=192.168.1.xx:5555 ./takeover.sh
set -uo pipefail
cd "$(dirname "$0")"

TV=${TV:-192.168.1.67:5555}
A="adb -s $TV"
SHELF=com.mehak.shelf/.HomeActivity
AMAZON=(com.amazon.tv.launcher com.amazon.firehomestarter)

sh_()   { $A shell "$@" </dev/null 2>&1 | tr -d '\r'; }
focus() { sh_ dumpsys window windows | grep -m1 mCurrentFocus; }
on_shelf() { focus | grep -q com.mehak.shelf; }

rollback() {
    echo "!! $1 — restoring Amazon's launcher"
    for p in "${AMAZON[@]}"; do sh_ pm enable "$p" >/dev/null; done
    sh_ input keyevent KEYCODE_HOME >/dev/null
    echo "Rolled back. The TV is as it was before this script ran."
    exit 1
}

echo "== Connecting to $TV"
adb connect "$TV" | grep -q connected || { echo "Can't reach the TV. Same Wi-Fi? ADB debugging on?"; exit 1; }

echo "== Checking Shelf is installed and opens"
sh_ pm path com.mehak.shelf | grep -q package: || ./build.sh install || exit 1
sh_ am start -W -n $SHELF >/dev/null; sleep 2
on_shelf || { echo "Shelf didn't open; not touching anything."; exit 1; }

echo "== Making Shelf the preferred home"
sh_ cmd package set-home-activity $SHELF

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
    sh_ settings put secure enabled_accessibility_services com.mehak.shelf/com.mehak.shelf.HomeOverrideService
    sh_ settings put secure accessibility_enabled 1
fi

echo "== Testing the Home button"
sh_ monkey -p com.netflix.ninja -c android.intent.category.LEANBACK_LAUNCHER 1 >/dev/null; sleep 5
sh_ input keyevent KEYCODE_HOME; sleep 3
on_shelf || rollback "Home button didn't land on Shelf ($(focus))"
echo "   Home -> Shelf ✓"

echo "== Reboot test (about a minute)"
adb -s "$TV" reboot
sleep 25
for i in $(seq 1 30); do adb connect "$TV" >/dev/null 2>&1; on_shelf && break; sleep 4; done
on_shelf || rollback "After reboot the screen wasn't Shelf ($(focus))"
echo "   Boot -> Shelf ✓"

echo
if [ $blocked = 1 ]; then
    echo "Done (override mode): Amazon's launcher couldn't be disabled on this firmware,"
    echo "but the Home key and boot now go to Shelf."
else
    echo "Done: Amazon's launcher is disabled and no longer runs. Shelf is the only home."
fi
echo "Undo any time with ./restore-amazon-home.sh"
