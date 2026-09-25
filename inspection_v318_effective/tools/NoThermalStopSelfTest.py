from pathlib import Path

root = Path(__file__).resolve().parents[1]
checks = {
    "RuntimeConstraints.java": root / "app/src/main/java/com/onsamiro/transcriber/RuntimeConstraints.java",
    "WorkCoordinator.java": root / "app/src/main/java/com/onsamiro/transcriber/WorkCoordinator.java",
    "Scheduler.java": root / "app/src/main/java/com/onsamiro/transcriber/Scheduler.java",
    "ScanJobService.java": root / "app/src/main/java/com/onsamiro/transcriber/ScanJobService.java",
    "MainActivity.java": root / "app/src/main/java/com/onsamiro/transcriber/MainActivity.java",
    "TranscriptionKeepAliveService.java": root / "app/src/main/java/com/onsamiro/transcriber/TranscriptionKeepAliveService.java",
}
forbidden = [
    "thermalSevere(",
    "retryAfterThermal",
    "thermal_retry",
    "THERMAL_RETRY_ID",
    "HEAT_RETRY_MS",
    "deferForThermal",
    "noteThermalWait",
    "발열 보호 중",
    "기기 발열로 잠시 대기",
    "기기 발열로 전사 잠시 대기",
]
for name, path in checks.items():
    text = path.read_text(encoding="utf-8")
    for token in forbidden:
        if token in text:
            raise SystemExit(f"FAIL: {name} still contains {token!r}")
print("PASS: app-level thermal stop/retry behavior is absent")
