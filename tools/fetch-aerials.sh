#!/bin/bash
# Downloads Aerie's aerial scenes (free Pexels clips), cuts each into a
# seamless loop with findloop.py, exports a still for the Scenery wallpaper,
# and, if TV is set, pushes them to Aerie on the TV.
#
# Usage: TV=192.168.1.50 tools/fetch-aerials.sh     # build + push
#        tools/fetch-aerials.sh                      # build only (into ./aerials)
#
# Needs: curl, ffmpeg/ffprobe, python3 with numpy. Aerie must be installed
# on the TV before pushing (the folder belongs to the app).
set -euo pipefail
cd "$(dirname "$0")/.."

OUT=aerials
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$OUT"

# name | Pexels video file | seam blend (s). Slow/static shots loop almost
# perfectly with a 1s blend; shots where the camera travels get a longer one.
SCENES=(
    "waterfall|https://videos.pexels.com/video-files/2098988/2098988-hd_1920_1080_30fps.mp4|1"
    "coast|https://videos.pexels.com/video-files/1409899/1409899-hd_1920_1080_25fps.mp4|3"
    "tropical|https://videos.pexels.com/video-files/2169880/2169880-hd_1920_1080_30fps.mp4|3"
)

for entry in "${SCENES[@]}"; do
    IFS='|' read -r name url blend <<< "$entry"
    if [ -f "$OUT/$name.mp4" ] && [ -f "$OUT/$name.jpg" ]; then
        echo "== $name (already built)"
        continue
    fi
    echo "== $name"
    curl -fsSL "$url" -o "$WORK/$name-src.mp4"
    python3 -I tools/findloop.py "$WORK/$name-src.mp4" "$OUT/$name.mp4" "$blend"
    ffmpeg -v error -y -i "$OUT/$name.mp4" -frames:v 1 -q:v 2 "$OUT/$name.jpg"
done

if [ -n "${TV:-}" ]; then
    [[ "$TV" == *:* ]] || TV="$TV:5555"
    DEST=/sdcard/Android/data/io.github.mrandhawa14.aerie/files/aerials
    adb connect "$TV" >/dev/null
    adb -s "$TV" shell mkdir -p "$DEST" </dev/null
    for f in "$OUT"/*.mp4 "$OUT"/*.jpg; do
        echo "pushing $(basename "$f")"
        adb -s "$TV" push "$f" "$DEST/" >/dev/null
    done
    echo "Done. Pick a scene in Aerie: Settings > Aerial scene."
else
    echo "Built into ./$OUT. Set TV=<ip> to push them to your TV."
fi
