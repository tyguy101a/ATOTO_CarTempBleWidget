# Car Temp BLE Widget

Android home-screen widgets for an ATOTO/SYU-style Android head unit that display:

- outside temperature from an ESP32 + DS18B20 sensor
- washer fluid status from a capacitive fluid-level sensor

The ESP32 broadcasts sensor data over BLE. The Android app reads the BLE advertisement and updates two separate widgets.

## What this project includes

- Android app package: `com.orbitaldelta.cartempble`
- Temperature widget: visually unchanged from the original project
- Washer fluid widget: separate second widget
- ESP32 Arduino sketch in `esp32/CarTempBleSensor/CarTempBleSensor.ino`
- ATOTO-specific keepalive handling for head units that aggressively kill background apps

## Current BLE payload format

The ESP32 advertises as:

```text
CAR_TEMP_ESP32
```

The compact BLE manufacturer payload is:

```text
T:22.4;W:OK
```

or:

```text
T:22.4;W:L
```

Meaning:

```text
T:22.4 = temperature in Celsius
W:OK   = washer fluid level OK
W:L    = washer fluid low
```

The Android app stores temperature in Celsius internally. The widget/app can display Fahrenheit.

The parser also accepts older/longer formats such as:

```text
TEMP:22.4C
TEMP:22.4C;WASHER:OK
TEMP:22.4C;WASHER:LOW
```

## Hardware used

### ESP32

This project was built around an ESP32 Dev Module.

### Outside temperature sensor

Waterproof DS18B20 temperature probe.

Typical wiring:

```text
DS18B20 VCC   -> ESP32 3.3V
DS18B20 GND   -> ESP32 GND
DS18B20 DATA  -> ESP32 GPIO 4
```

A DS18B20 normally needs a pull-up resistor between DATA and VCC. A common value is 4.7k ohm. Some breakout boards already include this resistor.

### Washer fluid sensor

Capacitive liquid-level sensor mounted to the outside of the washer fluid tank.

This project expects a digital sensor output where:

```text
HIGH = washer fluid low
LOW  = washer fluid level OK
```

Typical wiring used here:

```text
Sensor VCC     -> ESP32 3.3V
Sensor GND     -> ESP32 GND
Sensor Signal  -> ESP32 GPIO 27
```

The installed sensor worked from the ESP32 3.3V pin. If your capacitive sensor requires 5V, 12V, or 6-36V power, do not connect its output directly to the ESP32 unless you confirm the output is safe for 3.3V GPIO. Use a divider, transistor, optocoupler, or level shifter if needed.

## ESP32 Arduino setup

Install these Arduino libraries:

```text
OneWire
DallasTemperature
NimBLE-Arduino
```

Board used during development:

```text
ESP32 Dev Module
```

Sketch path:

```text
esp32/CarTempBleSensor/CarTempBleSensor.ino
```

Open Serial Monitor at:

```text
115200 baud
```

Expected Serial Monitor output looks like:

```text
Temp C: 22.4 | Washer raw: OK | Washer stable: FLUID OK
Advertised: T:22.4;W:OK
```

or:

```text
Temp C: 22.4 | Washer raw: LOW | Washer stable: FLUID LOW
Advertised: T:22.4;W:L
```

## Android build setup

Open this project in Android Studio and build/install the app module.

Project details:

```text
Package: com.orbitaldelta.cartempble
minSdk: 23
targetSdk: 29
compileSdk: 35
```

The app was built for an ATOTO S8G2104PR Android 10 head unit, but it should also work on other Android devices that allow BLE scanning and widgets.

## Widgets

After installing the app, add widgets from the launcher widget picker.

There are two widgets:

```text
Car Temp BLE temperature widget
Car Temp BLE washer fluid widget
```

The washer widget shows:

```text
WASHER
OK / LOW / --
LEVEL OK / REFILL / OFFLINE
```

## Permissions

The app needs Bluetooth scanning permissions. On Android 10, BLE scanning requires location permission.

Open the app once and grant location permission. On the ATOTO head unit, set location to allow all the time if the option is available.

