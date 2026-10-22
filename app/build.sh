#!/usr/bin/env bash
# Gradle-free build of the FFXII Thor Companion APK. Output: build/ffxii-companion.apk
# Signing: the release key when RELEASE_KEYSTORE (+ RELEASE_KEYSTORE_PASS, RELEASE_KEY_ALIAS) is set, otherwise the
# local Android debug key (fine for testing, but a debug-signed APK cannot update a release-signed one).
set -euo pipefail
cd "$(dirname "$0")"
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) EXE=.bat; DEF_SDK="$LOCALAPPDATA/Android/Sdk" ;; *) EXE=; DEF_SDK="$HOME/Android/Sdk" ;; esac
SDK="${ANDROID_SDK:-${ANDROID_HOME:-$DEF_SDK}}"
BT="$SDK/build-tools/${BUILD_TOOLS:-35.0.1}"
JAR="$SDK/platforms/android-35/android.jar"
JAVA_BIN="${JDK:+$JDK/bin/}"
rm -rf build && mkdir -p build/classes build/dex
# The matching game script and container installer ride along as assets (written to Download/FFXII_companion/install).
mkdir -p build/assets/install
cp ../game-script/ThorCompanion.lua ../game-script/container/install.cmd ../game-script/container/uninstall.cmd    ../game-script/container/README.txt build/assets/install/
"$BT/aapt2" link -o build/base.apk -I "$JAR" --manifest AndroidManifest.xml -A build/assets
"${JAVA_BIN}javac" -source 11 -target 11 -Xlint:-options -encoding UTF-8 -cp "$JAR" -d build/classes $(find src -name '*.java')
"$BT/d8$EXE" --release --min-api 30 --lib "$JAR" --output build/dex $(find build/classes -name '*.class')
(cd build/dex && "${JAVA_BIN}jar" uf ../base.apk classes.dex)
"$BT/zipalign" -f 4 build/base.apk build/aligned.apk
if [ -n "${RELEASE_KEYSTORE:-}" ]; then
  "$BT/apksigner$EXE" sign --ks "$RELEASE_KEYSTORE" --ks-pass env:RELEASE_KEYSTORE_PASS --ks-key-alias "${RELEASE_KEY_ALIAS:-ffxii-companion}" \
    --out build/ffxii-companion.apk build/aligned.apk
else
  "$BT/apksigner$EXE" sign --ks "$HOME/.android/debug.keystore" --ks-pass pass:android --key-pass pass:android \
    --out build/ffxii-companion.apk build/aligned.apk
fi
"$BT/apksigner$EXE" verify build/ffxii-companion.apk && echo "BUILT build/ffxii-companion.apk"
