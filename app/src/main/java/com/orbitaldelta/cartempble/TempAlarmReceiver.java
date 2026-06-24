package com.orbitaldelta.cartempble;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class TempAlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        TempWidgetProvider.updateAllWidgets(context);
        AtotoKeepAliveService.poke(context);

        // Always poke the foreground service and force a full scanner restart.
        // Some headunit Bluetooth stacks report the service as running while
        // BLE callbacks stop after the app UI closes.
        Intent serviceIntent = new Intent(context, TempScanService.class);
        serviceIntent.setAction(TempScanService.ACTION_FORCE_RESCAN);

        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
        } catch (Exception e) {
            TempStore.setServiceStatus(context, "Keepalive could not start scanner: " + e.getClass().getSimpleName());
        }

        TempScanService.scheduleKeepAlive(context);
    }
}
