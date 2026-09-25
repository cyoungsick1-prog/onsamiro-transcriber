#!/usr/bin/env python3
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit('usage: apply_v317.py <android-project-root>')
root = Path(sys.argv[1]).resolve()
if not (root / 'app/build.gradle').exists():
    raise SystemExit(f'not an Android project: {root}')

def replace_once(path, old, new):
    p = root / path
    s = p.read_text(encoding='utf-8')
    if old not in s:
        raise SystemExit(f'expected marker missing in {path}: {old[:80]!r}')
    p.write_text(s.replace(old, new, 1), encoding='utf-8')

# Preserve v3.1.16 continuous-background behavior while moving to the final stall recovery build.
replace_once('app/build.gradle', "versionCode 311500", "versionCode 311700")
replace_once('app/build.gradle', "versionName '3.1.15-auto-start-recovery'", "versionName '3.1.17-stall-recovery-final'")
replace_once(
    'app/src/main/java/com/onsamiro/transcriber/TranscriptionKeepAliveService.java',
    'private static final long ACTIVE_WAKELOCK_MS = 10 * 60_000L;',
    'private static final long ACTIVE_WAKELOCK_MS = Integer.MAX_VALUE; // ~24.8 days; always released in finally when active work ends')

(root/'app/src/main/java/com/onsamiro/transcriber/WhisperBridge.java').write_text(r'''package com.onsamiro.transcriber;

public final class WhisperBridge implements AutoCloseable {
    static { System.loadLibrary("onsamiro_whisper"); }

    // A 60-second audio chunk can legitimately take a while on a thermally throttled phone.
    // We therefore use a generous no-progress watchdog, plus a hard ceiling so one native
    // call can never hold the whole transcription queue forever.
    private static final long STALL_TIMEOUT_MS = 4L * 60_000L;
    private static final long HARD_TIMEOUT_MS = 12L * 60_000L;
    private static final String TIMEOUT_SENTINEL = "__ONSAMIRO_TIMEOUT__";

    private long handle;
    private volatile boolean unhealthy;

    public WhisperBridge(String modelPath) {
        handle = nativeCreate(modelPath);
        if (handle == 0) throw new IllegalStateException("Whisper 모델을 열 수 없습니다");
    }

    public static boolean validateModel(String path) {
        try { return nativeValidate(path); }
        catch (Throwable t) { return false; }
    }

    public synchronized String transcribe(float[] pcm16k, String language) {
        return transcribe(pcm16k, language, 1);
    }

    public synchronized String transcribe(float[] pcm16k, String language, int requestedThreads) {
        if (handle == 0) throw new IllegalStateException("Whisper context closed");
        int threads = Math.max(1, Math.min(2, requestedThreads));
        String s = nativeTranscribe(
                handle,
                pcm16k,
                language == null ? "ko" : language,
                threads,
                STALL_TIMEOUT_MS,
                HARD_TIMEOUT_MS);
        if (TIMEOUT_SENTINEL.equals(s)) {
            unhealthy = true;
            throw new IllegalStateException("전사 구간 시간초과");
        }
        if (s == null) {
            unhealthy = true;
            throw new IllegalStateException("Whisper returned null");
        }
        return s.trim();
    }

    /**
     * A native timeout/error can leave decoder state unsuitable for another file.
     * The coordinator discards this bridge and loads a fresh context before continuing.
     */
    public boolean isUnhealthy() {
        return unhealthy;
    }

    @Override public synchronized void close() {
        if (handle != 0) {
            nativeFree(handle);
            handle = 0;
        }
    }

    private static native long nativeCreate(String modelPath);
    private static native boolean nativeValidate(String modelPath);
    private static native String nativeTranscribe(
            long handle,
            float[] pcm16k,
            String language,
            int threads,
            long stallTimeoutMs,
            long hardTimeoutMs);
    private static native void nativeFree(long handle);
}
''', encoding='utf-8')

