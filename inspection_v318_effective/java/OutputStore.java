package com.onsamiro.transcriber;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class OutputStore {
    private final Context c;
    private final AppConfig cfg;

    public static final class ExistingIndex {
        private final Map<String, String> byStem = new HashMap<>();
        private int txtCount;

        public int txtCount() { return txtCount; }

        public String find(JobRecord j) {
            if (j == null) return "";
            String stem = normalizeSourceStem(j.displayName);
            String found = byStem.get(stem);
            return found == null ? "" : found;
        }
    }

    public OutputStore(Context c) {
        this.c = c;
        cfg = new AppConfig(c);
    }

    /**
     * Build one lightweight filename index of the selected TXT folder. Historical transcription
     * uses this once per request so already-saved calls are skipped before Whisper is loaded.
     * Matching is exact on the recording filename stem, accepting both legacy `name.txt` and
     * current `name_전사.txt` / hash-suffixed variants.
     */
    public ExistingIndex buildExistingIndex() {
        ExistingIndex out = new ExistingIndex();
        if (cfg.outputTree().isEmpty()) return out;
        try {
            Uri tree = Uri.parse(cfg.outputTree());
            String rootId = DocumentsContract.getTreeDocumentId(tree);
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, rootId);
            String[] projection = {
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_SIZE
            };
            try (Cursor cur = c.getContentResolver().query(children, projection, null, null, null)) {
                if (cur != null) {
                    while (cur.moveToNext()) {
                        String id = cur.getString(0);
                        String name = cur.getString(1);
                        long size = cur.isNull(2) ? -1L : cur.getLong(2);
                        if (id == null || name == null || !name.toLowerCase(Locale.ROOT).endsWith(".txt")) continue;
                        if (size == 0L) continue;
                        out.txtCount++;
                        String stem = normalizeTranscriptStem(name);
                        if (!stem.isEmpty() && !out.byStem.containsKey(stem)) {
                            Uri u = DocumentsContract.buildDocumentUriUsingTree(tree, id);
                            out.byStem.put(stem, u.toString());
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        return out;
    }

    private static String normalizeSourceStem(String name) {
        String n = name == null ? "" : name.trim();
        n = n.replaceAll("\\.[^.]+$", "");
        return normalizeStem(n);
    }

    private static String normalizeTranscriptStem(String name) {
        String n = name == null ? "" : name.trim();
        n = n.replaceAll("(?i)\\.txt$", "");
        n = n.replaceAll("(?i)_전사(?:_[0-9a-f]{8,64})?$", "");
        return normalizeStem(n);
    }

    private static String normalizeStem(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\/:*?\"<>|]", "_")
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    public String writeDraft(JobRecord j, String text, QualityGuard.Result q) throws Exception {
        File d = new File(c.getFilesDir(), "review");
        if (!d.exists()) d.mkdirs();
        File f = new File(d, "job_" + j.id + ".review.txt");
        try (FileOutputStream o = new FileOutputStream(f, false)) {
            o.write(render(j, text, q).getBytes(StandardCharsets.UTF_8));
            o.getFD().sync();
        }
        return f.getAbsolutePath();
    }

    /**
     * Idempotent final save. If Android kills the process after the TXT was written but before
     * the database was updated, the next run recognizes the already-saved file and reuses it
     * instead of creating a duplicate.
     */
    public String writeFinalIdempotent(JobRecord j, String text, QualityGuard.Result q) throws Exception {
        if (cfg.outputTree().isEmpty()) throw new IllegalStateException("TXT 저장폴더 미선택");

        Uri tree = Uri.parse(cfg.outputTree());
        String rootId = DocumentsContract.getTreeDocumentId(tree);
        Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree, rootId);
        Map<String, Uri> children = listChildren(tree, rootId);

        String key = sourceKey(j.sourceUri);
        String base = safeBase(j.displayName) + "_전사";
        String primaryName = base + ".txt";
        String fallbackName = base + "_" + key.substring(0, 8) + ".txt";

        Uri existing = children.get(primaryName);
        if (existing != null && verifySaved(existing, key)) return existing.toString();

        existing = children.get(fallbackName);
        if (existing != null && verifySaved(existing, key)) return existing.toString();

        String name = children.containsKey(primaryName) ? fallbackName : primaryName;
        if (children.containsKey(name)) {
            // Extremely rare hash/name collision with unrelated content. Use a stable longer key.
            name = base + "_" + key.substring(0, 16) + ".txt";
            Uri e2 = children.get(name);
            if (e2 != null && verifySaved(e2, key)) return e2.toString();
        }

        Uri out = DocumentsContract.createDocument(c.getContentResolver(), parent, "text/plain", name);
        if (out == null) throw new IllegalStateException("최종 TXT 생성 실패");

        byte[] payload = render(j, text, q).getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = c.getContentResolver().openOutputStream(out, "w")) {
            if (os == null) throw new IllegalStateException("최종 TXT 쓰기 실패");
            os.write(payload);
            os.flush();
        }

        if (!verifySaved(out, key)) {
            throw new IllegalStateException("TXT 저장 후 재확인 실패");
        }
        return out.toString();
    }

    private Map<String, Uri> listChildren(Uri tree, String rootId) {
        Map<String, Uri> out = new HashMap<>();
        Uri u = DocumentsContract.buildChildDocumentsUriUsingTree(tree, rootId);
        String[] projection = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
        };
        try (Cursor cur = c.getContentResolver().query(u, projection, null, null, null)) {
            if (cur != null) {
                while (cur.moveToNext()) {
                    String id = cur.getString(0);
                    String name = cur.getString(1);
                    if (name != null && id != null) {
                        out.put(name, DocumentsContract.buildDocumentUriUsingTree(tree, id));
                    }
                }
            }
        } catch (Throwable ignored) {}
        return out;
    }

    private boolean verifySaved(Uri uri, String expectedKey) {
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            if (in == null) return false;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[2048];
            int total = 0;
            while (total < 16_384) {
                int n = in.read(b, 0, Math.min(b.length, 16_384 - total));
                if (n <= 0) break;
                out.write(b, 0, n);
                total += n;
            }
            String head = out.toString(StandardCharsets.UTF_8.name());
            return head.contains("원본ID: " + expectedKey) && head.contains("온새미로 통화전사");
        } catch (Throwable t) {
            return false;
        }
    }

    private String render(JobRecord j, String text, QualityGuard.Result q) {
        return "온새미로 통화전사\n"
                + "원본: " + j.displayName + "\n"
                + "원본ID: " + sourceKey(j.sourceUri) + "\n"
                + "통화시각: " + new Date(j.callTimeMs) + "\n"
                + "원본길이(ms): " + j.durationMs + "\n"
                + "처리구간: " + j.doneSegments + "/" + j.totalSegments + "\n"
                + "품질상태: " + q.verdict + "\n"
                + "품질근거: " + q.reasonText() + "\n\n"
                + text.trim() + "\n";
    }

    private String sourceKey(String sourceUri) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest((sourceUri == null ? "" : sourceUri).getBytes(StandardCharsets.UTF_8));
            StringBuilder s = new StringBuilder();
            for (byte x : d) s.append(String.format(Locale.ROOT, "%02x", x));
            return s.toString();
        } catch (Throwable t) {
            return Integer.toHexString((sourceUri == null ? "" : sourceUri).hashCode()) + "0000000000000000";
        }
    }

    private String safeBase(String n) {
        String x = n == null ? "통화" : n.replaceAll("\\.[^.]+$", "").replaceAll("[\\\\/:*?\"<>|]", "_");
        return x.length() > 80 ? x.substring(0, 80) : x;
    }
}
