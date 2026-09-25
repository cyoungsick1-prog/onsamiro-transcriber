from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "app/src/main/java/com/onsamiro/transcriber"
main = (JAVA / "MainActivity.java").read_text(encoding="utf-8")
work = (JAVA / "WorkCoordinator.java").read_text(encoding="utf-8")
store = (JAVA / "JobStore.java").read_text(encoding="utf-8")
bridge = (JAVA / "WhisperBridge.java").read_text(encoding="utf-8")
jni = (ROOT / "app/src/main/cpp/whisper_jni.cpp").read_text(encoding="utf-8")
build = (ROOT / "app/build.gradle").read_text(encoding="utf-8")

assert "versionName '3.1.18-manual-skip-final'" in build
assert '현재 파일 건너뛰기 →' in main
assert 'skipCurrentFile()' in main
assert 'markManualSkip(current.id' in main
assert 'requestSkipCurrent(current.id)' in main
assert 'markManualSkip(long id' in store
assert '사용자 건너뜀 · 재처리 필요' in store
assert 'isManualSkipped(long id)' in store
assert 'ACTIVE_JOB_ID' in work and 'MANUAL_SKIP_JOB_ID' in work
assert 'requestSkipCurrent(long jobId)' in work
assert 'manualSkipRequested(long jobId)' in work
assert 'finishManualSkip' in work
assert '__ONSAMIRO_USER_SKIP__' in bridge
assert 'nativeRequestUserSkip' in bridge and 'nativeClearUserSkip' in bridge
assert 'g_user_skip_requested' in jni
assert 'whisper_full aborted by user skip' in jni
assert '__ONSAMIRO_USER_SKIP__' in jni
print("MANUAL CURRENT-FILE SKIP STATIC REGRESSION PASS")
