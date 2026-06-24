package com.orbitaldelta.cartempble;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;

/**
 * Compatibility entry point for ATOTO/SYU-style keep-alive managers.
 *
 * ATOTO starts this with startForegroundService(), so this service MUST call
 * startForeground() immediately or Android will crash the process with:
 * "Context.startForegroundService() did not then call Service.startForeground()".
 */
public class AtotoKeepAliveService extends Service {
    public static final String ACTION_ATOTO_KEEPALIVE = "com.atoto.keepalive";
    public static final String ACTION_ATOTO_KEEPALIVE_SERVICE = "com.atoto.keepaliveservice.keepAlive";
    public static final String ACTION_APP_KEEPALIVE = "com.orbitaldelta.cartempble.keepAlive";
    public static final String ACTION_APP_WAKEUP = "com.orbitaldelta.cartempble.wakeup";
    public static final String ACTION_START_SCAN = "com.orbitaldelta.cartempble.START_SCAN";
    public static final String ACTION_WIDGET_RESTART = "com.orbitaldelta.cartempble.WIDGET_RESTART";

    private static final String CHANNEL_ID = "car_temp_ble_keepalive";
    private static final int NOTIFICATION_ID = 43;
    private static final long MIN_SCANNER_KICK_MS = 30000L;
    private static long lastScannerKickElapsed = 0;

    private final IBinder binder = new LocalBinder();

    public class LocalBinder extends Binder {
        public AtotoKeepAliveService getService() {
            return AtotoKeepAliveService.this;
        }
    }

    public static void poke(Context context) {
        try {
            Intent intent = new Intent(context, AtotoKeepAliveService.class);
            intent.setAction(ACTION_APP_KEEPALIVE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Exception ignored) {
        }
    }

    public static void scheduleSelf(Context context, long delayMs) {
        try {
            Intent intent = new Intent(context, AtotoKeepAliveService.class);
            intent.setAction(ACTION_ATOTO_KEEPALIVE);
            PendingIntent pendingIntent = PendingIntent.getService(
                    context,
                    43043,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );
            AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (alarmManager != null) {
                long when = SystemClock.elapsedRealtime() + delayMs;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, when, pendingIntent);
                } else {
                    alarmManager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, when, pendingIntent);
                }
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        enterForeground("ATOTO keepalive ready");
        kickScanner("ATOTO keepalive created");
        scheduleSelf(this, 60000L);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : "direct start";
        enterForeground("ATOTO keepalive: " + action);
        boolean force = ACTION_WIDGET_RESTART.equals(action) || ACTION_START_SCAN.equals(action);
        kickScanner(force ? "Widget tapped; scanner restart requested" : "ATOTO keepalive start: " + action, force);
        scheduleSelf(this, 60000L);
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        String action = intent != null ? intent.getAction() : "direct bind";
        enterForeground("ATOTO keepalive bind");
        kickScanner("ATOTO keepalive bind: " + action);
        return binder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        enterForeground("ATOTO keepalive waiting");
        kickScanner("ATOTO keepalive unbind");
        return true;
    }

    private void kickScanner(String status) {
        kickScanner(status, false);
    }

    private void kickScanner(String status, boolean force) {
        TempStore.setServiceStatus(this, status);
        TempWidgetProvider.updateAllWidgets(this);
        WasherWidgetProvider.updateAllWidgets(this);
        TempScanService.scheduleKeepAlive(this);

        // ATOTO checks about every 3 seconds. Starting the scanner service on
        // every check causes repeated BLE scan starts and Android logs
        // "App is scanning too frequently". Keep this service foreground,
        // but only kick the scanner occasionally. A widget tap is a user action,
        // so it is allowed to force an immediate service poke.
        long now = android.os.SystemClock.elapsedRealtime();
        if (force || now - lastScannerKickElapsed >= MIN_SCANNER_KICK_MS) {
            lastScannerKickElapsed = now;
            TempScanService.start(this);
        }
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        enterForeground("ATOTO keepalive protected after app close");
        kickScanner("ATOTO keepalive task removed; restart scheduled");
        scheduleSelf(this, 2000L);
        // Intentionally do not call super. Some headunit builds use task removal
        // as a signal to tear down app services. Keep the foreground anchor alive.
    }

    @Override
    public void onDestroy() {
        TempStore.setServiceStatus(this, "ATOTO keepalive destroyed; restart scheduled");
        TempScanService.scheduleKeepAlive(this);
        scheduleSelf(this, 2000L);
        super.onDestroy();
    }

    private void enterForeground(String text) {
        createNotificationChannel();
        try {
            startForeground(NOTIFICATION_ID, buildNotification(text));
        } catch (Exception e) {
            TempStore.setServiceStatus(this, "ATOTO keepalive foreground failed: " + e.getClass().getSimpleName());
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Car Temp BLE keepalive",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("ATOTO keepalive entry point for the car temperature widget");
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification(String text) {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                1,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        return builder
                .setContentTitle("Car temp keepalive active")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }
}
