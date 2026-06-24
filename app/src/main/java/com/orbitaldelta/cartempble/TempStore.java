package com.orbitaldelta.cartempble;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class TempStore {
    public static final String PREFS = "car_temp_ble_prefs";
    public static final String KEY_TEMP_C = "latest_temp_c";
    public static final String KEY_RAW_PAYLOAD = "latest_raw_payload";
    public static final String KEY_UPDATED_AT = "latest_updated_at";
    public static final String KEY_USE_FAHRENHEIT = "use_fahrenheit";
    public static final String KEY_SERVICE_STATUS = "service_status";
    public static final String KEY_SERVICE_UPDATED_AT = "service_updated_at";
    public static final String KEY_LAST_SEEN_BLE_NAME = "last_seen_ble_name";
    public static final String KEY_WASHER_LOW = "washer_low";
    public static final String KEY_WASHER_UPDATED_AT = "washer_updated_at";
    public static final long STALE_AFTER_MS = 5 * 60 * 1000L;

    private static Context storageContext(Context context) {
        Context appContext = context.getApplicationContext();

        // The scanner/widget can be started by boot/ATOTO before the normal app UI opens.
        // Use device-protected storage so the unit preference is available during boot-start too.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Context deviceContext = appContext.createDeviceProtectedStorageContext();

            // Best-effort migration from older builds that saved preferences in normal app storage.
            try {
                deviceContext.moveSharedPreferencesFrom(appContext, PREFS);
            } catch (Exception ignored) {
            }

            return deviceContext;
        }

        return appContext;
    }

    private static SharedPreferences prefs(Context context) {
        return storageContext(context).getSharedPreferences(PREFS, Context.MODE_MULTI_PROCESS);
    }

    public static void saveCelsius(Context context, float tempC, String rawPayload) {
        saveReading(context, tempC, rawPayload, null);
    }

    public static void saveReading(Context context, float tempC, String rawPayload, Boolean washerLow) {
        SharedPreferences prefs = prefs(context);
        SharedPreferences.Editor editor = prefs.edit()
                .putFloat(KEY_TEMP_C, tempC)
                .putString(KEY_RAW_PAYLOAD, rawPayload)
                .putLong(KEY_UPDATED_AT, System.currentTimeMillis());

        if (washerLow != null) {
            editor.putBoolean(KEY_WASHER_LOW, washerLow)
                    .putLong(KEY_WASHER_UPDATED_AT, System.currentTimeMillis());
        }

        editor.commit();
    }

    public static boolean hasTemp(Context context) {
        return prefs(context).contains(KEY_TEMP_C);
    }

    public static float getTempC(Context context) {
        return prefs(context).getFloat(KEY_TEMP_C, Float.NaN);
    }

    public static boolean useFahrenheit(Context context) {
        // Default to Fahrenheit for the car widget. Earlier builds defaulted to Celsius,
        // which made boot-started widgets show C before the app UI ever opened.
        return prefs(context).getBoolean(KEY_USE_FAHRENHEIT, true);
    }

    public static void setUseFahrenheit(Context context, boolean useFahrenheit) {
        prefs(context)
                .edit()
                .putBoolean(KEY_USE_FAHRENHEIT, useFahrenheit)
                .commit();
    }

    public static String getDisplayTemp(Context context) {
        if (!hasTemp(context)) {
            return "--°";
        }

        float tempC = getTempC(context);
        if (Float.isNaN(tempC)) {
            return "--°";
        }

        if (useFahrenheit(context)) {
            float tempF = (tempC * 9.0f / 5.0f) + 32.0f;
            return String.format(Locale.US, "%.1f°", tempF);
        }

        return String.format(Locale.US, "%.1f°", tempC);
    }

    public static String getUnitLabel(Context context) {
        return useFahrenheit(context) ? "FAHRENHEIT" : "CELSIUS";
    }


    public static boolean hasWasherStatus(Context context) {
        return prefs(context).contains(KEY_WASHER_LOW);
    }

    public static boolean isWasherLow(Context context) {
        return prefs(context).getBoolean(KEY_WASHER_LOW, false);
    }

    public static long getWasherUpdatedAt(Context context) {
        return prefs(context).getLong(KEY_WASHER_UPDATED_AT, 0L);
    }

    public static boolean isWasherStale(Context context) {
        long updatedAt = getWasherUpdatedAt(context);
        if (updatedAt <= 0L) {
            return true;
        }
        return System.currentTimeMillis() - updatedAt > STALE_AFTER_MS;
    }

    public static String getWasherDisplayStatus(Context context) {
        if (!hasWasherStatus(context) || isWasherStale(context)) {
            return "--";
        }
        return isWasherLow(context) ? "LOW" : "OK";
    }

    public static String getWasherSubStatus(Context context) {
        if (!hasWasherStatus(context) || isWasherStale(context)) {
            return "OFFLINE";
        }
        return isWasherLow(context) ? "REFILL" : "LEVEL OK";
    }

    public static long getUpdatedAt(Context context) {
        return prefs(context).getLong(KEY_UPDATED_AT, 0L);
    }

    public static boolean isStale(Context context) {
        long updatedAt = getUpdatedAt(context);
        if (updatedAt <= 0L) {
            return true;
        }
        return System.currentTimeMillis() - updatedAt > STALE_AFTER_MS;
    }

    public static void setServiceStatus(Context context, String status) {
        prefs(context)
                .edit()
                .putString(KEY_SERVICE_STATUS, status)
                .putLong(KEY_SERVICE_UPDATED_AT, System.currentTimeMillis())
                .commit();
    }

    public static String getServiceStatus(Context context) {
        SharedPreferences prefs = prefs(context);
        String status = prefs.getString(KEY_SERVICE_STATUS, "Background scanner not started yet");
        long at = prefs.getLong(KEY_SERVICE_UPDATED_AT, 0L);
        if (at > 0L) {
            return status + " · " + new SimpleDateFormat("h:mm:ss a", Locale.US).format(new Date(at));
        }
        return status;
    }

    public static void setLastSeenBleName(Context context, String name) {
        prefs(context)
                .edit()
                .putString(KEY_LAST_SEEN_BLE_NAME, name == null ? "unnamed BLE device" : name)
                .commit();
    }

    public static String getLastSeenBleName(Context context) {
        return prefs(context)
                .getString(KEY_LAST_SEEN_BLE_NAME, "none yet");
    }

    public static void clear(Context context) {
        SharedPreferences prefs = prefs(context);
        prefs.edit()
                .remove(KEY_TEMP_C)
                .remove(KEY_RAW_PAYLOAD)
                .remove(KEY_UPDATED_AT)
                .remove(KEY_WASHER_LOW)
                .remove(KEY_WASHER_UPDATED_AT)
                .commit();
    }
}
