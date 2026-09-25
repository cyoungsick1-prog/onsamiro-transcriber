#!/usr/bin/env python3
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit('usage: apply_v319.py <android-project-root>')

root = Path(sys.argv[1]).resolve()
if not (root / 'app/build.gradle').exists():
    raise SystemExit(f'not an Android project: {root}')

def replace_once(path, old, new):
    p = root / path
    s = p.read_text(encoding='utf-8')
    if old not in s:
        raise SystemExit(f'expected marker missing in {path}: {old[:160]!r}')
    p.write_text(s.replace(old, new, 1), encoding='utf-8')

def replace_all(path, old, new):
    p = root / path
    s = p.read_text(encoding='utf-8')
    if old not in s:
        raise SystemExit(f'expected marker missing in {path}: {old[:160]!r}')
    p.write_text(s.replace(old, new), encoding='utf-8')

# ---------------------------------------------------------------------------
# Version
# ---------------------------------------------------------------------------
replace_once('app/build.gradle',
             "versionCode 311800",
             "versionCode 311900")
replace_once('app/build.gradle',
             "versionName '3.1.18-manual-skip-final'",
             "versionName '3.1.19-candidate-discovery-final'")

# Existing regression tests pin the previous version string. Keep their behavior
# checks, but move the expected version to this build.
for rel in [
    'tools/FullFolderScanSelfTest.py',
    'tools/ManualSkipSelfTest.py',
    'tools/StallRecoverySelfTest.py',
]:
    p = root / rel
    s = p.read_text(encoding='utf-8')
    s = s.replace("versionName '3.1.18-manual-skip-final'",
                  "versionName '3.1.19-candidate-discovery-final'")
    p.write_text(s, encoding='utf-8')

p = root / 'scripts/static_audit.sh'
s = p.read_text(encoding='utf-8')
s = s.replace("versionName '3.1.18-manual-skip-final'",
              "versionName '3.1.19-candidate-discovery-final'")
p.write_text(s, encoding='utf-8')

# ---------------------------------------------------------------------------
# AppConfig
# - v3.1.19 rewinds the recovery checkpoint once so files missed by v3.1.18
#   are reconsidered from the original source-selection baseline.
# - Track the last expensive SAF recovery separately from the cheap index scan.
# ---------------------------------------------------------------------------
replace_once(
    'app/src/main/java/com/onsamiro/transcriber/AppConfig.java',
    '''    public long lastContentTriggerMs() { return p.getLong("last_content_trigger_ms", 0L); }
    public void setLastContentTriggerMs(long v) { p.edit().putLong("last_content_trigger_ms", v).apply(); }
    public long lastSuccessMs() { return p.getLong("last_success_ms", 0L); }
''',
    '''    public long lastContentTriggerMs() { return p.getLong("last_content_trigger_ms", 0L); }
    public void setLastContentTriggerMs(long v) { p.edit().putLong("last_content_trigger_ms", v).apply(); }
    public long lastSafRecoveryScanMs() { return p.getLong("last_saf_recovery_scan_ms", 0L); }
    public void setLastSafRecoveryScanMs(long v) { p.edit().putLong("last_saf_recovery_scan_ms", v).apply(); }
    public long lastSuccessMs() { return p.getLong("last_success_ms", 0L); }
''')

replace_once(
    'app/src/main/java/com/onsamiro/transcriber/AppConfig.java',
    '''                .putLong("last_recovery_scan_ms", now)
                .putLong("last_content_trigger_ms", 0L)
                .putBoolean("activate_after_setup", !p.getBoolean("user_stopped_realtime", false))
''',
    '''                .putLong("last_recovery_scan_ms", now)
                .putLong("last_content_trigger_ms", 0L)
                .putLong("last_saf_recovery_scan_ms", 0L)
                .putBoolean("activate_after_setup", !p.getBoolean("user_stopped_realtime", false))
''')

