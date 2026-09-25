package com.onsamiro.transcriber;

import android.content.Context;
import android.net.Uri;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class WorkCoordinator {
    private static final long SEGMENT_MS = 60_000L;
    private static final long FILE_SETTLE_MS = 5_000L;
    private static final long FILE_RETRY_MS = 15_000L;
    private static final AtomicBoolean PROCESS_RUNNING = new AtomicBoolean(false);
    private static final AtomicLong ACTIVE_JOB_ID = new AtomicLong(-1L);
    private static final AtomicLong MANUAL_SKIP_JOB_ID = new AtomicLong(-1L);

    private final Context c;
    private final AppConfig cfg;
    private final JobStore store;
    private volatile boolean stop;
    private volatile String stopReason = "";
    private volatile boolean forceRun = false;
    private boolean manualHistorical = false;
    private boolean periodContinuationScheduled = false;

    public WorkCoordinator(Context c) {
        this.c = c;
        cfg = new AppConfig(c);
        store = new JobStore(c);
    }

    public static boolean requestSkipCurrent(long jobId) {
        if (jobId <= 0L || ACTIVE_JOB_ID.get() != jobId) return false;
        MANUAL_SKIP_JOB_ID.set(jobId);
        WhisperBridge.requestUserSkip();
        return true;
    }

    public void requestStop(String reason) {
        stop = true;
        stopReason = reason == null ? "" : reason;
    }

    public void runRecovery(boolean force) {
        if (!PROCESS_RUNNING.compareAndSet(false, true)) { store.close(); return; }
        forceRun = force;
        manualHistorical = false;
        cfg.setWorkMode("recovery");
        try {
            store.recoverInterruptedRunning("이전 실행 중단 복구");
            if (!preflight(force)) return;

            DocumentScanner.Result scan = new DocumentScanner(c, store).scanRecovery();
            cfg.setLastRunNote("누락구간 확인 · 메타데이터 " + scan.visited + "개 · 새 후보 " + scan.discovered + "개"
                    + (scan.note.isEmpty() ? "" : " · " + scan.note));
            processPending(0L, Long.MAX_VALUE);
        } catch (Throwable t) {
            cfg.setLastRunNote("복구 실행 오류: " + t.getClass().getSimpleName() + " · " + safe(t.getMessage()));
        } finally {
            finishRun();
            PROCESS_RUNNING.set(false);
        }
    }

    public void runTriggered(Uri[] triggered) {
        if (!PROCESS_RUNNING.compareAndSet(false, true)) { store.close(); return; }
        forceRun = false;
        manualHistorical = false;
        cfg.setWorkMode("realtime");
        try {
            store.recoverInterruptedRunning("이전 실행 중단 복구");
            if (!preflight(false)) return;

            cfg.setLastContentTriggerMs(System.currentTimeMillis());
            DocumentScanner scanner = new DocumentScanner(c, store);
            int direct = 0;
            boolean fallback = triggered == null || triggered.length == 0;
            String lastNote = "";

            if (triggered != null) {
                for (Uri u : triggered) {
                    DocumentScanner.DirectResult r = scanner.ingestTriggeredUri(u);
                    if (r.discovered) direct++;
                    if (r.needsRecoveryScan) fallback = true;
                    if (!r.note.isEmpty()) lastNote = r.note;
                }
            }

            if (fallback) {
                DocumentScanner.Result r = scanner.scanRecovery();
                cfg.setLastRunNote("새 통화 감지 · 직접 " + direct + "건 · 보완 후보 " + r.discovered + "건"
                        + (r.note.isEmpty() ? "" : " · " + r.note));
            } else {
                cfg.setLastRunNote("새 통화 직접 감지 " + direct + "건" + (lastNote.isEmpty() ? "" : " · " + lastNote));
            }
            processPending(0L, Long.MAX_VALUE);
        } catch (Throwable t) {
            cfg.setLastRunNote("새 통화 감지 오류: " + t.getClass().getSimpleName() + " · " + safe(t.getMessage()));
        } finally {
            finishRun();
            PROCESS_RUNNING.set(false);
        }
    }

    public void runPendingOnly(boolean force) {
        if (!PROCESS_RUNNING.compareAndSet(false, true)) { store.close(); return; }
        forceRun = force;
        manualHistorical = false;
        cfg.setWorkMode("pending");
        try {
            store.recoverInterruptedRunning("이전 실행 중단 복구");
            if (!preflight(force)) return;
            cfg.setLastRunNote("대기 작업 이어서 처리");
            processPending(0L, Long.MAX_VALUE);
        } catch (Throwable t) {
            cfg.setLastRunNote("이어하기 오류: " + t.getClass().getSimpleName() + " · " + safe(t.getMessage()));
        } finally {
            finishRun();
            PROCESS_RUNNING.set(false);
        }
    }

    public void runHistorical() {
        if (!PROCESS_RUNNING.compareAndSet(false, true)) { store.close(); return; }
        forceRun = true;
        manualHistorical = true;
        long start = cfg.rangeStartMs();
        long end = cfg.rangeEndMs();
        boolean continuation = cfg.periodFound() > 0 || cfg.periodAlready() > 0 || cfg.periodTarget() > 0;
        cfg.setWorkMode("period");
        periodContinuationScheduled = false;
        try {
            store.recoverInterruptedRunning("이전 실행 중단 복구");
            if (!preflight(true)) return;

            if (!continuation) {
                DocumentScanner.Result scan = new DocumentScanner(c, store).scanHistorical(start, end);
                int skippedByOutput = markExistingOutputs(start, end);
                JobStore.Stats periodStats = store.statsBetween(start, end);
                int already = periodStats.done + periodStats.skipped + periodStats.excluded;
                int target = Math.max(0, periodStats.total - already);
                cfg.setPeriodPlan(periodStats.total, already, target);
                cfg.setLastRunNote("기간 전사 · 확인 " + scan.visited + "개 · 새 전사 " + target + "개 · 기존 완료 " + already + "개 제외"
                        + (skippedByOutput > 0 ? " · TXT 일치 " + skippedByOutput + "개" : "")
                        + (scan.note.isEmpty() ? "" : " · " + scan.note));
            }

            if (cfg.periodTarget() <= 0) {
                cfg.setLastRunNote("기간 전사 · 기존 전사파일은 자동 제외 · 새로 전사할 파일 없음");
                return;
            }

            processPending(start, end);

            // A historical request must finish even when a file needs a delayed retry.
            // Schedule the continuation at the actual earliest retry time instead of polling.
            if (!periodContinuationScheduled && !shouldStop()) {
                long next = store.nextPendingAtInRange(start, end);
                if (next > 0L) {
                    periodContinuationScheduled = true;
                    Scheduler.historicalContinue(c, Math.max(1_000L, next - System.currentTimeMillis()));
                }
            }
        } catch (Throwable t) {
            cfg.setLastRunNote("기간 전사 오류: " + t.getClass().getSimpleName() + " · " + safe(t.getMessage()));
        } finally {
            if (!periodContinuationScheduled) cfg.finishPeriod();
            finishRun();
            if (periodContinuationScheduled) cfg.setWorkMode("period_pending");
            PROCESS_RUNNING.set(false);
        }
    }

    private int markExistingOutputs(long startMs, long endMs) {
        OutputStore.ExistingIndex index = new OutputStore(c).buildExistingIndex();
        int skipped = 0;
        for (JobRecord j : store.jobsInRange(startMs, endMs)) {
            if (Status.DONE.equals(j.status) || Status.NO_AUDIO.equals(j.status)
                    || Status.ALREADY_TRANSCRIBED.equals(j.status) || Status.USER_EXCLUDED.equals(j.status)) {
                continue;
            }
            String existing = index.find(j);
            if (!existing.isEmpty()) {
                store.markAlreadyTranscribed(j.id, existing);
                skipped++;
            }
        }
        return skipped;
    }

    private boolean preflight(boolean force) {
        if (!cfg.basicReady(c)) {
            cfg.setLastRunNote("설정 필요: 녹음폴더·저장폴더·모델 확인");
            return false;
        }
        if (!ModelManager.validateExisting(new java.io.File(cfg.modelPath()))) {
            cfg.setLastRunNote("모델 확인 실패: 다시 선택하거나 다운로드 필요");
            return false;
        }
        if (!force) {
            RuntimeConstraints.Check ck = RuntimeConstraints.check(c, cfg);
            if (!ck.ok) {
                cfg.setLastRunNote(ck.reason);
                return false;
            }
        }
        return true;
    }

    private void processPending(long startMs, long endMs) {
        int processed = 0;
        WhisperBridge whisper = null;
        try {
            while (!shouldStop()) {
                List<JobRecord> jobs = (startMs > 0L || endMs < Long.MAX_VALUE)
                        ? store.actionableInRange(System.currentTimeMillis(), 10, startMs, endMs)
                        : store.actionable(System.currentTimeMillis(), 10);
                if (jobs.isEmpty()) break;

                for (JobRecord j : jobs) {
                    if (shouldStop()) {
                        store.updateJob(j.id, Status.RETRY_WAIT, "중단됨", stopReason.isEmpty() ? "다음 실행에서 이어하기" : stopReason, "");
                        return;
                    }
                    // Load the native Whisper model only when there is actual pending work.
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
                }

                // Avoid monopolizing a background job indefinitely. If the OS lets us run,
                // continue in manageable batches; otherwise onStopJob reschedules and the ledger
                // resumes exactly where it left off.
                if (processed >= 100) {
                    if (manualHistorical) {
                        periodContinuationScheduled = true;
                        Scheduler.historicalContinue(c, 1_000L);
                    } else {
                        Scheduler.immediatePending(c, forceRun);
                    }
                    break;
                }
            }
        } finally {
            if (whisper != null) whisper.close();
        }
    }

    private boolean manualSkipRequested(long jobId) {
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
            Uri uri = Uri.parse(j.sourceUri);
            if (!waitUntilStable(j, uri)) return;
            if (manualSkipRequested(j.id)) {
                finishManualSkip(j, null, "전사 시작 전");
                return;
            }

            store.updateJob(j.id, Status.FETCHING, "원본 정보 확인", "", "");
            long dur = AudioChunkDecoder.durationMs(c, uri);
            if (dur <= 0) {
                store.scheduleRetry(j.id, "통화 길이를 읽지 못함");
                return;
            }

            int total = Math.max(1, (int)Math.ceil(dur / (double)SEGMENT_MS));
            store.setMetadata(j.id, dur, total);
            store.ensureSegments(j.id, dur, SEGMENT_MS);
            if (manualSkipRequested(j.id)) {
                finishManualSkip(j, null, "원본 확인 후");
                return;
            }

            List<SegmentRecord> segs = store.pendingSegments(j.id);
            for (SegmentRecord s : segs) {
                if (manualSkipRequested(j.id)) {
                    finishManualSkip(j, s, "구간 " + (s.index + 1) + "/" + total + " 시작 전");
                    return;
                }
                if (shouldStop()) {
                    store.updateJob(j.id, Status.RETRY_WAIT, "다음 실행에서 이어하기", stopReason, "");
                    return;
                }
                try {
                    store.updateJob(j.id, Status.TRANSCRIBING, "구간 " + (s.index + 1) + "/" + total + " 전사", "", "");
                    AudioChunkDecoder.Decoded d = AudioChunkDecoder.decodeRange(c, uri, s.startMs, s.endMs);
                    if (manualSkipRequested(j.id)) {
                        finishManualSkip(j, s, "구간 " + (s.index + 1) + "/" + total + " 디코딩 후");
                        return;
                    }
                    // Background work uses one Whisper thread. An explicit historical request may
                    // use two, still capped low enough to protect heat/battery.
                    String text = whisper.transcribe(d.pcm16k, "ko", manualHistorical ? 2 : 1);
                    s.decodedMs = d.decodedMs;
                    s.speechMs = d.speechMs;
                    s.text = text;
                    s.status = Status.DONE;
                    s.error = "";
                    store.saveSegment(s);
                } catch (Throwable x) {
                    s.retryCount++;
                    s.error = safe(x.getMessage());
                    boolean manualSkip = manualSkipRequested(j.id)
                            || s.error.contains("사용자 현재 파일 건너뜀");
                    if (manualSkip) {
                        finishManualSkip(j, s, "구간 " + (s.index + 1) + "/" + total);
                        return;
                    }
                    boolean stalled = whisper.isUnhealthy() || s.error.contains("전사 구간 시간초과");
                    if (stalled) {
                        // Keep the completed checkpoints, quarantine only this file for a short
                        // backoff, and immediately release the worker to the rest of the queue.
                        s.status = s.retryCount <= 3 ? Status.RETRY_WAIT : Status.FAILED;
                        store.saveSegment(s);
                        store.deferStalledSegment(j.id, "구간 " + (s.index + 1) + "/" + total + " · " + s.error, s.retryCount);
                        return;
                    }
                    s.status = s.retryCount < 3 ? Status.RETRY_WAIT : Status.FAILED;
                    store.saveSegment(s);
                }
            }

            if (manualSkipRequested(j.id)) {
                finishManualSkip(j, null, "구간 처리 후");
                return;
            }

            if (shouldStop()) {
                store.updateJob(j.id, Status.RETRY_WAIT, "중단됨",
                        stopReason.isEmpty() ? "다음 요청에서 이어하기" : stopReason, "");
                return;
            }

            store.refreshAggregates(j.id);
            j = store.get(j.id);
            StringBuilder all = new StringBuilder();
            for (SegmentRecord s : store.allSegments(j.id)) {
                if (s.text != null && !s.text.trim().isEmpty()) {
                    if (all.length() > 0) all.append('\n');
                    all.append(s.text.trim());
                }
            }

            QualityGuard.Input qi = new QualityGuard.Input();
            qi.durationMs = j.durationMs;
            qi.decodedMs = j.decodedDurationMs;
            qi.speechMs = j.speechMs;
            qi.processedUntilMs = j.processedUntilMs;
            qi.totalSegments = j.totalSegments;
            qi.doneSegments = j.doneSegments;
            qi.failedSegments = j.failedSegments;
            qi.text = all.toString();

            if (manualSkipRequested(j.id)) {
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
            String draft = os.writeDraft(j, all.toString(), qr);

            if (shouldStop()) {
                store.updateJob(j.id, Status.RETRY_WAIT, "중단됨",
                        stopReason.isEmpty() ? "다음 요청에서 이어하기" : stopReason, "");
                return;
            }

            if (qr.verdict == QualityGuard.Verdict.PASS) {
                store.updateJob(j.id, Status.SAVING, "TXT 저장 및 재확인", "", "");
                String finalUri = os.writeFinalIdempotent(j, all.toString(), qr);
                store.setResult(j.id, Status.DONE, "완료", preview(all.toString()), draft, finalUri, qr.reasonText());
                cfg.setLastSuccessMs(System.currentTimeMillis());
            } else if (qr.verdict == QualityGuard.Verdict.NO_AUDIO) {
                store.setResult(j.id, Status.NO_AUDIO, "음성 없음", preview(all.toString()), draft, "", qr.reasonText());
                cfg.setLastSuccessMs(System.currentTimeMillis());
            } else if (qr.verdict == QualityGuard.Verdict.PARTIAL) {
                if (store.countSegments(j.id, Status.RETRY_WAIT) > 0) {
                    store.setResult(j.id, Status.PARTIAL, "일부 완료", preview(all.toString()), draft, "", qr.reasonText());
                    store.schedulePartialRetry(j.id, qr.reasonText());
                } else {
                    store.setResult(j.id, Status.PARTIAL, "일부 완료 · 확인 필요", preview(all.toString()), draft, "", qr.reasonText());
                }
            } else {
                store.setResult(j.id, Status.REVIEW_REQUIRED, "확인 필요", preview(all.toString()), draft, "", qr.reasonText());
            }
        } catch (SecurityException se) {
            store.updateJob(j.id, Status.REVIEW_REQUIRED, "원본 접근 권한 확인", "권한 필요", safe(se.getMessage()));
        } catch (Throwable t) {
            store.scheduleRetry(j.id, t.getClass().getSimpleName() + " · " + safe(t.getMessage()));
        }
    }

    private boolean waitUntilStable(JobRecord j, Uri uri) {
        // Period transcription normally targets files whose recording finished minutes/hours/days
        // ago. Re-waiting 5 seconds per file would turn 169 historical recordings into a very
        // slow queue. If indexed metadata already proves the file is old and non-empty, simply
        // verify that Android can open it and start transcription immediately.
        if (manualHistorical && clearlySettledHistorical(j)) {
            if (canOpen(uri)) return true;
            store.scheduleRetry(j.id, "원본 파일을 열 수 없음");
            return false;
        }

        DocumentScanner scanner = new DocumentScanner(c, store);
        DocumentScanner.Probe a = scanner.probe(uri);
        if (!a.ok || a.size <= 0) {
            store.defer(j.id, "녹음 저장 완료 대기", FILE_RETRY_MS);
            return false;
        }
        try {
            Thread.sleep(FILE_SETTLE_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            store.defer(j.id, "다음 실행에서 이어하기", FILE_RETRY_MS);
            return false;
        }
        if (shouldStop()) return false;

        DocumentScanner.Probe b = scanner.probe(uri);
        if (!b.ok || b.size <= 0 || a.size != b.size || (a.modified > 0 && b.modified > 0 && a.modified != b.modified)) {
            store.defer(j.id, "녹음 저장 완료 대기", FILE_RETRY_MS);
            return false;
        }
        return true;
    }

    private boolean clearlySettledHistorical(JobRecord j) {
        if (j == null || j.sizeBytes <= 0L) return false;
        long stamp = j.modifiedMs > 0L ? j.modifiedMs : j.callTimeMs;
        return stamp > 0L && System.currentTimeMillis() - stamp >= 60_000L;
    }

    private boolean canOpen(Uri uri) {
        try (android.content.res.AssetFileDescriptor afd = c.getContentResolver().openAssetFileDescriptor(uri, "r")) {
            return afd != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private void finishRun() {
        cfg.clearWorkMode();
        cfg.setLastRunMs(System.currentTimeMillis());
        if (cfg.autoEnabled() && !cfg.paused()) {
            cfg.setNextExpectedMs(System.currentTimeMillis() + cfg.intervalMinutes() * 60_000L);
        }
        store.close();
    }

    private boolean shouldStop() {
        return stop || cfg.paused();
    }

    private static String preview(String s) {
        String x = s == null ? "" : s.replaceAll("\\s+", " ").trim();
        return x.length() > 160 ? x.substring(0, 160) + "…" : x;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