## ATOTO / SYU head unit notes

This project includes several ATOTO-specific workarounds because some ATOTO/SYU Android head units aggressively kill background apps and do not behave like a normal phone.

The important pieces are:

```text
AtotoKeepAliveService
TempScanService
BootReceiver
TempAlarmReceiver
TempWidgetProvider
WasherWidgetProvider
```

### Expected running services

On the head unit, it is normal to see two Car Temp BLE services:

```text
Car Temp BLE
  AtotoKeepAliveService
```

and:

```text
Car Temp BLE
  TempScanService
```

That is expected.

`AtotoKeepAliveService` is the ATOTO-visible foreground anchor service.

`TempScanService` is the actual BLE scanner and widget updater.

### Important ATOTO behavior

The ATOTO keepalive service checks for this action:

```text
com.atoto.keepalive
```

This project exposes `AtotoKeepAliveService` for that action. During testing, the ATOTO logcat output changed from:

```text
package:com.orbitaldelta.cartempble, action:com.atoto.keepalive is not running
```

to:

```text
package:com.orbitaldelta.cartempble, action:com.atoto.keepalive is running
```

That was the key fix that let the app start on reboot without manually opening it.

### Leaving the app

Best practice on the head unit:

```text
Press Home instead of swiping/force-closing the app.
```

Some head units treat swiping/clearing the app as a hard task kill. The app includes restart hooks and widget tap-to-restart, but a hard vendor task kill may still stop background services.

If the widget stops updating, tap the temperature widget once. The tap sends a restart action to `AtotoKeepAliveService`, which re-pokes the scanner without needing ADB.

## ADB commands

These commands are written for Windows PowerShell from the `platform-tools` folder, so every command uses:

```powershell
.\adb.exe
```

### Install or reinstall APK

If you build an APK from Android Studio, install it with:

```powershell
.\adb.exe install -r .\app-debug.apk
```


### Grant location permissions

Android 10 requires location permission for BLE scanning.

```powershell
.\adb.exe shell pm grant com.orbitaldelta.cartempble android.permission.ACCESS_FINE_LOCATION
.\adb.exe shell pm grant com.orbitaldelta.cartempble android.permission.ACCESS_BACKGROUND_LOCATION
```

If the second command fails, open the app settings on the head unit and manually set Location to "Allow all the time" if available.

### Allow background operation

```powershell
.\adb.exe shell cmd appops set com.orbitaldelta.cartempble RUN_IN_BACKGROUND allow
.\adb.exe shell cmd appops set com.orbitaldelta.cartempble RUN_ANY_IN_BACKGROUND allow
.\adb.exe shell cmd appops set com.orbitaldelta.cartempble START_FOREGROUND allow
.\adb.exe shell cmd appops set com.orbitaldelta.cartempble WAKE_LOCK allow
```

Some Android builds do not support every appops string. If one command says `Unknown operation string`, skip that one and continue.

### Add to device idle whitelist

```powershell
.\adb.exe shell dumpsys deviceidle whitelist +com.orbitaldelta.cartempble
```

Check the whitelist:

```powershell
.\adb.exe shell dumpsys deviceidle whitelist | Select-String cartempble
```

### Start the ATOTO keepalive service manually

This is the most important ATOTO-specific manual start command:

```powershell
.\adb.exe shell am startservice -a com.atoto.keepalive -n com.orbitaldelta.cartempble/.AtotoKeepAliveService
```

### Start the BLE scanner directly

```powershell
.\adb.exe shell am startservice -a com.orbitaldelta.cartempble.START_SCAN -n com.orbitaldelta.cartempble/.TempScanService
```

### Restart from widget action manually

```powershell
.\adb.exe shell am startservice -a com.orbitaldelta.cartempble.WIDGET_RESTART -n com.orbitaldelta.cartempble/.AtotoKeepAliveService
```

### Check whether the processes are running

```powershell
.\adb.exe shell ps -A | Select-String cartempble
```

Expected examples:

```text
com.orbitaldelta.cartempble
com.orbitaldelta.cartempble:scanner
```

