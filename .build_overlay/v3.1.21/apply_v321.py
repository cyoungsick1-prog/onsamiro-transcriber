#!/usr/bin/env python3
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("usage: apply_v321.py <android-project-root>")
root=Path(sys.argv[1]).resolve()

def replace_once(rel, old, new):
    p=root/rel
    s=p.read_text(encoding="utf-8")
    if old not in s:
        raise SystemExit(f"expected marker missing in {rel}: {old[:120]!r}")
    p.write_text(s.replace(old,new,1),encoding="utf-8")

# Version and inherited version-pinned tests.
replace_once("app/build.gradle","versionCode 312000","versionCode 312100")
replace_once("app/build.gradle","versionName '3.1.20-reliability-final'","versionName '3.1.21-live-progress-final'")
for rel in ["tools/FullFolderScanSelfTest.py","tools/ManualSkipSelfTest.py","tools/StallRecoverySelfTest.py",
            "tools/CandidateDiscoverySelfTest.py","tools/ReliabilityV320SelfTest.py","scripts/static_audit.sh"]:
    p=root/rel
    if p.exists():
        s=p.read_text(encoding="utf-8")
        s=s.replace("versionName '3.1.20-reliability-final'","versionName '3.1.21-live-progress-final'")
        p.write_text(s,encoding="utf-8")

# Native Whisper progress becomes observable by the UI.
replace_once("app/src/main/cpp/whisper_jni.cpp",
'''static std::atomic<bool> g_user_skip_requested {false};
''',
'''static std::atomic<bool> g_user_skip_requested {false};
static std::atomic<int> g_transcription_progress {-1};
''')

replace_once("app/src/main/cpp/whisper_jni.cpp",
'''    w->last_progress.store(progress, std::memory_order_relaxed);
    w->last_progress_ms.store(monotonic_ms(), std::memory_order_relaxed);
''',
'''    w->last_progress.store(progress, std::memory_order_relaxed);
    g_transcription_progress.store(std::max(0, std::min(100, progress)), std::memory_order_relaxed);
    w->last_progress_ms.store(monotonic_ms(), std::memory_order_relaxed);
''')

replace_once("app/src/main/cpp/whisper_jni.cpp",
'''    TranscriptionWatchdog watchdog;
    watchdog.started_ms = monotonic_ms();
''',
'''    g_transcription_progress.store(0, std::memory_order_relaxed);
    TranscriptionWatchdog watchdog;
    watchdog.started_ms = monotonic_ms();
''')

replace_once("app/src/main/cpp/whisper_jni.cpp",
'''    if(g_user_skip_requested.load(std::memory_order_relaxed)){
        LOGE("whisper_full aborted by user skip: rc=%d progress=%d", rc, watchdog.last_progress.load(std::memory_order_relaxed));
        return env->NewStringUTF("__ONSAMIRO_USER_SKIP__");
    }
    if(watchdog.timed_out.load(std::memory_order_relaxed)){
        LOGE("whisper_full watchdog timeout: rc=%d progress=%d", rc, watchdog.last_progress.load(std::memory_order_relaxed));
        return env->NewStringUTF("__ONSAMIRO_TIMEOUT__");
    }
    if(rc!=0){LOGE("whisper_full failed: %d",rc);return nullptr;}

    std::string out;int segs=whisper_full_n_segments(ctx);
''',
'''    if(g_user_skip_requested.load(std::memory_order_relaxed)){
        g_transcription_progress.store(-1, std::memory_order_relaxed);
        LOGE("whisper_full aborted by user skip: rc=%d progress=%d", rc, watchdog.last_progress.load(std::memory_order_relaxed));
        return env->NewStringUTF("__ONSAMIRO_USER_SKIP__");
    }
    if(watchdog.timed_out.load(std::memory_order_relaxed)){
        g_transcription_progress.store(-1, std::memory_order_relaxed);
        LOGE("whisper_full watchdog timeout: rc=%d progress=%d", rc, watchdog.last_progress.load(std::memory_order_relaxed));
        return env->NewStringUTF("__ONSAMIRO_TIMEOUT__");
    }
    if(rc!=0){g_transcription_progress.store(-1, std::memory_order_relaxed);LOGE("whisper_full failed: %d",rc);return nullptr;}
    g_transcription_progress.store(100, std::memory_order_relaxed);

    std::string out;int segs=whisper_full_n_segments(ctx);
''')

replace_once("app/src/main/cpp/whisper_jni.cpp",
'''extern "C" JNIEXPORT void JNICALL Java_com_onsamiro_transcriber_WhisperBridge_nativeRequestUserSkip(JNIEnv*,jclass){
''',
'''extern "C" JNIEXPORT jint JNICALL Java_com_onsamiro_transcriber_WhisperBridge_nativeCurrentProgress(JNIEnv*,jclass){
    return static_cast<jint>(g_transcription_progress.load(std::memory_order_relaxed));
}
extern "C" JNIEXPORT void JNICALL Java_com_onsamiro_transcriber_WhisperBridge_nativeRequestUserSkip(JNIEnv*,jclass){
''')