(root/'app/src/main/cpp/whisper_jni.cpp').write_text(r'''#include <jni.h>
#include <android/log.h>
#include <string>
#include <algorithm>
#include <atomic>
#include <chrono>
#include "whisper.h"

#define TAG "OnsamiroWhisper"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR,TAG,__VA_ARGS__)

static std::string jstr(JNIEnv *env,jstring s){if(!s)return {};const char *p=env->GetStringUTFChars(s,nullptr);std::string out=p?p:"";if(p)env->ReleaseStringUTFChars(s,p);return out;}

static int64_t monotonic_ms() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now().time_since_epoch()).count();
}

struct TranscriptionWatchdog {
    int64_t started_ms = 0;
    int64_t stall_timeout_ms = 0;
    int64_t hard_timeout_ms = 0;
    std::atomic<int64_t> last_progress_ms {0};
    std::atomic<int> last_progress {-1};
    std::atomic<bool> timed_out {false};
};

static void on_progress(whisper_context *, whisper_state *, int progress, void *user_data) {
    auto *w = reinterpret_cast<TranscriptionWatchdog *>(user_data);
    if (!w) return;
    w->last_progress.store(progress, std::memory_order_relaxed);
    w->last_progress_ms.store(monotonic_ms(), std::memory_order_relaxed);
}

static bool should_abort(void *user_data) {
    auto *w = reinterpret_cast<TranscriptionWatchdog *>(user_data);
    if (!w) return false;
    const int64_t now = monotonic_ms();
    const int64_t last = w->last_progress_ms.load(std::memory_order_relaxed);
    const bool hard = w->hard_timeout_ms > 0 && now - w->started_ms >= w->hard_timeout_ms;
    const bool stalled = w->stall_timeout_ms > 0 && now - last >= w->stall_timeout_ms;
    if (hard || stalled) {
        w->timed_out.store(true, std::memory_order_relaxed);
        return true;
    }
    return false;
}

extern "C" JNIEXPORT jlong JNICALL Java_com_onsamiro_transcriber_WhisperBridge_nativeCreate(JNIEnv *env,jclass,jstring path){
    std::string p=jstr(env,path); whisper_context_params cp=whisper_context_default_params();
    whisper_context *ctx=whisper_init_from_file_with_params(p.c_str(),cp); return reinterpret_cast<jlong>(ctx);
}
extern "C" JNIEXPORT jboolean JNICALL Java_com_onsamiro_transcriber_WhisperBridge_nativeValidate(JNIEnv *env,jclass,jstring path){
    std::string p=jstr(env,path); whisper_context_params cp=whisper_context_default_params(); whisper_context *ctx=whisper_init_from_file_with_params(p.c_str(),cp); if(!ctx)return JNI_FALSE; whisper_free(ctx);return JNI_TRUE;
}
extern "C" JNIEXPORT jstring JNICALL Java_com_onsamiro_transcriber_WhisperBridge_nativeTranscribe(
        JNIEnv *env,jclass,jlong ptr,jfloatArray audio,jstring language,jint threads,
        jlong stallTimeoutMs,jlong hardTimeoutMs){
    auto *ctx=reinterpret_cast<whisper_context*>(ptr); if(!ctx||!audio)return env->NewStringUTF("");
    const jsize n=env->GetArrayLength(audio); jfloat *pcm=env->GetFloatArrayElements(audio,nullptr); if(!pcm)return env->NewStringUTF("");
    std::string lang=jstr(env,language); whisper_full_params p=whisper_full_default_params(WHISPER_SAMPLING_GREEDY);

    TranscriptionWatchdog watchdog;
    watchdog.started_ms = monotonic_ms();
    watchdog.last_progress_ms.store(watchdog.started_ms, std::memory_order_relaxed);
    watchdog.stall_timeout_ms = std::max<int64_t>(30'000L, static_cast<int64_t>(stallTimeoutMs));
    watchdog.hard_timeout_ms = std::max<int64_t>(watchdog.stall_timeout_ms, static_cast<int64_t>(hardTimeoutMs));

    p.print_realtime=false;p.print_progress=false;p.print_timestamps=false;p.print_special=false;p.translate=false;p.no_context=true;p.single_segment=false;p.language=lang.empty()?"ko":lang.c_str();p.n_threads=std::max(1,(int)threads);
    p.progress_callback = on_progress;
    p.progress_callback_user_data = &watchdog;
    p.abort_callback = should_abort;
    p.abort_callback_user_data = &watchdog;

    int rc=whisper_full(ctx,p,pcm,n);
    env->ReleaseFloatArrayElements(audio,pcm,JNI_ABORT);

    if(watchdog.timed_out.load(std::memory_order_relaxed)){
        LOGE("whisper_full watchdog timeout: rc=%d progress=%d", rc, watchdog.last_progress.load(std::memory_order_relaxed));
        return env->NewStringUTF("__ONSAMIRO_TIMEOUT__");
    }
    if(rc!=0){LOGE("whisper_full failed: %d",rc);return nullptr;}

    std::string out;int segs=whisper_full_n_segments(ctx);for(int i=0;i<segs;i++){const char *t=whisper_full_get_segment_text(ctx,i);if(t){if(!out.empty())out.push_back(' ');out+=t;}}
    return env->NewStringUTF(out.c_str());
}
extern "C" JNIEXPORT void JNICALL Java_com_onsamiro_transcriber_WhisperBridge_nativeFree(JNIEnv*,jclass,jlong ptr){auto *ctx=reinterpret_cast<whisper_context*>(ptr);if(ctx)whisper_free(ctx);}
''', encoding='utf-8')

