#!/bin/bash
set -e

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
echo "==========================================="
echo " UsefulUnpack Builder v4.0"
echo "==========================================="

if [ -z "$ANDROID_NDK_HOME" ]; then
    if [ -d "$ANDROID_HOME/ndk" ]; then
        export ANDROID_NDK_HOME=$(ls -d "$ANDROID_HOME/ndk/"*/ | sort -r | head -1)
        echo "[0/3] NDK: $ANDROID_NDK_HOME"
    else
        echo "[!] ANDROID_NDK_HOME not set"
        exit 1
    fi
fi

echo ""
echo "[1/3] Building Rust native libraries..."
cd "$PROJECT_DIR"

# Build each format crate
CRATES=("xp3_core" "pfs_core" "nsa_core" "iso_core" "ypf_core" "zip_core" "sevenz_core")
TARGETS=("aarch64-linux-android" "armv7-linux-androideabi" "x86_64-linux-android")

for target in "${TARGETS[@]}"; do
    echo "  → $target"
    args=""
    for crate in "${CRATES[@]}"; do
        args="$args -p archive_$crate"
    done
    cargo ndk --target "$target" --platform 26 build --release $args 2>&1 | tail -1
done

# Copy .so files
echo "  Copying .so files..."
LIBDIR="$PROJECT_DIR/app/src/main/jniLibs"
mkdir -p "$LIBDIR/arm64-v8a" "$LIBDIR/armeabi-v7a" "$LIBDIR/x86_64"

ARCH_MAP=("aarch64-linux-android:arm64-v8a" "armv7-linux-androideabi:armeabi-v7a" "x86_64-linux-android:x86_64")

for pair in "${ARCH_MAP[@]}"; do
    target="${pair%%:*}"
    jni_dir="${pair##*:}"
    for crate in "${CRATES[@]}"; do
        src="$PROJECT_DIR/target/$target/release/libarchive_${crate}.so"
        dst="$LIBDIR/$jni_dir/"
        cp -f "$src" "$dst" 2>/dev/null || echo "  [warn] missing: $src"
    done
done

echo "  ✅ Rust build done"

echo ""
echo "[2/3] Building APK with Gradle..."
cd "$PROJECT_DIR"
./gradlew assembleRelease

echo ""
echo "[3/3] Done!"
APK="$PROJECT_DIR/app/build/outputs/apk/release/app-release.apk"
if [ -f "$APK" ]; then
    cp "$APK" "$PROJECT_DIR/UsefulUnpack.apk"
    echo "  ✅ APK: $PROJECT_DIR/UsefulUnpack.apk"
fi
echo ""
echo "==========================================="