replace_once("app/src/main/java/com/onsamiro/transcriber/WhisperBridge.java",
'''    public static void requestUserSkip() {
        nativeRequestUserSkip();
    }
''',
'''    public static int currentProgressPercent() {
        try { return nativeCurrentProgress(); }
        catch (Throwable t) { return -1; }
    }

    public static void requestUserSkip() {
        nativeRequestUserSkip();
    }
''')

replace_once("app/src/main/java/com/onsamiro/transcriber/WhisperBridge.java",
'''    private static native void nativeRequestUserSkip();
''',
'''    private static native int nativeCurrentProgress();
    private static native void nativeRequestUserSkip();
''')

# Show live per-segment Whisper progress instead of appearing frozen at 1%.
replace_once("app/src/main/java/com/onsamiro/transcriber/MainActivity.java",
'''        int fp = filePercent(current);
        currentFileText.setText("현재: " + shortName(current.displayName, 34));
        String stage = current.stage == null || current.stage.isEmpty() ? "처리 중" : current.stage;
        String seg = current.totalSegments > 0
                ? " · " + current.doneSegments + "/" + current.totalSegments + " 구간 완료"
                : "";
        fileProgressText.setText(stage + " · 파일 " + fp + "%" + seg);
        fileProgressBar.setProgress(fp);
''',
'''        int live = Status.TRANSCRIBING.equals(current.status)
                ? WhisperBridge.currentProgressPercent() : -1;
        int fp = filePercent(current);
        if (live >= 0 && current.totalSegments > 0) {
            int liveFile = (int)Math.round(
                    ((current.doneSegments + (live / 100.0)) / current.totalSegments) * 90.0);
            fp = Math.max(fp, Math.max(1, Math.min(90, liveFile)));
        }
        currentFileText.setText("현재: " + shortName(current.displayName, 34));
        String stage = current.stage == null || current.stage.isEmpty() ? "처리 중" : current.stage;
        String liveText = live >= 0 ? " · 현재 구간 " + live + "%" : "";
        String seg = current.totalSegments > 0
                ? " · " + current.doneSegments + "/" + current.totalSegments + " 구간 완료"
                : "";
        fileProgressText.setText(stage + liveText + " · 파일 " + fp + "%" + seg);
        fileProgressBar.setProgress(fp);
''')

# Make manual skip explicit and prevent a single accidental tap.
replace_once("app/src/main/java/com/onsamiro/transcriber/MainActivity.java",
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
''',
'''    private void skipCurrentFile() {
        JobRecord current;
        try (JobStore s = new JobStore(this)) {
            current = cfg.periodActive()
                    ? s.currentActiveInRange(cfg.rangeStartMs(), cfg.rangeEndMs())
                    : s.currentActive();
        }
        if (current == null) {
            toast("지금 건너뛸 파일이 없어요");
            refresh();
            return;
        }
        final JobRecord target = current;
        new AlertDialog.Builder(this)
                .setTitle("현재 파일을 건너뛸까요?")
                .setMessage("이 파일은 완료 처리하지 않고 '확인 필요'로 남습니다. 나중에 기록에서 다시 시도할 수 있어요.\n\n" + shortName(target.displayName, 48))
                .setNegativeButton("계속 전사", null)
                .setPositiveButton("건너뛰기", (d, w) -> {
                    try (JobStore s = new JobStore(this)) {
                        s.markManualSkip(target.id, "사용자가 화면에서 현재 파일을 건너뜀");
                    }
                    boolean signaled = WorkCoordinator.requestSkipCurrent(target.id);
                    toast(signaled ? "현재 파일을 건너뛰고 다음 파일로 넘어갑니다"
                            : "현재 파일을 확인 필요로 남겼습니다");
                    refresh();
                })
                .show();
    }
''')

(root/"tools/LiveProgressV321SelfTest.py").write_text(r'''from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
JAVA=ROOT/"app/src/main/java/com/onsamiro/transcriber"
main=(JAVA/"MainActivity.java").read_text(encoding="utf-8")
bridge=(JAVA/"WhisperBridge.java").read_text(encoding="utf-8")
jni=(ROOT/"app/src/main/cpp/whisper_jni.cpp").read_text(encoding="utf-8")
build=(ROOT/"app/build.gradle").read_text(encoding="utf-8")
assert "versionName '3.1.21-live-progress-final'" in build
assert "currentProgressPercent" in bridge and "nativeCurrentProgress" in bridge
assert "g_transcription_progress" in jni
assert "현재 구간 " in main and "WhisperBridge.currentProgressPercent()" in main
assert 'setTitle("현재 파일을 건너뛸까요?")' in main
assert "완료 처리하지 않고 '확인 필요'로 남습니다" in main
print("V3.1.21 LIVE PROGRESS REGRESSION PASS")
''',encoding="utf-8")

p=root/"scripts/static_audit.sh"
s=p.read_text(encoding="utf-8")
if "LiveProgressV321SelfTest.py" not in s:
    s += "\n# v3.1.21 live progress + safe manual skip\npython3 ./tools/LiveProgressV321SelfTest.py\n"
p.write_text(s,encoding="utf-8")

print("Applied Onsamiro v3.1.21 live progress final patch to",root)
