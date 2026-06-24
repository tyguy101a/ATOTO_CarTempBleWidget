package com.orbitaldelta.cartempble;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }

        String action = intent.getAction();

        boolean shouldStart = Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)
                || "com.htc.intent.action.QUICKBOOT_POWERON".equals(action);

        if (!shouldStart) {
            return;
        }

        // Do not trust an old reading after a boot/update. Show OFFLINE until the ESP32 is seen again.
        TempStore.clear(context);
        TempStore.setServiceStatus(context, "Auto-starting scanner after boot");
        TempWidgetProvider.updateAllWidgets(context);

        // Start even if the launcher has not reported widget IDs yet. Some Android headunits
        // restore widgets late during boot, and gating this on hasWidgets() can prevent startup.
        TempScanService.scheduleKeepAlive(context);
        AtotoKeepAliveService.poke(context);
        TempScanService.start(context);
    }
}
