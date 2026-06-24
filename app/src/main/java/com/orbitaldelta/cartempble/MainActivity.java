package com.orbitaldelta.cartempble;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.Switch;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int REQUEST_LOCATION = 1001;
    private static final int REQUEST_BACKGROUND_LOCATION = 1002;
    private static final long SCAN_RESTART_MS = 30000L;

    private TextView statusText;
    private TextView tempText;
    private Button rescanButton;
    private Button startBackgroundButton;
    private TextView backgroundStatusText;
    private Switch unitSwitch;
    private TextView unitText;
    private TextView washerStatusText;

    private BluetoothLeScanner scanner;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean isScanning = false;
    private int seenCount = 0;

    private final Runnable restartScanRunnable = new Runnable() {
        @Override
        public void run() {
            stopBleScan();
            startBleScan();
        }
    };


    private final Runnable uiRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            updateDisplayedTempFromStore();
            updateDisplayedWasherFromStore();
            refreshBackgroundStatus();
            handler.postDelayed(this, 1000L);
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
            setStatus("BLE scan failed: " + errorCode);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        tempText = findViewById(R.id.tempText);
        rescanButton = findViewById(R.id.rescanButton);
        startBackgroundButton = findViewById(R.id.startBackgroundButton);
        backgroundStatusText = findViewById(R.id.backgroundStatusText);
        unitSwitch = findViewById(R.id.unitSwitch);
        unitText = findViewById(R.id.unitText);
        washerStatusText = findViewById(R.id.washerStatusText);

        unitSwitch.setChecked(TempStore.useFahrenheit(this));
        updateDisplayedTempFromStore();
        updateDisplayedWasherFromStore();
        updateUnitText();

        unitSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                TempStore.setUseFahrenheit(MainActivity.this, isChecked);
                updateUnitText();
                updateDisplayedTempFromStore();
                TempWidgetProvider.updateAllWidgets(MainActivity.this);
            }
        });

        rescanButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                stopBleScan();
                startFlow();
                refreshBackgroundStatus();
            }
        });

        startBackgroundButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startBackgroundScanner();
            }
        });

        startFlow();
        refreshBackgroundStatus();
    }

    private boolean hasFineLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean needsBackgroundLocationPermission() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                && checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED;
    }

    private void requestBackgroundLocationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION}, REQUEST_BACKGROUND_LOCATION);
            setStatus("Background location is required so the widget can update while the app is closed");
        }
    }

    private void startFlow() {
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            setStatus("BLE feature not reported by this device");
            return;
        }

        if (!hasFineLocationPermission()) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_LOCATION);
            setStatus("Location permission is required for BLE scan on Android 10");
            return;
        }

        if (needsBackgroundLocationPermission()) {
            requestBackgroundLocationPermission();
            return;
        }

        if (!isLocationEnabled()) {
            setStatus("Turn on Android Location, then tap Rescan");
            return;
        }

        startBackgroundScanner();
        setStatus("Background scanner requested. You can press Home; do not swipe-close the app.");
    }

    private void startBackgroundScanner() {
        if (!hasFineLocationPermission()) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_LOCATION);
            setStatus("Location permission is required for BLE scan on Android 10");
            refreshBackgroundStatus();
            return;
        }

        if (needsBackgroundLocationPermission()) {
            requestBackgroundLocationPermission();
            refreshBackgroundStatus();
            return;
        }

        if (!isLocationEnabled()) {
            setStatus("Turn on Android Location, then tap Start Background Scanner");
            refreshBackgroundStatus();
            return;
        }

        TempScanService.scheduleKeepAlive(this);
        TempScanService.start(this);
        TempWidgetProvider.updateAllWidgets(this);
        refreshBackgroundStatus();
    }

    private void refreshBackgroundStatus() {
        if (backgroundStatusText != null) {
            backgroundStatusText.setText("Background: " + TempStore.getServiceStatus(this)
                    + "\nLast BLE seen: " + TempStore.getLastSeenBleName(this));
        }
    }

    private void startBleScan() {
        BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);

        if (bluetoothManager == null) {
            setStatus("Bluetooth manager unavailable");
            return;
        }

        BluetoothAdapter adapter = bluetoothManager.getAdapter();

        if (adapter == null) {
            setStatus("Bluetooth adapter unavailable");
            return;
        }

        if (!adapter.isEnabled()) {
            setStatus("Bluetooth is off");
            return;
        }

        scanner = adapter.getBluetoothLeScanner();

        if (scanner == null) {
            setStatus("BLE scanner unavailable");
            return;
        }

        try {
            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .setReportDelay(0)
                    .build();
            scanner.startScan(null, settings, scanCallback);
            isScanning = true;
            setStatus("Scanning for CAR_TEMP_ESP32 manufacturer data...");

            handler.removeCallbacks(restartScanRunnable);
            handler.postDelayed(restartScanRunnable, SCAN_RESTART_MS);
        } catch (SecurityException e) {
            setStatus("Missing BLE/location permission");
        } catch (Exception e) {
            setStatus("Could not start BLE scan: " + e.getMessage());
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

    private void handleScanResult(ScanResult result) {
        if (result == null || result.getScanRecord() == null) {
            return;
        }

        String deviceName = TempBleParser.getDeviceName(result);
        seenCount++;

        if (!TempBleParser.DEVICE_NAME.equals(deviceName)) {
            if (seenCount % 10 == 0) {
                final String seenName = deviceName == null ? "unnamed BLE device" : deviceName;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        statusText.setText("Scanning... saw " + seenName + ", waiting for CAR_TEMP_ESP32");
                    }
                });
            }
            return;
        }

        final TempBleParser.ParsedReading reading = TempBleParser.parseReading(result);
        final String time = new SimpleDateFormat("h:mm:ss a", Locale.US).format(new Date());
        final int rssi = result.getRssi();

        if (reading == null) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    statusText.setText("Found CAR_TEMP_ESP32, waiting for TEMP payload... " + TempBleParser.debugPayloadSummary(result));
                }
            });
            return;
        }

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                TempStore.saveReading(MainActivity.this, reading.tempC, reading.rawPayload, reading.washerLow);
                updateDisplayedTempFromStore();
                updateDisplayedWasherFromStore();
                statusText.setText("Found " + reading.rawPayload + " at " + time + " · RSSI " + rssi);
                TempWidgetProvider.updateAllWidgets(MainActivity.this);
                WasherWidgetProvider.updateAllWidgets(MainActivity.this);
                refreshBackgroundStatus();
            }
        });
    }

    private void updateDisplayedTempFromStore() {
        if (tempText != null) {
            tempText.setText(TempStore.getDisplayTemp(this));
        }
    }

    private void updateDisplayedWasherFromStore() {
        if (washerStatusText != null) {
            washerStatusText.setText("Washer: " + TempStore.getWasherDisplayStatus(this)
                    + " · " + TempStore.getWasherSubStatus(this));
        }
    }

    private void updateUnitText() {
        if (unitText != null) {
            unitText.setText(TempStore.useFahrenheit(this) ? "Display: Fahrenheit" : "Display: Celsius");
        }
    }

    private boolean isLocationEnabled() {
        try {
            int mode = Settings.Secure.getInt(getContentResolver(), Settings.Secure.LOCATION_MODE);
            return mode != Settings.Secure.LOCATION_MODE_OFF;
        } catch (Exception e) {
            return true;
        }
    }

    private void setStatus(final String status) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                statusText.setText(status);
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_LOCATION
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startFlow();
            return;
        }

        if (requestCode == REQUEST_BACKGROUND_LOCATION
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startFlow();
            return;
        }

        if (requestCode == REQUEST_BACKGROUND_LOCATION) {
            setStatus("Background location denied. The app can scan while open, but the widget may not update while the app is closed.");
            return;
        }

        setStatus("Location permission denied. BLE scanning will not work on Android 10.");
    }

    private void keepServicesAlive(String reason) {
        try {
            TempStore.setServiceStatus(this, reason);
            TempScanService.scheduleKeepAlive(this);
            AtotoKeepAliveService.poke(this);
            TempScanService.start(this);
            TempWidgetProvider.updateAllWidgets(this);
            WasherWidgetProvider.updateAllWidgets(this);
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateDisplayedTempFromStore();
        updateDisplayedWasherFromStore();
        refreshBackgroundStatus();
        handler.removeCallbacks(uiRefreshRunnable);
        handler.post(uiRefreshRunnable);

        // Practical headunit behavior: every time the app is opened/resumed,
        // automatically kick the background scanner. This avoids needing to
        // press a button after boot or after the headunit kills the task.
        if (hasFineLocationPermission() && !needsBackgroundLocationPermission() && isLocationEnabled()) {
            startBackgroundScanner();
            setStatus("Background scanner requested. Service should continue after leaving the app.");
        }
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(uiRefreshRunnable);
        keepServicesAlive("App left screen; keeping background services alive");
        super.onPause();
    }

    @Override
    protected void onStop() {
        keepServicesAlive("App stopped; keeping background services alive");
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        // Do NOT stop BLE scanning here. On this headunit, closing the Activity
        // was also stopping/killing the scanner path. Leaving/closing the app
        // should actively re-poke both foreground services instead.
        handler.removeCallbacks(restartScanRunnable);
        keepServicesAlive("App closed; scanner/ATOTO keepalive re-poked");
        super.onDestroy();
    }
}