replace_once(
    'app/src/main/java/com/onsamiro/transcriber/AppConfig.java',
    '''    public void prepareFullFolderScanFixMigration() {
        int applied = p.getInt("full_folder_scan_fix_version", 0);
        if (applied >= 311400) return;
        long baseline = autoBaselineMs();
        SharedPreferences.Editor e = p.edit().putInt("full_folder_scan_fix_version", 311400);
        if (baseline > 0L) e.putLong("last_recovery_scan_ms", baseline);
        e.apply();
    }


    public long rangeStartMs() { return p.getLong("range_start", 0); }
''',
    '''    public void prepareFullFolderScanFixMigration() {
        int applied = p.getInt("full_folder_scan_fix_version", 0);
        if (applied >= 311400) return;
        long baseline = autoBaselineMs();
        SharedPreferences.Editor e = p.edit().putInt("full_folder_scan_fix_version", 311400);
        if (baseline > 0L) e.putLong("last_recovery_scan_ms", baseline);
        e.apply();
    }

    /**
     * v3.1.18 could report thousands of metadata rows yet still produce zero
     * candidates when provider timestamps/index coverage were incomplete.
     * Rewind once to the user's original source-selection baseline so a corrected
     * SAF scan can recover anything missed without importing pre-baseline history.
     */
    public void prepareCandidateDiscoveryFixMigration() {
        int applied = p.getInt("candidate_discovery_fix_version", 0);
        if (applied >= 311900) return;
        long baseline = autoBaselineMs();
        SharedPreferences.Editor e = p.edit()
                .putInt("candidate_discovery_fix_version", 311900)
                .putLong("last_saf_recovery_scan_ms", 0L);
        if (baseline > 0L) e.putLong("last_recovery_scan_ms", baseline);
        e.apply();
    }


    public long rangeStartMs() { return p.getLong("range_start", 0); }
''')

# ---------------------------------------------------------------------------
# MainActivity / keepalive startup
# - Apply migration from both foreground and background entry points.
# - If a period request survived an app/process restart, explicitly re-arm it.
# ---------------------------------------------------------------------------
replace_once(
    'app/src/main/java/com/onsamiro/transcriber/MainActivity.java',
    '''        cfg = new AppConfig(this);
        cfg.prepareRealtimeDetectionFixMigration();
        buildUi();
''',
    '''        cfg = new AppConfig(this);
        cfg.prepareRealtimeDetectionFixMigration();
        cfg.prepareFullFolderScanFixMigration();
        cfg.prepareCandidateDiscoveryFixMigration();
        buildUi();
''')

replace_once(
    'app/src/main/java/com/onsamiro/transcriber/MainActivity.java',
    '''        Scheduler.reschedule(this);
        TranscriptionKeepAliveService.start(this);
        refresh();
''',
    '''        Scheduler.reschedule(this);
        if (cfg.periodActive()) {
            // A package update/process death must not strand the period screen at
            // "대상 계산 중". Re-arm the durable historical continuation.
            Scheduler.historicalContinue(this, 1_000L);
        }
        TranscriptionKeepAliveService.start(this);
        refresh();
''')

replace_once(
    'app/src/main/java/com/onsamiro/transcriber/TranscriptionKeepAliveService.java',
    '''        cfg.prepareRealtimeDetectionFixMigration();
        cfg.prepareFullFolderScanFixMigration();
        try {
''',
    '''        cfg.prepareRealtimeDetectionFixMigration();
        cfg.prepareFullFolderScanFixMigration();
        cfg.prepareCandidateDiscoveryFixMigration();
        try {
''')

replace_once(
    'app/src/main/java/com/onsamiro/transcriber/TranscriptionKeepAliveService.java',
    '''                @Override public void onChange(boolean selfChange, Uri uri) {
                    main.removeCallbacks(contentKick);
                    main.postDelayed(contentKick, EVENT_DEBOUNCE_MS);
                }
''',
    '''                @Override public void onChange(boolean selfChange, Uri uri) {
                    // Remember that the selected source tree actually changed. Recovery can
                    // immediately use the authoritative SAF path instead of waiting for MediaStore.
                    new AppConfig(TranscriptionKeepAliveService.this)
                            .setLastContentTriggerMs(System.currentTimeMillis());
                    main.removeCallbacks(contentKick);
                    main.postDelayed(contentKick, EVENT_DEBOUNCE_MS);
                }
''')

# ---------------------------------------------------------------------------
# DocumentScanner
# - Period transcription uses the selected SAF folder as the source of truth.
# - Filename timestamps are accepted when provider LAST_MODIFIED is missing/stale.
# - Realtime SAF fallback runs immediately after a folder-change event and at a
#   controlled cadence otherwise, instead of trusting "visited > 0" as proof
#   that the index contains the selected recording files.
# ---------------------------------------------------------------------------
replace_once(
    'app/src/main/java/com/onsamiro/transcriber/DocumentScanner.java',
    '''import java.util.ArrayDeque;
import java.util.Locale;
''',
    '''import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
''')

