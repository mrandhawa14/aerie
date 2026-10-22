#!/bin/bash
# Removes Fire OS ad, tracking and preinstalled-app packages for the current
# user only. Nothing is deleted from the system partition: every package can
# be brought back with --restore (or a factory reset).
#
# Usage: TV=192.168.1.50 tools/debloat-firetv.sh            # remove
#        TV=192.168.1.50 tools/debloat-firetv.sh --restore  # put everything back
#
# Tested on Fire OS 7 (Android 9). Edit the list to keep anything you use:
# Prime Video, Amazon Music, Silk, Alexa and the Live tab are in it.
set -uo pipefail
: "${TV:?Set TV to the Fire TV IP address, e.g. TV=192.168.1.50 tools/debloat-firetv.sh}"
[[ "$TV" == *:* ]] || TV="$TV:5555"
A="adb -s $TV"

PACKAGES=(
    # Ads, sponsored content and recommendations on the home screen
    com.amazon.ftvads.deeplinking          # protected on most firmware; skipped if so
    com.amazon.hybridadidservice
    com.amazon.kso.blackbird               # "special offers"
    com.amazon.sneakpeek                   # autoplaying sponsored previews
    com.amazon.media.recommendations
    com.amazon.shoptv.client
    com.amazon.shoptv.firetv.client
    com.amazon.minitv.android.app
    com.amazon.tv.democontent.provider
    # Tracking and telemetry
    com.amazon.tv.acr                      # automatic content recognition
    com.amazon.device.crashmanager
    com.amazon.device.logmanager
    com.amazon.tv.fw.metrics
    com.amazon.wirelessmetrics.service
    com.amazon.perfcollection
    com.amazon.neo.minerva.logcat
    com.amazon.dp.logger
    # Tutorials and release notes
    com.amazon.tmm.tutorial
    com.amazon.storm.lightning.tutorial
    com.amazon.tv.releasenotes
    # Preinstalled apps (remove the lines for any you use)
    com.amazon.firebat                     # Prime Video
    com.amazon.bueller.music               # Amazon Music
    com.amazon.bueller.photos              # Amazon Photos
    com.amazon.cloud9                      # Silk browser
    com.amazon.tv.livetv                   # Live tab
    com.amazon.spiderpork                  # Live / free channels
    com.amazon.gamehub                     # Luna / games
    com.amazon.spotify.mediabrowserservice
    # Alexa voice (the remote's mic button stops working)
    com.amazon.vizzini
    com.amazon.vizzini.ftvcds
    com.amazon.tv.alexaalerts
    com.amazon.tv.alexanotifications
)

adb connect "$TV" | grep -q connected || { echo "Can't reach $TV. Same network? ADB debugging on?"; exit 1; }

if [ "${1:-}" = --restore ]; then
    for p in "${PACKAGES[@]}"; do
        $A shell cmd package install-existing "$p" </dev/null >/dev/null 2>&1
        $A shell pm enable "$p" </dev/null >/dev/null 2>&1
        echo "restored  $p"
    done
    exit 0
fi

for p in "${PACKAGES[@]}"; do
    r=$($A shell pm uninstall -k --user 0 "$p" </dev/null 2>&1 | tr -d '\r' | tail -1)
    if [ "$r" = Success ]; then
        echo "removed   $p"
        continue
    fi
    # Some packages refuse a per-user uninstall but can still be disabled.
    r=$($A shell pm disable-user --user 0 "$p" </dev/null 2>&1 | tr -d '\r' | tail -1)
    case "$r" in
        *disabled*) echo "disabled  $p" ;;
        *) echo "skipped   $p (protected by Fire OS)" ;;
    esac
done
