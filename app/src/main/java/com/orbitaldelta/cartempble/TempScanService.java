package com.orbitaldelta.cartempble;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.Looper;
import android.os.SystemClock;

public class TempScanService extends Service {
    private static final String CHANNEL_ID = "car_temp_ble_scan";
    private static final int NOTIFICATION_ID = 42;
    public static final String ACTION_FORCE_RESCAN = "com.orbitaldelta.cartempble.FORCE_RESCAN";

    // ATOTO's keepalive service can poke us every ~3 seconds.
    // Android's BLE stack will throttle/crash scans if we repeatedly stop/start scanning.
    // Keep one long-running scan alive and only restart it rarely.
    private static final long SCAN_RESTART_MS = 120000L;
    private static final long WIDGET_REFRESH_MS = 10000L;
    private static final long KEEP_ALIVE_MS = 30000L;
    private static final long OFFLINE_RESCAN_MS = 120000L;
    private static final long MIN_SCAN_RESTART_MS = 60000L;

    private BluetoothLeScanner scanner;
    private boolean isScanning = false;
    private long anyBleCount = 0;
    private long targetBleCount = 0;
    private long lastNotificationUpdate = 0;
    private long lastScanStartElapsed = 0;
    private long lastForcedRestartElapsed = 0;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wakeLock;

    public static void start(Context context) {
        Intent intent = new Intent(context, TempScanService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Exception e) {
            TempStore.setServiceStatus(context, "Could not start background scanner: " + e.getClass().getSimpleName());
        }
    }

