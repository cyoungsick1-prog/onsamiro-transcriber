package com.onsamiro.transcriber;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        Context app = context.getApplicationContext();
        try (JobStore store = new JobStore(app)) {
            store.recoverInterruptedRunning("기기/앱 재시작 후 이어하기");
        }
        Scheduler.reschedule(app);
        AppConfig cfg = new AppConfig(app);
        if (cfg.autoEnabled() && !cfg.paused() && cfg.basicReady(app)) {
            TranscriptionKeepAliveService.startWithRecovery(app);
        }
    }
}
