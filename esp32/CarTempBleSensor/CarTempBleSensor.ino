#include <OneWire.h>
#include <DallasTemperature.h>
#include <NimBLEDevice.h>
#include <math.h>

// ------------------------------------------------------------
// Car Temp BLE + Washer Fluid Sensor
// ------------------------------------------------------------
// Hardware used in this project:
// - ESP32 Dev Module
// - Waterproof DS18B20 temperature probe on GPIO 4
// - Capacitive liquid level sensor on GPIO 27
//
// BLE device name:
//   CAR_TEMP_ESP32
//
// BLE manufacturer payload examples:
//   T:22.4;W:OK
//   T:22.4;W:L
//
// Android app expects Celsius in the payload. The app/widget handles
// Fahrenheit display itself.
// ------------------------------------------------------------

// ---------- Pins ----------
#define ONE_WIRE_BUS 4      // DS18B20 data pin
#define WASHER_PIN 27       // Washer fluid sensor signal pin

// ---------- BLE ----------
const char* DEVICE_NAME = "CAR_TEMP_ESP32";
const unsigned long UPDATE_INTERVAL_MS = 5000;

// ---------- Washer sensor ----------
const unsigned long WASHER_CONFIRM_MS = 5000;

// This project sensor wiring/behavior:
// HIGH = washer fluid low
// LOW  = washer fluid level OK
const bool WASHER_LOW_IS_HIGH = true;

bool washerLowStable = false;
bool washerLowLastRaw = false;
unsigned long washerLowChangedAt = 0;

// ---------- Temperature ----------
OneWire oneWire(ONE_WIRE_BUS);
DallasTemperature sensors(&oneWire);

NimBLEAdvertising* pAdvertising = nullptr;

unsigned long lastUpdate = 0;
bool haveLastTemp = false;
float lastGoodTempC = NAN;

// Optional heat-soak smoothing.
// These values slow upward movement, which helps reduce false high readings
// when the car is idling and the grille/bumper area gets warm.
const float MAX_RISE_PER_UPDATE_C = 0.04;   // about 0.07°F per 5 sec
const float MAX_DROP_PER_UPDATE_C = 0.6;    // about 1.1°F per 5 sec

bool readWasherLowRaw() {
  int raw = digitalRead(WASHER_PIN);

  if (WASHER_LOW_IS_HIGH) {
    return raw == HIGH;
  } else {
    return raw == LOW;
  }
}

void updateWasherStable() {
  bool washerLowRaw = readWasherLowRaw();

  if (washerLowRaw != washerLowLastRaw) {
    washerLowLastRaw = washerLowRaw;
    washerLowChangedAt = millis();
  }

  if (millis() - washerLowChangedAt >= WASHER_CONFIRM_MS) {
    washerLowStable = washerLowRaw;
  }
}

float readFilteredTempC() {
  sensors.requestTemperatures();
  float tempC = sensors.getTempCByIndex(0);

  // DS18B20 error values / sanity checks
  if (tempC == DEVICE_DISCONNECTED_C || tempC < -55 || tempC > 125 || isnan(tempC)) {
    return lastGoodTempC;
  }

  if (!haveLastTemp || isnan(lastGoodTempC)) {
    lastGoodTempC = tempC;
    haveLastTemp = true;
    return lastGoodTempC;
  }

  float diff = tempC - lastGoodTempC;

  if (diff > MAX_RISE_PER_UPDATE_C) {
    lastGoodTempC += MAX_RISE_PER_UPDATE_C;
  } else if (diff < -MAX_DROP_PER_UPDATE_C) {
    lastGoodTempC -= MAX_DROP_PER_UPDATE_C;
  } else {
    lastGoodTempC = tempC;
  }

  return lastGoodTempC;
}

void advertiseReading(float tempC, bool washerLow) {
  if (isnan(tempC)) {
    Serial.println("Temperature invalid, not advertising update.");
    return;
  }

  // Compact payload for the Android parser:
  //   T:22.4;W:OK
  //   T:22.4;W:L
  char payloadText[24];
  snprintf(
    payloadText,
    sizeof(payloadText),
    "T:%.1f;W:%s",
    tempC,
    washerLow ? "L" : "OK"
  );

  std::string manufacturerData;

  // Manufacturer ID 0xFFFF. The Android app also scans raw advertisement
  // bytes for the ASCII payload as a fallback.
  manufacturerData += (char)0xFF;
  manufacturerData += (char)0xFF;
  manufacturerData += payloadText;

  NimBLEAdvertisementData advData;
  advData.setName(DEVICE_NAME);
  advData.setManufacturerData(manufacturerData);

  if (pAdvertising == nullptr) {
    pAdvertising = NimBLEDevice::getAdvertising();
  }

  pAdvertising->stop();
  delay(50);
  pAdvertising->setAdvertisementData(advData);
  pAdvertising->setMinInterval(800);   // 500 ms
  pAdvertising->setMaxInterval(1600);  // 1000 ms
  pAdvertising->start();

  Serial.print("Advertised: ");
  Serial.println(payloadText);
}

void setup() {
  Serial.begin(115200);
  delay(500);

  Serial.println();
  Serial.println("Starting car temp + washer fluid BLE sensor...");

  pinMode(WASHER_PIN, INPUT);

  washerLowLastRaw = readWasherLowRaw();
  washerLowStable = washerLowLastRaw;
  washerLowChangedAt = millis();

  sensors.begin();
  sensors.setResolution(12);

  NimBLEDevice::init(DEVICE_NAME);
  NimBLEDevice::setPower(ESP_PWR_LVL_P9);
  pAdvertising = NimBLEDevice::getAdvertising();

  float tempC = readFilteredTempC();
  advertiseReading(tempC, washerLowStable);

  Serial.println("Setup complete.");
}

void loop() {
  updateWasherStable();

  unsigned long now = millis();

  if (now - lastUpdate >= UPDATE_INTERVAL_MS) {
    lastUpdate = now;

    float tempC = readFilteredTempC();

    Serial.print("Temp C: ");
    if (isnan(tempC)) {
      Serial.print("INVALID");
    } else {
      Serial.print(tempC, 1);
    }

    Serial.print(" | Washer raw: ");
    Serial.print(readWasherLowRaw() ? "LOW" : "OK");

    Serial.print(" | Washer stable: ");
    Serial.println(washerLowStable ? "FLUID LOW" : "FLUID OK");

    advertiseReading(tempC, washerLowStable);
  }

  delay(100);
}
