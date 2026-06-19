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

# ─── 1. Rust → Android .so ───
echo ""
echo "[1/3] Building Rust native library for Android..."
cd "$PROJECT_DIR"

# Author: znso4pa
RUST_TARGETS=("aarch64-linux-android" "armv7-linux-androideabi" "x86_64-linux-android")

for target in "${RUST_TARGETS[@]}"; do
    echo "  → $target"
    cargo build --release --target "$target" 2>&1 | tail -1 || echo "  [skip] $target not installed"
done

# Copy .so to jniLibs
# Author: znso4pa
mkdir -p "$PROJECT_DIR/app/src/main/jniLibs/arm64-v8a"
mkdir -p "$PROJECT_DIR/app/src/main/jniLibs/armeabi-v7a"
mkdir -p "$PROJECT_DIR/app/src/main/jniLibs/x86_64"

cp -f "$PROJECT_DIR/target/aarch64-linux-android/release/libarchive_core.so" "$PROJECT_DIR/app/src/main/jniLibs/arm64-v8a/" 2>/dev/null || echo "  [warn] arm64 .so missing (need cargo-ndk + Android NDK)"
cp -f "$PROJECT_DIR/target/armv7-linux-androideabi/release/libarchive_core.so" "$PROJECT_DIR/app/src/main/jniLibs/armeabi-v7a/" 2>/dev/null || true
cp -f "$PROJECT_DIR/target/x86_64-linux-android/release/libarchive_core.so" "$PROJECT_DIR/app/src/main/jniLibs/x86_64/" 2>/dev/null || true

echo "  ✅ Rust build done"

# ─── 2. Gradle build ───
echo ""
echo "[2/3] Building APK with Gradle..."
cd "$PROJECT_DIR"
./gradlew assembleRelease 2>&1 | tail -5 || echo "  [warn] Gradle not available (need Android Studio or gradlew)"

# ─── 3. Output ───
echo ""
echo "[3/3] Done!"
if [ -f "$PROJECT_DIR/app/build/outputs/apk/release/app-release-unsigned.apk" ]; then
    cp "$PROJECT_DIR/app/build/outputs/apk/release/app-release-unsigned.apk" "$PROJECT_DIR/UsefulUnpack.apk"
    echo "  ✅ APK: $PROJECT_DIR/UsefulUnpack.apk"
fi
echo ""
echo "Author: znso4pa (锌帕)"
echo "Run 'cargo build --release --target aarch64-linux-android' for native lib"
echo "==========================================="
