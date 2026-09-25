package com.onsamiro.transcriber;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.DocumentsContract;
import android.content.pm.ServiceInfo;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Small foreground watchdog for realtime transcription.
 *
 * Heavy work remains in JobScheduler/WorkCoordinator. This service only keeps the
 * realtime intent alive, watches the selected recording tree for changes, re-arms
 * missing scheduler jobs, and nudges recovery after process/task/network events.
 */
public final class TranscriptionKeepAliveService extends Service {
    private static final String CHANNEL_ID = "onsamiro_transcription_guard";
    private static final int NOTIFICATION_ID = 7315;
    private static final long WATCHDOG_MS = 45_000L;
    private static final long EVENT_DEBOUNCE_MS = 1_500L;
    private static final long TRIGGER_WAKELOCK_MS = 15_000L;
    private static final long ACTIVE_WAKELOCK_MS = Integer.MAX_VALUE; // ~24.8 days; always released in finally when active work ends
    private static final String EXTRA_RECOVER = "recover_now";
    private static final String ACTION_REFRESH = "com.onsamiro.transcriber.REFRESH_KEEPALIVE";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            AppConfig cfg = new AppConfig(TranscriptionKeepAliveService.this);
            cfg.setServiceHeartbeatMs(System.currentTimeMillis());
            if (!shouldRun(cfg)) {
                stopForeground(true);
                stopSelf();
                return;
            }
            Scheduler.ensureRealtimeScheduled(TranscriptionKeepAliveService.this);
            runDirectRecovery(false);
            main.postDelayed(this, WATCHDOG_MS);
        }
    };
    private final Runnable contentKick = () -> runDirectRecovery(false);
    private final Runnable pendingKick = () -> runDirectRecovery(true);

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean workerBusy = new AtomicBoolean(false);
    private volatile WorkCoordinator activeCoordinator;

    private ContentObserver sourceObserver;
    private Uri observedSource;
    private ConnectivityManager connectivity;
    private ConnectivityManager.NetworkCallback networkCallback;
    private BroadcastReceiver recoveryReceiver;

    public static void start(Context context) {
        startInternal(context, false, false);
    }

    public static void startWithRecovery(Context context) {
        startInternal(context, true, false);
    }

    public static void refresh(Context context) {
        startInternal(context, false, true);
    }

    public static void stop(Context context) {
        try {
            context.getApplicationContext().stopService(
                    new Intent(context.getApplicationContext(), TranscriptionKeepAliveService.class));
        } catch (Throwable ignored) {}
    }

    private static void startInternal(Context context, boolean recover, boolean refresh) {
        Context app = context.getApplicationContext();
        AppConfig cfg = new AppConfig(app);
        if (!shouldRun(app, cfg)) return;

        Intent i = new Intent(app, TranscriptionKeepAliveService.class);
        i.putExtra(EXTRA_RECOVER, recover);
        if (refresh) i.setAction(ACTION_REFRESH);
        try {
            if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(i);
            else app.startService(i);
        } catch (Throwable t) {
            // The durable JobScheduler path remains active even if an OEM temporarily
            // rejects a foreground-service start from the current process state.
            cfg.setLastRunNote("백그라운드 보호 시작 지연 · 예약 복구는 계속 작동");
            Scheduler.ensureRealtimeScheduled(app);
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        AppConfig cfg = new AppConfig(this);
        cfg.prepareRealtimeDetectionFixMigration();
        cfg.prepareFullFolderScanFixMigration();
        try {
            createNotificationChannel();
            enterForeground();
        } catch (Throwable t) {
            // A foreground-service setup failure must never crash the whole app process.
            cfg.setLastRunNote("백그라운드 보호 초기화 실패 · " + t.getClass().getSimpleName());
            Scheduler.ensureRealtimeScheduled(this);
            stopSelf();
            return;
        }
        registerSourceObserver();
        registerNetworkCallback();
        registerRecoveryReceiver();
        main.removeCallbacks(watchdog);
        main.post(watchdog);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        AppConfig cfg = new AppConfig(this);
        if (!shouldRun(cfg)) {
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (intent != null && ACTION_REFRESH.equals(intent.getAction())) {
            registerSourceObserver();
        }

        Scheduler.ensureRealtimeScheduled(this);
        cfg.setServiceHeartbeatMs(System.currentTimeMillis());

        // null intent means Android recreated this START_STICKY service after a process kill.
        boolean recover = intent == null || (intent != null && intent.getBooleanExtra(EXTRA_RECOVER, false));
        if (recover) runDirectRecovery(false);
        return START_STICKY;
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        // Removing the recent-app card must not turn realtime transcription off.
        runDirectRecovery(false);
        Scheduler.ensureRealtimeScheduled(this);
        super.onTaskRemoved(rootIntent);
    }

    @Override public void onDestroy() {
        main.removeCallbacksAndMessages(null);
        unregisterSourceObserver();
        unregisterNetworkCallback();
        unregisterRecoveryReceiver();
        WorkCoordinator running = activeCoordinator;
        if (running != null) running.requestStop("보호 서비스 재시작");
        worker.shutdownNow();

        AppConfig cfg = new AppConfig(this);
        if (shouldRun(cfg)) {
            // If the process was reclaimed, START_STICKY requests a recreation. The durable
            // scheduler is also re-armed here so transcription still has a second recovery path.
            Scheduler.ensureRealtimeScheduled(this);
        }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    private void enterForeground() {
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
                return;
            } catch (Throwable ignored) {
                // Some OEM builds are stricter than AOSP about the typed overload.
                // Retry using the manifest-declared service type before giving up.
            }
        }
        startForeground(NOTIFICATION_ID, notification);
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(
                this,
                7315,
                open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return b.setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("온새미로 자동전사")
                .setContentText("백그라운드 자동전사 보호 중")
                .setContentIntent(content)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setShowWhen(false)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID,
                "자동전사 백그라운드 보호",
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("화면이 꺼져도 자동전사 복구 상태를 유지합니다");
        ch.setShowBadge(false);
        ch.setSound(null, null);
        nm.createNotificationChannel(ch);
    }

    private void registerSourceObserver() {
        unregisterSourceObserver();
        AppConfig cfg = new AppConfig(this);
        if (!shouldRun(cfg) || cfg.sourceTree().isEmpty()) return;
        try {
            observedSource = Uri.parse(cfg.sourceTree());
            sourceObserver = new ContentObserver(main) {
                @Override public void onChange(boolean selfChange) {
                    onChange(selfChange, null);
                }

                @Override public void onChange(boolean selfChange, Uri uri) {
                    main.removeCallbacks(contentKick);
                    main.postDelayed(contentKick, EVENT_DEBOUNCE_MS);
                }
            };
            getContentResolver().registerContentObserver(observedSource, true, sourceObserver);
            try {
                String rootId = DocumentsContract.getTreeDocumentId(observedSource);
                Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(observedSource, rootId);
                getContentResolver().registerContentObserver(children, true, sourceObserver);
            } catch (Throwable ignored) {
                // Periodic direct SAF recovery below remains the reliable fallback.
            }
        } catch (Throwable ignored) {
            sourceObserver = null;
            observedSource = null;
        }
    }

    private void unregisterSourceObserver() {
        if (sourceObserver != null) {
            try { getContentResolver().unregisterContentObserver(sourceObserver); } catch (Throwable ignored) {}
        }
        sourceObserver = null;
        observedSource = null;
    }

    private void registerNetworkCallback() {
        unregisterNetworkCallback();
        try {
            connectivity = (ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) {
                    main.removeCallbacks(pendingKick);
                    main.postDelayed(pendingKick, EVENT_DEBOUNCE_MS);
                }
            };
            connectivity.registerDefaultNetworkCallback(networkCallback);
        } catch (Throwable ignored) {
            connectivity = null;
            networkCallback = null;
        }
    }

    private void unregisterNetworkCallback() {
        if (connectivity != null && networkCallback != null) {
            try { connectivity.unregisterNetworkCallback(networkCallback); } catch (Throwable ignored) {}
        }
        connectivity = null;
        networkCallback = null;
    }

    private void registerRecoveryReceiver() {
        unregisterRecoveryReceiver();
        recoveryReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                main.removeCallbacks(pendingKick);
                main.postDelayed(pendingKick, EVENT_DEBOUNCE_MS);
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_POWER_CONNECTED);
        f.addAction(Intent.ACTION_BATTERY_OKAY);
        f.addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(recoveryReceiver, f, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(recoveryReceiver, f);
            }
        } catch (Throwable ignored) {
            recoveryReceiver = null;
        }
    }

    private void unregisterRecoveryReceiver() {
        if (recoveryReceiver != null) {
            try { unregisterReceiver(recoveryReceiver); } catch (Throwable ignored) {}
        }
        recoveryReceiver = null;
    }


    private void runDirectRecovery(boolean pendingOnly) {
        AppConfig cfg = new AppConfig(this);
        if (!shouldRun(cfg)) return;
        if (!workerBusy.compareAndSet(false, true)) return;

        try {
            worker.execute(() -> {
                PowerManager.WakeLock lock = null;
                try {
                    PowerManager pm = (PowerManager)getSystemService(Context.POWER_SERVICE);
                    if (pm != null) {
                        lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                                getPackageName() + ":active-transcription");
                        lock.setReferenceCounted(false);
                        lock.acquire(ACTIVE_WAKELOCK_MS);
                    }
                    WorkCoordinator wc = new WorkCoordinator(getApplicationContext());
                    activeCoordinator = wc;
                    if (pendingOnly) wc.runPendingOnly(false);
                    else wc.runRecovery(false);
                } catch (Throwable t) {
                    new AppConfig(TranscriptionKeepAliveService.this)
                            .setLastRunNote("보호 실행 오류 · " + t.getClass().getSimpleName());
                    Scheduler.immediateRecovery(TranscriptionKeepAliveService.this, false);
                } finally {
                    activeCoordinator = null;
                    if (lock != null && lock.isHeld()) {
                        try { lock.release(); } catch (Throwable ignored) {}
                    }
                    workerBusy.set(false);
                }
            });
        } catch (Throwable t) {
            workerBusy.set(false);
            Scheduler.immediateRecovery(this, false);
        }
    }

    private void triggerRecovery(boolean force) {
        withShortWakeLock(() -> Scheduler.immediateRecovery(this, force));
    }

    private void triggerPending(boolean force) {
        withShortWakeLock(() -> Scheduler.immediatePending(this, force));
    }

    private void withShortWakeLock(Runnable action) {
        PowerManager.WakeLock lock = null;
        try {
            PowerManager pm = (PowerManager)getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                        getPackageName() + ":recovery-trigger");
                lock.setReferenceCounted(false);
                lock.acquire(TRIGGER_WAKELOCK_MS);
            }
            action.run();
        } catch (Throwable ignored) {
        } finally {
            if (lock != null && lock.isHeld()) {
                try { lock.release(); } catch (Throwable ignored) {}
            }
        }
    }

    private boolean shouldRun(AppConfig cfg) {
        return shouldRun(this, cfg);
    }

    private static boolean shouldRun(Context c, AppConfig cfg) {
        return cfg.autoEnabled() && !cfg.paused() && cfg.basicReady(c);
    }
}
