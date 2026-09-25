#include <jni.h>
#include <android/log.h>
#include <string>
#include <algorithm>
#include <atomic>
#include <chrono>
#include "whisper.h"

#define TAG "OnsamiroWhisper"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR,TAG,__VA_ARGS__)

static std::atomic<bool> g_user_skip_requested {false};

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
    if (g_user_skip_requested.load(std::memory_order_relaxed)) return true;
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

    if(g_user_skip_requested.load(std::memory_order_relaxed)){
        LOGE("whisper_full aborted by user skip: rc=%d progress=%d", rc, watchdog.last_progress.load(std::memory_order_relaxed));
        return env->NewStringUTF("__ONSAMIRO_USER_SKIP__");
    }
    if(watchdog.timed_out.load(std::memory_order_relaxed)){
        LOGE("whisper_full watchdog timeout: rc=%d progress=%d", rc, watchdog.last_progress.load(std::memory_order_relaxed));
        return env->NewStringUTF("__ONSAMIRO_TIMEOUT__");
    }
    if(rc!=0){LOGE("whisper_full failed: %d",rc);return nullptr;}

    std::string out;int segs=whisper_full_n_segments(ctx);for(int i=0;i<segs;i++){const char *t=whisper_full_get_segment_text(ctx,i);if(t){if(!out.empty())out.push_back(' ');out+=t;}}
    return env->NewStringUTF(out.c_str());
}
extern "C" JNIEXPORT void JNICALL Java_com_onsamiro_transcriber_WhisperBridge_nativeRequestUserSkip(JNIEnv*,jclass){
    g_user_skip_requested.store(true, std::memory_order_relaxed);
}
extern "C" JNIEXPORT void JNICALL Java_com_onsamiro_transcriber_WhisperBridge_nativeClearUserSkip(JNIEnv*,jclass){
    g_user_skip_requested.store(false, std::memory_order_relaxed);
}
extern "C" JNIEXPORT void JNICALL Java_com_onsamiro_transcriber_WhisperBridge_nativeFree(JNIEnv*,jclass,jlong ptr){auto *ctx=reinterpret_cast<whisper_context*>(ptr);if(ctx)whisper_free(ctx);}
