#!/usr/bin/env python3
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit('usage: apply_v318.py <android-project-root>')

root = Path(sys.argv[1]).resolve()
if not (root / 'app/build.gradle').exists():
    raise SystemExit(f'not an Android project: {root}')

def replace_once(path, old, new):
    p = root / path
    s = p.read_text(encoding='utf-8')
    if old not in s:
        raise SystemExit(f'expected marker missing in {path}: {old[:120]!r}')
    p.write_text(s.replace(old, new, 1), encoding='utf-8')

def replace_all(path, old, new):
    p = root / path
    s = p.read_text(encoding='utf-8')
    if old not in s:
        raise SystemExit(f'expected marker missing in {path}: {old[:120]!r}')
    p.write_text(s.replace(old, new), encoding='utf-8')

# Version: preserve all v3.1.17 stall recovery behavior and add manual current-file skip.
replace_once('app/build.gradle',
             "versionCode 311700",
             "versionCode 311800")
replace_once('app/build.gradle',
             "versionName '3.1.17-stall-recovery-final'",
             "versionName '3.1.18-manual-skip-final'")

# ---------------------------------------------------------------------------
# JobStore: manual skip is PARTIAL, never DONE. Original audio and completed
# segment checkpoints remain. History's existing "이 통화 다시 시도" can reset it.
# ---------------------------------------------------------------------------
replace_once(
    'app/src/main/java/com/onsamiro/transcriber/JobStore.java',
    '    public void exclude(long id) { updateJob(id,Status.USER_EXCLUDED,"사용자 제외","",""); }\n',
    '''    public void markManualSkip(long id, String reason) {
        ContentValues v = new ContentValues();
        v.put("status", Status.PARTIAL);
        v.put("stage", "사용자 건너뜀 · 재처리 필요");
        v.put("wait_reason", "");
        v.put("fail_reason", nz(reason));
        v.put("next_attempt_ms", 0);
        v.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().update(
                "jobs", v,
                "id=? AND status IN (?,?,?,?,?)",
                new String[]{Long.toString(id), Status.FETCHING, Status.TRANSCRIBING,
                        Status.QUALITY_CHECK, Status.SAVING, Status.PARTIAL});
    }

    public boolean isManualSkipped(long id) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT status,stage FROM jobs WHERE id=?",
                new String[]{Long.toString(id)})) {
            if (!c.moveToFirst()) return false;
            String status = c.getString(0);
            String stage = c.getString(1);
            return Status.PARTIAL.equals(status)
                    && stage != null
                    && stage.startsWith("사용자 건너뜀");
        }
    }

    public void exclude(long id) { updateJob(id,Status.USER_EXCLUDED,"사용자 제외","",""); }
''')

# ---------------------------------------------------------------------------
# WhisperBridge: add an explicit user-skip sentinel that is separate from stall
# timeout. The context is marked unhealthy so the next file gets a fresh model.
# ---------------------------------------------------------------------------
replace_once(
    'app/src/main/java/com/onsamiro/transcriber/WhisperBridge.java',
    '    private static final String TIMEOUT_SENTINEL = "__ONSAMIRO_TIMEOUT__";\n',
    '    private static final String TIMEOUT_SENTINEL = "__ONSAMIRO_TIMEOUT__";\n'
    '    private static final String USER_SKIP_SENTINEL = "__ONSAMIRO_USER_SKIP__";\n')

replace_once(
    'app/src/main/java/com/onsamiro/transcriber/WhisperBridge.java',
    '''        if (TIMEOUT_SENTINEL.equals(s)) {
            unhealthy = true;
            throw new IllegalStateException("전사 구간 시간초과");
        }
''',
    '''        if (USER_SKIP_SENTINEL.equals(s)) {
            unhealthy = true;
            throw new IllegalStateException("사용자 현재 파일 건너뜀");
        }
        if (TIMEOUT_SENTINEL.equals(s)) {
            unhealthy = true;
            throw new IllegalStateException("전사 구간 시간초과");
        }
''')

replace_once(
    'app/src/main/java/com/onsamiro/transcriber/WhisperBridge.java',
    '''    public boolean isUnhealthy() {
        return unhealthy;
    }

    @Override public synchronized void close() {
''',
    '''    public boolean isUnhealthy() {
        return unhealthy;
    }

    public static void requestUserSkip() {
        nativeRequestUserSkip();
    }

    public static void clearUserSkip() {
        nativeClearUserSkip();
    }

    @Override public synchronized void close() {
''')

