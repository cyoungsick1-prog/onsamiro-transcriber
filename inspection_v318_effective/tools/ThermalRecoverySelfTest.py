from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "app/src/main/java/com/onsamiro/transcriber"
coordinator = (JAVA / "WorkCoordinator.java").read_text(encoding="utf-8")
scheduler = (JAVA / "Scheduler.java").read_text(encoding="utf-8")
service = (JAVA / "ScanJobService.java").read_text(encoding="utf-8")
runtime = (JAVA / "RuntimeConstraints.java").read_text(encoding="utf-8")
main = (JAVA / "MainActivity.java").read_text(encoding="utf-8")
model_manager = (JAVA / "ModelManager.java").read_text(encoding="utf-8")
keepalive = (JAVA / "TranscriptionKeepAliveService.java").read_text(encoding="utf-8")

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
combined = "\n".join([coordinator, scheduler, service, runtime, main, keepalive])
for token in forbidden:
    assert token not in combined, f"app-level thermal stop behavior remains: {token}"

validate_existing = model_manager[
    model_manager.index("public static boolean validateExisting"):
    model_manager.index("private static String sha256")
]
assert "WhisperBridge.validateModel" not in validate_existing, (
    "background model validation must not load the native model"
)

pending = coordinator[
    coordinator.index("private void processPending"):
    coordinator.index("private void processOne")
]
assert pending.index("if (jobs.isEmpty()) break;") < pending.index(
    "new WhisperBridge(cfg.modelPath())"
), "empty scans must not load Whisper"
assert "setMinimumLatency(delay)" in scheduler

print("NO-THERMAL-STOP STATIC REGRESSION PASS")