    public static void scheduleKeepAlive(Context context) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            return;
        }

        Intent intent = new Intent(context, TempAlarmReceiver.class);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                context,
                4242,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        long triggerAt = SystemClock.elapsedRealtime() + KEEP_ALIVE_MS;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent);
        } else {
            alarmManager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent);
        }
    }

    public static void cancelKeepAlive(Context context) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            return;
        }

        Intent intent = new Intent(context, TempAlarmReceiver.class);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                context,
                4242,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        alarmManager.cancel(pendingIntent);
    }

    public static void pokeSoon(Context context, long delayMs) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            return;
        }

        Intent intent = new Intent(context, TempAlarmReceiver.class);
        intent.setAction(ACTION_FORCE_RESCAN);

        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                context,
                4243,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        long triggerAt = SystemClock.elapsedRealtime() + delayMs;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent);
        } else {
            alarmManager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent);
        }
    }

    private final Runnable restartScanRunnable = new Runnable() {
        @Override
        public void run() {
            stopBleScan();
            startBleScan();
        }
    };

    private final Runnable widgetRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            TempWidgetProvider.updateAllWidgets(TempScanService.this);
            WasherWidgetProvider.updateAllWidgets(TempScanService.this);
            maybeUpdateDebugNotification("Service alive · BLE " + anyBleCount + " · Target " + targetBleCount);

            // Keep the widget fresh, but do not repeatedly restart BLE here.
            // ATOTO may poke this service every few seconds, and frequent BLE
            // scan restarts trigger: "App is scanning too frequently".
            TempStore.setServiceStatus(TempScanService.this, "Background scanner alive · BLE " + anyBleCount + " · Target " + targetBleCount);
            if (!isScanning) {
                startBleScan();
            }

            handler.postDelayed(this, WIDGET_REFRESH_MS);
        }
    };

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            handleScanResult(result);
        }

        @Override
        public void onBatchScanResults(java.util.List<ScanResult> results) {
            if (results == null) {
                return;
            }
            for (ScanResult result : results) {
                handleScanResult(result);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            isScanning = false;
            TempStore.setServiceStatus(TempScanService.this, "Background BLE scan failed: " + errorCode);
            TempWidgetProvider.updateAllWidgets(TempScanService.this);
            WasherWidgetProvider.updateAllWidgets(TempScanService.this);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        acquireWakeLock();
        startForeground(NOTIFICATION_ID, buildNotification("Scanning for CAR_TEMP_ESP32"));
        TempStore.setServiceStatus(this, "Background scanner service started");
        scheduleKeepAlive(this);
        TempWidgetProvider.updateAllWidgets(this);
        WasherWidgetProvider.updateAllWidgets(TempScanService.this);
        handler.removeCallbacks(widgetRefreshRunnable);
        handler.post(widgetRefreshRunnable);
        startBleScan();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, buildNotification("Scanning for CAR_TEMP_ESP32"));
        scheduleKeepAlive(this);
        TempWidgetProvider.updateAllWidgets(this);
        WasherWidgetProvider.updateAllWidgets(TempScanService.this);

        // ATOTO may startForegroundService() this package every ~3 seconds.
        // Do NOT restart BLE on every poke or Android will throttle scans.
        if (!isScanning) {
            startBleScan();
        } else {
            TempStore.setServiceStatus(this, "Background scanner already running · keepalive poke");
        }

        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startBleScan() {
        if (isScanning) {
            TempStore.setServiceStatus(this, "Background scanner already running");
            return;
        }

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            TempStore.setServiceStatus(this, "Background scanner blocked: location permission missing");
            TempWidgetProvider.updateAllWidgets(this);
            WasherWidgetProvider.updateAllWidgets(TempScanService.this);
            return;
        }

        BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager == null) {
            TempStore.setServiceStatus(this, "Bluetooth manager unavailable");
            return;
        }

        BluetoothAdapter adapter = bluetoothManager.getAdapter();
        if (adapter == null) {
            TempStore.setServiceStatus(this, "Bluetooth adapter unavailable");
            return;
        }

        if (!adapter.isEnabled()) {
            TempStore.setServiceStatus(this, "Bluetooth is off");
            return;
        }

        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            TempStore.setServiceStatus(this, "BLE scanner unavailable");
            return;
        }

        try {
            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .setReportDelay(0)
                    .build();
            scanner.startScan(null, settings, scanCallback);
            isScanning = true;
            lastScanStartElapsed = SystemClock.elapsedRealtime();
            TempStore.setServiceStatus(this, "Background BLE scan running");
            updateNotification("Background scan running · BLE " + anyBleCount + " · Target " + targetBleCount);
            handler.removeCallbacks(restartScanRunnable);
            handler.postDelayed(restartScanRunnable, SCAN_RESTART_MS);
        } catch (SecurityException e) {
            isScanning = false;
            TempStore.setServiceStatus(this, "Background scan blocked: permission/security error");
        } catch (Exception e) {
            isScanning = false;
            TempStore.setServiceStatus(this, "Could not start BLE scan: " + e.getClass().getSimpleName());
        }
    }

    private void stopBleScan() {
        handler.removeCallbacks(restartScanRunnable);
        if (scanner != null && isScanning) {
            try {
                scanner.stopScan(scanCallback);
            } catch (Exception ignored) {
            }
        }
        isScanning = false;
    }

    private void forceRestartScan() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastForcedRestartElapsed < MIN_SCAN_RESTART_MS) {
            TempStore.setServiceStatus(this, "BLE restart skipped: throttle guard active");
            return;
        }
        lastForcedRestartElapsed = now;
        stopBleScan();
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                startBleScan();
            }
        }, 1500L);
    }

    private void handleScanResult(ScanResult result) {
        anyBleCount++;
        if (result == null || result.getScanRecord() == null) {
            return;
        }

        String deviceName = TempBleParser.getDeviceName(result);
        TempStore.setLastSeenBleName(this, deviceName);

        if (!TempBleParser.isTargetDevice(result)) {
            maybeUpdateDebugNotification("Saw BLE devices · BLE " + anyBleCount + " · Target " + targetBleCount);
            return;
        }

        targetBleCount++;
        maybeUpdateDebugNotification("Saw CAR_TEMP_ESP32 · parsing payload");

        TempBleParser.ParsedReading reading = TempBleParser.parseReading(result);
        if (reading == null) {
            TempStore.setServiceStatus(this, "Found CAR_TEMP_ESP32, waiting for TEMP payload");
            updateNotification("Found sensor, no TEMP payload · Target " + targetBleCount);
            TempWidgetProvider.updateAllWidgets(this);
            WasherWidgetProvider.updateAllWidgets(TempScanService.this);
            return;
        }

        TempStore.saveReading(this, reading.tempC, reading.rawPayload, reading.washerLow);
        TempStore.setServiceStatus(this, "Updated from " + reading.rawPayload);
        updateNotification("Latest: " + TempStore.getDisplayTemp(this) + " · " + TempStore.getUnitLabel(this));
        TempWidgetProvider.updateAllWidgets(this);
        WasherWidgetProvider.updateAllWidgets(TempScanService.this);
    }

    private void acquireWakeLock() {
        try {
            PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (powerManager != null && (wakeLock == null || !wakeLock.isHeld())) {
                wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CarTempBLE:ScannerWakeLock");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire();
                TempStore.setServiceStatus(this, "Scanner wake lock acquired");
            }
        } catch (Exception e) {
            TempStore.setServiceStatus(this, "Wake lock failed: " + e.getClass().getSimpleName());
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Exception ignored) {
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Car Temp BLE",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Keeps the outside temperature widget updated");
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }


    private void maybeUpdateDebugNotification(String text) {
        long now = System.currentTimeMillis();
        if (now - lastNotificationUpdate >= 5000L) {
            lastNotificationUpdate = now;
            updateNotification(text);
        }
    }

    private void updateNotification(String text) {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification(text));
        }
    }

    private Notification buildNotification(String text) {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        return builder
                .setContentTitle("Car temp sensor active")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // Some headunit builds kill foreground services when the app is swiped away.
        // Schedule a quick restart so the widget can recover without opening the app.
        TempStore.setServiceStatus(this, "Scanner task removed; keeping service alive and scheduling restart");
        // Keep the foreground service alive as aggressively as possible.
        // Some headunits call onTaskRemoved when Back/Recents closes the UI;
        // calling super here can allow the system to tear the service down.
        startForeground(NOTIFICATION_ID, buildNotification("Keeping scanner alive after app exit"));
        scheduleKeepAlive(this);
        pokeSoon(this, 2000L);
        if (!isScanning) {
            startBleScan();
        }
        // Intentionally do not call super.onTaskRemoved(rootIntent).
    }

    @Override
    public void onDestroy() {
        // If the OS really destroys the service, clean up the old scanner but
        // immediately schedule multiple restart paths. The Activity never calls
        // stopService, so this should only happen if the headunit kills us.
        stopBleScan();
        releaseWakeLock();
        handler.removeCallbacks(widgetRefreshRunnable);
        TempStore.setServiceStatus(this, "Scanner service destroyed by system; restart scheduled");
        scheduleKeepAlive(this);
        pokeSoon(this, 2000L);
        AtotoKeepAliveService.poke(this);
        super.onDestroy();
    }
}