replace_once(
    'app/src/main/java/com/onsamiro/transcriber/WhisperBridge.java',
    '''    private static native void nativeFree(long handle);
}
''',
    '''    private static native void nativeRequestUserSkip();
    private static native void nativeClearUserSkip();
    private static native void nativeFree(long handle);
}
''')

# ---------------------------------------------------------------------------
# Native whisper abort: the UI can interrupt the current native whisper_full()
# immediately. The flag is cleared by WorkCoordinator only after the requested
# job has left the active slot, preventing the next file from inheriting it.
# ---------------------------------------------------------------------------
replace_once(
    'app/src/main/cpp/whisper_jni.cpp',
    '#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR,TAG,__VA_ARGS__)\n',
    '#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR,TAG,__VA_ARGS__)\n\n'
    'static std::atomic<bool> g_user_skip_requested {false};\n')

replace_once(
    'app/src/main/cpp/whisper_jni.cpp',
    '''static bool should_abort(void *user_data) {
    auto *w = reinterpret_cast<TranscriptionWatchdog *>(user_data);
    if (!w) return false;
''',
    '''static bool should_abort(void *user_data) {
    if (g_user_skip_requested.load(std::memory_order_relaxed)) return true;
    auto *w = reinterpret_cast<TranscriptionWatchdog *>(user_data);
    if (!w) return false;
''')

replace_once(
    'app/src/main/cpp/whisper_jni.cpp',
    '''    int rc=whisper_full(ctx,p,pcm,n);
    env->ReleaseFloatArrayElements(audio,pcm,JNI_ABORT);

    if(watchdog.timed_out.load(std::memory_order_relaxed)){
''',
    '''    int rc=whisper_full(ctx,p,pcm,n);
    env->ReleaseFloatArrayElements(audio,pcm,JNI_ABORT);

    if(g_user_skip_requested.load(std::memory_order_relaxed)){
        LOGE("whisper_full aborted by user skip: rc=%d progress=%d", rc, watchdog.last_progress.load(std::memory_order_relaxed));
        return env->NewStringUTF("__ONSAMIRO_USER_SKIP__");
    }
    if(watchdog.timed_out.load(std::memory_order_relaxed)){
''')

replace_once(
    'app/src/main/cpp/whisper_jni.cpp',
    '''extern "C" JNIEXPORT void JNICALL Java_com_onsamiro_transcriber_WhisperBridge_nativeFree(JNIEnv*,jclass,jlong ptr){auto *ctx=reinterpret_cast<whisper_context*>(ptr);if(ctx)whisper_free(ctx);}
''',
    '''extern "C" JNIEXPORT void JNICALL Java_com_onsamiro_transcriber_WhisperBridge_nativeRequestUserSkip(JNIEnv*,jclass){
    g_user_skip_requested.store(true, std::memory_order_relaxed);
}
extern "C" JNIEXPORT void JNICALL Java_com_onsamiro_transcriber_WhisperBridge_nativeClearUserSkip(JNIEnv*,jclass){
    g_user_skip_requested.store(false, std::memory_order_relaxed);
}
extern "C" JNIEXPORT void JNICALL Java_com_onsamiro_transcriber_WhisperBridge_nativeFree(JNIEnv*,jclass,jlong ptr){auto *ctx=reinterpret_cast<whisper_context*>(ptr);if(ctx)whisper_free(ctx);}
''')

# ---------------------------------------------------------------------------
# WorkCoordinator: track the exact active job. A skip request can only abort
# that same job, so a race cannot accidentally cancel the following file.
# ---------------------------------------------------------------------------
replace_once(
    'app/src/main/java/com/onsamiro/transcriber/WorkCoordinator.java',
    'import java.util.concurrent.atomic.AtomicBoolean;\n',
    'import java.util.concurrent.atomic.AtomicBoolean;\n'
    'import java.util.concurrent.atomic.AtomicLong;\n')

replace_once(
    'app/src/main/java/com/onsamiro/transcriber/WorkCoordinator.java',
    '    private static final AtomicBoolean PROCESS_RUNNING = new AtomicBoolean(false);\n',
    '''    private static final AtomicBoolean PROCESS_RUNNING = new AtomicBoolean(false);
    private static final AtomicLong ACTIVE_JOB_ID = new AtomicLong(-1L);
    private static final AtomicLong MANUAL_SKIP_JOB_ID = new AtomicLong(-1L);
''')

