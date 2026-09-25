from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
JAVA=ROOT/"app/src/main/java/com/onsamiro/transcriber"
scanner=(JAVA/"DocumentScanner.java").read_text(encoding="utf-8")
config=(JAVA/"AppConfig.java").read_text(encoding="utf-8")
service=(JAVA/"TranscriptionKeepAliveService.java").read_text(encoding="utf-8")
build=(ROOT/"app/build.gradle").read_text(encoding="utf-8")
assert "versionName '3.1.18-manual-skip-final'" in build
assert "AUTO_SAF_FALLBACK_MAX_DOCS = Integer.MAX_VALUE" in scanner
assert "녹음폴더 전체확인" in scanner
assert "if (d.depth < MAX_DEPTH)" in scanner
assert "if (!r.truncated) cfg.setLastRecoveryScanMs(started);" in scanner
assert "기준시각 유지" in scanner
assert "prepareFullFolderScanFixMigration" in config
assert "full_folder_scan_fix_version" in config
assert "prepareFullFolderScanFixMigration" in service
assert "1500" not in scanner, "old 1500-document hard cap must not remain"
print("FULL FOLDER SCAN REGRESSION PASS")
