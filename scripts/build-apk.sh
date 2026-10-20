#!/usr/bin/env bash
#
# Builds a debug-signed APK without Gradle.
#
# This exists because the original author's device only had a JDK + Android
# build-tools installed (no Gradle distribution). It is also handy for CI
# debugging: `./scripts/build-apk.sh` produces build/local/headless-android-debug.apk.
#
# Environment overrides:
#   JAVA_HOME      JDK 17+
#   ANDROID_JAR    path to android.jar (compileSdk 36 / API 36)
#   BUILD_TOOLS    path to an Android build-tools directory (aapt2, zipalign,
#                  lib/d8.jar, lib/apksigner.jar)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP="$ROOT/app"
BUILD="$ROOT/build/local"
DEPS="$BUILD/deps"
JARS="$DEPS/jars"
GEN="$BUILD/gen"
CLASSES="$BUILD/classes"
DEX="$BUILD/dex"

JAVA_HOME="${JAVA_HOME:-}"
if [ -z "$JAVA_HOME" ] || [ ! -x "$JAVA_HOME/bin/javac" ]; then
    for candidate in \
        /tmp/opencode/jdk-17.0.20.1+1 \
        "$HOME/.jdks/temurin-17" \
        /usr/lib/jvm/java-17-openjdk-arm64 \
        /usr/lib/jvm/java-17-openjdk-amd64 \
        /usr/lib/jvm/java-17-openjdk; do
        if [ -x "$candidate/bin/javac" ]; then
            JAVA_HOME="$candidate"
            break
        fi
    done
fi
if [ -z "$JAVA_HOME" ] || [ ! -x "$JAVA_HOME/bin/javac" ]; then
    echo "JDK 17+ not found. Set JAVA_HOME." >&2
    exit 1
fi

ANDROID_JAR="${ANDROID_JAR:-}"
if [ -z "$ANDROID_JAR" ]; then
    for candidate in \
        /tmp/opencode/sdkcheck/android-36/android.jar \
        "$HOME/Android/Sdk/platforms/android-36/android.jar" \
        /opt/android-sdk/platforms/android-36/android.jar; do
        if [ -f "$candidate" ]; then
            ANDROID_JAR="$candidate"
            break
        fi
    done
fi
if [ -z "$ANDROID_JAR" ] || [ ! -f "$ANDROID_JAR" ]; then
    echo "android.jar (API 36) not found. Set ANDROID_JAR." >&2
    exit 1
fi

BUILD_TOOLS="${BUILD_TOOLS:-}"
if [ -z "$BUILD_TOOLS" ]; then
    for candidate in \
        /tmp/opencode/sdkcheck/android-15 \
        "$HOME/Android/Sdk/build-tools/35.0.0" \
        /opt/android-sdk/build-tools/35.0.0; do
        if [ -x "$candidate/aapt2" ]; then
            BUILD_TOOLS="$candidate"
            break
        fi
    done
fi
if [ -z "$BUILD_TOOLS" ] || [ ! -x "$BUILD_TOOLS/aapt2" ]; then
    echo "build-tools (aapt2) not found. Set BUILD_TOOLS." >&2
    exit 1
fi

JAVA="$JAVA_HOME/bin/java"
JAVAC="$JAVA_HOME/bin/javac"
KEYTOOL="$JAVA_HOME/bin/keytool"

mkdir -p "$JARS" "$GEN" "$CLASSES" "$DEX"
rm -rf "$GEN" "$CLASSES" "$DEX"
mkdir -p "$GEN" "$CLASSES" "$DEX"

# --- Shizuku API AARs (classes.jar + compile-only annotations) -------------
fetch_shizuku() {
    local artifact="$1" version="$2"
    local aar="$DEPS/shizuku-$artifact-$version.aar"
    if [ ! -f "$aar" ]; then
        echo "== downloading dev.rikka.shizuku:$artifact:$version"
        curl -fsSL -o "$aar" \
            "https://repo1.maven.org/maven2/dev/rikka/shizuku/$artifact/$version/$artifact-$version.aar"
    fi
    if [ ! -f "$JARS/shizuku-$artifact.jar" ]; then
        unzip -p "$aar" classes.jar > "$JARS/shizuku-$artifact.jar"
    fi
}

