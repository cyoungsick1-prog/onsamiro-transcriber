package com.onsamiro.transcriber;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class HistoryActivity extends Activity {
    private ListView list;
    private List<JobRecord> rows = new ArrayList<>();

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(14), dp(16), dp(18));
        TextView title = new TextView(this);
        title.setText("전사 기록");
        title.setTextSize(26);
        root.addView(title);
        TextView hint = new TextView(this);
        hint.setText("최근 200건 · 항목을 누르면 상세 상태와 재시도를 볼 수 있습니다");
        hint.setTextSize(13);
        hint.setPadding(0, dp(6), 0, dp(10));
        root.addView(hint);
        list = new ListView(this);
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        list.setOnItemClickListener((p, v, pos, id) -> showJob(rows.get(pos)));
        setContentView(root);
        refresh();
    }

    @Override protected void onResume() { super.onResume(); refresh(); }

    private void refresh() {
        try (JobStore s = new JobStore(this)) {
            rows = s.recent(200);
        }
        List<String> labels = new ArrayList<>();
        for (JobRecord j : rows) {
            labels.add(fmt(j.callTimeMs) + "  " + j.displayName + "\n" + human(j));
        }
        list.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, labels));
    }

    private String human(JobRecord j) {
        if (Status.DONE.equals(j.status)) return "완료 · TXT 저장됨";
        if (Status.NO_AUDIO.equals(j.status)) return "완료 · 음성 없음";
        if (Status.REVIEW_REQUIRED.equals(j.status)) return "확인 필요 · " + j.failReason;
        if (Status.FAILED.equals(j.status)) return "실패 · " + j.failReason;
        if (Status.PARTIAL.equals(j.status)) return "일부 완료 · " + j.failReason;
        if (Status.USER_EXCLUDED.equals(j.status)) return "사용자 제외";
        if (Status.ALREADY_TRANSCRIBED.equals(j.status)) return "이미 전사됨 · 자동 제외";
        return j.stage == null || j.stage.isEmpty() ? j.status : j.stage;
    }

    private void showJob(JobRecord j) {
        String msg = "통화시각: " + fmt(j.callTimeMs)
                + "\n파일: " + j.displayName
                + "\n길이: " + (j.durationMs > 0 ? j.durationMs / 1000 + "초" : "미확인")
                + "\n상태: " + human(j)
                + "\n구간: " + j.doneSegments + "/" + j.totalSegments
                + "\n재시도: " + j.retryCount
                + "\n마지막 변화: " + fmt(j.updatedAt)
                + "\n저장: " + (j.finalUri == null || j.finalUri.isEmpty() ? "최종 TXT 없음" : "TXT 저장 완료")
                + "\n\n미리보기\n" + (j.resultPreview == null ? "" : j.resultPreview);

        new AlertDialog.Builder(this)
                .setTitle(j.displayName)
                .setMessage(msg)
                .setPositiveButton("이 통화 다시 시도", (d, w) -> {
                    try (JobStore s = new JobStore(this)) { s.retry(j.id); }
                    Scheduler.immediatePending(this, true);
                    refresh();
                })
                .setNeutralButton("사용자 제외", (d, w) -> {
                    try (JobStore s = new JobStore(this)) { s.exclude(j.id); }
                    refresh();
                })
                .setNegativeButton("닫기", null)
                .show();
    }

    private String fmt(long ms) {
        return ms <= 0 ? "시간 미확인" : new SimpleDateFormat("MM-dd HH:mm", Locale.KOREA).format(new Date(ms));
    }

    private int dp(int x) { return (int)(x * getResources().getDisplayMetrics().density + .5f); }
}
