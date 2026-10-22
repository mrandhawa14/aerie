#!/bin/bash
# Gives the home screen back to Amazon's launcher and turns off Aerie's
# Home-button service (other accessibility services are left alone).
# Usage: TV=192.168.1.50 tools/restore-amazon-home.sh
set -uo pipefail
: "${TV:?Set TV to the Fire TV IP address, e.g. TV=192.168.1.50 tools/restore-amazon-home.sh}"
[[ "$TV" == *:* ]] || TV="$TV:5555"
A="adb -s $TV"
SERVICE=io.github.mrandhawa14.aerie/io.github.mrandhawa14.aerie.HomeOverrideService

adb connect "$TV" >/dev/null
for p in com.amazon.tv.launcher com.amazon.firehomestarter; do $A shell pm enable "$p" </dev/null >/dev/null 2>&1; done
cur=$($A shell settings get secure enabled_accessibility_services </dev/null | tr -d '\r')
rest=$(echo "$cur" | tr ':' '\n' | grep -vx "$SERVICE" | paste -sd: -)
if [ -z "$rest" ] || [ "$rest" = null ]; then
    $A shell settings delete secure enabled_accessibility_services </dev/null
else
    $A shell settings put secure enabled_accessibility_services "$rest" </dev/null
fi
$A shell input keyevent KEYCODE_HOME </dev/null
echo "Amazon's home screen restored."