replace_once(
    'app/src/main/java/com/onsamiro/transcriber/WorkCoordinator.java',
    '''    public void requestStop(String reason) {
        stop = true;
        stopReason = reason == null ? "" : reason;
    }
''',
    '''    public static boolean requestSkipCurrent(long jobId) {
        if (jobId <= 0L || ACTIVE_JOB_ID.get() != jobId) return false;
        MANUAL_SKIP_JOB_ID.set(jobId);
        WhisperBridge.requestUserSkip();
        return true;
    }

    public void requestStop(String reason) {
        stop = true;
        stopReason = reason == null ? "" : reason;
    }
''')

replace_once(
    'app/src/main/java/com/onsamiro/transcriber/WorkCoordinator.java',
    '''                    // Load the native Whisper model only when there is actual pending work.
                    if (whisper == null) whisper = new WhisperBridge(cfg.modelPath());
                    processOne(j, whisper);
                    processed++;
                    // A native timeout/error gets a fresh model context before the next file.
                    // This prevents one damaged decoder state from contaminating the queue.
                    if (whisper.isUnhealthy()) {
                        whisper.close();
                        whisper = null;
                    }
''',
    '''                    // Load the native Whisper model only when there is actual pending work.
                    ACTIVE_JOB_ID.set(j.id);
                    try {
                        if (whisper == null) whisper = new WhisperBridge(cfg.modelPath());
                        processOne(j, whisper);
                        processed++;
                    } finally {
                        ACTIVE_JOB_ID.compareAndSet(j.id, -1L);
                        if (MANUAL_SKIP_JOB_ID.compareAndSet(j.id, -1L)) {
                            WhisperBridge.clearUserSkip();
                        }
                    }
                    // A native timeout/error or user abort gets a fresh model context before the next file.
                    if (whisper != null && whisper.isUnhealthy()) {
                        whisper.close();
                        whisper = null;
                    }
''')

replace_once(
    'app/src/main/java/com/onsamiro/transcriber/WorkCoordinator.java',
    '''    private void processOne(JobRecord j, WhisperBridge whisper) {
        try {
''',
    '''    private boolean manualSkipRequested(long jobId) {
        return MANUAL_SKIP_JOB_ID.get() == jobId || store.isManualSkipped(jobId);
    }

    private void finishManualSkip(JobRecord j, SegmentRecord s, String detail) {
        if (s != null) {
            s.status = Status.RETRY_WAIT;
            s.error = "사용자 건너뜀";
            store.saveSegment(s);
        }
        store.markManualSkip(j.id, "현재 파일 건너뜀 · " + detail);
        MANUAL_SKIP_JOB_ID.compareAndSet(j.id, -1L);
        WhisperBridge.clearUserSkip();
    }

    private void processOne(JobRecord j, WhisperBridge whisper) {
        try {
''')

replace_once(
    'app/src/main/java/com/onsamiro/transcriber/WorkCoordinator.java',
    '''            Uri uri = Uri.parse(j.sourceUri);
            if (!waitUntilStable(j, uri)) return;

            store.updateJob(j.id, Status.FETCHING, "원본 정보 확인", "", "");
''',
    '''            Uri uri = Uri.parse(j.sourceUri);
            if (!waitUntilStable(j, uri)) return;
            if (manualSkipRequested(j.id)) {
                finishManualSkip(j, null, "전사 시작 전");
                return;
            }

            store.updateJob(j.id, Status.FETCHING, "원본 정보 확인", "", "");
''')

replace_once(
    'app/src/main/java/com/onsamiro/transcriber/WorkCoordinator.java',
    '''            store.setMetadata(j.id, dur, total);
            store.ensureSegments(j.id, dur, SEGMENT_MS);

            List<SegmentRecord> segs = store.pendingSegments(j.id);
''',
    '''            store.setMetadata(j.id, dur, total);
            store.ensureSegments(j.id, dur, SEGMENT_MS);
            if (manualSkipRequested(j.id)) {
                finishManualSkip(j, null, "원본 확인 후");
                return;
            }

            List<SegmentRecord> segs = store.pendingSegments(j.id);
''')

replace_once(
    'app/src/main/java/com/onsamiro/transcriber/WorkCoordinator.java',
    '''            for (SegmentRecord s : segs) {
                if (shouldStop()) {
''',
    '''            for (SegmentRecord s : segs) {
                if (manualSkipRequested(j.id)) {
                    finishManualSkip(j, s, "구간 " + (s.index + 1) + "/" + total + " 시작 전");
                    return;
                }
                if (shouldStop()) {
''')

