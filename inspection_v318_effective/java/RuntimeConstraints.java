package com.onsamiro.transcriber;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;

public final class RuntimeConstraints {
    public static final class Check {
        public final boolean ok;
        public final String reason;
        Check(boolean ok, String reason) { this.ok = ok; this.reason = reason; }
    }

    public static Check check(Context c, AppConfig cfg) {
        if (cfg.paused()) return new Check(false, "일시정지 상태");
        if (cfg.wifiOnly()) {
            ConnectivityManager cm = (ConnectivityManager)c.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network n = cm.getActiveNetwork();
            NetworkCapabilities cap = n == null ? null : cm.getNetworkCapabilities(n);
            if (cap == null || !cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return new Check(false, "Wi-Fi 연결 대기");
            }
        }
        Intent b = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (b != null) {
            int status = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                    || status == BatteryManager.BATTERY_STATUS_FULL;
            if (cfg.chargingOnly() && !charging) return new Check(false, "충전 대기");
            int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int pct = scale > 0 ? level * 100 / scale : 100;
            if (cfg.batteryNotLow() && pct >= 0 && pct < 20 && !charging) {
                return new Check(false, "배터리 20% 미만이라 대기");
            }
        }
        return new Check(true, "");
    }
}