### Check running services

```powershell
.\adb.exe shell dumpsys activity services com.orbitaldelta.cartempble
```

### Test ATOTO keepalive logs

Clear logs:

```powershell
.\adb.exe logcat -c
```

Start the ATOTO keepalive entry point:

```powershell
.\adb.exe shell am startservice -a com.atoto.keepalive -n com.orbitaldelta.cartempble/.AtotoKeepAliveService
```

Dump relevant logs:

```powershell
.\adb.exe logcat -d | Select-String "AndroidRuntime|FATAL EXCEPTION|com.orbitaldelta.cartempble|KeepAliveService|scanning too frequently"
```

Good signs:

```text
package:com.orbitaldelta.cartempble, action:com.atoto.keepalive is running
```

Bad signs:

```text
FATAL EXCEPTION
Context.startForegroundService() did not then call Service.startForeground()
App 'com.orbitaldelta.cartempble' is scanning too frequently
```

The final project version was adjusted to avoid those known crash/throttle cases.

### Simulate boot/package replacement behavior

Normal Android blocks sending `BOOT_COMPLETED` from the shell on many devices, so this may fail with a permission error:

```powershell
.\adb.exe shell am broadcast -a android.intent.action.BOOT_COMPLETED -p com.orbitaldelta.cartempble
```

A better test is to use the app's explicit service start commands above, then reboot the head unit and confirm the widget populates by itself.

### Useful settings to check on the head unit

Developer Options:

```text
Background process limit: Standard limit
Don't keep activities: Off
Standby apps -> Car Temp BLE: Exempted, if available
```

If the head unit has an ATOTO/SYU settings screen for any of the following, add Car Temp BLE:

```text
Keep Alive
Auto Start
Protected Apps
Background Apps
Sleep whitelist
Startup apps
Battery optimization exemption
```

## Troubleshooting

### App says it found the ESP32 but is waiting on temp payload

The Android app sees the BLE device name, but not the expected manufacturer payload.

Check the ESP32 Serial Monitor. It should print:

```text
Advertised: T:22.4;W:OK
```

or:

```text
Advertised: T:22.4;W:L
```

If it only says `TEMP:22.4C`, the app should still show temperature, but washer status will be offline.

### Temperature works but washer is offline

The ESP32 is not sending the washer part of the payload. Confirm the sketch is sending:

```text
;W:OK
```

or:

```text
;W:L
```

### Washer reads backwards

In the ESP32 sketch, change:

```cpp
const bool WASHER_LOW_IS_HIGH = true;
```

to:

```cpp
const bool WASHER_LOW_IS_HIGH = false;
```

### Washer flickers while driving

Increase this value in the ESP32 sketch:

```cpp
const unsigned long WASHER_CONFIRM_MS = 5000;
```

For example:

```cpp
const unsigned long WASHER_CONFIRM_MS = 10000;
```

Washer fluid does not need instant updates, so a longer confirmation time is usually better.

### Temperature rises while idling

That is heat soak from the grille/bumper/engine bay area. The ESP32 sketch includes a slow-rise filter:

```cpp
const float MAX_RISE_PER_UPDATE_C = 0.04;
const float MAX_DROP_PER_UPDATE_C = 0.6;
```

The best fix is still physical sensor placement: keep the probe away from radiator heat and direct sun-heated metal/plastic.

## Known limitations

- The app relies on BLE advertisements, not a persistent BLE connection.
- Some Android head units aggressively kill user-installed apps.
- ATOTO keepalive behavior may vary by model/firmware.
- Swiping/force-closing the app may still stop the service on some units.
- The washer fluid sensor is a single-level digital warning, not a percentage gauge.

## Project layout

```text
app/                                  Android app source
app/src/main/java/...                 Java source
app/src/main/res/layout/widget_temp.xml
app/src/main/res/layout/widget_washer.xml
app/src/main/res/xml/temp_widget_info.xml
app/src/main/res/xml/washer_widget_info.xml
esp32/CarTempBleSensor/              ESP32 Arduino sketch
README.md                             Project documentation
```