replace_once(
    'app/src/main/java/com/onsamiro/transcriber/JobStore.java',
    '''    public void defer(long id, String reason, long delayMs) {\n        ContentValues v = new ContentValues();\n        v.put("status", Status.WAIT_ORIGINAL);\n        v.put("stage", reason);\n        v.put("wait_reason", reason);\n        v.put("next_attempt_ms", System.currentTimeMillis() + Math.max(1_000L, delayMs));\n        v.put("updated_at", System.currentTimeMillis());\n        getWritableDatabase().update("jobs", v, "id=?", new String[]{Long.toString(id)});\n    }\n''',
    '''    public void defer(long id, String reason, long delayMs) {\n        ContentValues v = new ContentValues();\n        v.put("status", Status.WAIT_ORIGINAL);\n        v.put("stage", reason);\n        v.put("wait_reason", reason);\n        v.put("next_attempt_ms", System.currentTimeMillis() + Math.max(1_000L, delayMs));\n        v.put("updated_at", System.currentTimeMillis());\n        getWritableDatabase().update("jobs", v, "id=?", new String[]{Long.toString(id)});\n    }\n\n    /**\n     * A stalled native transcription must not pin the whole queue. The problematic file is\n     * moved out of the runnable set for a short backoff while newer files keep processing.\n     * After three automatic retries it is isolated as PARTIAL for explicit review.\n     */\n    public void deferStalledSegment(long id, String reason, int segmentRetryCount) {\n        long now = System.currentTimeMillis();\n        ContentValues v = new ContentValues();\n        if (segmentRetryCount <= 3) {\n            long[] delays = {2 * 60_000L, 5 * 60_000L, 15 * 60_000L};\n            int ix = Math.max(0, Math.min(delays.length - 1, segmentRetryCount - 1));\n            v.put("status", Status.RETRY_WAIT);\n            v.put("stage", "구간 자동복구 대기");\n            v.put("wait_reason", "전사 정지 감지 · 다른 파일 계속 처리");\n            v.put("next_attempt_ms", now + delays[ix]);\n        } else {\n            v.put("status", Status.PARTIAL);\n            v.put("stage", "반복 정지 · 확인 필요");\n            v.put("wait_reason", "");\n            v.put("next_attempt_ms", 0);\n        }\n        v.put("fail_reason", nz(reason));\n        v.put("updated_at", now);\n        getWritableDatabase().update("jobs", v, "id=?", new String[]{Long.toString(id)});\n    }\n''')

