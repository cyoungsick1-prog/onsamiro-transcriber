package com.onsamiro.transcriber;

public final class WhisperBridge implements AutoCloseable {
    static { System.loadLibrary("onsamiro_whisper"); }

    // A 60-second audio chunk can legitimately take a while on a thermally throttled phone.
    // We therefore use a generous no-progress watchdog, plus a hard ceiling so one native
    // call can never hold the whole transcription queue forever.
    private static final long STALL_TIMEOUT_MS = 4L * 60_000L;
    private static final long HARD_TIMEOUT_MS = 12L * 60_000L;
    private static final String TIMEOUT_SENTINEL = "__ONSAMIRO_TIMEOUT__";
    private static final String USER_SKIP_SENTINEL = "__ONSAMIRO_USER_SKIP__";

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
        if (USER_SKIP_SENTINEL.equals(s)) {
            unhealthy = true;
            throw new IllegalStateException("사용자 현재 파일 건너뜀");
        }
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

    public static void requestUserSkip() {
        nativeRequestUserSkip();
    }

    public static void clearUserSkip() {
        nativeClearUserSkip();
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
    private static native void nativeRequestUserSkip();
    private static native void nativeClearUserSkip();
    private static native void nativeFree(long handle);
}
