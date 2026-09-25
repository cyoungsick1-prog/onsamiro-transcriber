from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "app/src/main/java/com/onsamiro/transcriber"
service = (JAVA / "TranscriptionKeepAliveService.java").read_text(encoding="utf-8")
work = (JAVA / "WorkCoordinator.java").read_text(encoding="utf-8")
runtime = (JAVA / "RuntimeConstraints.java").read_text(encoding="utf-8")

assert "ACTIVE_WAKELOCK_MS = Integer.MAX_VALUE" in service, "active transcription wakelock still has short timeout"
assert "lock.acquire(ACTIVE_WAKELOCK_MS);" in service, "active transcription wakelock not acquired"
assert "if (lock != null && lock.isHeld())" in service and "lock.release()" in service, "active wakelock must be released in finally"
assert "PARTIAL_WAKE_LOCK" in service
assert "START_STICKY" in service
assert "WATCHDOG_MS = 45_000L" in service
assert "runDirectRecovery(false)" in service
assert "return stop || cfg.paused();" in work, "unexpected automatic stop condition added"
for token in ["thermalSevere(", "retryAfterThermal", "deferForThermal", "발열 보호 중", "기기 발열로 전사 잠시 대기"]:
    assert token not in service + work + runtime, f"thermal stop marker remains: {token}"
print("BACKGROUND CONTINUOUS TRANSCRIPTION STATIC REGRESSION PASS")
