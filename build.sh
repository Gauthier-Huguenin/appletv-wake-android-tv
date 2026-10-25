#!/usr/bin/env bash
# Builds dist/AppleTVWake.apk without Gradle: aapt2, javac, R8, zipalign, apksigner.
#
# Needs: JDK 17+ (JAVA_HOME or java on PATH) and the Android SDK (ANDROID_HOME) with
# platforms;android-30 and any build-tools >= 30.
# Release signing: set KEYSTORE_FILE, KEYSTORE_PASSWORD and KEY_ALIAS. Otherwise a local
# debug keystore is created in build/.
set -euo pipefail
cd "$(dirname "$0")"

SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
[ -n "$SDK" ] || { echo "Set ANDROID_HOME to your Android SDK" >&2; exit 1; }
BT=$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)
ANDROID_JAR=$SDK/platforms/android-30/android.jar
[ -f "$ANDROID_JAR" ] || { echo "Missing $ANDROID_JAR: sdkmanager \"platforms;android-30\"" >&2; exit 1; }
JAVA=${JAVA_HOME:+$JAVA_HOME/bin/}java
JAVAC=${JAVA_HOME:+$JAVA_HOME/bin/}javac
JAR=${JAVA_HOME:+$JAVA_HOME/bin/}jar
KEYTOOL=${JAVA_HOME:+$JAVA_HOME/bin/}keytool

# BouncyCastle provides X25519, Ed25519 and ChaCha20-Poly1305 (Android 11 lacks the first two).
BC_VERSION=1.78.1
BC_SHA256=add5915e6acfc6ab5836e1fd8a5e21c6488536a8c1f21f386eeb3bf280b702d7
BC=build/libs/bcprov-jdk18on-$BC_VERSION.jar
if [ ! -f "$BC" ]; then
    mkdir -p build/libs
    curl -sSfL -o "$BC" "https://repo1.maven.org/maven2/org/bouncycastle/bcprov-jdk18on/$BC_VERSION/bcprov-jdk18on-$BC_VERSION.jar"
fi
echo "$BC_SHA256  $BC" | shasum -a 256 -c - >/dev/null || { echo "BouncyCastle checksum mismatch" >&2; rm -f "$BC"; exit 1; }

OUT=build/apk
rm -rf "$OUT" && mkdir -p "$OUT/classes" "$OUT/gen" "$OUT/dex" dist

"$BT/aapt2" compile --dir app/res -o "$OUT/res.zip"
"$BT/aapt2" link -I "$ANDROID_JAR" --manifest app/AndroidManifest.xml -o "$OUT/base.apk" "$OUT/res.zip" \
    --java "$OUT/gen" --min-sdk-version 30 --target-sdk-version 30

"$JAVAC" --release 11 -encoding UTF-8 -d "$OUT/classes" -cp "$ANDROID_JAR:$BC" \
    $(find app/src "$OUT/gen" -name '*.java')
(cd "$OUT/classes" && "$JAR" cf ../classes.jar .)

"$JAVA" -cp "$BT/lib/d8.jar" com.android.tools.r8.R8 --release --min-api 30 \
    --lib "$ANDROID_JAR" --pg-conf app/proguard-rules.pro --output "$OUT/dex" "$OUT/classes.jar" "$BC"

cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q ../unsigned.apk classes*.dex)
"$BT/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

if [ -n "${KEYSTORE_FILE:-}" ]; then
    "$BT/apksigner" sign --ks "$KEYSTORE_FILE" --ks-pass env:KEYSTORE_PASSWORD --ks-key-alias "$KEY_ALIAS" \
        --out dist/AppleTVWake.apk "$OUT/aligned.apk"
else
    KS=build/debug.keystore
    [ -f "$KS" ] || "$KEYTOOL" -genkeypair -keystore "$KS" -storepass android -keypass android \
        -alias debug -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=AppleTV Wake debug" >/dev/null 2>&1
    "$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --out dist/AppleTVWake.apk "$OUT/aligned.apk"
fi
echo "Built dist/AppleTVWake.apk"
