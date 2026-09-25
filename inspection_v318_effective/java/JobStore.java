package com.onsamiro.transcriber;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

public final class JobStore extends SQLiteOpenHelper {
    private static final String DB = "onsamiro_jobs_v3.db";
    private static final int VER = 1;

    public static final class Stats {
        public int total, done, running, waiting, partial, failed, review, skipped, excluded;
        public int percent() { return total == 0 ? 0 : Math.min(100, (int)Math.round(done * 100.0 / total)); }
    }

    public JobStore(Context c) { super(c, DB, null, VER); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE jobs ("+
                "id INTEGER PRIMARY KEY AUTOINCREMENT,"+
                "source_uri TEXT NOT NULL UNIQUE, display_name TEXT NOT NULL, size_bytes INTEGER NOT NULL DEFAULT 0,"+
                "modified_ms INTEGER NOT NULL DEFAULT 0, call_time_ms INTEGER NOT NULL DEFAULT 0, duration_ms INTEGER NOT NULL DEFAULT 0,"+
                "contact_hint TEXT NOT NULL DEFAULT '', status TEXT NOT NULL, stage TEXT NOT NULL DEFAULT '',"+
                "started_at INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL DEFAULT 0, retry_count INTEGER NOT NULL DEFAULT 0,"+
                "next_attempt_ms INTEGER NOT NULL DEFAULT 0, wait_reason TEXT NOT NULL DEFAULT '', fail_reason TEXT NOT NULL DEFAULT '',"+
                "result_preview TEXT NOT NULL DEFAULT '', draft_path TEXT NOT NULL DEFAULT '', final_uri TEXT NOT NULL DEFAULT '',"+
                "decoded_duration_ms INTEGER NOT NULL DEFAULT 0, speech_ms INTEGER NOT NULL DEFAULT 0, processed_until_ms INTEGER NOT NULL DEFAULT 0,"+
                "total_segments INTEGER NOT NULL DEFAULT 0, done_segments INTEGER NOT NULL DEFAULT 0, failed_segments INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX idx_jobs_status ON jobs(status,next_attempt_ms,modified_ms)");
        db.execSQL("CREATE TABLE segments ("+
                "job_id INTEGER NOT NULL, segment_idx INTEGER NOT NULL, start_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL,"+
                "status TEXT NOT NULL, retry_count INTEGER NOT NULL DEFAULT 0, text TEXT NOT NULL DEFAULT '', error TEXT NOT NULL DEFAULT '',"+
                "decoded_ms INTEGER NOT NULL DEFAULT 0, speech_ms INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL DEFAULT 0,"+
                "PRIMARY KEY(job_id,segment_idx), FOREIGN KEY(job_id) REFERENCES jobs(id) ON DELETE CASCADE)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) { throw new IllegalStateException("No upgrade path yet"); }

    public long upsertDiscovered(String uri, String name, long size, long modified, long callTime, String contact) {
        SQLiteDatabase db = getWritableDatabase();
        long now = System.currentTimeMillis();
        try (Cursor c = db.rawQuery("SELECT id,size_bytes,modified_ms,status FROM jobs WHERE source_uri=?", new String[]{uri})) {
            if (c.moveToFirst()) {
                long id = c.getLong(0), oldSize = c.getLong(1), oldMod = c.getLong(2);
                String status = c.getString(3);
                if (oldSize != size || oldMod != modified) {
                    ContentValues v = new ContentValues();
                    v.put("display_name", name); v.put("size_bytes", size); v.put("modified_ms", modified); v.put("call_time_ms", callTime);
                    v.put("contact_hint", contact); v.put("updated_at", now);
                    if (!Status.DONE.equals(status)) { v.put("status", Status.DISCOVERED); v.put("stage", "원본 변경 감지"); }
                    db.update("jobs", v, "id=?", new String[]{Long.toString(id)});
                }
                return id;
            }
        }
        ContentValues v = new ContentValues();
        v.put("source_uri", uri); v.put("display_name", name); v.put("size_bytes", size); v.put("modified_ms", modified);
        v.put("call_time_ms", callTime); v.put("contact_hint", contact); v.put("status", Status.DISCOVERED); v.put("stage", "통화 발견"); v.put("updated_at", now);
        return db.insertOrThrow("jobs", null, v);
    }

    /**
     * Explicit user-requested historical transcription may safely release an old file that was
     * previously left in WAIT_ORIGINAL by an interrupted/older build. This never touches DONE
     * results and only applies to files whose metadata shows they are not currently being written.
     */
    public void reactivateHistoricalIfSettled(long id, long modifiedMs, long sizeBytes) {
        if (id <= 0 || sizeBytes <= 0) return;
        long age = modifiedMs > 0 ? System.currentTimeMillis() - modifiedMs : Long.MAX_VALUE;
        if (age < 60_000L) return;
        ContentValues v = new ContentValues();
        v.put("status", Status.DISCOVERED);
        v.put("stage", "기간 전사 준비");
        v.put("wait_reason", "");
        v.put("next_attempt_ms", 0);
        v.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().update(
                "jobs", v,
                "id=? AND status IN (?,?)",
                new String[]{Long.toString(id), Status.WAIT_ORIGINAL, Status.RETRY_WAIT});
    }

    public JobRecord get(long id) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT * FROM jobs WHERE id=?", new String[]{Long.toString(id)})) {
            return c.moveToFirst() ? fromJob(c) : null;
        }
    }

