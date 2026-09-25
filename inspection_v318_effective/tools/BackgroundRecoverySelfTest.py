from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "app/src/main/java/com/onsamiro/transcriber"
manifest = (ROOT / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
service = (JAVA / "TranscriptionKeepAliveService.java").read_text(encoding="utf-8")
main = (JAVA / "MainActivity.java").read_text(encoding="utf-8")
boot = (JAVA / "BootReceiver.java").read_text(encoding="utf-8")
scheduler = (JAVA / "Scheduler.java").read_text(encoding="utf-8")
settings = (JAVA / "SettingsActivity.java").read_text(encoding="utf-8")
config = (JAVA / "AppConfig.java").read_text(encoding="utf-8")

required_manifest = [
    'android.permission.FOREGROUND_SERVICE',
    'android.permission.FOREGROUND_SERVICE_SPECIAL_USE',
    'android.permission.WAKE_LOCK',
    'android:name=".TranscriptionKeepAliveService"',
    'android:stopWithTask="false"',
    'android:foregroundServiceType="specialUse"',
    'android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE',
    'android.intent.action.BOOT_COMPLETED',
    'android.intent.action.MY_PACKAGE_REPLACED',
]
for token in required_manifest:
    assert token in manifest, f"manifest missing: {token}"

assert "POST_NOTIFICATIONS" not in manifest, "notification runtime permission should remain unnecessary"

required_service = [
    "START_STICKY",
    "onTaskRemoved",
    "startForegroundService",
    "startForeground(",
    "FOREGROUND_SERVICE_TYPE_SPECIAL_USE",
    "ContentObserver",
    "registerContentObserver",
    "registerDefaultNetworkCallback",
    "ACTION_DEVICE_IDLE_MODE_CHANGED",
    "PARTIAL_WAKE_LOCK",
    "TRIGGER_WAKELOCK_MS",
    "WATCHDOG_MS = 45_000L",
    "ACTIVE_WAKELOCK_MS",
    "runDirectRecovery(false)",
    "Executors.newSingleThreadExecutor",
    "Scheduler.ensureRealtimeScheduled",
    "Scheduler.immediateRecovery",
]
for token in required_service:
    assert token in service, f"service missing: {token}"

assert "TranscriptionKeepAliveService.start(this);" in main
assert "TranscriptionKeepAliveService.startWithRecovery(this);" in main
assert "TranscriptionKeepAliveService.stop(this);" in main
assert "TranscriptionKeepAliveService.startWithRecovery(app);" in boot
assert "ensureRealtimeScheduled" in scheduler
assert "serviceHeartbeatMs" in config
assert "prepareRealtimeDetectionFixMigration" in config
assert "prepareRealtimeDetectionFixMigration" in service
assert "백그라운드 보호" in settings

# Preserve durable job-based recovery as the second safety net.
for token in ["setPersisted(true)", "scheduleContentWatch", "schedulePeriodicRecovery"]:
    assert token in scheduler, f"scheduler fallback missing: {token}"

# The foreground guard performs a metadata-only bounded probe roughly every 45 seconds.
assert "WATCHDOG_MS = 45_000L" in service
assert "postDelayed(this, WATCHDOG_MS)" in service
scanner = (JAVA / "DocumentScanner.java").read_text(encoding="utf-8")
assert "AUTO_SAF_FALLBACK_MAX_DOCS = Integer.MAX_VALUE" in scanner
assert "녹음폴더 전체확인" in scanner
assert "if (!r.truncated) cfg.setLastRecoveryScanMs(started);" in scanner
assert "prepareFullFolderScanFixMigration" in config
assert "prepareFullFolderScanFixMigration" in service


print("BACKGROUND RECOVERY STATIC REGRESSION PASS")
