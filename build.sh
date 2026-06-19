#!/bin/bash
# ╔══════════════════════════════════════════════════════════════╗
# ║  UsefulUnpack - XP3 & PFS Archive Tool                      ║
# ║  Author : znso4pa (锌帕)                                     ║
# ║  Build: Rust → Android .so → Gradle → APK                   ║
# ╚══════════════════════════════════════════════════════════════╝
set -e

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
echo "==========================================="
echo " UsefulUnpack Builder - znso4pa (锌帕)"
echo "==========================================="

# ─── 0. Prerequisites ───
if [ -z "$ANDROID_NDK_HOME" ]; then
    if [ -d "$ANDROID_HOME/ndk" ]; then
        export ANDROID_NDK_HOME=$(ls -d "$ANDROID_HOME/ndk/"*/ | sort -r | head -1)
        echo "[0/3] Auto-detected NDK: $ANDROID_NDK_HOME"
    else
        echo "[!] ANDROID_NDK_HOME not set and no NDK found in ANDROID_HOME"
        exit 1
    fi
fi

# ─── 1. Rust → Android .so ───
echo ""
echo "[1/3] Building Rust native library for Android (cargo-ndk)..."

cd "$PROJECT_DIR"

cargo ndk \
    --target aarch64-linux-android \
    --target armv7-linux-androideabi \
    --target x86_64-linux-android \
    --platform 26 \
    build --release

# Copy .so to jniLibs
mkdir -p "$PROJECT_DIR/app/src/main/jniLibs/arm64-v8a"
mkdir -p "$PROJECT_DIR/app/src/main/jniLibs/armeabi-v7a"
mkdir -p "$PROJECT_DIR/app/src/main/jniLibs/x86_64"

cp -f "$PROJECT_DIR/target/aarch64-linux-android/release/libarchive_core.so" "$PROJECT_DIR/app/src/main/jniLibs/arm64-v8a/"
cp -f "$PROJECT_DIR/target/armv7-linux-androideabi/release/libarchive_core.so" "$PROJECT_DIR/app/src/main/jniLibs/armeabi-v7a/"
cp -f "$PROJECT_DIR/target/x86_64-linux-android/release/libarchive_core.so" "$PROJECT_DIR/app/src/main/jniLibs/x86_64/"

echo "  ✅ Rust build done"

# ─── 2. Gradle build ───
echo ""
echo "[2/3] Building APK with Gradle..."
cd "$PROJECT_DIR"
./gradlew assembleRelease

# ─── 3. Output ───
echo ""
echo "[3/3] Done!"
OUTPUT_APK="$PROJECT_DIR/app/build/outputs/apk/release/app-release.apk"
if [ -f "$OUTPUT_APK" ]; then
    cp "$OUTPUT_APK" "$PROJECT_DIR/UsefulUnpack.apk"
    echo "  ✅ APK: $PROJECT_DIR/UsefulUnpack.apk"
elif [ -f "$PROJECT_DIR/app/build/outputs/apk/release/app-release-unsigned.apk" ]; then
    cp "$PROJECT_DIR/app/build/outputs/apk/release/app-release-unsigned.apk" "$PROJECT_DIR/UsefulUnpack.apk"
    echo "  ✅ APK: $PROJECT_DIR/UsefulUnpack.apk"
fi

echo ""
echo "Author: znso4pa (锌帕)"
echo "==========================================="
