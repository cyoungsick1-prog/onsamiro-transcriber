package com.onsamiro.transcriber;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.net.Uri;
import android.os.PersistableBundle;

public final class Scheduler {
    private static final int PERIODIC_ID = 7310;
    private static final int IMMEDIATE_ID = 7311;
    private static final int CONTENT_ID = 7312;
    private static final int HISTORICAL_ID = 7313;
    private static final int HISTORICAL_CONT_ID = 7314;

    private Scheduler() {}

    public static void reschedule(Context c) {
        AppConfig cfg = new AppConfig(c);
        JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        js.cancel(PERIODIC_ID);
        js.cancel(CONTENT_ID);

        if (cfg.paused() || !cfg.autoEnabled() || !cfg.basicReady(c)) {
            cfg.setNextExpectedMs(0L);
            return;
        }

        cfg.ensureAutoBaseline();
        schedulePeriodicRecovery(c);
        scheduleContentWatch(c);
    }

    public static void ensureRealtimeScheduled(Context c) {
        AppConfig cfg = new AppConfig(c);
        if (cfg.paused() || !cfg.autoEnabled() || !cfg.basicReady(c)) return;

        JobScheduler js = (JobScheduler)c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        cfg.ensureAutoBaseline();
        if (js.getPendingJob(PERIODIC_ID) == null) schedulePeriodicRecovery(c);
        if (js.getPendingJob(CONTENT_ID) == null) scheduleContentWatch(c);
    }

    private static JobInfo.Builder baseBuilder(Context c, int id) {
        AppConfig cfg = new AppConfig(c);
        JobInfo.Builder b = new JobInfo.Builder(id, new ComponentName(c, ScanJobService.class))
                .setBackoffCriteria(15 * 60_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL);
        if (cfg.wifiOnly()) b.setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED);
        if (cfg.chargingOnly()) b.setRequiresCharging(true);
        if (cfg.batteryNotLow()) b.setRequiresBatteryNotLow(true);
        return b;
    }

    private static void schedulePeriodicRecovery(Context c) {
        AppConfig cfg = new AppConfig(c);
        JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        long period = Math.max(30, cfg.intervalMinutes()) * 60_000L;

        PersistableBundle x = new PersistableBundle();
        x.putString("mode", "recovery");

        JobInfo.Builder b = baseBuilder(c, PERIODIC_ID)
                .setExtras(x)
                .setPersisted(true)
                .setPeriodic(period);

        js.schedule(b.build());
        cfg.setNextExpectedMs(System.currentTimeMillis() + period);
    }

    public static boolean scheduleContentWatch(Context c) {
        AppConfig cfg = new AppConfig(c);
        JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);

        if (cfg.paused() || !cfg.autoEnabled() || !cfg.basicReady(c) || cfg.sourceTree().isEmpty()) return false;

        try {
            PersistableBundle x = new PersistableBundle();
            x.putString("mode", "content");
            Uri source = Uri.parse(cfg.sourceTree());

            JobInfo.Builder b = baseBuilder(c, CONTENT_ID)
                    .setExtras(x)
                    .addTriggerContentUri(new JobInfo.TriggerContentUri(
                            source,
                            JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
                    .setTriggerContentUpdateDelay(5_000L)
                    .setTriggerContentMaxDelay(30_000L);

            int result = js.schedule(b.build());
            if (result != JobScheduler.RESULT_SUCCESS) {
                cfg.setLastRunNote("새 통화 감지 예약 실패 · 주기 복구는 계속 작동");
                return false;
            }
            return true;
        } catch (Throwable t) {
            // Some document providers do not support content-trigger jobs. Periodic recovery
            // remains the no-notification safety net in that case.
            cfg.setLastRunNote("새 통화 즉시감지 미지원 · 주기 복구로 보호 중");
            return false;
        }
    }

    public static void immediateRecovery(Context c, boolean force) {
        scheduleOneShot(c, IMMEDIATE_ID, "recovery", force, false);
    }

    public static void immediatePending(Context c, boolean force) {
        scheduleOneShot(c, IMMEDIATE_ID, "pending", force, false);
    }

    public static void historical(Context c, long startMs, long endMs, String label) {
        AppConfig cfg = new AppConfig(c);
        // A period request is explicit user action, so it may run even when realtime is OFF.
        // It also clears a previous emergency-stop latch without silently turning realtime ON.
        cfg.setPaused(false);
        cfg.beginPeriod(startMs, endMs, label);
        JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        js.cancel(HISTORICAL_CONT_ID);
        scheduleOneShot(c, HISTORICAL_ID, "historical", true, true);
    }

    public static void historicalContinue(Context c, long delayMs) {
        scheduleOneShot(c, HISTORICAL_CONT_ID, "historical", true, true, delayMs);
    }

    private static void scheduleOneShot(Context c, int id, String mode, boolean force, boolean ignoreAutoPause) {
        scheduleOneShot(c, id, mode, force, ignoreAutoPause, 0L);
    }

    private static void scheduleOneShot(Context c, int id, String mode, boolean force, boolean ignoreAutoPause, long delayMs) {
        AppConfig cfg = new AppConfig(c);
        if (cfg.paused() && !force && !ignoreAutoPause) return;

        JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        js.cancel(id);

        PersistableBundle x = new PersistableBundle();
        x.putString("mode", mode);
        x.putBoolean("force", force);

        long delay = Math.max(0L, delayMs);
        JobInfo.Builder b = new JobInfo.Builder(id, new ComponentName(c, ScanJobService.class))
                .setExtras(x)
                .setMinimumLatency(delay)
                .setOverrideDeadline(delay + 15_000L);
        if (id == HISTORICAL_ID || id == HISTORICAL_CONT_ID) {
            b.setPersisted(true);
        }
        js.schedule(b.build());
    }

    public static void cancelRealtime(Context c) {
        JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        js.cancel(PERIODIC_ID);
        js.cancel(IMMEDIATE_ID);
        js.cancel(CONTENT_ID);
        new AppConfig(c).setNextExpectedMs(0L);
    }

    public static void cancelAll(Context c) {
        JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        js.cancel(PERIODIC_ID);
        js.cancel(IMMEDIATE_ID);
        js.cancel(CONTENT_ID);
        js.cancel(HISTORICAL_ID);
        js.cancel(HISTORICAL_CONT_ID);
        AppConfig cfg = new AppConfig(c);
        cfg.setNextExpectedMs(0L);
        cfg.finishPeriod();
        cfg.clearWorkMode();
    }
}