replace_once(
    'app/src/main/java/com/onsamiro/transcriber/DocumentScanner.java',
    '''    private static final int HISTORICAL_MAX_DOCS = 20_000;
    private static final int MAX_DEPTH = 6;
    private static final long AUTO_OVERLAP_MS = 2L * 60L * 1000L;
    private static final int AUTO_SAF_FALLBACK_MAX_DOCS = Integer.MAX_VALUE; // selected recording tree must be exhausted
''',
    '''    private static final int HISTORICAL_MAX_DOCS = 100_000;
    private static final int MAX_DEPTH = 16;
    private static final long AUTO_OVERLAP_MS = 2L * 60L * 1000L;
    private static final long SAF_FALLBACK_INTERVAL_MS = 60_000L;
    private static final long CONTENT_EVENT_WINDOW_MS = 15_000L;
    private static final int AUTO_SAF_FALLBACK_MAX_DOCS = Integer.MAX_VALUE; // selected recording tree must be exhausted
    private static final Pattern FILE_TIME = Pattern.compile(
            "(?<!\\d)(20\\d{6})[ _.-]?([0-2]\\d[0-5]\\d[0-5]\\d)(?!\\d)");
''')

# Direct content-trigger: an actual file-change URI is stronger evidence than an
# unreliable old mtime. Use filename time when available and do not reject it
# merely because the provider reports a stale modified time.
replace_once(
    'app/src/main/java/com/onsamiro/transcriber/DocumentScanner.java',
    '''            if (mod > 0 && cfg.autoBaselineMs() > 0 && mod < cfg.autoBaselineMs()) {
                r.note = "기준선 이전 기존 파일 변경 무시";
                return r;
            }

            Uri doc = changedUri;
''',
    '''            long filenameTime = filenameEventTime(name);
            long eventTime = filenameTime > 0L ? filenameTime : mod;

            Uri doc = changedUri;
''')

replace_once(
    'app/src/main/java/com/onsamiro/transcriber/DocumentScanner.java',
    '''            long callTime = mod > 0 ? mod : System.currentTimeMillis();
            store.upsertDiscovered(doc.toString(), safeName(name), size, mod, callTime, contactHint(name));
''',
    '''            long callTime = eventTime > 0L ? eventTime : System.currentTimeMillis();
            store.upsertDiscovered(doc.toString(), safeName(name), size, mod, callTime, contactHint(name));
''')

replace_once(
    'app/src/main/java/com/onsamiro/transcriber/DocumentScanner.java',
    '''        // Samsung call-recording folders and some SAF providers are not always mirrored into
        // MediaStore. In that case the old implementation reported "metadata 0" forever and
        // never discovered a new recording. Fall back to a bounded metadata-only SAF scan of
        // the selected recording tree. This reads names/size/mtime only, never audio bytes.
        boolean needSafFallback = !m.available || m.permissionMissing || m.visited == 0;
        if (needSafFallback) {
            long since = Math.max(baseline, Math.max(0L, previous - AUTO_OVERLAP_MS));
            Result saf = scanRange(since, started + 60_000L, false, AUTO_SAF_FALLBACK_MAX_DOCS);
            r.visited += saf.visited;
            r.discovered += saf.discovered;
            String prefix = m.note == null || m.note.isEmpty() ? "" : m.note + " · ";
            r.note = prefix + "녹음폴더 전체확인"
                    + (saf.note == null || saf.note.isEmpty() ? "" : " · " + saf.note);
        }

        // Advance the checkpoint only after a complete scan. If any safety limit or provider
''',
    '''        // MediaStore can be available and still omit Samsung call recordings. "visited > 0"
        // therefore does not prove that the selected folder is covered. The authoritative SAF
        // tree is checked immediately after a real source-change event and periodically when the
        // index produced no candidate.
        long lastSaf = cfg.lastSafRecoveryScanMs();
        long lastContent = cfg.lastContentTriggerMs();
        boolean sourceChangedRecently = lastContent > 0L
                && started >= lastContent
                && started - lastContent <= CONTENT_EVENT_WINDOW_MS;
        boolean safDue = lastSaf <= 0L || started - lastSaf >= SAF_FALLBACK_INTERVAL_MS;
        boolean indexNeedsHelp = !m.available || m.permissionMissing || m.discovered == 0;
        boolean needSafFallback = indexNeedsHelp && (sourceChangedRecently || safDue);
        if (needSafFallback) {
            long since = Math.max(baseline, Math.max(0L, previous - AUTO_OVERLAP_MS));
            Result saf = scanRange(since, started + 60_000L, false, AUTO_SAF_FALLBACK_MAX_DOCS);
            r.visited += saf.visited;
            r.discovered += saf.discovered;
            r.truncated = saf.truncated;
            if (!saf.truncated) cfg.setLastSafRecoveryScanMs(started);
            String prefix = m.note == null || m.note.isEmpty() ? "" : m.note + " · ";
            r.note = prefix + "녹음폴더 전체확인"
                    + (saf.note == null || saf.note.isEmpty() ? "" : " · " + saf.note);
        }

        // Advance the checkpoint only after a complete scan. If any safety limit or provider
''')

