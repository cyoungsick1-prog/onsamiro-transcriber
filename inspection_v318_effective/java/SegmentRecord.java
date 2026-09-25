package com.onsamiro.transcriber;

public final class SegmentRecord {
    public long jobId;
    public int index;
    public long startMs;
    public long endMs;
    public String status;
    public int retryCount;
    public String text;
    public String error;
    public long decodedMs;
    public long speechMs;
    public long updatedAt;
}