replace_once(
    'app/src/main/java/com/onsamiro/transcriber/WorkCoordinator.java',
    '''                    if (whisper == null) whisper = new WhisperBridge(cfg.modelPath());\n                    processOne(j, whisper);\n                    processed++;\n''',
    '''                    if (whisper == null) whisper = new WhisperBridge(cfg.modelPath());\n                    processOne(j, whisper);\n                    processed++;\n                    // A native timeout/error gets a fresh model context before the next file.\n                    // This prevents one damaged decoder state from contaminating the queue.\n                    if (whisper.isUnhealthy()) {\n                        whisper.close();\n                        whisper = null;\n                    }\n''')
replace_once(
    'app/src/main/java/com/onsamiro/transcriber/WorkCoordinator.java',
    '''                } catch (Throwable x) {\n                    s.retryCount++;\n                    s.error = safe(x.getMessage());\n                    s.status = s.retryCount < 3 ? Status.RETRY_WAIT : Status.FAILED;\n                    store.saveSegment(s);\n                }\n''',
    '''                } catch (Throwable x) {\n                    s.retryCount++;\n                    s.error = safe(x.getMessage());\n                    boolean stalled = whisper.isUnhealthy() || s.error.contains("전사 구간 시간초과");\n                    if (stalled) {\n                        // Keep the completed checkpoints, quarantine only this file for a short\n                        // backoff, and immediately release the worker to the rest of the queue.\n                        s.status = s.retryCount <= 3 ? Status.RETRY_WAIT : Status.FAILED;\n                        store.saveSegment(s);\n                        store.deferStalledSegment(j.id, "구간 " + (s.index + 1) + "/" + total + " · " + s.error, s.retryCount);\n                        return;\n                    }\n                    s.status = s.retryCount < 3 ? Status.RETRY_WAIT : Status.FAILED;\n                    store.saveSegment(s);\n                }\n''')

(root/'tools/BackgroundContinuousSelfTest.py').write_text('''from pathlib import Path\n\nROOT = Path(__file__).resolve().parents[1]\nJAVA = ROOT / "app/src/main/java/com/onsamiro/transcriber"\nservice = (JAVA / "TranscriptionKeepAliveService.java").read_text(encoding="utf-8")\nwork = (JAVA / "WorkCoordinator.java").read_text(encoding="utf-8")\nruntime = (JAVA / "RuntimeConstraints.java").read_text(encoding="utf-8")\n\nassert "ACTIVE_WAKELOCK_MS = Integer.MAX_VALUE" in service, "active transcription wakelock still has short timeout"\nassert "lock.acquire(ACTIVE_WAKELOCK_MS);" in service, "active transcription wakelock not acquired"\nassert "if (lock != null && lock.isHeld())" in service and "lock.release()" in service, "active wakelock must be released in finally"\nassert "PARTIAL_WAKE_LOCK" in service\nassert "START_STICKY" in service\nassert "WATCHDOG_MS = 45_000L" in service\nassert "runDirectRecovery(false)" in service\nassert "return stop || cfg.paused();" in work, "unexpected automatic stop condition added"\nfor token in ["thermalSevere(", "retryAfterThermal", "deferForThermal", "발열 보호 중", "기기 발열로 전사 잠시 대기"]:\n    assert token not in service + work + runtime, f"thermal stop marker remains: {token}"\nprint("BACKGROUND CONTINUOUS TRANSCRIPTION STATIC REGRESSION PASS")\n''', encoding='utf-8')

replace_once('tools/FullFolderScanSelfTest.py', "versionName '3.1.15-auto-start-recovery'", "versionName '3.1.17-stall-recovery-final'")

