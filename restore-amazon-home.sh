#!/bin/bash
# Puts Amazon's launcher back exactly as it was.
TV=${TV:-192.168.1.67:5555}
A="adb -s $TV"
adb connect "$TV"
for p in com.amazon.tv.launcher com.amazon.firehomestarter; do $A shell pm enable "$p"; done
$A shell settings delete secure enabled_accessibility_services
$A shell input keyevent KEYCODE_HOME
echo "Amazon's home screen restored."