    public List<JobRecord> recent(int limit) {
        List<JobRecord> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT * FROM jobs ORDER BY modified_ms DESC,id DESC LIMIT ?", new String[]{Integer.toString(limit)})) {
            while (c.moveToNext()) out.add(fromJob(c));
        }
        return out;
    }

    public List<JobRecord> jobsInRange(long startMs, long endMs) {
        List<JobRecord> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT * FROM jobs WHERE call_time_ms>=? AND call_time_ms<=? ORDER BY modified_ms ASC,id ASC",
                new String[]{Long.toString(startMs), Long.toString(endMs)})) {
            while (c.moveToNext()) out.add(fromJob(c));
        }
        return out;
    }

    public JobRecord currentActiveInRange(long startMs, long endMs) {
        String sql = "SELECT * FROM jobs WHERE call_time_ms>=? AND call_time_ms<=? AND status IN (?,?,?,?) " +
                "ORDER BY updated_at DESC,id DESC LIMIT 1";
        String[] a = {Long.toString(startMs), Long.toString(endMs),
                Status.FETCHING, Status.TRANSCRIBING, Status.QUALITY_CHECK, Status.SAVING};
        try (Cursor c = getReadableDatabase().rawQuery(sql, a)) {
            return c.moveToFirst() ? fromJob(c) : null;
        }
    }

    public JobRecord currentActive() {
        String sql = "SELECT * FROM jobs WHERE status IN (?,?,?,?) ORDER BY updated_at DESC,id DESC LIMIT 1";
        String[] a = {Status.FETCHING, Status.TRANSCRIBING, Status.QUALITY_CHECK, Status.SAVING};
        try (Cursor c = getReadableDatabase().rawQuery(sql, a)) {
            return c.moveToFirst() ? fromJob(c) : null;
        }
    }

    public void markAlreadyTranscribed(long id, String finalUri) {
        ContentValues v = new ContentValues();
        v.put("status", Status.ALREADY_TRANSCRIBED);
        v.put("stage", "이미 전사됨 · 건너뜀");
        v.put("wait_reason", "");
        v.put("fail_reason", "");
        v.put("next_attempt_ms", 0);
        v.put("final_uri", nz(finalUri));
        v.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().update(
                "jobs", v,
                "id=? AND status NOT IN (?,?,?)",
                new String[]{Long.toString(id), Status.DONE, Status.NO_AUDIO, Status.USER_EXCLUDED});
    }

    public List<JobRecord> actionable(long now, int limit) {
        String sql = "SELECT * FROM jobs WHERE status IN (?,?,?) AND (next_attempt_ms=0 OR next_attempt_ms<=?) ORDER BY modified_ms ASC LIMIT ?";
        String[] a = {Status.DISCOVERED, Status.WAIT_ORIGINAL, Status.RETRY_WAIT, Long.toString(now), Integer.toString(limit)};
        List<JobRecord> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(sql, a)) { while (c.moveToNext()) out.add(fromJob(c)); }
        return out;
    }

    public List<JobRecord> actionableInRange(long now, int limit, long startMs, long endMs) {
        String sql = "SELECT * FROM jobs WHERE status IN (?,?,?) AND (next_attempt_ms=0 OR next_attempt_ms<=?) " +
                "AND call_time_ms>=? AND call_time_ms<=? ORDER BY modified_ms ASC LIMIT ?";
        String[] a = {Status.DISCOVERED, Status.WAIT_ORIGINAL, Status.RETRY_WAIT,
                Long.toString(now), Long.toString(startMs), Long.toString(endMs), Integer.toString(limit)};
        List<JobRecord> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(sql, a)) {
            while (c.moveToNext()) out.add(fromJob(c));
        }
        return out;
    }

    public void updateJob(long id, String status, String stage, String wait, String fail) {
        ContentValues v = new ContentValues(); v.put("status", status); v.put("stage", stage); v.put("wait_reason", nz(wait)); v.put("fail_reason", nz(fail)); v.put("updated_at", System.currentTimeMillis());
        if (Status.TRANSCRIBING.equals(status) || Status.FETCHING.equals(status)) v.put("started_at", System.currentTimeMillis());
        getWritableDatabase().update("jobs", v, "id=?", new String[]{Long.toString(id)});
    }

    public void setMetadata(long id, long durationMs, int totalSegments) {
        ContentValues v = new ContentValues(); v.put("duration_ms", durationMs); v.put("total_segments", totalSegments); v.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().update("jobs", v, "id=?", new String[]{Long.toString(id)});
    }

    public void ensureSegments(long jobId, long durationMs, long segmentMs) {
        int total = Math.max(1, (int)Math.ceil(durationMs / (double)segmentMs));
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (int i=0;i<total;i++) {
                long s=i*segmentMs, e=Math.min(durationMs,(i+1)*segmentMs);
                ContentValues v=new ContentValues(); v.put("job_id",jobId); v.put("segment_idx",i); v.put("start_ms",s); v.put("end_ms",e); v.put("status",Status.DISCOVERED); v.put("updated_at",System.currentTimeMillis());
                db.insertWithOnConflict("segments",null,v,SQLiteDatabase.CONFLICT_IGNORE);
            }
            ContentValues j=new ContentValues(); j.put("total_segments",total); j.put("updated_at",System.currentTimeMillis()); db.update("jobs",j,"id=?",new String[]{Long.toString(jobId)});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    public List<SegmentRecord> pendingSegments(long jobId) {
        List<SegmentRecord> out=new ArrayList<>();
        String[] states={Status.DISCOVERED,Status.RETRY_WAIT};
        try(Cursor c=getReadableDatabase().rawQuery("SELECT * FROM segments WHERE job_id=? AND status IN (?,?) ORDER BY segment_idx",new String[]{Long.toString(jobId),states[0],states[1]})) {
            while(c.moveToNext()) out.add(fromSegment(c));
        }
        return out;
    }

    public List<SegmentRecord> allSegments(long jobId) {
        List<SegmentRecord> out=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT * FROM segments WHERE job_id=? ORDER BY segment_idx",new String[]{Long.toString(jobId)})) { while(c.moveToNext()) out.add(fromSegment(c)); }
        return out;
    }

    public void saveSegment(SegmentRecord s) {
        ContentValues v=new ContentValues(); v.put("status",s.status); v.put("retry_count",s.retryCount); v.put("text",nz(s.text)); v.put("error",nz(s.error)); v.put("decoded_ms",s.decodedMs); v.put("speech_ms",s.speechMs); v.put("updated_at",System.currentTimeMillis());
        getWritableDatabase().update("segments",v,"job_id=? AND segment_idx=?",new String[]{Long.toString(s.jobId),Integer.toString(s.index)});
        refreshAggregates(s.jobId);
    }

    public void refreshAggregates(long jobId) {
        SQLiteDatabase db=getWritableDatabase(); int total=0,done=0,failed=0; long decoded=0,speech=0,until=0;
        try(Cursor c=db.rawQuery("SELECT status,decoded_ms,speech_ms,end_ms FROM segments WHERE job_id=?",new String[]{Long.toString(jobId)})) {
            while(c.moveToNext()) { total++; String st=c.getString(0); decoded+=c.getLong(1); speech+=c.getLong(2); if(Status.DONE.equals(st)){done++;until=Math.max(until,c.getLong(3));} if(Status.FAILED.equals(st))failed++; }
        }
        ContentValues v=new ContentValues(); v.put("total_segments",total);v.put("done_segments",done);v.put("failed_segments",failed);v.put("decoded_duration_ms",decoded);v.put("speech_ms",speech);v.put("processed_until_ms",until);v.put("updated_at",System.currentTimeMillis());db.update("jobs",v,"id=?",new String[]{Long.toString(jobId)});
    }

    public int countSegments(long jobId,String status) {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM segments WHERE job_id=? AND status=?",new String[]{Long.toString(jobId),status})) { return c.moveToFirst()?c.getInt(0):0; }
    }

    public void schedulePartialRetry(long id,String reason) {
        JobRecord j=get(id); int n=j==null?1:j.retryCount+1; long[] delays={15*60_000L,60*60_000L,4*60*60_000L};
        ContentValues v=new ContentValues(); v.put("retry_count",n); v.put("status",n<=3?Status.RETRY_WAIT:Status.PARTIAL); v.put("stage",n<=3?"실패 구간 재시도 대기":"일부 완료 · 수동 확인 필요"); v.put("next_attempt_ms",n<=3?System.currentTimeMillis()+delays[Math.min(n-1,2)]:0); v.put("fail_reason",nz(reason)); v.put("updated_at",System.currentTimeMillis()); getWritableDatabase().update("jobs",v,"id=?",new String[]{Long.toString(id)});
    }

    public void setResult(long id,String status,String stage,String preview,String draftPath,String finalUri,String reason) {
        ContentValues v=new ContentValues();v.put("status",status);v.put("stage",stage);v.put("result_preview",nz(preview));v.put("draft_path",nz(draftPath));v.put("final_uri",nz(finalUri));v.put("fail_reason",nz(reason));v.put("wait_reason","");v.put("updated_at",System.currentTimeMillis());getWritableDatabase().update("jobs",v,"id=?",new String[]{Long.toString(id)});
    }

    public void scheduleRetry(long id,String reason) {
        JobRecord j=get(id); int n=j==null?1:j.retryCount+1; long[] delays={15*60_000L,60*60_000L,4*60*60_000L};
        ContentValues v=new ContentValues();v.put("retry_count",n);v.put("status",n<=3?Status.RETRY_WAIT:Status.FAILED);v.put("stage",n<=3?"재시도 대기":"재시도 한도 초과");v.put("next_attempt_ms",n<=3?System.currentTimeMillis()+delays[Math.min(n-1,2)]:0);v.put("fail_reason",nz(reason));v.put("updated_at",System.currentTimeMillis());getWritableDatabase().update("jobs",v,"id=?",new String[]{Long.toString(id)});
    }

    public void defer(long id, String reason, long delayMs) {
        ContentValues v = new ContentValues();
        v.put("status", Status.WAIT_ORIGINAL);
        v.put("stage", reason);
        v.put("wait_reason", reason);
        v.put("next_attempt_ms", System.currentTimeMillis() + Math.max(1_000L, delayMs));
        v.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().update("jobs", v, "id=?", new String[]{Long.toString(id)});
    }

    /**
     * A stalled native transcription must not pin the whole queue. The problematic file is
     * moved out of the runnable set for a short backoff while newer files keep processing.
     * After three automatic retries it is isolated as PARTIAL for explicit review.
     */
    public void deferStalledSegment(long id, String reason, int segmentRetryCount) {
        long now = System.currentTimeMillis();
        ContentValues v = new ContentValues();
        if (segmentRetryCount <= 3) {
            long[] delays = {2 * 60_000L, 5 * 60_000L, 15 * 60_000L};
            int ix = Math.max(0, Math.min(delays.length - 1, segmentRetryCount - 1));
            v.put("status", Status.RETRY_WAIT);
            v.put("stage", "구간 자동복구 대기");
            v.put("wait_reason", "전사 정지 감지 · 다른 파일 계속 처리");
            v.put("next_attempt_ms", now + delays[ix]);
        } else {
            v.put("status", Status.PARTIAL);
            v.put("stage", "반복 정지 · 확인 필요");
            v.put("wait_reason", "");
            v.put("next_attempt_ms", 0);
        }
        v.put("fail_reason", nz(reason));
        v.put("updated_at", now);
        getWritableDatabase().update("jobs", v, "id=?", new String[]{Long.toString(id)});
    }

    public void retry(long id) {
        SQLiteDatabase db=getWritableDatabase();
        long now=System.currentTimeMillis();
        db.beginTransaction();
        try {
            // 사용자가 특정 통화를 다시 시도하면 실제 음성 구간부터 다시 처리한다.
            // REVIEW_REQUIRED/DONE처럼 기존 구간이 DONE인 경우도 재전사되어야 한다.
            db.execSQL("UPDATE segments SET status=?, retry_count=0, text='', error='', decoded_ms=0, speech_ms=0, updated_at=? WHERE job_id=?",
                    new Object[]{Status.DISCOVERED,now,id});
            ContentValues v=new ContentValues();
            v.put("status",Status.DISCOVERED);v.put("next_attempt_ms",0);v.put("retry_count",0);
            v.put("wait_reason","");v.put("fail_reason","");v.put("stage","사용자 재시도");
            v.put("result_preview","");v.put("draft_path","");v.put("final_uri","");
            v.put("decoded_duration_ms",0);v.put("speech_ms",0);v.put("processed_until_ms",0);
            v.put("done_segments",0);v.put("failed_segments",0);v.put("updated_at",now);
            db.update("jobs",v,"id=?",new String[]{Long.toString(id)});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    public void recoverInterruptedRunning() {
        recoverInterruptedRunning("이전 작업 중단 복구");
    }

    public void recoverInterruptedRunning(String reason) {
        ContentValues v = new ContentValues();
        v.put("status", Status.RETRY_WAIT);
        v.put("stage", reason);
        v.put("wait_reason", reason);
        v.put("next_attempt_ms", 0);
        v.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().update(
                "jobs", v,
                "status IN (?,?,?,?)",
                new String[]{Status.FETCHING, Status.TRANSCRIBING, Status.QUALITY_CHECK, Status.SAVING});
    }

    public void retryFailuresAndReviews() {
        SQLiteDatabase db=getWritableDatabase();
        long now=System.currentTimeMillis();
        db.beginTransaction();
        try {
            // 확인필요는 QC 결과를 바꾸려면 전체 구간을 실제로 다시 전사해야 한다.
            db.execSQL("UPDATE segments SET status=?, retry_count=0, text='', error='', decoded_ms=0, speech_ms=0, updated_at=? WHERE job_id IN (SELECT id FROM jobs WHERE status=?)",
                    new Object[]{Status.DISCOVERED,now,Status.REVIEW_REQUIRED});
            // 실패/재시도대기는 성공한 구간을 보존하고 실패 구간만 다시 처리한다.
            db.execSQL("UPDATE segments SET status=?, retry_count=0, text='', error='', decoded_ms=0, speech_ms=0, updated_at=? WHERE job_id IN (SELECT id FROM jobs WHERE status IN (?,?)) AND status IN (?,?)",
                    new Object[]{Status.DISCOVERED,now,Status.FAILED,Status.RETRY_WAIT,Status.FAILED,Status.RETRY_WAIT});
            db.execSQL("UPDATE jobs SET status=?, next_attempt_ms=0, retry_count=0, wait_reason='', fail_reason='', stage='사용자 일괄 재시도', result_preview='', draft_path='', final_uri='', updated_at=? WHERE status IN (?,?,?)",
                    new Object[]{Status.DISCOVERED,now,Status.FAILED,Status.RETRY_WAIT,Status.REVIEW_REQUIRED});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    public void markManualSkip(long id, String reason) {
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

    /**
     * Earliest time a non-terminal job in the selected period can run again.
     * Returns 0 when the period has no remaining retry/pending work.
     */
    public long nextPendingAtInRange(long startMs, long endMs) {
        String sql = "SELECT MIN(CASE WHEN next_attempt_ms<=0 THEN ? ELSE next_attempt_ms END) " +
                "FROM jobs WHERE call_time_ms>=? AND call_time_ms<=? AND status IN (?,?,?,?,?,?,?)";
        long now = System.currentTimeMillis();
        String[] a = {
                Long.toString(now), Long.toString(startMs), Long.toString(endMs),
                Status.DISCOVERED, Status.WAIT_ORIGINAL, Status.RETRY_WAIT,
                Status.FETCHING, Status.TRANSCRIBING, Status.QUALITY_CHECK, Status.SAVING
        };
        try (Cursor c = getReadableDatabase().rawQuery(sql, a)) {
            if (!c.moveToFirst() || c.isNull(0)) return 0L;
            return c.getLong(0);
        }
    }

    public Stats stats() {
        Stats s=new Stats();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT status,COUNT(*) FROM jobs GROUP BY status",null)) {
            while(c.moveToNext()) {
                String st=c.getString(0); int n=c.getInt(1); s.total+=n;
                if(Status.DONE.equals(st)||Status.NO_AUDIO.equals(st)) s.done+=n;
                else if(Status.ALREADY_TRANSCRIBED.equals(st)) s.skipped+=n;
                else if(Status.USER_EXCLUDED.equals(st)) s.excluded+=n;
                else if(Status.TRANSCRIBING.equals(st)||Status.FETCHING.equals(st)||Status.QUALITY_CHECK.equals(st)||Status.SAVING.equals(st)) s.running+=n;
                else if(Status.PARTIAL.equals(st)) s.partial+=n;
                else if(Status.REVIEW_REQUIRED.equals(st)) s.review+=n;
                else if(Status.FAILED.equals(st)) s.failed+=n;
                else s.waiting+=n;
            }
        }
        return s;
    }

    public Stats statsBetween(long startMs, long endMs) {
        Stats s = new Stats();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT status,COUNT(*) FROM jobs WHERE call_time_ms>=? AND call_time_ms<=? GROUP BY status",
                new String[]{Long.toString(startMs), Long.toString(endMs)})) {
            while (c.moveToNext()) {
                String st = c.getString(0);
                int n = c.getInt(1);
                s.total += n;
                if (Status.DONE.equals(st) || Status.NO_AUDIO.equals(st)) s.done += n;
                else if (Status.ALREADY_TRANSCRIBED.equals(st)) s.skipped += n;
                else if (Status.USER_EXCLUDED.equals(st)) s.excluded += n;
                else if (Status.TRANSCRIBING.equals(st) || Status.FETCHING.equals(st) || Status.QUALITY_CHECK.equals(st) || Status.SAVING.equals(st)) s.running += n;
                else if (Status.PARTIAL.equals(st)) s.partial += n;
                else if (Status.REVIEW_REQUIRED.equals(st)) s.review += n;
                else if (Status.FAILED.equals(st)) s.failed += n;
                else s.waiting += n;
            }
        }
        return s;
    }

    public JobRecord lastCompleted() {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT * FROM jobs WHERE status IN (?,?) ORDER BY updated_at DESC,id DESC LIMIT 1",
                new String[]{Status.DONE, Status.NO_AUDIO})) {
            return c.moveToFirst() ? fromJob(c) : null;
        }
    }

    private JobRecord fromJob(Cursor c) {
        JobRecord j=new JobRecord();
        j.id=lg(c,"id");j.sourceUri=st(c,"source_uri");j.displayName=st(c,"display_name");j.sizeBytes=lg(c,"size_bytes");j.modifiedMs=lg(c,"modified_ms");j.callTimeMs=lg(c,"call_time_ms");j.durationMs=lg(c,"duration_ms");j.contactHint=st(c,"contact_hint");j.status=st(c,"status");j.stage=st(c,"stage");j.startedAt=lg(c,"started_at");j.updatedAt=lg(c,"updated_at");j.retryCount=in(c,"retry_count");j.nextAttemptMs=lg(c,"next_attempt_ms");j.waitReason=st(c,"wait_reason");j.failReason=st(c,"fail_reason");j.resultPreview=st(c,"result_preview");j.draftPath=st(c,"draft_path");j.finalUri=st(c,"final_uri");j.decodedDurationMs=lg(c,"decoded_duration_ms");j.speechMs=lg(c,"speech_ms");j.processedUntilMs=lg(c,"processed_until_ms");j.totalSegments=in(c,"total_segments");j.doneSegments=in(c,"done_segments");j.failedSegments=in(c,"failed_segments");return j;
    }
    private SegmentRecord fromSegment(Cursor c) { SegmentRecord s=new SegmentRecord();s.jobId=lg(c,"job_id");s.index=in(c,"segment_idx");s.startMs=lg(c,"start_ms");s.endMs=lg(c,"end_ms");s.status=st(c,"status");s.retryCount=in(c,"retry_count");s.text=st(c,"text");s.error=st(c,"error");s.decodedMs=lg(c,"decoded_ms");s.speechMs=lg(c,"speech_ms");s.updatedAt=lg(c,"updated_at");return s; }
    private static String st(Cursor c,String n){return c.getString(c.getColumnIndexOrThrow(n));} private static long lg(Cursor c,String n){return c.getLong(c.getColumnIndexOrThrow(n));} private static int in(Cursor c,String n){return c.getInt(c.getColumnIndexOrThrow(n));} private static String nz(String s){return s==null?"":s;}
}
