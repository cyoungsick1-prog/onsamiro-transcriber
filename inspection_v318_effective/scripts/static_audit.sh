#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MAN="$ROOT/app/src/main/AndroidManifest.xml"
CMAKE="$ROOT/app/src/main/cpp/CMakeLists.txt"
fail(){ echo "FAIL: $*"; exit 1; }
for bad in READ_CALL_LOG READ_SMS SEND_SMS SYSTEM_ALERT_WINDOW BIND_ACCESSIBILITY_SERVICE MANAGE_EXTERNAL_STORAGE RECORD_AUDIO QUERY_ALL_PACKAGES REQUEST_INSTALL_PACKAGES POST_NOTIFICATIONS; do
  if grep -q "$bad" "$MAN"; then fail "unexpected sensitive permission/API marker $bad"; fi
done
grep -q 'usesCleartextTraffic="false"' "$MAN" || fail "cleartext not disabled"
grep -q 'max-page-size=16384' "$CMAKE" || fail "16 KiB linker flag missing"
grep -q 'common-page-size=16384' "$CMAKE" || fail "16 KiB common page flag missing"
grep -q 'android.permission.FOREGROUND_SERVICE' "$MAN" || fail "foreground service permission missing"
grep -q 'android.permission.FOREGROUND_SERVICE_SPECIAL_USE' "$MAN" || fail "specialUse foreground service permission missing"
grep -q 'android:name=".TranscriptionKeepAliveService"' "$MAN" || fail "keep-alive service missing"
grep -q 'android:foregroundServiceType="specialUse"' "$MAN" || fail "specialUse type missing"
if find "$ROOT" -type f \( -name '*.p12' -o -name '*.jks' -o -name '*.keystore' -o -name '*.key' \) | grep -q .; then fail "signing/private key found in source tree"; fi
if find "$ROOT" -type f -name '*.so' | grep -q .; then fail "prebuilt native .so found; clean source must rebuild native code"; fi
python3 "$ROOT/tools/ThermalRecoverySelfTest.py"
python3 "$ROOT/tools/BackgroundRecoverySelfTest.py"
python3 "$ROOT/tools/BackgroundContinuousSelfTest.py"
grep -q 'AUTO_SAF_FALLBACK_MAX_DOCS' "$ROOT/app/src/main/java/com/onsamiro/transcriber/DocumentScanner.java" || fail "SAF realtime fallback missing"
grep -q 'runDirectRecovery(false)' "$ROOT/app/src/main/java/com/onsamiro/transcriber/TranscriptionKeepAliveService.java" || fail "direct foreground recovery missing"
echo "STATIC_AUDIT PASS"

# v3.1.17 stalled native transcription recovery
grep -q "versionName '3.1.18-manual-skip-final'" app/build.gradle
grep -q 'STALL_TIMEOUT_MS = 4L \* 60_000L' app/src/main/java/com/onsamiro/transcriber/WhisperBridge.java
grep -q 'HARD_TIMEOUT_MS = 12L \* 60_000L' app/src/main/java/com/onsamiro/transcriber/WhisperBridge.java
grep -q 'deferStalledSegment' app/src/main/java/com/onsamiro/transcriber/JobStore.java
grep -q 'p.abort_callback = should_abort' app/src/main/cpp/whisper_jni.cpp
python3 ./tools/StallRecoverySelfTest.py

# v3.1.18 manual current-file skip
python3 ./tools/ManualSkipSelfTest.py
