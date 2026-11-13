#!/usr/bin/env bash
# Verifies the checked-in prebuilt C JNI libraries: ELF architecture, JNI export names,
# absence of legacy Java_com_shilapi_xcertplay_* symbols, and SHA-256 registered in NOTICE.md.
# Usage: ANDROID_NDK_HOME=/path/to/ndk/28.2.13676358 scripts/check_prebuilt_jni_libs.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NDK="${ANDROID_NDK_HOME:-${1:-}}"
if [[ -z "$NDK" ]]; then
  echo "Set ANDROID_NDK_HOME (NDK 28.2.13676358)" >&2
  exit 2
fi

HOST_TAG="$(uname -s | tr '[:upper:]' '[:lower:]')-x86_64"
BIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin"
READELF="$BIN/llvm-readelf"
NM="$BIN/llvm-nm"
NOTICE="$ROOT/shared/src/main/jniLibs/NOTICE.md"

class_for() { case "$1" in arm64-v8a) echo ELF64 ;; armeabi-v7a) echo ELF32 ;; x86_64) echo ELF64 ;; esac; }
mach_for() { case "$1" in arm64-v8a) echo AArch64 ;; armeabi-v7a) echo ARM ;; x86_64) echo "Advanced Micro Devices X86-64" ;; esac; }
src_for() { case "$1" in liblocal_hotspot_radio) echo local_hotspot_radio.c ;; libspeex_echo) echo speex_echo_jni.c ;; libdiplay_i2c) echo linux_i2c_jni.c ;; esac; }

fail=0
for abi in arm64-v8a armeabi-v7a x86_64; do
  for lib in liblocal_hotspot_radio libspeex_echo libdiplay_i2c; do
    file="$ROOT/shared/src/main/jniLibs/$abi/$lib.so"
    if [[ ! -s "$file" ]]; then
      echo "FAIL $abi/$lib.so is missing" >&2; fail=1; continue
    fi

    header="$("$READELF" --file-header "$file")"
    grep -Eq "Class:[[:space:]]+$(class_for "$abi")" <<<"$header" \
      || { echo "FAIL $abi/$lib.so wrong ELF class (want $(class_for "$abi"))" >&2; fail=1; }
    grep -Eq "Machine:[[:space:]]+$(mach_for "$abi")" <<<"$header" \
      || { echo "FAIL $abi/$lib.so wrong machine (want $(mach_for "$abi"))" >&2; fail=1; }

    symbols="$("$NM" --dynamic --defined-only "$file" | awk 'NF { print $NF }' | LC_ALL=C sort)"
    if grep -q '^Java_com_shilapi_xcertplay' <<<"$symbols"; then
      echo "FAIL $abi/$lib.so still exports legacy Java_com_shilapi_xcertplay_* symbols" >&2; fail=1
    fi
    # Compare the complete sorted set, not just the count: a wrong symbol could otherwise slip through
    # when another one happens to make the totals equal.
    exported_syms="$(grep '^Java_com_shihab_diplay_' <<<"$symbols" | LC_ALL=C sort -u)"
    source_syms="$(grep -ho 'Java_com_shihab_diplay_[A-Za-z0-9_]*' "$ROOT/shared/src/main/jni/$(src_for "$lib")" | LC_ALL=C sort -u)"
    if [[ "$exported_syms" != "$source_syms" ]]; then
      echo "FAIL $abi/$lib.so JNI export set differs from source $(src_for "$lib")" >&2
      diff -u <(printf '%s\n' "$source_syms") <(printf '%s\n' "$exported_syms") | sed 's/^/    /' >&2 || true
      fail=1
    fi
    new_count="$(printf '%s\n' "$exported_syms" | grep -c . || true)"

    expected_sha="$(python3 - "$NOTICE" "$abi" "$lib.so" <<'PY'
import re, sys
notice, abi, lib = sys.argv[1:4]
for line in open(notice, encoding="utf-8"):
    if f"| {abi} | {lib} |" in line:
        match = re.search(r"`([0-9a-fA-F]{64})`", line)
        if match:
            print(match.group(1).lower())
            break
PY
)"
    actual_sha="$(python3 - "$file" <<'PY'
import hashlib, sys
print(hashlib.sha256(open(sys.argv[1], "rb").read()).hexdigest())
PY
)"
    if [[ -z "$expected_sha" ]]; then
      echo "FAIL $abi/$lib.so is not registered in jniLibs/NOTICE.md" >&2; fail=1
    elif [[ "$actual_sha" != "$expected_sha" ]]; then
      echo "FAIL $abi/$lib.so sha256 $actual_sha does not match NOTICE $expected_sha" >&2; fail=1
    fi

    printf '%s: %s — %s/%s, %s renamed JNI exports, sha256 registered\n' \
      "$abi" "$lib.so" "$(class_for "$abi")" "$(mach_for "$abi")" "$new_count"
  done
done

if [[ "$fail" -ne 0 ]]; then
  echo "Prebuilt C JNI library check FAILED" >&2
  exit 1
fi
echo "Prebuilt C JNI libraries verified: ELF, JNI exports, no legacy prefix, NOTICE sha256."
