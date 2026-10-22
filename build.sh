#!/bin/bash
# Builds Shelf.apk without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.
# Usage: ./build.sh [install]   (install pushes to the Fire TV at $TV, default 192.168.1.67)
set -euo pipefail
cd "$(dirname "$0")"

SDK=${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}
BT=$SDK/build-tools/34.0.0
JAR=$SDK/platforms/android-34/android.jar
export JAVA_HOME=${JAVA_HOME:-/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home}
TV=${TV:-192.168.1.67:5555}

rm -rf build && mkdir -p build/res build/gen build/classes build/dex

"$BT/aapt2" compile --dir res -o build/res/
"$BT/aapt2" link -I "$JAR" --manifest AndroidManifest.xml --java build/gen \
    -o build/unsigned.apk build/res/*.flat

"$JAVA_HOME/bin/javac" -source 8 -target 8 -Xlint:-options -encoding UTF-8 \
    -classpath "$JAR" -d build/classes \
    $(find src build/gen -name '*.java')

"$BT/d8" --min-api 25 --lib "$JAR" --output build/dex $(find build/classes -name '*.class')
(cd build/dex && zip -q ../unsigned.apk classes.dex)

"$BT/zipalign" -f 4 build/unsigned.apk build/aligned.apk

KS=debug.keystore
[ -f $KS ] || "$JAVA_HOME/bin/keytool" -genkeypair -keystore $KS -storepass android \
    -keypass android -alias shelf -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Shelf" >/dev/null 2>&1
"$BT/apksigner" sign --ks $KS --ks-pass pass:android --key-pass pass:android \
    --out Shelf.apk build/aligned.apk

echo "Built $(pwd)/Shelf.apk"

if [ "${1:-}" = install ]; then
    adb connect "$TV" >/dev/null
    adb -s "$TV" install -r Shelf.apk
fi
