#!/usr/bin/env bash
# Builds the Rust library (crates/android) for the phone's processors and puts it where the
# Android app picks it up: android/app/src/main/jniLibs/<abi>/libmdm_android.so
#
# Needs: Rust (rustup), the Android NDK (ANDROID_NDK_HOME pointing at it; Android Studio's
# SDK Manager → SDK Tools → NDK installs one), and network access for Cargo's first fetch.
# Usage: tools/android/build-rust.sh [debug|release]   (release by default)
set -euo pipefail

profile="${1:-release}"
root="$(cd "$(dirname "$0")/../.." && pwd)"

if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
  echo "ANDROID_NDK_HOME is not set: point it at the NDK (…/Android/Sdk/ndk/<version>)." >&2
  exit 1
fi

rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
command -v cargo-ndk >/dev/null || cargo install cargo-ndk --locked

flags=()
[[ "$profile" == "release" ]] && flags+=(--release)

cd "$root/crates/android"
# -P 26: the app's minSdk (android/app/build.gradle.kts).
cargo ndk -P 26 \
  -t arm64-v8a -t armeabi-v7a -t x86_64 \
  -o "$root/android/app/src/main/jniLibs" \
  build "${flags[@]}"

echo "Built into android/app/src/main/jniLibs. Now open android/ in Android Studio and run the app."
