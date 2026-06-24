package com.orbitaldelta.cartempble;

import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.util.SparseArray;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

public class TempBleParser {
    public static final String DEVICE_NAME = "CAR_TEMP_ESP32";
    public static final int MANUFACTURER_ID = 0xFFFF;

    public static class ParsedReading {
        public final float tempC;
        public final String rawPayload;
        public final Boolean washerLow;

        public ParsedReading(float tempC, String rawPayload, Boolean washerLow) {
            this.tempC = tempC;
            this.rawPayload = rawPayload;
            this.washerLow = washerLow;
        }
    }

    public static boolean isTargetDevice(ScanResult result) {
        String name = getDeviceName(result);
        return DEVICE_NAME.equals(name);
    }

    public static String getDeviceName(ScanResult result) {
        if (result == null) {
            return null;
        }

        ScanRecord record = result.getScanRecord();
        if (record != null && record.getDeviceName() != null) {
            return record.getDeviceName();
        }

        try {
            return result.getDevice() == null ? null : result.getDevice().getName();
        } catch (SecurityException e) {
            return null;
        }
    }

    public static ParsedReading parseReading(ScanResult result) {
        if (result == null || result.getScanRecord() == null) {
            return null;
        }

        ScanRecord record = result.getScanRecord();

        // Normal path: Android should expose the manufacturer payload by ID.
        ParsedReading parsed = parseBytes(record.getManufacturerSpecificData(MANUFACTURER_ID));
        if (parsed != null) {
            return parsed;
        }

        // Fallback: inspect every manufacturer block. Some vendor stacks report
        // the ID oddly, or include the ID bytes inside the value.
        SparseArray<byte[]> all = record.getManufacturerSpecificData();
        if (all != null) {
            for (int i = 0; i < all.size(); i++) {
                parsed = parseBytes(all.valueAt(i));
                if (parsed != null) {
                    return parsed;
                }
            }
        }

        // Last-resort fallback: scan the whole BLE advertisement byte array for
        // ASCII "TEMP:". This catches malformed/packed manufacturer data.
        return parseBytes(record.getBytes());
    }

    public static String debugPayloadSummary(ScanResult result) {
        if (result == null || result.getScanRecord() == null) {
            return "no scan record";
        }

        ScanRecord record = result.getScanRecord();
        StringBuilder sb = new StringBuilder();
        byte[] direct = record.getManufacturerSpecificData(MANUFACTURER_ID);
        sb.append("mfgFFFF=").append(toDisplayString(direct));

        SparseArray<byte[]> all = record.getManufacturerSpecificData();
        if (all != null && all.size() > 0) {
            sb.append(" allMfg=");
            for (int i = 0; i < all.size(); i++) {
                sb.append(all.keyAt(i)).append(":").append(toDisplayString(all.valueAt(i))).append(" ");
            }
        }

        sb.append(" raw=").append(toDisplayString(record.getBytes()));
        return sb.toString();
    }

    private static ParsedReading parseBytes(byte[] data) {
        String text = bytesToAscii(data);
        if (text == null) {
            return null;
        }
        return parseText(text);
    }

    private static ParsedReading parseText(String text) {
        if (text == null) {
            return null;
        }

        String upperAll = text.toUpperCase(Locale.US);
        int tempIndex = upperAll.indexOf("TEMP:");
        int compactIndex = upperAll.indexOf("T:");
        boolean compact = false;
        if (tempIndex < 0 && compactIndex >= 0) {
            tempIndex = compactIndex;
            compact = true;
        }
        if (tempIndex < 0) {
            return null;
        }

        String payload = text.substring(tempIndex).trim();

        // Supported payloads:
        //   TEMP:22.4C
        //   TEMP:22.4C;WASHER:OK
        //   TEMP:22.4C;WASHER:LOW
        //   T:22.4;W:OK
        //   T:22.4;W:L
        // Also tolerates junk bytes before TEMP:/T:, missing C, or future extra fields.
        int start = payload.indexOf(':') + 1;
        if (start <= 0 || start >= payload.length()) {
            return null;
        }

        StringBuilder number = new StringBuilder();
        boolean sawDigit = false;
        for (int i = start; i < payload.length(); i++) {
            char c = payload.charAt(i);
            if ((c >= '0' && c <= '9') || c == '-' || c == '+') {
                number.append(c);
                sawDigit = true;
            } else if (c == '.') {
                number.append(c);
            } else if (sawDigit) {
                break;
            }
        }

        if (number.length() == 0) {
            return null;
        }

        Boolean washerLow = null;
        String upper = payload.toUpperCase(Locale.US);
        if (upper.contains("WASHER:LOW") || upper.contains("WASHER=LOW") || upper.contains("W:LOW") || upper.contains("W=L") || upper.contains("W:L")) {
            washerLow = true;
        } else if (upper.contains("WASHER:OK") || upper.contains("WASHER=OK") || upper.contains("W:OK") || upper.contains("W=OK") || upper.contains("W:0") || upper.contains("W=0")) {
            washerLow = false;
        }

        try {
            float tempC = Float.parseFloat(number.toString());
            return new ParsedReading(tempC, payload, washerLow);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String bytesToAscii(byte[] data) {
        if (data == null || data.length == 0) {
            return null;
        }

        try {
            String raw = new String(data, StandardCharsets.UTF_8);
            StringBuilder clean = new StringBuilder();
            for (int i = 0; i < raw.length(); i++) {
                char c = raw.charAt(i);
                if (c >= 32 && c <= 126) {
                    clean.append(c);
                }
            }
            String text = clean.toString().trim();
            return text.length() == 0 ? null : text;
        } catch (Exception e) {
            return null;
        }
    }

    private static String toDisplayString(byte[] data) {
        if (data == null || data.length == 0) {
            return "null";
        }
        String ascii = bytesToAscii(data);
        if (ascii != null) {
            int tempIndex = ascii.toUpperCase(Locale.US).indexOf("TEMP:");
            if (tempIndex >= 0) {
                return ascii.substring(tempIndex);
            }
            if (ascii.length() > 32) {
                return ascii.substring(0, 32) + "...";
            }
            return ascii;
        }
        return "bytes=" + data.length;
    }
}
