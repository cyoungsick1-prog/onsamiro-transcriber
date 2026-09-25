package com.onsamiro.transcriber;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.DocumentsContract;
import android.provider.MediaStore;

import java.util.Locale;

/**
 * Low-heat recovery path for primary shared storage.
 * Instead of walking every SAF document, query MediaStore only for rows modified
 * after the saved checkpoint and inside the selected source tree.
 */
public final class MediaDeltaScanner {
    public static final class Result {
        public boolean available;
        public boolean permissionMissing;
        public int visited;
        public int discovered;
        public String note = "";
    }

    private static final long OVERLAP_MS = 2L * 60L * 1000L;

    private final Context c;
    private final AppConfig cfg;
    private final JobStore store;

    public MediaDeltaScanner(Context c, JobStore store) {
        this.c = c;
        this.cfg = new AppConfig(c);
        this.store = store;
    }

    public static boolean hasAudioReadPermission(Context c) {
        if (Build.VERSION.SDK_INT >= 33) {
            return c.checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED;
        }
        return c.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    public static boolean treeSupported(String treeText) {
        if (treeText == null || treeText.isEmpty()) return false;
        try {
            Uri tree = Uri.parse(treeText);
            if (!"com.android.externalstorage.documents".equals(tree.getAuthority())) return false;
            String id = DocumentsContract.getTreeDocumentId(tree);
            return id != null && id.startsWith("primary:");
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean canUse(Context c, String treeText) {
        return treeSupported(treeText) && hasAudioReadPermission(c);
    }

    public Result scanRecovery(long baselineMs, long previousScanMs) {
        long since = Math.max(baselineMs, Math.max(0L, previousScanMs - OVERLAP_MS));
        return scanRangeInternal(since, Long.MAX_VALUE, false);
    }

    /** Explicit user-requested period scan. */
    public Result scanRange(long sinceMs, long untilMs) {
        return scanRangeInternal(sinceMs, untilMs, true);
    }

    private Result scanRangeInternal(long sinceMs, long untilMs, boolean historical) {
        Result r = new Result();
        String treeText = cfg.sourceTree();
        if (!treeSupported(treeText)) {
            r.note = "저발열 인덱스 미지원 폴더";
            return r;
        }
        r.available = true;
        if (!hasAudioReadPermission(c)) {
            r.permissionMissing = true;
            r.note = "오디오 접근 권한 필요";
            return r;
        }

        String relative = relativePath(treeText);
        if (relative == null) {
            r.available = false;
            r.note = "선택 폴더 경로 확인 실패";
            return r;
        }

        Uri files = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        String[] projection = new String[]{
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.MIME_TYPE,
                MediaStore.Audio.Media.SIZE,
                MediaStore.Audio.Media.DATE_MODIFIED,
                MediaStore.Audio.Media.RELATIVE_PATH
        };

        long sinceSec = Math.max(0L, sinceMs / 1000L);
        long untilSec = untilMs == Long.MAX_VALUE ? Long.MAX_VALUE : Math.max(0L, untilMs / 1000L + 1L);
        String selection;
        String[] args;
        if (relative.isEmpty()) {
            selection = MediaStore.Audio.Media.DATE_MODIFIED + " >= ? AND "
                    + MediaStore.Audio.Media.DATE_MODIFIED + " <= ?";
            args = new String[]{String.valueOf(sinceSec), String.valueOf(untilSec)};
        } else {
            selection = MediaStore.Audio.Media.DATE_MODIFIED + " >= ? AND "
                    + MediaStore.Audio.Media.DATE_MODIFIED + " <= ? AND "
                    + MediaStore.Audio.Media.RELATIVE_PATH + " LIKE ?";
            args = new String[]{String.valueOf(sinceSec), String.valueOf(untilSec), relative + "%"};
        }

        String sort = MediaStore.Audio.Media.DATE_MODIFIED + " ASC";
        try (Cursor cur = c.getContentResolver().query(files, projection, selection, args, sort)) {
            if (cur == null) {
                r.note = "오디오 인덱스 조회 실패";
                return r;
            }
            while (cur.moveToNext()) {
                r.visited++;
                long id = cur.getLong(0);
                String name = cur.getString(1);
                String mime = cur.getString(2);
                long size = cur.isNull(3) ? 0L : cur.getLong(3);
                long modMs = cur.isNull(4) ? 0L : cur.getLong(4) * 1000L;
                if (!isAudio(name, mime)) continue;
                if (modMs <= 0L || modMs < sinceMs || (untilMs != Long.MAX_VALUE && modMs > untilMs)) continue;

                Uri doc = ContentUris.withAppendedId(files, id);
                long jobId = store.upsertDiscovered(doc.toString(), safeName(name), size, modMs, modMs, contactHint(name));
                if (historical) {
                    // Explicit period transcription: old, already-written files must not remain
                    // stuck in WAIT_ORIGINAL from a previous interrupted run/build.
                    store.reactivateHistoricalIfSettled(jobId, modMs, size);
                }
                r.discovered++;
            }
            r.note = "저발열 인덱스 조회";
            return r;
        } catch (SecurityException se) {
            r.permissionMissing = true;
            r.note = "오디오 접근 권한 필요";
            return r;
        } catch (Throwable t) {
            r.note = "오디오 인덱스 오류: " + t.getClass().getSimpleName();
            return r;
        }
    }

    private static String relativePath(String treeText) {
        try {
            String id = DocumentsContract.getTreeDocumentId(Uri.parse(treeText));
            String path = id.substring("primary:".length());
            while (path.startsWith("/")) path = path.substring(1);
            if (path.isEmpty()) return "";
            return path.endsWith("/") ? path : path + "/";
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isAudio(String name, String mime) {
        if (mime != null && mime.toLowerCase(Locale.ROOT).startsWith("audio/")) return true;
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return n.endsWith(".m4a") || n.endsWith(".mp3") || n.endsWith(".wav")
                || n.endsWith(".aac") || n.endsWith(".3gp") || n.endsWith(".ogg")
                || n.endsWith(".opus") || n.endsWith(".amr") || n.endsWith(".flac");
    }

    private static String safeName(String s) {
        if (s == null || s.trim().isEmpty()) return "통화녹음";
        return s.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static String contactHint(String name) {
        if (name == null) return "";
        String s = name;
        int dot = s.lastIndexOf('.');
        if (dot > 0) s = s.substring(0, dot);
        s = s.replaceAll("_[0-9]{8,17}$", "");
        return s.trim();
    }
}
