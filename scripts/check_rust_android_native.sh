#!/usr/bin/env bash
# Builds and verifies the Rust Android libraries.
# Usage: scripts/check_rust_android_native.sh [abi ...]
#   no abi  -> build + verify every shipped ABI and require identical JNI exports
#   one abi -> build + verify just that ABI (arm64-v8a / armeabi-v7a / x86_64)
#   ARM64 is the default production package; ARMv7 is the fallback; x86_64 is test-only (emulator).
# Needs ANDROID_NDK_HOME (NDK 28.2.13676358), Rust 1.97.1 and the Android rust targets.
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
for compiler in aarch64-linux-android28-clang armv7a-linux-androideabi28-clang x86_64-linux-android28-clang; do
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

abi_target() {
  case "$1" in
    arm64-v8a) echo aarch64-linux-android ;;
    armeabi-v7a) echo armv7-linux-androideabi ;;
    x86_64) echo x86_64-linux-android ;;
    *) return 1 ;;
  esac
}
abi_class() { case "$1" in arm64-v8a) echo ELF64 ;; armeabi-v7a) echo ELF32 ;; x86_64) echo ELF64 ;; esac; }
abi_machine() {
  case "$1" in
    arm64-v8a) echo AArch64 ;;
    armeabi-v7a) echo ARM ;;
    x86_64) echo "Advanced Micro Devices X86-64" ;;
  esac
}

ARGS=()
while [[ "$#" -gt 0 ]]; do
  case "$1" in
    --abi)
      [[ "$#" -ge 2 ]] || { echo "--abi needs a value" >&2; exit 2; }
      ARGS+=("$2"); shift 2 ;;
    arm64-v8a|armeabi-v7a|x86_64)
      ARGS+=("$1"); shift ;;
    *)
      echo "Unknown argument: $1" >&2; exit 2 ;;
  esac
done
if [[ "${#ARGS[@]}" -eq 0 ]]; then
  SELECTED_ABIS=(arm64-v8a armeabi-v7a x86_64)
else
  SELECTED_ABIS=("${ARGS[@]}")
fi

BUILD_TARGETS=()
for abi in "${SELECTED_ABIS[@]}"; do
  target="$(abi_target "$abi")"
  BUILD_TARGETS+=("$target")
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
DIPLAY_JNI_LIBS_DIR="$OUTPUT_DIR" sh "$CRATE_DIR/build.sh" "${BUILD_TARGETS[@]}"

READELF="$BIN/llvm-readelf"
NM="$BIN/llvm-nm"

check_library() {
  local abi="$1"
  local file="$2"
  local expected_class
  local expected_machine
  local header symbols java_count

  expected_class="$(abi_class "$abi")"
  expected_machine="$(abi_machine "$abi")"

  test -s "$file"
  header="$("$READELF" --file-header "$file")"
  grep -Eq "Class:[[:space:]]+$expected_class" <<<"$header"
  grep -Eq "Machine:[[:space:]]+$expected_machine" <<<"$header"

  symbols="$("$NM" --dynamic --defined-only "$file" | awk 'NF { print $NF }' | LC_ALL=C sort)"
  java_count="$(awk '/^Java_/ { count++ } END { print count + 0 }' <<<"$symbols")"
  if [[ "$java_count" != "494" ]]; then
    echo "$abi exports $java_count Java JNI methods; expected 494" >&2
    exit 1
  fi
  if ! grep -Fxq "JNI_OnLoad" <<<"$symbols"; then
    echo "$abi is missing JNI_OnLoad" >&2
    exit 1
  fi
  grep '^Java_' <<<"$symbols" > "$VERIFY_TMP/$abi-jni-symbols.txt"
  # Keep the export list next to the library so a downstream job can compare ABIs.
  cp "$VERIFY_TMP/$abi-jni-symbols.txt" "$OUTPUT_DIR/$abi-jni-symbols.txt"

  printf '%s: %s Java JNI exports + JNI_OnLoad; ELF %s / %s\n' \
    "$abi" "$java_count" "$expected_class" "$expected_machine"
  shasum -a 256 "$file"
}

for abi in "${SELECTED_ABIS[@]}"; do
  target="$(abi_target "$abi")"
  check_library "$abi" "$OUTPUT_DIR/$abi/libdiplay_media.so"
done

if [[ "${#SELECTED_ABIS[@]}" -gt 1 ]]; then
  # Every ABI must expose exactly the same Java JNI contract; compare each against the first.
  reference="${SELECTED_ABIS[0]}"
  for abi in "${SELECTED_ABIS[@]:1}"; do
    if ! cmp -s "$VERIFY_TMP/$reference-jni-symbols.txt" "$VERIFY_TMP/$abi-jni-symbols.txt"; then
      echo "$reference and $abi JNI export sets differ" >&2
      diff -u "$VERIFY_TMP/$reference-jni-symbols.txt" "$VERIFY_TMP/$abi-jni-symbols.txt" || true
      exit 1
    fi
  done
fi

git -C "$ROOT" rev-parse HEAD > "$OUTPUT_DIR/SOURCE-REVISION.txt"
echo "Rust Android native build passed: ${SELECTED_ABIS[*]} valid and JNI contract checked."
