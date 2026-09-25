from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "app/src/main/java/com/onsamiro/transcriber"
bridge = (JAVA / "WhisperBridge.java").read_text(encoding="utf-8")
work = (JAVA / "WorkCoordinator.java").read_text(encoding="utf-8")
store = (JAVA / "JobStore.java").read_text(encoding="utf-8")
jni = (ROOT / "app/src/main/cpp/whisper_jni.cpp").read_text(encoding="utf-8")
build = (ROOT / "app/build.gradle").read_text(encoding="utf-8")

assert "versionName '3.1.18-manual-skip-final'" in build
assert "STALL_TIMEOUT_MS = 4L * 60_000L" in bridge
assert "HARD_TIMEOUT_MS = 12L * 60_000L" in bridge
assert "TIMEOUT_SENTINEL" in bridge and "isUnhealthy()" in bridge
assert "p.progress_callback = on_progress" in jni
assert "p.abort_callback = should_abort" in jni
assert "__ONSAMIRO_TIMEOUT__" in jni
assert "deferStalledSegment" in store
assert "2 * 60_000L" in store and "5 * 60_000L" in store and "15 * 60_000L" in store
assert "다른 파일 계속 처리" in store
assert "whisper.isUnhealthy()" in work
assert "store.deferStalledSegment" in work
print("STALL RECOVERY STATIC REGRESSION PASS")
