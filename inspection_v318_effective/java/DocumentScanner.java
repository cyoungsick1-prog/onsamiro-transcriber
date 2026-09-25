package com.onsamiro.transcriber;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.content.res.AssetFileDescriptor;

import java.util.ArrayDeque;
import java.util.Locale;

public final class DocumentScanner {
    public static final class Result {
        public int visited;
        public int discovered;
        public boolean truncated;
        public String note = "";
    }

    public static final class DirectResult {
        public boolean discovered;
        public boolean needsRecoveryScan;
        public String note = "";
    }

    public static final class Probe {
        public boolean ok;
        public long size;
        public long modified;
        public String note = "";
    }

    private static final int HISTORICAL_MAX_DOCS = 20_000;
    private static final int MAX_DEPTH = 6;
    private static final long AUTO_OVERLAP_MS = 2L * 60L * 1000L;
    private static final int AUTO_SAF_FALLBACK_MAX_DOCS = Integer.MAX_VALUE; // selected recording tree must be exhausted

    private final Context c;
    private final JobStore store;
    private final AppConfig cfg;

    public DocumentScanner(Context c, JobStore store) {
        this.c = c;
        this.store = store;
        this.cfg = new AppConfig(c);
    }

    private static final class Dir {
        final Uri children;
        final int depth;
        Dir(Uri u, int d) { children = u; depth = d; }
    }

