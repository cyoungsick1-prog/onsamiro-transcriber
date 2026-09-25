package com.onsamiro.transcriber;

public final class Status {
    private Status() {}
    public static final String DISCOVERED = "DISCOVERED";
    public static final String WAIT_ORIGINAL = "WAIT_ORIGINAL";
    public static final String FETCHING = "FETCHING";
    public static final String TRANSCRIBING = "TRANSCRIBING";
    public static final String QUALITY_CHECK = "QUALITY_CHECK";
    public static final String SAVING = "SAVING";
    public static final String DONE = "DONE";
    public static final String PARTIAL = "PARTIAL";
    public static final String NO_AUDIO = "NO_AUDIO";
    public static final String REVIEW_REQUIRED = "REVIEW_REQUIRED";
    public static final String RETRY_WAIT = "RETRY_WAIT";
    public static final String FAILED = "FAILED";
    public static final String USER_EXCLUDED = "USER_EXCLUDED";
    public static final String ALREADY_TRANSCRIBED = "ALREADY_TRANSCRIBED";

    public static boolean terminal(String s) {
        return DONE.equals(s) || NO_AUDIO.equals(s) || USER_EXCLUDED.equals(s) || ALREADY_TRANSCRIBED.equals(s);
    }
}
