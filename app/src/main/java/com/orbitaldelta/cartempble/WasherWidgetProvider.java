package com.orbitaldelta.cartempble;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

public class WasherWidgetProvider extends AppWidgetProvider {

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        TempScanService.scheduleKeepAlive(context);
        AtotoKeepAliveService.poke(context);
        TempScanService.start(context);
        for (int appWidgetId : appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId);
        }
    }

    @Override
    public void onEnabled(Context context) {
        super.onEnabled(context);
        TempScanService.scheduleKeepAlive(context);
        AtotoKeepAliveService.poke(context);
        TempScanService.start(context);
    }

    public static boolean hasWidgets(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        ComponentName componentName = new ComponentName(context, WasherWidgetProvider.class);
        int[] ids = manager.getAppWidgetIds(componentName);
        return ids != null && ids.length > 0;
    }

    public static void updateAllWidgets(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        ComponentName componentName = new ComponentName(context, WasherWidgetProvider.class);
        int[] ids = manager.getAppWidgetIds(componentName);
        for (int id : ids) {
            updateWidget(context, manager, id);
        }
    }

    private static void updateWidget(Context context, AppWidgetManager manager, int appWidgetId) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_washer);

        String washerStatus = TempStore.getWasherDisplayStatus(context);
        String washerSubStatus = TempStore.getWasherSubStatus(context);

        views.setTextViewText(R.id.widgetWasherLabel, "WASHER");
        views.setTextViewText(R.id.widgetWasherStatus, washerStatus);
        views.setTextViewText(R.id.widgetWasherSubStatus, washerSubStatus);

        Intent restartIntent = new Intent(context, AtotoKeepAliveService.class);
        restartIntent.setAction(AtotoKeepAliveService.ACTION_WIDGET_RESTART);
        PendingIntent pendingIntent = PendingIntent.getService(
                context,
                44045,
                restartIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        views.setOnClickPendingIntent(R.id.widgetWasherRoot, pendingIntent);

        manager.updateAppWidget(appWidgetId, views);
    }
}
