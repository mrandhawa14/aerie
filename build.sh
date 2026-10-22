#!/bin/bash
# Builds Aerie.apk without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.
#
# Usage:
#   ./build.sh                       build Aerie.apk
#   TV=192.168.1.50 ./build.sh install   build and install on the TV over adb
#
# Needs a JDK 17+ and the Android SDK's build-tools 34.0.0 + platform 34
# (ANDROID_HOME, or the usual install locations, are searched).
set -euo pipefail
cd "$(dirname "$0")"

find_sdk() {
    for d in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" \
             "$HOME/Library/Android/sdk" "$HOME/Android/Sdk" \
             /opt/homebrew/share/android-commandlinetools /usr/local/share/android-commandlinetools; do
        [ -n "$d" ] && [ -d "$d/build-tools/34.0.0" ] && [ -f "$d/platforms/android-34/android.jar" ] \
            && { echo "$d"; return; }
    done
    echo "Android SDK with build-tools 34.0.0 and platforms;android-34 not found." >&2
    echo "Install with: sdkmanager \"build-tools;34.0.0\" \"platforms;android-34\" and set ANDROID_HOME." >&2
    exit 1
}

SDK=$(find_sdk)
BT=$SDK/build-tools/34.0.0
JAR=$SDK/platforms/android-34/android.jar
JAVAC=${JAVA_HOME:+$JAVA_HOME/bin/}javac
KEYTOOL=${JAVA_HOME:+$JAVA_HOME/bin/}keytool

rm -rf build && mkdir -p build/res build/gen build/classes build/dex

"$BT/aapt2" compile --dir res -o build/res/
"$BT/aapt2" link -I "$JAR" --manifest AndroidManifest.xml --java build/gen \
    -o build/unsigned.apk build/res/*.flat

"$JAVAC" -source 8 -target 8 -Xlint:-options -encoding UTF-8 \
    -classpath "$JAR" -d build/classes \
    $(find src build/gen -name '*.java')

"$BT/d8" --min-api 25 --lib "$JAR" --output build/dex $(find build/classes -name '*.class')
(cd build/dex && zip -q ../unsigned.apk classes.dex)

"$BT/zipalign" -f 4 build/unsigned.apk build/aligned.apk

# A local signing key, created on first build. It is git-ignored on purpose:
# keep it, because updates must be signed with the same key to install over
# an existing Aerie.
KS=aerie-debug.keystore
[ -f "$KS" ] || "$KEYTOOL" -genkeypair -keystore "$KS" -storepass android \
    -keypass android -alias aerie -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Aerie debug" >/dev/null 2>&1
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
    --out Aerie.apk build/aligned.apk

echo "Built $(pwd)/Aerie.apk"

if [ "${1:-}" = install ]; then
    : "${TV:?Set TV to the Fire TV IP address, e.g. TV=192.168.1.50 ./build.sh install}"
    [[ "$TV" == *:* ]] || TV="$TV:5555"
    adb connect "$TV" >/dev/null
    adb -s "$TV" install -r Aerie.apk
fi