(root/'tools/StallRecoverySelfTest.py').write_text('''from pathlib import Path\n\nROOT = Path(__file__).resolve().parents[1]\nJAVA = ROOT / "app/src/main/java/com/onsamiro/transcriber"\nbridge = (JAVA / "WhisperBridge.java").read_text(encoding="utf-8")\nwork = (JAVA / "WorkCoordinator.java").read_text(encoding="utf-8")\nstore = (JAVA / "JobStore.java").read_text(encoding="utf-8")\njni = (ROOT / "app/src/main/cpp/whisper_jni.cpp").read_text(encoding="utf-8")\nbuild = (ROOT / "app/build.gradle").read_text(encoding="utf-8")\n\nassert "versionName '3.1.17-stall-recovery-final'" in build\nassert "STALL_TIMEOUT_MS = 4L * 60_000L" in bridge\nassert "HARD_TIMEOUT_MS = 12L * 60_000L" in bridge\nassert "TIMEOUT_SENTINEL" in bridge and "isUnhealthy()" in bridge\nassert "p.progress_callback = on_progress" in jni\nassert "p.abort_callback = should_abort" in jni\nassert "__ONSAMIRO_TIMEOUT__" in jni\nassert "deferStalledSegment" in store\nassert "2 * 60_000L" in store and "5 * 60_000L" in store and "15 * 60_000L" in store\nassert "다른 파일 계속 처리" in store\nassert "if (whisper.isUnhealthy())" in work\nassert "store.deferStalledSegment" in work\nprint("STALL RECOVERY STATIC REGRESSION PASS")\n''', encoding='utf-8')

p = root/'scripts/static_audit.sh'
s = p.read_text(encoding='utf-8')
if 'python3 "$ROOT/tools/BackgroundContinuousSelfTest.py"' not in s:
    s = s.replace('python3 "$ROOT/tools/BackgroundRecoverySelfTest.py"\n', 'python3 "$ROOT/tools/BackgroundRecoverySelfTest.py"\npython3 "$ROOT/tools/BackgroundContinuousSelfTest.py"\n', 1)
s += '''\n# v3.1.17 stalled native transcription recovery\ngrep -q "versionName '3.1.17-stall-recovery-final'" app/build.gradle\ngrep -q 'STALL_TIMEOUT_MS = 4L \\* 60_000L' app/src/main/java/com/onsamiro/transcriber/WhisperBridge.java\ngrep -q 'HARD_TIMEOUT_MS = 12L \\* 60_000L' app/src/main/java/com/onsamiro/transcriber/WhisperBridge.java\ngrep -q 'deferStalledSegment' app/src/main/java/com/onsamiro/transcriber/JobStore.java\ngrep -q 'p.abort_callback = should_abort' app/src/main/cpp/whisper_jni.cpp\npython3 ./tools/StallRecoverySelfTest.py\n'''
p.write_text(s, encoding='utf-8')

(root/'V3116_BACKGROUND_CONTINUOUS.md').write_text('''# v3.1.16 Background Continuous Transcription\n\n- Fixes the active transcription PARTIAL_WAKE_LOCK lease expiring after 10 minutes.\n- Active work now receives a long lease (~24.8 days) and the lock is still released immediately in the existing finally block as soon as the run finishes.\n- Keeps START_STICKY, foreground specialUse service, 45-second watchdog, content observer, and JobScheduler recovery paths.\n- App-level thermal stop/retry remains removed. Android may still throttle CPU at the OS level for device safety, but the app does not stop transcription because of temperature.\n- Adds BackgroundContinuousSelfTest.py regression coverage.\n''', encoding='utf-8')
(root/'V3117_STALL_RECOVERY_FINAL.md').write_text('''# v3.1.17 Stall Recovery Final\n\n- Preserves the v3.1.16 continuous background wakelock and existing UI.\n- Adds a native Whisper no-progress watchdog: 4 minutes without progress triggers abort; 12 minutes is the absolute per-segment ceiling.\n- A timed-out segment keeps already completed segment checkpoints.\n- The affected file is deferred for 2 / 5 / 15 minutes while the rest of the queue continues.\n- After repeated stalls the file is isolated as PARTIAL instead of freezing the queue.\n- Any native timeout/error marks the Whisper context unhealthy; the next file receives a fresh model context.\n- Adds StallRecoverySelfTest.py and static audit coverage.\n''', encoding='utf-8')

print('Applied Onsamiro v3.1.17 stall recovery final patch to', root)