package com.onsamiro.transcriber;

import android.app.Activity;
import android.Manifest;
import android.app.AlertDialog;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.os.Bundle;
import android.os.Build;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class SettingsActivity extends Activity {
    private static final int PICK_SOURCE = 100;
    private static final int PICK_OUTPUT = 101;
    private static final int PICK_MODEL = 102;
    private static final int REQ_AUDIO_READ = 103;

    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private AppConfig cfg;
    private TextView status;
    private TextView diagnostic;
    private Spinner interval;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        cfg = new AppConfig(this);
        buildUi();
        refresh();
    }

    @Override protected void onResume() { super.onResume(); refresh(); }
    @Override protected void onDestroy() { bg.shutdownNow(); super.onDestroy(); }

    private void buildUi() {
        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(16), dp(18), dp(28));
        sv.addView(root);

        root.addView(text("설정", 28, true));
        root.addView(text("처음 한 번만 연결하면 평소에는 메인 화면만 보면 됩니다", 14, false));
        status = text("", 16, true);
        root.addView(status);

        root.addView(section("필수 설정"));
        root.addView(button("1. 익시오 녹음폴더 선택", v -> pickTree(PICK_SOURCE)));
        root.addView(button("2. TXT 저장폴더 선택", v -> pickTree(PICK_OUTPUT)));
        root.addView(button("3. 추천 한국어 모델 다운로드", v -> downloadModel()));
        root.addView(button("모델 파일 직접 선택", v -> pickModel()));

        root.addView(section("자동 복구"));
        root.addView(text("실시간 전사가 켜져 있으면 백그라운드 보호 서비스를 유지합니다. 새 파일 변경 신호를 우선 사용하고, 놓친 구간은 예약 복구가 보완합니다.", 13, false));
        root.addView(button("저발열 복구용 오디오 접근 권한", v -> requestAudioPermission()));
        interval = new Spinner(this);
        String[] opts = {"30분", "1시간", "2시간 (권장)", "4시간"};
        interval.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, opts));
        interval.setSelection(indexFor(cfg.intervalMinutes()));
        root.addView(interval);
        root.addView(button("보완 확인 주기 적용", v -> {
            int[] m = {30, 60, 120, 240};
            cfg.setIntervalMinutes(m[interval.getSelectedItemPosition()]);
            Scheduler.reschedule(this);
            TranscriptionKeepAliveService.refresh(this);
            toast("보완 확인 주기를 적용했어요");
            refresh();
        }));

        root.addView(text("실시간 전사 ON/OFF는 메인 화면에서 조작합니다.", 13, false));
        root.addView(check("Wi-Fi에서만 자동 실행", cfg.wifiOnly(), v -> {
            cfg.setWifiOnly(((CheckBox)v).isChecked()); Scheduler.reschedule(this); TranscriptionKeepAliveService.refresh(this);
        }));
        root.addView(check("충전 중에만 자동 실행", cfg.chargingOnly(), v -> {
            cfg.setChargingOnly(((CheckBox)v).isChecked()); Scheduler.reschedule(this); TranscriptionKeepAliveService.refresh(this);
        }));
        root.addView(check("배터리 20% 미만이면 자동 실행 대기", cfg.batteryNotLow(), v -> {
            cfg.setBatteryNotLow(((CheckBox)v).isChecked()); Scheduler.reschedule(this); TranscriptionKeepAliveService.refresh(this);
        }));

        root.addView(section("진단"));
        diagnostic = text("", 13, false);
        root.addView(diagnostic);
        root.addView(button("진단정보 복사", v -> copyDiagnostic()));
        root.addView(button("새 통화/누락 구간 지금 확인", v -> {
            Scheduler.immediateRecovery(this, true);
            toast("확인을 요청했어요");
        }));
        root.addView(button("실패·확인필요 다시 시도", v -> {
            try (JobStore s = new JobStore(this)) { s.retryFailuresAndReviews(); }
            Scheduler.immediatePending(this, true);
            toast("재시도를 요청했어요");
        }));
        root.addView(button("기존 TXT 의심결과 빠른 검사", v -> auditLegacy()));

        // Keep the final settings controls comfortably above the Android navigation area.
        View bottomSpacer = new View(this);
        root.addView(bottomSpacer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(160)));

        setContentView(sv);
    }

    private void refresh() {
        if (status == null) return;
        StringBuilder s = new StringBuilder(cfg.basicReady(this) ? "설정 완료" : "설정이 필요합니다");
        s.append("\n녹음폴더: ").append(cfg.sourceTree().isEmpty() ? "미선택" : "연결됨");
        s.append("\nTXT 폴더: ").append(cfg.outputTree().isEmpty() ? "미선택" : "연결됨");
        s.append("\n모델: ").append(cfg.modelPath().isEmpty() ? "미선택" : new File(cfg.modelPath()).getName());
        status.setText(s.toString());
        diagnostic.setText(buildDiagnostic());
    }

    private String buildDiagnostic() {
        StringBuilder d = new StringBuilder();
        d.append("앱 버전: ").append(appVersion()).append('\n');
        d.append("자동전사: ").append(cfg.autoEnabled() && !cfg.paused() ? "ON" : "OFF/정지").append('\n');
        d.append("백그라운드 보호: ").append(cfg.autoEnabled() && !cfg.paused() && cfg.basicReady(this) ? "사용 중" : "대기/꺼짐").append('\n');
        d.append("보호 서비스 최근 신호: ").append(fmt(cfg.serviceHeartbeatMs())).append('\n');
        d.append("실시간 폴더 확인: 약 45초\n");
        d.append("예약 보완 주기: ").append(cfg.intervalMinutes()).append("분\n");
        d.append("자동 기준선: ").append(fmt(cfg.autoBaselineMs())).append('\n');
        d.append("최근 콘텐츠 감지: ").append(fmt(cfg.lastContentTriggerMs())).append('\n');
        d.append("최근 보완 확인: ").append(fmt(cfg.lastRecoveryScanMs())).append('\n');
        d.append("저발열 인덱스: ")
                .append(MediaDeltaScanner.treeSupported(cfg.sourceTree())
                        ? (MediaDeltaScanner.hasAudioReadPermission(this) ? "사용 가능" : "권한 필요")
                        : "선택 폴더 미지원")
                .append('\n');
        d.append("최근 전사 성공: ").append(fmt(cfg.lastSuccessMs())).append('\n');
        d.append("최근 상태: ").append(cfg.lastRunNote()).append('\n');
        d.append("예약 작업: ").append(jobCount()).append("개\n");
        d.append("앱 발열 자동중단: 사용 안 함");
        return d.toString();
    }

    private String appVersion() {
        try {
            android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            return pi.versionName == null ? "확인 불가" : pi.versionName;
        } catch (Throwable t) {
            return "확인 불가";
        }
    }

    private int jobCount() {
        try {
            JobScheduler js = (JobScheduler)getSystemService(JOB_SCHEDULER_SERVICE);
            List<JobInfo> all = js.getAllPendingJobs();
            return all == null ? 0 : all.size();
        } catch (Throwable t) { return -1; }
    }

    private void copyDiagnostic() {
        ClipboardManager cm = (ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("온새미로 자동전사 진단", buildDiagnostic()));
        toast("진단정보를 복사했어요");
    }

    private void pickTree(int req) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, req);
    }

    private void pickModel() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.setType("application/octet-stream");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(i, PICK_MODEL);
    }

    @Override protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri u = data.getData();
        try {
            if (req == PICK_SOURCE || req == PICK_OUTPUT) {
                int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                getContentResolver().takePersistableUriPermission(u, flags);
                if (req == PICK_SOURCE) {
                    String treeId = DocumentsContract.getTreeDocumentId(u);
                    if (treeId == null || treeId.isEmpty() || "primary:".equals(treeId) || "home:".equals(treeId)) {
                        toast("기기 전체가 아닌 실제 통화녹음 폴더를 선택해 주세요");
                        return;
                    }
                    cfg.setSourceTreeWithNewBaseline(u.toString());
                    toast("녹음폴더를 연결했어요. 기존 파일은 자동으로 전사하지 않습니다");
                    if (MediaDeltaScanner.treeSupported(u.toString()) && !MediaDeltaScanner.hasAudioReadPermission(this)) {
                        requestAudioPermission();
                    }
                } else {
                    cfg.setOutputTree(u.toString());
                    toast("TXT 저장폴더를 연결했어요");
                }
                Scheduler.reschedule(this);
                if (cfg.activateAfterSetup(this)) {
                    Scheduler.reschedule(this);
                    TranscriptionKeepAliveService.startWithRecovery(this);
                }
                TranscriptionKeepAliveService.refresh(this);
                refresh();
                return;
            }

            if (req == PICK_MODEL) {
                final Uri modelUri = u;
                bg.execute(() -> {
                    try {
                        File d = new File(getFilesDir(), "models");
                        d.mkdirs();
                        File f = new File(d, "selected-model.bin");
                        File part = new File(d, "selected-model.bin.part");
                        try (java.io.InputStream in = getContentResolver().openInputStream(modelUri);
                             java.io.FileOutputStream o = new java.io.FileOutputStream(part, false)) {
                            if (in == null) throw new IllegalStateException("모델 파일 열기 실패");
                            byte[] b = new byte[1024 * 1024];
                            int n;
                            while ((n = in.read(b)) > 0) o.write(b, 0, n);
                            o.getFD().sync();
                        }
                        if (!WhisperBridge.validateModel(part.getAbsolutePath())) {
                            part.delete();
                            throw new IllegalStateException("Whisper가 이 모델을 열지 못했습니다");
                        }
                        if (f.exists()) f.delete();
                        if (!part.renameTo(f)) throw new IllegalStateException("모델 저장 완료 처리 실패");
                        cfg.setModelPath(f.getAbsolutePath());
                        cfg.activateAfterSetup(this);
                        Scheduler.reschedule(this);
                        TranscriptionKeepAliveService.startWithRecovery(this);
                        runOnUiThread(() -> { toast("모델 선택 완료"); refresh(); });
                    } catch (Throwable t) {
                        runOnUiThread(() -> toast("모델 설정 실패: " + t.getMessage()));
                    }
                });
            }
        } catch (Throwable t) {
            toast("설정 실패: " + t.getMessage());
        }
    }

    private void requestAudioPermission() {
        if (MediaDeltaScanner.hasAudioReadPermission(this)) {
            toast("오디오 접근 권한이 이미 허용되어 있어요");
            refresh();
            return;
        }
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{Manifest.permission.READ_MEDIA_AUDIO}, REQ_AUDIO_READ);
        } else {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_AUDIO_READ);
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_AUDIO_READ) return;
        if (MediaDeltaScanner.hasAudioReadPermission(this)) {
            toast("저발열 복구 준비 완료");
            Scheduler.reschedule(this);
            TranscriptionKeepAliveService.refresh(this);
        } else {
            toast("권한이 없으면 자동 보완은 대량 스캔하지 않고 생략됩니다");
        }
        refresh();
    }

    private void downloadModel() {
        bg.execute(() -> {
            try {
                File f = ModelManager.downloadRecommended(this, (done, total, stage) -> runOnUiThread(() -> {
                    status.setText(stage + (total > 0 ? " " + (done * 100 / Math.max(1, total)) + "%" : ""));
                }));
                cfg.setModelPath(f.getAbsolutePath());
                cfg.activateAfterSetup(this);
                Scheduler.reschedule(this);
                TranscriptionKeepAliveService.startWithRecovery(this);
                runOnUiThread(() -> { toast("모델 준비 완료"); refresh(); });
            } catch (Throwable t) {
                runOnUiThread(() -> new AlertDialog.Builder(this)
                        .setTitle("모델 준비 실패")
                        .setMessage(t.getClass().getSimpleName() + "\n" + (t.getMessage() == null ? "" : t.getMessage()))
                        .setPositiveButton("확인", null)
                        .show());
            }
        });
    }

    private void auditLegacy() {
        bg.execute(() -> {
            List<LegacyResultAuditor.Finding> f = LegacyResultAuditor.audit(this, 200);
            runOnUiThread(() -> new AlertDialog.Builder(this)
                    .setTitle("기존 TXT 검사")
                    .setMessage(f.isEmpty() ? "간단 검사에서 의심 TXT를 찾지 못했습니다." : formatFindings(f))
                    .setPositiveButton("확인", null)
                    .show());
        });
    }

    private String formatFindings(List<LegacyResultAuditor.Finding> f) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < Math.min(30, f.size()); i++) {
            s.append("• ").append(f.get(i).name).append(" : ").append(f.get(i).reason).append('\n');
        }
        if (f.size() > 30) s.append("외 ").append(f.size() - 30).append("건");
        return s.toString();
    }

    private TextView section(String s) { TextView v = text(s, 20, true); v.setPadding(0, dp(22), 0, dp(6)); return v; }
    private TextView text(String s, int sp, boolean bold) { TextView v = new TextView(this); v.setText(s); v.setTextSize(sp); v.setPadding(0, dp(7), 0, dp(7)); if (bold) v.setTypeface(null, Typeface.BOLD); return v; }
    private Button button(String s, View.OnClickListener l) { Button b = new Button(this); b.setText(s); b.setAllCaps(false); b.setOnClickListener(l); return b; }
    private CheckBox check(String s, boolean v, View.OnClickListener l) { CheckBox c = new CheckBox(this); c.setText(s); c.setChecked(v); c.setOnClickListener(l); return c; }
    private int indexFor(int m) { return m == 30 ? 0 : m == 60 ? 1 : m == 240 ? 3 : 2; }
    private int dp(int x) { return (int)(x * getResources().getDisplayMetrics().density + .5f); }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
    private String fmt(long ms) { return ms <= 0 ? "없음" : new SimpleDateFormat("MM-dd HH:mm:ss", Locale.KOREA).format(new Date(ms)); }
}
