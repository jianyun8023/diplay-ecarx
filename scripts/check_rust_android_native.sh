#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CRATE_DIR="$ROOT/shared/src/main/rust/diplay-media"

: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to NDK 28.2.13676358}"
NDK_REVISION="$(sed -n 's/^Pkg.Revision = //p' "$ANDROID_NDK_HOME/source.properties" | tr -d '\r')"
if [[ "$NDK_REVISION" != "28.2.13676358" ]]; then
  echo "Expected Android NDK 28.2.13676358, found '$NDK_REVISION'" >&2
  exit 1
fi

HOST_TAG="$(uname -s | tr '[:upper:]' '[:lower:]')-x86_64"
BIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$HOST_TAG/bin"
for compiler in aarch64-linux-android28-clang armv7a-linux-androideabi28-clang; do
  if [[ ! -x "$BIN/$compiler" ]]; then
    echo "Missing Android NDK compiler: $BIN/$compiler" >&2
    exit 1
  fi
done

RUST_VERSION="$(cd "$CRATE_DIR" && rustc --version)"
if [[ "$RUST_VERSION" != "rustc 1.97.1 "* ]]; then
  echo "Expected Rust 1.97.1, found '$RUST_VERSION'" >&2
  exit 1
fi
INSTALLED_TARGETS="$(cd "$CRATE_DIR" && rustup target list --installed)"
for target in aarch64-linux-android armv7-linux-androideabi; do
  if ! grep -Fxq "$target" <<<"$INSTALLED_TARGETS"; then
    echo "Rust target is not installed: $target" >&2
    exit 1
  fi
done

OWN_OUTPUT=0
OUTPUT_PARENT=""
if [[ -n "${DIPLAY_JNI_LIBS_DIR:-}" ]]; then
  OUTPUT_DIR="$DIPLAY_JNI_LIBS_DIR"
else
  OUTPUT_PARENT="$(mktemp -d)"
  OUTPUT_DIR="$OUTPUT_PARENT/jniLibs"
  OWN_OUTPUT=1
fi
if [[ "$OUTPUT_DIR" != /* ]]; then
  OUTPUT_DIR="$ROOT/$OUTPUT_DIR"
fi
VERIFY_TMP="$(mktemp -d)"
cleanup() {
  rm -rf "$VERIFY_TMP"
  if [[ "$OWN_OUTPUT" == "1" ]]; then
    rm -rf "$OUTPUT_PARENT"
  fi
}
trap cleanup EXIT
mkdir -p "$OUTPUT_DIR"

# build.sh is the single source of truth for linker, API-level and ARMv7 flags.
DIPLAY_JNI_LIBS_DIR="$OUTPUT_DIR" sh "$CRATE_DIR/build.sh"

READELF="$BIN/llvm-readelf"
NM="$BIN/llvm-nm"
ARM64="$OUTPUT_DIR/arm64-v8a/libdiplay_media.so"
ARMV7="$OUTPUT_DIR/armeabi-v7a/libdiplay_media.so"

check_library() {
  local abi="$1"
  local file="$2"
  local expected_class="$3"
  local expected_machine="$4"
  local header symbols java_count

  test -s "$file"
  header="$("$READELF" --file-header "$file")"
  grep -Eq "Class:[[:space:]]+$expected_class" <<<"$header"
  grep -Eq "Machine:[[:space:]]+$expected_machine" <<<"$header"

  symbols="$("$NM" --dynamic --defined-only "$file" | awk 'NF { print $NF }' | LC_ALL=C sort)"
  java_count="$(awk '/^Java_/ { count++ } END { print count + 0 }' <<<"$symbols")"
  if [[ "$java_count" != "491" ]]; then
    echo "$abi exports $java_count Java JNI methods; expected 491" >&2
    exit 1
  fi
  if ! grep -Fxq "JNI_OnLoad" <<<"$symbols"; then
    echo "$abi is missing JNI_OnLoad" >&2
    exit 1
  fi
  grep '^Java_' <<<"$symbols" > "$VERIFY_TMP/$abi-jni-symbols.txt"

  printf '%s: %s Java JNI exports + JNI_OnLoad; ELF %s / %s\n' \
    "$abi" "$java_count" "$expected_class" "$expected_machine"
  shasum -a 256 "$file"
}

check_library arm64-v8a "$ARM64" ELF64 AArch64
check_library armeabi-v7a "$ARMV7" ELF32 ARM
if ! cmp -s "$VERIFY_TMP/arm64-v8a-jni-symbols.txt" "$VERIFY_TMP/armeabi-v7a-jni-symbols.txt"; then
  echo "ARM64 and ARMv7 JNI export sets differ" >&2
  diff -u "$VERIFY_TMP/arm64-v8a-jni-symbols.txt" "$VERIFY_TMP/armeabi-v7a-jni-symbols.txt" || true
  exit 1
fi

git -C "$ROOT" rev-parse HEAD > "$OUTPUT_DIR/SOURCE-REVISION.txt"
echo "Rust Android native build passed: both ABIs are valid and expose the same JNI contract."