replace_once(
    'app/src/main/java/com/onsamiro/transcriber/WorkCoordinator.java',
    '''                    AudioChunkDecoder.Decoded d = AudioChunkDecoder.decodeRange(c, uri, s.startMs, s.endMs);
                    // Background work uses one Whisper thread. An explicit historical request may
''',
    '''                    AudioChunkDecoder.Decoded d = AudioChunkDecoder.decodeRange(c, uri, s.startMs, s.endMs);
                    if (manualSkipRequested(j.id)) {
                        finishManualSkip(j, s, "구간 " + (s.index + 1) + "/" + total + " 디코딩 후");
                        return;
                    }
                    // Background work uses one Whisper thread. An explicit historical request may
''')

replace_once(
    'app/src/main/java/com/onsamiro/transcriber/WorkCoordinator.java',
    '''                } catch (Throwable x) {
                    s.retryCount++;
                    s.error = safe(x.getMessage());
                    boolean stalled = whisper.isUnhealthy() || s.error.contains("전사 구간 시간초과");
''',
    '''                } catch (Throwable x) {
                    s.retryCount++;
                    s.error = safe(x.getMessage());
                    boolean manualSkip = manualSkipRequested(j.id)
                            || s.error.contains("사용자 현재 파일 건너뜀");
                    if (manualSkip) {
                        finishManualSkip(j, s, "구간 " + (s.index + 1) + "/" + total);
                        return;
                    }
                    boolean stalled = whisper.isUnhealthy() || s.error.contains("전사 구간 시간초과");
''')

replace_once(
    'app/src/main/java/com/onsamiro/transcriber/WorkCoordinator.java',
    '''            if (shouldStop()) {
                store.updateJob(j.id, Status.RETRY_WAIT, "중단됨",
''',
    '''            if (manualSkipRequested(j.id)) {
                finishManualSkip(j, null, "구간 처리 후");
                return;
            }

            if (shouldStop()) {
                store.updateJob(j.id, Status.RETRY_WAIT, "중단됨",
''')

replace_once(
    'app/src/main/java/com/onsamiro/transcriber/WorkCoordinator.java',
    '''            store.updateJob(j.id, Status.QUALITY_CHECK, "내용 검사", "", "");
            QualityGuard.Result qr = QualityGuard.evaluate(qi);
            OutputStore os = new OutputStore(c);
''',
    '''            if (manualSkipRequested(j.id)) {
                finishManualSkip(j, null, "품질 검사 전");
                return;
            }
            store.updateJob(j.id, Status.QUALITY_CHECK, "내용 검사", "", "");
            QualityGuard.Result qr = QualityGuard.evaluate(qi);
            if (manualSkipRequested(j.id)) {
                finishManualSkip(j, null, "품질 검사 후");
                return;
            }
            OutputStore os = new OutputStore(c);
''')

# ---------------------------------------------------------------------------
# MainActivity: one compact button between period and emergency stop. It only
# targets the current active job and marks it PARTIAL/reprocess-needed.
# ---------------------------------------------------------------------------
replace_once(
    'app/src/main/java/com/onsamiro/transcriber/MainActivity.java',
    '''    private Button realtimeButton;
    private Button periodButton;
    private Button stopButton;
''',
    '''    private Button realtimeButton;
    private Button periodButton;
    private Button skipButton;
    private Button stopButton;
''')

replace_once(
    'app/src/main/java/com/onsamiro/transcriber/MainActivity.java',
    '''        periodButton = mainButton("기간별 전사", v -> showPeriodPicker());
        rootLayout.addView(periodButton, fullWidthFixed(dp(70), dp(12)));

        stopButton = mainButton("즉시 중단", v -> immediateStop());
''',
    '''        periodButton = mainButton("기간별 전사", v -> showPeriodPicker());
        rootLayout.addView(periodButton, fullWidthFixed(dp(70), dp(12)));

        skipButton = mainButton("건너뛸 파일 없음", v -> skipCurrentFile());
        skipButton.setEnabled(false);
        skipButton.setTextColor(Color.parseColor("#5F6368"));
        skipButton.setBackground(roundRect("#E5E7EB", 18));
        rootLayout.addView(skipButton, fullWidthFixed(dp(58), dp(12)));

        stopButton = mainButton("즉시 중단", v -> immediateStop());
''')

