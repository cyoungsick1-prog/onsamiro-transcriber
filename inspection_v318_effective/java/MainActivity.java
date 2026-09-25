package com.onsamiro.transcriber;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * Daily-use screen: status + realtime + period + immediate stop.
 * v3.1.10: move the whole main card stack two more 56dp steps lower (184dp top padding total).
 * Everything technical stays behind the top-right menu.
 */
public final class MainActivity extends Activity {
    private static final int MENU_HISTORY = 1;
    private static final int MENU_SETTINGS = 2;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            refresh();
            ui.postDelayed(this, 1000L);
        }
    };

    private AppConfig cfg;
    private LinearLayout rootLayout;
    private LinearLayout statusCard;
    private TextView state;
    private TextView detail;
    private Button realtimeButton;
    private Button periodButton;
    private Button skipButton;
    private Button stopButton;
    private LinearLayout progressBox;
    private TextView overallProgressText;
    private ProgressBar overallProgressBar;
    private TextView currentFileText;
    private TextView fileProgressText;
    private ProgressBar fileProgressBar;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        cfg = new AppConfig(this);
        cfg.prepareRealtimeDetectionFixMigration();
        buildUi();

        // Re-arm only lightweight jobs. Opening the app never walks the recording folder.
        Scheduler.reschedule(this);
        TranscriptionKeepAliveService.start(this);
        refresh();
    }

    @Override protected void onResume() {
        super.onResume();
        TranscriptionKeepAliveService.start(this);
        ui.removeCallbacks(tick);
        tick.run();
    }

    @Override protected void onPause() {
        ui.removeCallbacks(tick);
        super.onPause();
    }

    @Override public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, MENU_HISTORY, 0, "기록");
        menu.add(0, MENU_SETTINGS, 1, "설정");
        return true;
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == MENU_HISTORY) {
            startActivity(new Intent(this, HistoryActivity.class));
            return true;
        }
        if (item.getItemId() == MENU_SETTINGS) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void buildUi() {
        // The main screen is intentionally NOT scrollable. The previous
        // A fixed main layout prevents the top status card from being shifted
        // under the action bar by restored scroll positions or focus changes.
        rootLayout = new LinearLayout(this);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setPadding(dp(20), dp(184), dp(20), dp(24));

        statusCard = new LinearLayout(this);
        statusCard.setOrientation(LinearLayout.VERTICAL);
        statusCard.setPadding(dp(18), dp(15), dp(18), dp(15));

        state = label("", 24, true);
        detail = label("", 15, false);
        detail.setTextColor(Color.parseColor("#5F6368"));
        statusCard.addView(state);
        statusCard.addView(detail);

        progressBox = new LinearLayout(this);
        progressBox.setOrientation(LinearLayout.VERTICAL);
        progressBox.setPadding(0, dp(12), 0, 0);

        overallProgressText = label("", 15, true);
        overallProgressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        overallProgressBar.setMax(100);
        progressBox.addView(overallProgressText);
        progressBox.addView(overallProgressBar, fullWidthFixed(dp(10), dp(8)));

        currentFileText = label("", 14, true);
        fileProgressText = label("", 13, false);
        fileProgressText.setTextColor(Color.parseColor("#5F6368"));
        fileProgressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        fileProgressBar.setMax(100);
        progressBox.addView(currentFileText);
        progressBox.addView(fileProgressText);
        progressBox.addView(fileProgressBar, fullWidthFixed(dp(10), 0));
        progressBox.setVisibility(View.GONE);
        statusCard.addView(progressBox);

        rootLayout.addView(statusCard, fullWidthWrap(dp(18)));

        realtimeButton = mainButton("실시간 전사", v -> toggleRealtime());
        rootLayout.addView(realtimeButton, fullWidthFixed(dp(70), dp(12)));

        periodButton = mainButton("기간별 전사", v -> showPeriodPicker());
        rootLayout.addView(periodButton, fullWidthFixed(dp(70), dp(12)));

        skipButton = mainButton("건너뛸 파일 없음", v -> skipCurrentFile());
        skipButton.setEnabled(false);
        skipButton.setTextColor(Color.parseColor("#5F6368"));
        skipButton.setBackground(roundRect("#E5E7EB", 18));
        rootLayout.addView(skipButton, fullWidthFixed(dp(58), dp(12)));

        stopButton = mainButton("즉시 중단", v -> immediateStop());
        stopButton.setTextColor(Color.WHITE);
        stopButton.setBackground(roundRect("#C62828", 18));
        rootLayout.addView(stopButton, fullWidthFixed(dp(70), 0));

        setContentView(rootLayout);
    }

    private void refresh() {
        if (state == null) return;

        if (!cfg.basicReady(this)) {
            showState("초기 설정 필요", "우측 상단 ⋮ → 설정에서 처음 한 번만 연결해주세요", "#FFF4E5", "#8A4B08");
            realtimeButton.setText("설정하기");
            realtimeButton.setEnabled(true);
            realtimeButton.setBackground(roundRect("#DADCE0", 18));
            realtimeButton.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
            periodButton.setText("기간별 전사");
            periodButton.setEnabled(false);
            skipButton.setText("건너뛸 파일 없음");
            skipButton.setEnabled(false);
            skipButton.setTextColor(Color.parseColor("#5F6368"));
            skipButton.setBackground(roundRect("#E5E7EB", 18));
            stopButton.setEnabled(false);
            return;
        }

        realtimeButton.setOnClickListener(v -> toggleRealtime());
        stopButton.setEnabled(true);

        JobStore.Stats stats;
        JobRecord last;
        JobStore.Stats periodStats = null;
        JobRecord current = null;
        try (JobStore s = new JobStore(this)) {
            stats = s.stats();
            last = s.lastCompleted();
            if (cfg.periodActive()) {
                periodStats = s.statsBetween(cfg.rangeStartMs(), cfg.rangeEndMs());
                current = s.currentActiveInRange(cfg.rangeStartMs(), cfg.rangeEndMs());
            } else {
                current = s.currentActive();
            }
        }

        boolean canSkip = current != null;
        skipButton.setEnabled(canSkip);
        skipButton.setText(canSkip ? "현재 파일 건너뛰기 →" : "건너뛸 파일 없음");
        skipButton.setTextColor(canSkip ? Color.WHITE : Color.parseColor("#5F6368"));
        skipButton.setBackground(roundRect(canSkip ? "#EF6C00" : "#E5E7EB", 18));

        int problems = stats.failed + stats.review + stats.partial;
        boolean realtimeOn = cfg.autoEnabled() && !cfg.paused();

        if (realtimeOn) {
            realtimeButton.setText("실시간 전사 ON");
            realtimeButton.setTextColor(Color.WHITE);
            realtimeButton.setBackground(roundRect("#1976D2", 18));
        } else {
            realtimeButton.setText("실시간 전사 OFF");
            realtimeButton.setTextColor(Color.parseColor("#202124"));
            realtimeButton.setBackground(roundRect("#DADCE0", 18));
        }

        if (cfg.periodActive()) {
            periodButton.setText("기간별 전사 중");
            periodButton.setEnabled(false);
            periodButton.setTextColor(Color.parseColor("#5F6368"));
            periodButton.setBackground(roundRect("#E5E7EB", 18));
        } else {
            periodButton.setText("기간별 전사");
            periodButton.setEnabled(true);
            periodButton.setTextColor(Color.parseColor("#202124"));
            periodButton.setBackground(roundRect("#DADCE0", 18));
        }

        if (cfg.periodActive()) {
            int found = cfg.periodFound();
            int already = cfg.periodAlready();
            int target = cfg.periodTarget();
            if (periodStats == null || found == 0) {
                showState("기간별 전사 확인 중", "기존 전사파일과 대상 통화를 확인하고 있습니다", "#EAF2FF", "#174EA6");
                showProgress(0, "대상 계산 중", current, false);
                return;
            }

            int terminalNow = periodStats.done + periodStats.skipped + periodStats.excluded;
            int completed = Math.max(0, terminalNow - already);
            completed = Math.min(completed, target);
            int remaining = Math.max(0, target - completed);
            int pct = target <= 0 ? 100 : Math.min(100, (int)Math.round(completed * 100.0 / target));
            int issues = periodStats.failed + periodStats.review + periodStats.partial;
            boolean waitingMode = "period_pending".equals(cfg.workMode());

            String title = target == 0 ? "기간별 전사 완료" : (waitingMode ? "기간별 전사 대기 중" : "기간별 전사 중");
            String sub = "전체 " + completed + " / " + target + " 완료 · " + remaining + "개 남음 · " + pct + "%";
            if (already > 0) sub += "\n기존 전사 " + already + "건 자동 제외";
            if (issues > 0) sub += " · 확인 " + issues + "건";
            showState(title, sub, "#EAF2FF", "#174EA6");
            showProgress(pct, "전체 진행 " + pct + "%", current, true);
            return;
        }

        if (cfg.paused()) {
            showState("즉시 중단됨", "모든 전사 작업이 멈춰 있습니다", "#F3F4F6", "#3C4043");
            return;
        }

        if (stats.running > 0) {
            showState("전사 중", "현재 통화를 처리하고 있습니다 · 대기 " + stats.waiting + "건", "#EAF2FF", "#174EA6");
            showProgress(-1, "", current, true);
            return;
        }

        if (problems > 0) {
            showState("확인 필요 · " + problems + "건", "우측 상단 ⋮ → 기록에서 확인할 수 있습니다", "#FDECEC", "#B3261E");
            return;
        }

        if (realtimeOn) {
            showState("실시간 전사 대기 중", last == null
                    ? "새 통화를 기다리고 있습니다"
                    : "최근 전사 " + fmtDateTime(last.updatedAt > 0 ? last.updatedAt : last.callTimeMs),
                    "#EDF7F0", "#137333");
        } else {
            showState("실시간 전사 꺼짐", last == null
                    ? "필요할 때 실시간 전사를 켜주세요"
                    : "최근 전사 " + fmtDateTime(last.updatedAt > 0 ? last.updatedAt : last.callTimeMs),
                    "#F3F4F6", "#3C4043");
        }
    }

    private void toggleRealtime() {
        if (!cfg.basicReady(this)) {
            startActivity(new Intent(this, SettingsActivity.class));
            return;
        }

        if (cfg.autoEnabled() && !cfg.paused()) {
            cfg.setAutoEnabled(false);
            cfg.setPaused(false);
            Scheduler.cancelRealtime(this);
            TranscriptionKeepAliveService.stop(this);
            toast("실시간 전사를 껐어요");
        } else {
            cfg.setPaused(false);
            cfg.setAutoEnabled(true);
            Scheduler.reschedule(this);
            TranscriptionKeepAliveService.startWithRecovery(this);
            // Catch anything that arrived while realtime was off, using the indexed low-heat path.
            Scheduler.immediateRecovery(this, true);
            toast("실시간 전사를 켰어요");
        }
        refresh();
    }

    private void skipCurrentFile() {
        JobRecord current;
        try (JobStore s = new JobStore(this)) {
            current = cfg.periodActive()
                    ? s.currentActiveInRange(cfg.rangeStartMs(), cfg.rangeEndMs())
                    : s.currentActive();
            if (current == null) {
                toast("지금 건너뛸 파일이 없어요");
                refresh();
                return;
            }
            s.markManualSkip(current.id, "사용자가 화면에서 현재 파일을 건너뜀");
        }

        boolean signaled = WorkCoordinator.requestSkipCurrent(current.id);
        if (signaled) {
            toast("현재 파일을 건너뛰고 다음 파일로 넘어갑니다");
        } else {
            toast("현재 파일을 건너뛰었습니다");
        }
        refresh();
    }

    private void immediateStop() {
        // Emergency stop means exactly that: current work + realtime + period jobs all stop.
        cfg.setPaused(true);
        cfg.setAutoEnabled(false);
        Scheduler.cancelAll(this);
        TranscriptionKeepAliveService.stop(this);
        try (JobStore s = new JobStore(this)) {
            s.recoverInterruptedRunning("사용자 즉시 중단 · 다음 요청에서 이어하기");
        }
        toast("모든 전사 작업을 중단했어요");
        refresh();
    }

    private void showPeriodPicker() {
        if (!cfg.basicReady(this)) {
            startActivity(new Intent(this, SettingsActivity.class));
            return;
        }
        if (cfg.periodActive()) {
            toast("기간별 전사가 이미 진행 중이에요");
            return;
        }

        String[] items = {"오늘", "어제", "최근 3일", "최근 7일", "날짜 직접 선택"};
        new AlertDialog.Builder(this)
                .setTitle("기간별 전사")
                .setItems(items, (dialog, which) -> {
                    switch (which) {
                        case 0: startPreset(0); break;
                        case 1: startPreset(1); break;
                        case 2: startPreset(3); break;
                        case 3: startPreset(7); break;
                        default: pickCustomStart(); break;
                    }
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private void startPreset(int days) {
        long[] r = rangeForPreset(days);
        Scheduler.historical(this, r[0], r[1], labelForPreset(days));
        toast(labelForPreset(days) + " 전사를 시작했어요");
        refresh();
    }

    private void pickCustomStart() {
        Calendar now = Calendar.getInstance();
        new DatePickerDialog(this, (v, y, m, d) -> {
            Calendar start = Calendar.getInstance();
            start.set(y, m, d, 0, 0, 0);
            start.set(Calendar.MILLISECOND, 0);
            pickCustomEnd(start.getTimeInMillis());
        }, now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void pickCustomEnd(long startMs) {
        Calendar init = Calendar.getInstance();
        init.setTimeInMillis(Math.max(startMs, System.currentTimeMillis()));
        new DatePickerDialog(this, (v, y, m, day) -> {
            Calendar end = Calendar.getInstance();
            end.set(y, m, day, 23, 59, 59);
            end.set(Calendar.MILLISECOND, 999);
            long endMs = end.getTimeInMillis();
            if (endMs < startMs) {
                toast("종료일은 시작일보다 빠를 수 없어요");
                return;
            }
            String label = fmtDate(startMs) + " ~ " + fmtDate(endMs);
            Scheduler.historical(this, startMs, endMs, label);
            toast("선택한 기간 전사를 시작했어요");
            refresh();
        }, init.get(Calendar.YEAR), init.get(Calendar.MONTH), init.get(Calendar.DAY_OF_MONTH)).show();
    }

    private long[] rangeForPreset(int days) {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        long today = c.getTimeInMillis();
        if (days == 0) return new long[]{today, today + 86_400_000L - 1};
        if (days == 1) return new long[]{today - 86_400_000L, today - 1};
        return new long[]{today - (days - 1L) * 86_400_000L, System.currentTimeMillis()};
    }

    private String labelForPreset(int d) {
        return d == 0 ? "오늘" : d == 1 ? "어제" : "최근 " + d + "일";
    }

    private void showState(String title, String sub, String cardColor, String titleColor) {
        hideProgress();
        state.setText(title);
        state.setTextColor(Color.parseColor(titleColor));
        detail.setText(sub);
        statusCard.setBackground(roundRect(cardColor, 18));
    }

    private void hideProgress() {
        if (progressBox != null) progressBox.setVisibility(View.GONE);
    }

    private void showProgress(int overallPercent, String overallText, JobRecord current, boolean showCurrent) {
        if (progressBox == null) return;
        progressBox.setVisibility(View.VISIBLE);

        if (overallPercent >= 0) {
            overallProgressText.setVisibility(View.VISIBLE);
            overallProgressBar.setVisibility(View.VISIBLE);
            overallProgressText.setText(overallText);
            overallProgressBar.setProgress(Math.max(0, Math.min(100, overallPercent)));
        } else {
            overallProgressText.setVisibility(View.GONE);
            overallProgressBar.setVisibility(View.GONE);
        }

        if (!showCurrent || current == null) {
            currentFileText.setText("현재: 다음 파일 준비 중");
            fileProgressText.setText("파일 진행률은 전사가 시작되면 표시됩니다");
            fileProgressBar.setProgress(0);
            return;
        }

        int fp = filePercent(current);
        currentFileText.setText("현재: " + shortName(current.displayName, 34));
        String stage = current.stage == null || current.stage.isEmpty() ? "처리 중" : current.stage;
        String seg = current.totalSegments > 0
                ? " · " + current.doneSegments + "/" + current.totalSegments + " 구간 완료"
                : "";
        fileProgressText.setText(stage + " · 파일 " + fp + "%" + seg);
        fileProgressBar.setProgress(fp);
    }

    private int filePercent(JobRecord j) {
        if (j == null) return 0;
        if (Status.DONE.equals(j.status) || Status.NO_AUDIO.equals(j.status) || Status.ALREADY_TRANSCRIBED.equals(j.status)) return 100;
        if (Status.SAVING.equals(j.status)) return 98;
        if (Status.QUALITY_CHECK.equals(j.status)) return 95;
        if (j.totalSegments > 0) {
            int segmentPart = (int)Math.round(j.doneSegments * 90.0 / j.totalSegments);
            if (Status.TRANSCRIBING.equals(j.status)) return Math.max(1, Math.min(90, segmentPart));
            return Math.max(0, Math.min(90, segmentPart));
        }
        if (Status.FETCHING.equals(j.status)) return 2;
        return 0;
    }

    private String shortName(String s, int max) {
        if (s == null) return "통화";
        String x = s.replace('\n', ' ').replace('\r', ' ').trim();
        if (x.length() <= max) return x;
        return x.substring(0, Math.max(1, max - 1)) + "…";
    }


    private TextView label(String s, int sp, boolean bold) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(sp);
        v.setTextColor(Color.parseColor("#202124"));
        v.setPadding(0, dp(4), 0, dp(4));
        if (bold) v.setTypeface(null, Typeface.BOLD);
        return v;
    }

    private Button mainButton(String s, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(s);
        b.setTextSize(19);
        b.setAllCaps(false);
        b.setTypeface(null, Typeface.BOLD);
        b.setOnClickListener(listener);
        b.setBackground(roundRect("#DADCE0", 18));
        return b;
    }

    private LinearLayout.LayoutParams fullWidthFixed(int height, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, height);
        p.setMargins(0, 0, 0, bottom);
        return p;
    }

    private LinearLayout.LayoutParams fullWidthWrap(int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, 0, 0, bottom);
        return p;
    }

    private GradientDrawable roundRect(String hex, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.parseColor(hex));
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private int dp(int x) {
        return (int)(x * getResources().getDisplayMetrics().density + .5f);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private String fmtDateTime(long ms) {
        return ms <= 0 ? "없음" : new SimpleDateFormat("MM-dd HH:mm", Locale.KOREA).format(new Date(ms));
    }

    private String fmtDate(long ms) {
        return new SimpleDateFormat("yyyy.MM.dd", Locale.KOREA).format(new Date(ms));
    }
}
