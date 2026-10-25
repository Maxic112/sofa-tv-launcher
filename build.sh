#!/bin/sh
# Build and install Sofa TV Launcher.
#   ./build.sh                 — build the release APK
#   ./build.sh install <ip>    — build, install on the TV (<ip>:5555) and open it
#
# Needs JDK 17+ and the Android SDK: set JAVA_HOME (or have java on PATH) and
# either ANDROID_HOME or sdk.dir in local.properties.
set -eu

cd "$(dirname "$0")"

if [ ! -f local.properties ] && [ -n "${ANDROID_HOME:-}" ]; then
    echo "sdk.dir=$ANDROID_HOME" > local.properties
fi

./gradlew --quiet assembleRelease
APK=app/build/outputs/apk/release/app-release.apk
ls -l "$APK"

if [ "${1:-}" = "install" ]; then
    DEVICE="${2:?TV IP address required}:5555"
    adb connect "$DEVICE" >/dev/null
    adb -s "$DEVICE" install -r "$APK"
    adb -s "$DEVICE" shell am start -n dev.sofatv.launcher/.MainActivity
fi