replace_once(
    'app/src/main/java/com/onsamiro/transcriber/MainActivity.java',
    '''            periodButton.setText("기간별 전사");
            periodButton.setEnabled(false);
            stopButton.setEnabled(false);
            return;
''',
    '''            periodButton.setText("기간별 전사");
            periodButton.setEnabled(false);
            skipButton.setText("건너뛸 파일 없음");
            skipButton.setEnabled(false);
            skipButton.setTextColor(Color.parseColor("#5F6368"));
            skipButton.setBackground(roundRect("#E5E7EB", 18));
            stopButton.setEnabled(false);
            return;
''')

replace_once(
    'app/src/main/java/com/onsamiro/transcriber/MainActivity.java',
    '''        int problems = stats.failed + stats.review + stats.partial;
        boolean realtimeOn = cfg.autoEnabled() && !cfg.paused();
''',
    '''        boolean canSkip = current != null;
        skipButton.setEnabled(canSkip);
        skipButton.setText(canSkip ? "현재 파일 건너뛰기 →" : "건너뛸 파일 없음");
        skipButton.setTextColor(canSkip ? Color.WHITE : Color.parseColor("#5F6368"));
        skipButton.setBackground(roundRect(canSkip ? "#EF6C00" : "#E5E7EB", 18));

        int problems = stats.failed + stats.review + stats.partial;
        boolean realtimeOn = cfg.autoEnabled() && !cfg.paused();
''')

replace_once(
    'app/src/main/java/com/onsamiro/transcriber/MainActivity.java',
    '''    private void immediateStop() {
''',
    '''    private void skipCurrentFile() {
        JobRecord current;
        try (JobStore s = new JobStore(this)) {
            current = cfg.periodActive()
                    ? s.currentActiveInRange(cfg.rangeStartMs(), cfg.rangeEndMs())
                    : s.currentActive();
            if (current == null) {
                toast("지금 건너뛸 파일이 없어요");
                refresh();
                return;
            }
            s.markManualSkip(current.id, "사용자가 화면에서 현재 파일을 건너뜀");
        }

        boolean signaled = WorkCoordinator.requestSkipCurrent(current.id);
        if (signaled) {
            toast("현재 파일을 건너뛰고 다음 파일로 넘어갑니다");
        } else {
            toast("현재 파일을 건너뛰었습니다");
        }
        refresh();
    }

    private void immediateStop() {
''')

# v3.1.17's stall self-test used the exact old if-expression. v3.1.18 adds a
# null guard because the user-abort path can close/recreate the context.
p = root/'tools/StallRecoverySelfTest.py'
st = p.read_text(encoding='utf-8')
st = st.replace('assert "if (whisper.isUnhealthy())" in work',
                'assert "whisper.isUnhealthy()" in work')
p.write_text(st, encoding='utf-8')

# Version checks introduced by v3.1.17 must follow the new version.
for path in [
    'tools/StallRecoverySelfTest.py',
    'tools/FullFolderScanSelfTest.py',
    'scripts/static_audit.sh',
]:
    p = root / path
    if p.exists():
        s = p.read_text(encoding='utf-8')
        s = s.replace("versionName '3.1.17-stall-recovery-final'",
                      "versionName '3.1.18-manual-skip-final'")
        p.write_text(s, encoding='utf-8')

(root/'tools/ManualSkipSelfTest.py').write_text(r'''from pathlib import Path

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
''', encoding='utf-8')

p = root/'scripts/static_audit.sh'
s = p.read_text(encoding='utf-8')
if 'ManualSkipSelfTest.py' not in s:
    s += '\n# v3.1.18 manual current-file skip\npython3 ./tools/ManualSkipSelfTest.py\n'
p.write_text(s, encoding='utf-8')

(root/'V3118_MANUAL_SKIP_FINAL.md').write_text('''# v3.1.18 Manual Current-File Skip Final

- Preserves v3.1.17 stall recovery, background recovery, and the existing main-screen flow.
- Adds a compact **현재 파일 건너뛰기 →** button between period transcription and emergency stop.
- A manual skip never marks the audio as completed and never deletes the original recording.
- The current job is stored as PARTIAL with **사용자 건너뜀 · 재처리 필요**.
- Existing completed segment checkpoints are kept. History's **이 통화 다시 시도** can reset and transcribe the file again.
- The native Whisper abort callback now accepts an explicit user-skip signal, so a stuck whisper_full call can return immediately instead of waiting for the watchdog timeout.
- The skip signal is tied to the exact active job ID. The following file cannot inherit a stale skip request.
- After a user abort, the Whisper context is recreated before processing the next file.
''', encoding='utf-8')

print('Applied Onsamiro v3.1.18 manual current-file skip patch to', root)