replace_once(
    'app/src/main/java/com/onsamiro/transcriber/DocumentScanner.java',
    '''    /** User-requested historical scan. Old files are allowed only in this explicit path. */
    public Result scanHistorical(long startMs, long endMs) {
        MediaDeltaScanner.Result m = new MediaDeltaScanner(c, store).scanRange(startMs, endMs);
        if (m.available && !m.permissionMissing) {
            Result r = new Result();
            r.visited = m.visited;
            r.discovered = m.discovered;
            r.note = m.note;
            return r;
        }
        return scanRange(startMs, endMs, true, HISTORICAL_MAX_DOCS);
    }
''',
    '''    /** User-requested historical scan. The selected SAF folder is authoritative. */
    public Result scanHistorical(long startMs, long endMs) {
        // Period transcription is an explicit user request, so correctness wins over the cheap
        // MediaStore index. Samsung call-recording folders can be absent from MediaStore even
        // while the index itself is available, which made v3.1.18 return zero candidates.
        Result saf = scanRange(startMs, endMs, true, HISTORICAL_MAX_DOCS);
        if (saf.discovered > 0 || saf.truncated) {
            saf.note = "선택 녹음폴더 직접확인"
                    + (saf.note == null || saf.note.isEmpty() ? "" : " · " + saf.note);
            return saf;
        }

        // If the document provider exposed no matching metadata at all, use MediaStore only as
        // a secondary compatibility path. Because SAF found zero, this cannot duplicate SAF jobs
        // from this scan.
        MediaDeltaScanner.Result m = new MediaDeltaScanner(c, store).scanRange(startMs, endMs);
        if (m.available && !m.permissionMissing && m.discovered > 0) {
            Result r = new Result();
            r.visited = saf.visited + m.visited;
            r.discovered = m.discovered;
            r.note = "선택 녹음폴더 0건 · 저발열 인덱스 보완";
            return r;
        }
        saf.note = "선택 녹음폴더 직접확인"
                + (saf.note == null || saf.note.isEmpty() ? "" : " · " + saf.note);
        return saf;
    }
''')

replace_once(
    'app/src/main/java/com/onsamiro/transcriber/DocumentScanner.java',
    '''                    if (!isAudio(name, mime)) continue;

                    if (historical) {
                        if (mod == 0 || mod < since || mod > until) continue;
                        long jobId = store.upsertDiscovered(doc.toString(), safeName(name), size, mod, mod, contactHint(name));
                        store.reactivateHistoricalIfSettled(jobId, mod, size);
                        r.discovered++;
                    } else {
                        // Never auto-import an undated old file from a broad scan. A direct
                        // content-trigger can still safely import an undated newly changed file.
                        if (mod <= 0 || mod < since || mod > until) continue;
                        store.upsertDiscovered(doc.toString(), safeName(name), size, mod, mod, contactHint(name));
                        r.discovered++;
                    }
''',
    '''                    if (!isAudio(name, mime)) continue;

                    // Samsung/external-storage providers occasionally expose stale or zero
                    // LAST_MODIFIED values. Call-recording filenames normally carry the actual
                    // YYYYMMDD_HHMMSS event time, so use that as the call-time source when present.
                    long filenameTime = filenameEventTime(name);
                    long eventTime = filenameTime > 0L ? filenameTime : mod;

                    if (historical) {
                        if (eventTime <= 0L || eventTime < since || eventTime > until) continue;
                        long jobId = store.upsertDiscovered(doc.toString(), safeName(name), size, mod, eventTime, contactHint(name));
                        store.reactivateHistoricalIfSettled(jobId, eventTime, size);
                        r.discovered++;
                    } else {
                        // Broad automatic scans still require a trustworthy event time. A direct
                        // content-trigger can separately import an undated newly changed file.
                        if (eventTime <= 0L || eventTime < since || eventTime > until) continue;
                        store.upsertDiscovered(doc.toString(), safeName(name), size, mod, eventTime, contactHint(name));
                        r.discovered++;
                    }
''')

