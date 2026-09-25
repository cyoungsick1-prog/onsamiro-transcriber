package com.onsamiro.transcriber;

public final class JobRecord {
    public long id;
    public String sourceUri;
    public String displayName;
    public long sizeBytes;
    public long modifiedMs;
    public long callTimeMs;
    public long durationMs;
    public String contactHint;
    public String status;
    public String stage;
    public long startedAt;
    public long updatedAt;
    public int retryCount;
    public long nextAttemptMs;
    public String waitReason;
    public String failReason;
    public String resultPreview;
    public String draftPath;
    public String finalUri;
    public long decodedDurationMs;
    public long speechMs;
    public long processedUntilMs;
    public int totalSegments;
    public int doneSegments;
    public int failedSegments;

    public String summary() {
        return displayName + "\n" + status + " · " + stage + " · " + doneSegments + "/" + totalSegments +
                " 구간 · 재시도 " + retryCount + "회";
    }
}
