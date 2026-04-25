#!/bin/bash
set -euo pipefail

# Build script for tiverify.xcframework from source in iphone/lib/tiverify_src/
#
# Compiles TiVerify.m for three platform slices and assembles them into
# an xcframework at iphone/lib/tiverify.xcframework.
#
# Usage: ./build_tiverify.sh

SCRIPT_PATH=$(cd "$(dirname "$0")"; pwd)
ROOT_DIR=$(cd "$SCRIPT_PATH/../.."; pwd)

SRC_DIR="$ROOT_DIR/iphone/lib/tiverify_src"
FRAMEWORK_DIR="$ROOT_DIR/iphone/lib/tiverify.xcframework"
BUILD_DIR="$ROOT_DIR/iphone/lib/tiverify_build"

SOURCE_FILE="$SRC_DIR/TiVerify.m"

# Clean previous build artifacts
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR"

echo "Building tiverify.xcframework from source..."

# --- iOS device (arm64) ---
echo "  Compiling for ios-arm64..."
xcrun clang \
  -arch arm64 \
  -isysroot $(xcrun --sdk iphoneos --show-sdk-path) \
  -miphoneos-version-min=12.0 \
  -target arm64-apple-ios12.0 \
  -fobjc-arc \
  -c "$SOURCE_FILE" \
  -o "$BUILD_DIR/TiVerify_arm64.o"

xcrun ar rcs "$BUILD_DIR/libtiverify_ios_arm64.a" "$BUILD_DIR/TiVerify_arm64.o"

# --- iOS simulator (arm64 + x86_64) ---
echo "  Compiling for ios-arm64_x86_64-simulator..."
xcrun clang \
  -arch arm64 \
  -isysroot $(xcrun --sdk iphonesimulator --show-sdk-path) \
  -miphonesimulator-version-min=12.0 \
  -target arm64-apple-ios12.0-simulator \
  -fobjc-arc \
  -c "$SOURCE_FILE" \
  -o "$BUILD_DIR/TiVerify_arm64_sim.o"

xcrun clang \
  -arch x86_64 \
  -isysroot $(xcrun --sdk iphonesimulator --show-sdk-path) \
  -miphonesimulator-version-min=12.0 \
  -target x86_64-apple-ios12.0-simulator \
  -fobjc-arc \
  -c "$SOURCE_FILE" \
  -o "$BUILD_DIR/TiVerify_x86_64_sim.o"

xcrun lipo -create \
  "$BUILD_DIR/TiVerify_arm64_sim.o" \
  "$BUILD_DIR/TiVerify_x86_64_sim.o" \
  -output "$BUILD_DIR/TiVerify_sim_fat.o"

xcrun ar rcs "$BUILD_DIR/libtiverify_sim.a" "$BUILD_DIR/TiVerify_sim_fat.o"

# --- Mac Catalyst (arm64 + x86_64) ---
echo "  Compiling for ios-arm64_x86_64-maccatalyst..."
xcrun clang \
  -arch arm64 \
  -isysroot $(xcrun --sdk macosx --show-sdk-path) \
  -target arm64-apple-ios-macabi \
  -fobjc-arc \
  -c "$SOURCE_FILE" \
  -o "$BUILD_DIR/TiVerify_arm64_catalyst.o"

xcrun clang \
  -arch x86_64 \
  -isysroot $(xcrun --sdk macosx --show-sdk-path) \
  -target x86_64-apple-ios-macabi \
  -fobjc-arc \
  -c "$SOURCE_FILE" \
  -o "$BUILD_DIR/TiVerify_x86_64_catalyst.o"

xcrun lipo -create \
  "$BUILD_DIR/TiVerify_arm64_catalyst.o" \
  "$BUILD_DIR/TiVerify_x86_64_catalyst.o" \
  -output "$BUILD_DIR/TiVerify_catalyst_fat.o"

xcrun ar rcs "$BUILD_DIR/libtiverify_catalyst.a" "$BUILD_DIR/TiVerify_catalyst_fat.o"

# --- Prepare clean headers directory ---
HEADERS_DIR="$BUILD_DIR/headers"
mkdir -p "$HEADERS_DIR"
cp "$SRC_DIR/TiVerify.h" "$HEADERS_DIR/"

# --- Remove old xcframework and create new one ---
rm -rf "$FRAMEWORK_DIR"

echo "  Creating xcframework..."
xcodebuild -create-xcframework \
  -library "$BUILD_DIR/libtiverify_ios_arm64.a" \
  -headers "$HEADERS_DIR" \
  -library "$BUILD_DIR/libtiverify_sim.a" \
  -headers "$HEADERS_DIR" \
  -library "$BUILD_DIR/libtiverify_catalyst.a" \
  -headers "$HEADERS_DIR" \
  -output "$FRAMEWORK_DIR"

# --- Clean up build artifacts ---
rm -rf "$BUILD_DIR"

echo "tiverify.xcframework rebuilt successfully at $FRAMEWORK_DIR"