    /**
     * Ingest a URI delivered by JobScheduler's content trigger. If the provider gives an
     * actual document URI, only that one item is read. Broad tree/children notifications are
     * marked for a lightweight recovery scan instead of treating every old file as new.
     */
    public DirectResult ingestTriggeredUri(Uri changedUri) {
        DirectResult r = new DirectResult();
        if (changedUri == null) {
            r.needsRecoveryScan = true;
            r.note = "변경 파일 URI 없음";
            return r;
        }
        String treeText = cfg.sourceTree();
        if (treeText.isEmpty()) {
            r.note = "녹음폴더 미선택";
            return r;
        }
        Uri tree = Uri.parse(treeText);
        if (tree.getAuthority() == null || !tree.getAuthority().equals(changedUri.getAuthority())) {
            r.note = "선택 폴더 밖 변경 무시";
            return r;
        }

        String path = changedUri.getPath() == null ? "" : changedUri.getPath();
        if ((path.contains("/tree/") && !path.contains("/document/")) || path.endsWith("/children")) {
            r.needsRecoveryScan = true;
            r.note = "폴더 변경 알림 · 신규구간만 보완확인";
            return r;
        }

        String[] proj = projection();
        try (Cursor cur = c.getContentResolver().query(changedUri, proj, null, null, null)) {
            if (cur == null || !cur.moveToFirst()) {
                r.needsRecoveryScan = true;
                r.note = "변경 파일 정보 없음";
                return r;
            }
            String id = cur.getString(0);
            String name = cur.getString(1);
            String mime = cur.getString(2);
            long size = cur.isNull(3) ? 0 : cur.getLong(3);
            long mod = cur.isNull(4) ? 0 : cur.getLong(4);

            if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                r.needsRecoveryScan = true;
                r.note = "폴더 변경 알림 · 신규구간만 보완확인";
                return r;
            }
            if (!isAudio(name, mime)) {
                r.note = "오디오가 아닌 변경 무시";
                return r;
            }
            if (mod > 0 && cfg.autoBaselineMs() > 0 && mod < cfg.autoBaselineMs()) {
                r.note = "기준선 이전 기존 파일 변경 무시";
                return r;
            }

            Uri doc = changedUri;
            if (id != null && !id.isEmpty()) {
                try { doc = DocumentsContract.buildDocumentUriUsingTree(tree, id); }
                catch (Throwable ignored) {}
            }

            // A direct content-trigger is itself evidence that this file just changed. Even if
            // LAST_MODIFIED is unavailable, it is safe to queue this one file. Stability is
            // checked immediately before decoding.
            long callTime = mod > 0 ? mod : System.currentTimeMillis();
            store.upsertDiscovered(doc.toString(), safeName(name), size, mod, callTime, contactHint(name));
            r.discovered = true;
            r.note = "새 통화 1건 감지";
            return r;
        } catch (SecurityException se) {
            r.note = "폴더 읽기 권한 없음";
            return r;
        } catch (Throwable t) {
            r.needsRecoveryScan = true;
            r.note = "변경 파일 확인 오류: " + t.getClass().getSimpleName();
            return r;
        }
    }

    /**
     * Automatic recovery scan.
     * Prefer MediaStore's indexed date query so automatic checks never walk thousands of SAF rows.
     * If the selected provider cannot support the indexed path, automatic bulk traversal is skipped;
     * the explicit period-transcription path remains available to the user.
     */
    public Result scanRecovery() {
        cfg.ensureAutoBaseline();
        long baseline = cfg.autoBaselineMs();
        long previous = cfg.lastRecoveryScanMs();
        long started = System.currentTimeMillis();

        MediaDeltaScanner.Result m = new MediaDeltaScanner(c, store).scanRecovery(baseline, previous);
        Result r = new Result();
        r.visited = m.visited;
        r.discovered = m.discovered;
        r.note = m.note;

        // Samsung call-recording folders and some SAF providers are not always mirrored into
        // MediaStore. In that case the old implementation reported "metadata 0" forever and
        // never discovered a new recording. Fall back to a bounded metadata-only SAF scan of
        // the selected recording tree. This reads names/size/mtime only, never audio bytes.
        boolean needSafFallback = !m.available || m.permissionMissing || m.visited == 0;
        if (needSafFallback) {
            long since = Math.max(baseline, Math.max(0L, previous - AUTO_OVERLAP_MS));
            Result saf = scanRange(since, started + 60_000L, false, AUTO_SAF_FALLBACK_MAX_DOCS);
            r.visited += saf.visited;
            r.discovered += saf.discovered;
            String prefix = m.note == null || m.note.isEmpty() ? "" : m.note + " · ";
            r.note = prefix + "녹음폴더 전체확인"
                    + (saf.note == null || saf.note.isEmpty() ? "" : " · " + saf.note);
        }

        // Advance the checkpoint only after a complete scan. If any safety limit or provider
        // interruption truncates the scan, keep the previous checkpoint so a missed recording
        // is never made permanently invisible on the next pass.
        if (!r.truncated) cfg.setLastRecoveryScanMs(started);
        else r.note = (r.note == null || r.note.isEmpty() ? "" : r.note + " · ") + "기준시각 유지";
        return r;
    }

    /** User-requested historical scan. Old files are allowed only in this explicit path. */
    public Result scanHistorical(long startMs, long endMs) {
        MediaDeltaScanner.Result m = new MediaDeltaScanner(c, store).scanRange(startMs, endMs);
        if (m.available && !m.permissionMissing) {
            Result r = new Result();
            r.visited = m.visited;
            r.discovered = m.discovered;
            r.note = m.note;
            return r;
        }
        return scanRange(startMs, endMs, true, HISTORICAL_MAX_DOCS);
    }

    private Result scanRange(long since, long until, boolean historical, int maxDocs) {
        Result r = new Result();
        String s = cfg.sourceTree();
        if (s.isEmpty()) {
            r.note = "녹음폴더 미선택";
            return r;
        }

        Uri tree = Uri.parse(s);
        ContentResolver cr = c.getContentResolver();
        String rootId;
        try {
            rootId = DocumentsContract.getTreeDocumentId(tree);
        } catch (Throwable t) {
            r.note = "녹음폴더 형식 오류";
            return r;
        }

        ArrayDeque<Dir> q = new ArrayDeque<>();
        q.add(new Dir(DocumentsContract.buildChildDocumentsUriUsingTree(tree, rootId), 0));
        String[] proj = projection();

        while (!q.isEmpty() && r.visited < maxDocs) {
            Dir d = q.removeFirst();
            // Ask providers that honor sort order to give newest items first. Correctness does
            // not depend on it; it just makes common providers cheaper.
            String sort = DocumentsContract.Document.COLUMN_LAST_MODIFIED + " DESC";
            try (Cursor cur = cr.query(d.children, proj, null, null, sort)) {
                if (cur == null) continue;
                while (cur.moveToNext() && r.visited < maxDocs) {
                    r.visited++;
                    String id = cur.getString(0);
                    String name = cur.getString(1);
                    String mime = cur.getString(2);
                    long size = cur.isNull(3) ? 0 : cur.getLong(3);
                    long mod = cur.isNull(4) ? 0 : cur.getLong(4);
                    Uri doc = DocumentsContract.buildDocumentUriUsingTree(tree, id);

                    if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                        // For automatic recovery, directory timestamps can prune old subtrees.
                        if (d.depth < MAX_DEPTH) {
                            q.addLast(new Dir(DocumentsContract.buildChildDocumentsUriUsingTree(tree, id), d.depth + 1));
                        }
                        continue;
                    }
                    if (!isAudio(name, mime)) continue;

                    if (historical) {
                        if (mod == 0 || mod < since || mod > until) continue;
                        long jobId = store.upsertDiscovered(doc.toString(), safeName(name), size, mod, mod, contactHint(name));
                        store.reactivateHistoricalIfSettled(jobId, mod, size);
                        r.discovered++;
                    } else {
                        // Never auto-import an undated old file from a broad scan. A direct
                        // content-trigger can still safely import an undated newly changed file.
                        if (mod <= 0 || mod < since || mod > until) continue;
                        store.upsertDiscovered(doc.toString(), safeName(name), size, mod, mod, contactHint(name));
                        r.discovered++;
                    }
                }
            } catch (SecurityException se) {
                r.note = "폴더 읽기 권한이 없습니다";
                break;
            } catch (Throwable t) {
                r.note = "폴더 확인 오류: " + t.getClass().getSimpleName();
            }
        }

        if (r.visited >= maxDocs) {
            r.truncated = true;
            r.note = "확인 한도 " + maxDocs + "개 도달";
        }
        return r;
    }

    public Probe probe(Uri uri) {
        Probe p = new Probe();
        ContentResolver cr = c.getContentResolver();

        // MediaStore URIs and SAF document URIs expose different metadata columns.
        // Query each provider with the columns it actually understands, then fall back to
        // the file descriptor length. This is what prevents old MediaStore files from being
        // misclassified forever as "녹음 저장 완료 대기".
        boolean media = uri != null && "media".equals(uri.getAuthority());
        try {
            if (media) {
                try (Cursor cur = cr.query(
                        uri,
                        new String[]{OpenableColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED},
                        null, null, null)) {
                    if (cur != null && cur.moveToFirst()) {
                        p.size = cur.isNull(0) ? 0L : cur.getLong(0);
                        p.modified = cur.isNull(1) ? 0L : cur.getLong(1) * 1000L;
                        p.ok = true;
                    }
                }
            } else {
                try (Cursor cur = cr.query(
                        uri,
                        new String[]{DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED},
                        null, null, null)) {
                    if (cur != null && cur.moveToFirst()) {
                        p.size = cur.isNull(0) ? 0L : cur.getLong(0);
                        p.modified = cur.isNull(1) ? 0L : cur.getLong(1);
                        p.ok = true;
                    }
                }
            }
        } catch (Throwable ignored) {
            // The provider may not expose one of the requested metadata columns.
        }

        if (p.size <= 0L) {
            try (AssetFileDescriptor afd = cr.openAssetFileDescriptor(uri, "r")) {
                if (afd != null) {
                    long len = afd.getLength();
                    if (len > 0L) p.size = len;
                    p.ok = true;
                }
            } catch (Throwable t) {
                if (!p.ok) p.note = t.getClass().getSimpleName();
            }
        }

        if (!p.ok && p.note.isEmpty()) p.note = "원본 정보 없음";
        return p;
    }

    private static String[] projection() {
        return new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
        };
    }

    private boolean isAudio(String name, String mime) {
        if (mime != null && mime.startsWith("audio/")) return true;
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return n.endsWith(".m4a") || n.endsWith(".aac") || n.endsWith(".mp3")
                || n.endsWith(".wav") || n.endsWith(".amr") || n.endsWith(".3gp")
                || n.endsWith(".ogg") || n.endsWith(".opus") || n.endsWith(".flac")
                || n.endsWith(".mp4");
    }

    private String contactHint(String n) {
        if (n == null) return "";
        String x = n.replaceAll("\\.[^.]+$", "").replaceAll("[_-]+", " ").trim();
        return x.length() > 48 ? x.substring(0, 48) : x;
    }

    private static String safeName(String name) {
        return name == null || name.trim().isEmpty() ? "recording" : name;
    }
}
