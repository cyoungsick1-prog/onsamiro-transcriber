#!/usr/bin/env bash
set -euo pipefail

APK="${1:-}"
[[ -f "$APK" ]] || { echo "APK missing: $APK" >&2; exit 2; }

SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
BT="$SDK_ROOT/build-tools/35.0.0"

[[ -x "$BT/apksigner" ]] || { echo "apksigner missing: $BT/apksigner" >&2; exit 3; }
[[ -x "$BT/zipalign" ]] || { echo "zipalign missing: $BT/zipalign" >&2; exit 3; }

"$BT/apksigner" verify --verbose "$APK"
"$BT/zipalign" -c -P 16 -v 4 "$APK"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
unzip -q "$APK" 'lib/*/*.so' -d "$TMP"

count=0
bad=0
while IFS= read -r -d '' so; do
  count=$((count+1))
  echo "checking $so"

  while IFS= read -r align; do
    hex="${align#0x}"

    if [[ ! "$hex" =~ ^[0-9A-Fa-f]+$ ]]; then
      echo "FAIL: invalid PT_LOAD alignment value: $align ($so)" >&2
      bad=1
      continue
    fi

    value=$((16#$hex))

    if (( value < 16384 )); then
      echo "FAIL: PT_LOAD alignment < 16KiB: $align ($so)" >&2
      bad=1
    fi
  done < <(readelf -lW "$so" | awk '$1=="LOAD" {print $NF}')
done < <(find "$TMP/lib" -type f -name '*.so' -print0)

[[ $count -gt 0 ]] || { echo "No native libraries found" >&2; exit 4; }

if (( bad != 0 )); then
  exit 1
fi

echo "APK_VERIFY PASS ($count native libraries)"