fetch_shizuku api 13.1.5
fetch_shizuku provider 13.1.5
fetch_shizuku aidl 13.1.5
fetch_shizuku shared 13.1.5

ANNOTATION_JAR="$DEPS/annotation-1.3.0.jar"
if [ ! -f "$ANNOTATION_JAR" ]; then
    curl -fsSL -o "$ANNOTATION_JAR" \
        "https://dl.google.com/dl/android/maven2/androidx/annotation/annotation/1.3.0/annotation-1.3.0.jar"
fi

# --- resources --------------------------------------------------------------
echo "== aapt2 compile"
"$BUILD_TOOLS/aapt2" compile --dir "$APP/src/main/res" -o "$BUILD/res.zip"

# aapt2 requires a package attribute; AGP 9 forbids it in the source manifest,
# so inject it into a build-time copy only.
sed '0,/<manifest /s//<manifest package="com.bliss.headless" /' \
    "$APP/src/main/AndroidManifest.xml" > "$BUILD/AndroidManifest.xml"

echo "== aapt2 link"
"$BUILD_TOOLS/aapt2" link \
    -o "$BUILD/app-unsigned.apk" \
    -I "$ANDROID_JAR" \
    --manifest "$BUILD/AndroidManifest.xml" \
    --java "$GEN" \
    --custom-package com.bliss.headless \
    --min-sdk-version 29 \
    --target-sdk-version 36 \
    --version-code 2 \
    --version-name 1.0.1 \
    "$BUILD/res.zip"

# --- java -------------------------------------------------------------------
echo "== javac"
CP="$ANDROID_JAR:$ANNOTATION_JAR"
for jar in "$JARS"/*.jar; do
    CP="$CP:$jar"
done

find "$APP/src/main/java" "$GEN" -name '*.java' > "$BUILD/sources.txt"
"$JAVAC" -source 17 -target 17 -nowarn -Xlint:-options \
    -classpath "$CP" \
    -d "$CLASSES" \
    @"$BUILD/sources.txt"

# --- dex --------------------------------------------------------------------
echo "== d8"
find "$CLASSES" -name '*.class' > "$BUILD/classes.txt"
"$JAVA" -Xmx2g -cp "$BUILD_TOOLS/lib/d8.jar" com.android.tools.r8.D8 \
    --release \
    --lib "$ANDROID_JAR" \
    --min-api 29 \
    --output "$DEX" \
    @"$BUILD/classes.txt" \
    "$JARS/shizuku-api.jar" \
    "$JARS/shizuku-provider.jar" \
    "$JARS/shizuku-aidl.jar" \
    "$JARS/shizuku-shared.jar"

# --- package ----------------------------------------------------------------
echo "== package"
cp "$BUILD/app-unsigned.apk" "$BUILD/app-dexed.apk"
(cd "$DEX" && "$JAVA_HOME/bin/jar" uf "$BUILD/app-dexed.apk" classes.dex)

"$BUILD_TOOLS/zipalign" -f 4 "$BUILD/app-dexed.apk" "$BUILD/app-aligned.apk"

KEYSTORE="$BUILD/debug.keystore"
if [ ! -f "$KEYSTORE" ]; then
    echo "== generating debug keystore"
    "$KEYTOOL" -genkeypair -keystore "$KEYSTORE" -storepass android -keypass android \
        -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
fi

echo "== apksigner"
"$JAVA" -jar "$BUILD_TOOLS/lib/apksigner.jar" sign \
    --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
    --ks-key-alias androiddebugkey \
    --out "$BUILD/headless-android-debug.apk" \
    "$BUILD/app-aligned.apk"

"$JAVA" -jar "$BUILD_TOOLS/lib/apksigner.jar" verify --print-certs \
    "$BUILD/headless-android-debug.apk" | head -5

echo
echo "APK:  $BUILD/headless-android-debug.apk"
"$BUILD_TOOLS/aapt2" dump badging "$BUILD/headless-android-debug.apk" | head -6