replace_once(
    'app/src/main/java/com/onsamiro/transcriber/DocumentScanner.java',
    '''    private String contactHint(String n) {
        if (n == null) return "";
''',
    '''    private static long filenameEventTime(String name) {
        if (name == null || name.isEmpty()) return 0L;
        Matcher m = FILE_TIME.matcher(name);
        long best = 0L;
        while (m.find()) {
            try {
                SimpleDateFormat f = new SimpleDateFormat("yyyyMMddHHmmss", Locale.KOREA);
                f.setLenient(false);
                Date d = f.parse(m.group(1) + m.group(2));
                if (d != null && d.getTime() > best) best = d.getTime();
            } catch (Throwable ignored) {
            }
        }
        return best;
    }

    private String contactHint(String n) {
        if (n == null) return "";
''')

# ---------------------------------------------------------------------------
# Static regression test for the exact v3.1.18 failure mode.
# ---------------------------------------------------------------------------
(root / 'tools/CandidateDiscoverySelfTest.py').write_text(r'''from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "app/src/main/java/com/onsamiro/transcriber"
doc = (JAVA / "DocumentScanner.java").read_text(encoding="utf-8")
cfg = (JAVA / "AppConfig.java").read_text(encoding="utf-8")
main = (JAVA / "MainActivity.java").read_text(encoding="utf-8")
service = (JAVA / "TranscriptionKeepAliveService.java").read_text(encoding="utf-8")
build = (ROOT / "app/build.gradle").read_text(encoding="utf-8")

assert "versionName '3.1.19-candidate-discovery-final'" in build
assert "prepareCandidateDiscoveryFixMigration" in cfg
assert "candidate_discovery_fix_version" in cfg and "311900" in cfg
assert "last_saf_recovery_scan_ms" in cfg
assert "Result saf = scanRange(startMs, endMs, true, HISTORICAL_MAX_DOCS);" in doc
assert "selected SAF folder is authoritative" in doc
assert "filenameEventTime" in doc and "FILE_TIME" in doc
assert "eventTime = filenameTime > 0L ? filenameTime : mod" in doc
assert "indexNeedsHelp" in doc and "sourceChangedRecently" in doc and "safDue" in doc
assert "r.truncated = saf.truncated" in doc
assert "setLastContentTriggerMs(System.currentTimeMillis())" in service
assert "cfg.prepareCandidateDiscoveryFixMigration();" in main
assert "Scheduler.historicalContinue(this, 1_000L);" in main
print("CANDIDATE DISCOVERY REGRESSION PASS")
''', encoding='utf-8')

p = root / 'scripts/static_audit.sh'
s = p.read_text(encoding='utf-8')
if 'CandidateDiscoverySelfTest.py' not in s:
    s += '''
# v3.1.19 candidate discovery / historical SAF source-of-truth
grep -q "versionName '3.1.19-candidate-discovery-final'" app/build.gradle
grep -q 'prepareCandidateDiscoveryFixMigration' app/src/main/java/com/onsamiro/transcriber/AppConfig.java
grep -q 'filenameEventTime' app/src/main/java/com/onsamiro/transcriber/DocumentScanner.java
python3 ./tools/CandidateDiscoverySelfTest.py
'''
p.write_text(s, encoding='utf-8')

(root / 'V3119_CANDIDATE_DISCOVERY_FINAL.md').write_text('''# v3.1.19 Candidate Discovery Final

- Period transcription now scans the exact user-selected SAF recording folder first instead of trusting an available-but-incomplete MediaStore index.
- Samsung-style call-recording timestamps in filenames are used as the call event time when provider LAST_MODIFIED is stale or missing.
- Realtime recovery uses SAF immediately after a real folder-change event and periodically when the index finds no candidate.
- The v3.1.19 one-time migration rewinds the recovery checkpoint to the existing source-selection baseline so files missed by v3.1.18 are reconsidered.
- A period request that survives a process/package update is re-armed instead of remaining forever at 대상 계산 중.
- Existing v3.1.17 stall recovery and v3.1.18 manual-skip behavior are preserved.
''', encoding='utf-8')

print('Applied Onsamiro v3.1.19 candidate discovery final patch to', root)
