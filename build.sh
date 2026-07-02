#!/bin/bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
echo "==========================================="
echo " UsefulUnpack Builder v4.0"
echo "==========================================="

if [ -z "${ANDROID_NDK_HOME:-}" ]; then
    if [ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME/ndk" ]; then
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

# package name:library artifact suffix
CRATES=(
    "archive_xp3-core:xp3_core"
    "archive_pfs-core:pfs_core"
    "archive_nsa-core:nsa_core"
    "archive_iso-core:iso_core"
    "archive_ypf-core:ypf_core"
    "archive_zip_core:zip_core"
    "archive_sevenz_core:sevenz_core"
)
TARGETS=("aarch64-linux-android" "armv7-linux-androideabi" "x86_64-linux-android")

for target in "${TARGETS[@]}"; do
    echo "  → $target"
    args=()
    for crate in "${CRATES[@]}"; do
        package="${crate%%:*}"
        args+=("-p" "$package")
    done
    cargo ndk --target "$target" --platform 26 build --release "${args[@]}"
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
        lib_suffix="${crate##*:}"
        src="$PROJECT_DIR/target/$target/release/libarchive_${lib_suffix}.so"
        dst="$LIBDIR/$jni_dir/"
        cp -f "$src" "$dst"
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
