package com.onsamiro.transcriber;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.net.Uri;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ScanJobService extends JobService {
    private final ExecutorService one = Executors.newSingleThreadExecutor();
    private volatile WorkCoordinator coordinator;

    @Override public boolean onStartJob(JobParameters params) {
        final String mode = params.getExtras() == null ? "recovery" : params.getExtras().getString("mode", "recovery");
        final boolean force = params.getExtras() != null && params.getExtras().getBoolean("force", false);
        final Uri[] triggered = params.getTriggeredContentUris();
        coordinator = new WorkCoordinator(getApplicationContext());

        one.execute(() -> {
            try {
                switch (mode) {
                    case "pending":
                        coordinator.runPendingOnly(force);
                        break;
                    case "historical":
                        coordinator.runHistorical();
                        break;
                    case "content":
                        coordinator.runTriggered(triggered);
                        break;
                    case "recovery":
                    default:
                        coordinator.runRecovery(force);
                        break;
                }
            } finally {
                if ("content".equals(mode)) {
                    // Content-trigger jobs are one-shot. Android recommends replacing the same
                    // job ID here instead of jobFinished(), so changes arriving during this run
                    // are carried into the next trigger job.
                    if (!Scheduler.scheduleContentWatch(getApplicationContext())) {
                        jobFinished(params, false);
                    }
                } else {
                    jobFinished(params, false);
                }
            }
        });
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        if (coordinator != null) coordinator.requestStop("운영체제가 작업을 중단함");
        return true;
    }

    @Override public void onDestroy() {
        one.shutdownNow();
        super.onDestroy();
    }
}
