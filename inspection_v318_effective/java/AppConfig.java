package com.onsamiro.transcriber;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import java.io.File;

public final class AppConfig {
    private static final String PREF = "onsamiro_cfg_v31";
    private final SharedPreferences p;

    public AppConfig(Context c) {
        p = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public String sourceTree() { return p.getString("source_tree", ""); }
    public String outputTree() { return p.getString("output_tree", ""); }
    public String modelPath() { return p.getString("model_path", ""); }
    public void setOutputTree(String v) { p.edit().putString("output_tree", v == null ? "" : v).apply(); }
    public void setModelPath(String v) { p.edit().putString("model_path", v == null ? "" : v).apply(); }

    /**
     * A new source folder establishes a baseline. Files older than this baseline are never
     * auto-enqueued merely because the app restarted. The user can still request them from
     * the period-transcription UI.
     */
    public void setSourceTreeWithNewBaseline(String v) {
        long now = System.currentTimeMillis();
        p.edit()
                .putString("source_tree", v == null ? "" : v)
                .putLong("auto_baseline_ms", now)
                .putLong("last_recovery_scan_ms", now)
                .putLong("last_content_trigger_ms", 0L)
                .putBoolean("activate_after_setup", !p.getBoolean("user_stopped_realtime", false))
                .apply();
    }

    public int intervalMinutes() { return p.getInt("interval_min", 120); }
    public void setIntervalMinutes(int v) { p.edit().putInt("interval_min", v).apply(); }
    public boolean autoEnabled() { return p.getBoolean("auto_enabled", true); }
    public void setAutoEnabled(boolean v) { p.edit().putBoolean("auto_enabled", v).putBoolean("activate_after_setup", false).putBoolean("user_stopped_realtime", !v).apply(); }
    /** Arm only after a user selects a recording folder. An explicit stop always wins. */
    public boolean activateAfterSetup(Context c) {
        if (!p.getBoolean("activate_after_setup", false) || paused() || !basicReady(c)) return false;
        p.edit().putBoolean("auto_enabled", true).putBoolean("activate_after_setup", false).commit();
        return true;
    }
    public boolean paused() { return p.getBoolean("paused", false); }
    public void setPaused(boolean v) { p.edit().putBoolean("paused", v).apply(); }
    public boolean wifiOnly() { return p.getBoolean("wifi_only", false); }
    public void setWifiOnly(boolean v) { p.edit().putBoolean("wifi_only", v).apply(); }
    public boolean chargingOnly() { return p.getBoolean("charging_only", false); }
    public void setChargingOnly(boolean v) { p.edit().putBoolean("charging_only", v).apply(); }
    public boolean batteryNotLow() { return p.getBoolean("battery_not_low", true); }
    public void setBatteryNotLow(boolean v) { p.edit().putBoolean("battery_not_low", v).apply(); }

    public long lastRunMs() { return p.getLong("last_run_ms", 0); }
    public void setLastRunMs(long v) { p.edit().putLong("last_run_ms", v).apply(); }
    public long nextExpectedMs() { return p.getLong("next_expected_ms", 0); }
    public void setNextExpectedMs(long v) { p.edit().putLong("next_expected_ms", v).apply(); }
    public String lastRunNote() { return p.getString("last_run_note", "아직 실행 안 됨"); }
    public void setLastRunNote(String v) { p.edit().putString("last_run_note", v == null ? "" : v).apply(); }

    public long autoBaselineMs() { return p.getLong("auto_baseline_ms", 0L); }
    public void ensureAutoBaseline() {
        if (autoBaselineMs() == 0L) {
            long now = System.currentTimeMillis();
            p.edit().putLong("auto_baseline_ms", now).putLong("last_recovery_scan_ms", now).apply();
        }
    }
    public long lastRecoveryScanMs() { return p.getLong("last_recovery_scan_ms", 0L); }
    public void setLastRecoveryScanMs(long v) { p.edit().putLong("last_recovery_scan_ms", v).apply(); }
    public long lastContentTriggerMs() { return p.getLong("last_content_trigger_ms", 0L); }
    public void setLastContentTriggerMs(long v) { p.edit().putLong("last_content_trigger_ms", v).apply(); }
    public long lastSuccessMs() { return p.getLong("last_success_ms", 0L); }
    public void setLastSuccessMs(long v) { p.edit().putLong("last_success_ms", v).apply(); }
    public long serviceHeartbeatMs() { return p.getLong("service_heartbeat_ms", 0L); }
    public void setServiceHeartbeatMs(long v) { p.edit().putLong("service_heartbeat_ms", v).apply(); }

    /** One-time v3.1.13 migration: v3.1.12 could advance the recovery checkpoint even
     * when MediaStore returned zero rows. Rewind to the existing auto baseline once so
     * recordings missed by that build can be discovered by the SAF fallback. */
    public void prepareRealtimeDetectionFixMigration() {
        int applied = p.getInt("realtime_detection_fix_version", 0);
        if (applied >= 311300) return;
        long baseline = autoBaselineMs();
        SharedPreferences.Editor e = p.edit().putInt("realtime_detection_fix_version", 311300);
        if (baseline > 0L) e.putLong("last_recovery_scan_ms", baseline);
        e.apply();
    }


    /** One-time v3.1.14 migration: v3.1.13 could stop after 1500 SAF rows and still
     * advance the recovery checkpoint. Rewind to the source-selection baseline once so
     * recordings skipped behind that provider ordering are visible again. */
    public void prepareFullFolderScanFixMigration() {
        int applied = p.getInt("full_folder_scan_fix_version", 0);
        if (applied >= 311400) return;
        long baseline = autoBaselineMs();
        SharedPreferences.Editor e = p.edit().putInt("full_folder_scan_fix_version", 311400);
        if (baseline > 0L) e.putLong("last_recovery_scan_ms", baseline);
        e.apply();
    }

    public long rangeStartMs() { return p.getLong("range_start", 0); }
    public long rangeEndMs() { return p.getLong("range_end", Long.MAX_VALUE); }
    public void setRange(long s, long e) {
        p.edit().putLong("range_start", s).putLong("range_end", e).apply();
    }

    // Lightweight UI state. This is intentionally only status metadata; the durable
    // per-file source of truth remains JobStore.
    public String workMode() { return p.getString("work_mode", ""); }
    public void setWorkMode(String v) { p.edit().putString("work_mode", v == null ? "" : v).apply(); }
    public boolean periodActive() { return p.getBoolean("period_active", false); }
    public int periodTotal() { return p.getInt("period_total", 0); }
    public int periodFound() { return p.getInt("period_found", 0); }
    public int periodAlready() { return p.getInt("period_already", 0); }
    public int periodTarget() { return p.getInt("period_target", 0); }
    public String periodLabel() { return p.getString("period_label", ""); }
    public void beginPeriod(long start, long end, String label) {
        p.edit()
                .putLong("range_start", start)
                .putLong("range_end", end)
                .putBoolean("period_active", true)
                .putInt("period_total", 0)
                .putInt("period_found", 0)
                .putInt("period_already", 0)
                .putInt("period_target", 0)
                .putString("period_label", label == null ? "" : label)
                .putString("work_mode", "period_pending")
                .apply();
    }
    public void setPeriodTotal(int n) { p.edit().putInt("period_total", Math.max(0, n)).apply(); }
    public void setPeriodPlan(int found, int already, int target) {
        p.edit()
                .putInt("period_found", Math.max(0, found))
                .putInt("period_already", Math.max(0, already))
                .putInt("period_target", Math.max(0, target))
                .putInt("period_total", Math.max(0, target))
                .apply();
    }
    public void finishPeriod() {
        p.edit().putBoolean("period_active", false).putString("work_mode", "").apply();
    }
    public void clearWorkMode() { p.edit().putString("work_mode", "").apply(); }

    public boolean basicReady(Context c) {
        if (sourceTree().isEmpty() || outputTree().isEmpty() || modelPath().isEmpty()) return false;
        File m = new File(modelPath());
        if (!m.isFile() || m.length() < 1024 * 1024) return false;
        return hasPersistedPermission(c, sourceTree(), true, false)
                && hasPersistedPermission(c, outputTree(), true, true);
    }

    private boolean hasPersistedPermission(Context c, String uri, boolean needRead, boolean needWrite) {
        try {
            Uri u = Uri.parse(uri);
            for (android.content.UriPermission up : c.getContentResolver().getPersistedUriPermissions()) {
                if (!up.getUri().equals(u)) continue;
                boolean readOk = !needRead || up.isReadPermission();
                boolean writeOk = !needWrite || up.isWritePermission();
                if (readOk && writeOk) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }
}
