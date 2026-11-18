#!/usr/bin/env bash
# Builds FreeMyTVLite.apk (lite/): a light home screen for old Fire TV devices, Android 5.1+. Same tools as build.sh.
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ]; then
  for d in "$HOME/Library/Android/sdk" "$HOME/Android/Sdk" /opt/homebrew/share/android-commandlinetools \
           /usr/local/share/android-commandlinetools /usr/lib/android-sdk; do
    if [ -d "$d/build-tools" ]; then SDK="$d"; break; fi
  done
fi
[ -n "$SDK" ] && [ -d "$SDK/build-tools" ] || { echo "Android SDK not found. Install it and set ANDROID_HOME (see README)."; exit 1; }
BT="$SDK/build-tools/$(ls "$SDK/build-tools" | sort -V | tail -1)"
AJ="$(ls -d "$SDK"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1 || true)"
[ -f "${AJ:-}" ] || { echo "Android platform not found. Run: sdkmanager \"platforms;android-34\""; exit 1; }
JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
KEYTOOL="${JAVA_HOME:+$JAVA_HOME/bin/}keytool"
command -v "$JAVAC" >/dev/null 2>&1 || { echo "JDK not found. Install JDK 17 and set JAVA_HOME."; exit 1; }

O=out/lite
rm -rf "$O" && mkdir -p "$O/gen" "$O/classes" "$O/res"
"$BT/aapt2" compile --dir lite/res -o "$O/res/lite.zip"
# The icon and banner are the same as the full app's.
"$BT/aapt2" compile app/res/drawable-nodpi/icon.png app/res/drawable-nodpi/banner.png -o "$O/res"
"$BT/aapt2" link -o "$O/base.apk" -I "$AJ" --manifest lite/AndroidManifest.xml --java "$O/gen" \
  --min-sdk-version 22 --target-sdk-version 22 "$O"/res/*
"$JAVAC" -nowarn -Xlint:-options -source 8 -target 8 -bootclasspath "$AJ:$BT/core-lambda-stubs.jar" -classpath "$AJ" \
  -d "$O/classes" $(find lite/src "$O/gen" -name '*.java')
"$BT/d8" --release --min-api 22 --lib "$AJ" --output "$O" $(find "$O/classes" -name '*.class')
(cd "$O" && zip -qj base.apk classes.dex)
"$BT/zipalign" -f -p 4 "$O/base.apk" "$O/aligned.apk"

# Same key as the full app (keep freemytv.keystore).
if [ ! -f freemytv.keystore ]; then
  "$KEYTOOL" -genkeypair -keystore freemytv.keystore -alias freemytv -keyalg RSA -keysize 2048 -validity 20000 \
    -storepass freemytv -keypass freemytv -dname "CN=Free My TV" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks freemytv.keystore --ks-pass pass:freemytv --key-pass pass:freemytv \
  --out FreeMyTVLite.apk "$O/aligned.apk"
echo "Built FreeMyTVLite.apk ($(du -h FreeMyTVLite.apk | cut -f1)). Install: adb install -r FreeMyTVLite.apk"
