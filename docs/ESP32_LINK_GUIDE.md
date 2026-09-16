# ESP32 Live Sensor Link — USB & BLE Guide

Grounded in a read of this repo as of commit `a86b95e` ("Phase 4: USB
leftovers + BLE re-wire"). Every claim below cites the file/line it came
from. Nothing here was modified — app-side changes are given as snippets
only, per your instruction.

**Assumptions locked in from your answers**, used throughout this doc:

1. Board: classic ESP32 (WROOM/WROVER) with a **CP2102/CP2104** USB-UART
   bridge.
2. Current firmware on the board is **unknown** — Q1 and Q2 both start
   with "verify what's on it now" before flashing anything.
3. Your phone **confirmed supports USB OTG/host mode** — USB is a live
   option, not blocked on hardware.
4. Sample rate: **keep the app pipeline at 25 Hz**. Your ESP32's sensors
   are natively 50 Hz-capable, but the sketches below deliberately
   transmit at 25 Hz (send every other reading) so `PpgPipeline.FS_HZ`,
   `UsbSerialSensorSource.sampleRateHz`, and `BleSensorSource.sampleRateHz`
   — all currently `25.0` — need **no changes**.

---

## Step 1 — What actually exists in this repo

This is a bigger head start than the task brief assumed: USB and BLE are
**both already structurally wired end-to-end**, including the Android
manifest, the USB permission flow, the BLE GATT client, and the UI radio
buttons. What's missing is real hardware validation, not plumbing.

### 1a. `app/build.gradle.kts` / `gradle/libs.versions.toml`

- USB-serial dependency: **present**.
  `app/build.gradle.kts:37` → `implementation(libs.usbSerial)`, resolved
  in `gradle/libs.versions.toml:10,16` to
  `com.github.mik3y:usb-serial-for-android:3.11.0`.
- BLE dependency: **none, and none needed**. `BleSensorSource.kt` uses
  the platform `android.bluetooth.*` APIs directly (no Nordic/other BLE
  library) — normal for Android BLE central code.
- SDK levels: `app/build.gradle.kts:8-10` `compileSdk = release(37)`;
  line 14 `minSdk = 26`; line 15 `targetSdk = 37`.

### 1b. `AndroidManifest.xml`

All of the following are **already present**:

- `<uses-feature android:name="android.hardware.usb.host" android:required="true" />`
  — line 27.
- `USB_DEVICE_ATTACHED` intent-filter + `device_filter.xml` meta-data on
  `MainActivity` — lines 57-62 (with a comment explaining `singleTop` is
  there specifically so a plug-in event while the app is already open
  doesn't spawn a second activity and drop the session).
- `BLUETOOTH_SCAN` with `android:usesPermissionFlags="neverForLocation"`
  and `BLUETOOTH_CONNECT` (API 31+) — lines 15-18.
- Legacy `BLUETOOTH` / `BLUETOOTH_ADMIN` / `ACCESS_FINE_LOCATION`, all
  `android:maxSdkVersion="30"` — lines 8-10, for pre-Android-12 BLE
  scanning.
- `<uses-feature android:name="android.hardware.bluetooth_le" android:required="true" />`
  — line 20.

Nothing needs adding here for either path.

### 1c. `res/xml/device_filter.xml`

Already lists your exact chip:

```
<usb-device vendor-id="4292" product-id="60000" />   <!-- CP2102/CP2104 -->
```

(`device_filter.xml:12-13`, decimal for hex `0x10C4`/`0xEA60`.) Also
present: CH340 (15-16), CH9102 (18-19), FTDI FT232R/FT232H (21-22,
24-25), and Espressif native USB-CDC matched by vendor only (27-31). No
change needed for your board.

### 1d. Data-source layer

- **Interface**: `sensor/SensorSource.kt:15-38` — `sampleRateHz`,
  `start(onSample, onStatus)`, `stop()`, optional
  `setOnFinishedListener`/`setOnReferenceBpmListener`. Four
  implementations exist behind it: `SimulatedSensorSource`,
  `CsvDatasetSource`, `UsbSerialSensorSource`, `BleSensorSource`.
- **Sample struct**: `model/SensorSample.kt:27-34`:
  ```kotlin
  data class SensorSample(
      val timestampMs: Long,
      val ir: Int,       // <- this is your "ppg" channel
      val red: Int,
      val accX: Float,
      val accY: Float,
      val accZ: Float
  )
  ```
  **Naming note**: the task brief calls it "ppg + red" — in this codebase
  the PPG channel field is literally named `ir` (infrared), not `ppg`.
  `PpgPipeline.kt:76` selects `it.ir.toDouble()` as the HR-path input,
  with a comment explaining IR is used because it penetrates tissue
  better than red on MAX3010x-family sensors, and `red` is carried
  alongside for a future SpO2 feature, not used in v1. Whatever your
  ESP32's raw IR-channel reading is, that's what goes in the `ir` field
  — not a separately-computed "ppg" value.
- **Sample-rate assumption**: `PpgPipeline.FS_HZ = 25.0`
  (`dsp/PpgPipeline.kt:39`), matched by
  `UsbSerialSensorSource.sampleRateHz = 25.0` (`sensor/UsbSerialSensorSource.kt:49`)
  and `BleSensorSource.sampleRateHz = 25.0` (`sensor/BleSensorSource.kt:52`).
  `dsp/FilterCoefficients.kt:29-43` has precomputed Butterworth band-pass
  SOS coefficients for **both** 25 Hz and 50 Hz, auto-selected by
  `bandpassFor()` (lines 48-54) — so if you ever revisit the "50 Hz"
  decision, it's a 3-constant change, not new DSP.
- **Exact hook point into the LMS+HR chain**: `MainActivity.handleSample()`,
  `MainActivity.kt:275-295`. Every `SensorSource` implementation calls
  `onSample: (SensorSample) -> Unit` for each reading; `MainActivity`
  passes `{ sample -> handleSample(sample) }` as that callback
  (`MainActivity.kt:212`); `handleSample()` calls
  `pipeline.process(sample)` (`MainActivity.kt:276`), which runs
  band-pass → normalize → accelerometer-referenced LMS → HR estimation
  (`dsp/PpgPipeline.kt:78-103`) — entirely in Kotlin, no ML/DL, matching
  your project rule. **This is the seam a live source plugs into — it
  requires no changes**, only a `SensorSource` implementation that calls
  `onSample` correctly.

### 1e. Existing serial/BLE code — status, not half-written stubs

Both `UsbSerialSensorSource.kt` and `BleSensorSource.kt` are **complete,
compiling implementations**, not placeholders — their own header
comments say so explicitly:

- `sensor/UsbSerialSensorSource.kt:31-35`: *"STATUS: structurally
  complete (find device → request permission → open → read → parse) but
  UNTESTED against real firmware."*
- `sensor/BleSensorSource.kt:26-33`: *"STATUS: structurally complete
  (scan → connect → discover → enable notifications → parse) but
  UNTESTED against real firmware."*

Both are wired into `MainActivity`:
`MainActivity.onStartClicked()` routes `R.id.rbUsb` to
`UsbSerialSensorSource` (`MainActivity.kt:150`) and `R.id.rbBle` to
`BleSensorSource` via `onBleStartRequested()` (`MainActivity.kt:151,159-167`).
Both radio buttons exist in the layout
(`res/layout/activity_main.xml:69-79`, "ESP32 (USB)" and "ESP32 (BLE)").
BLE runtime-permission handling for API 31+ vs. older is already
implemented in `blePermissionsNeeded()` (`MainActivity.kt:169-178`) and
requested on Start-tap via `requestBlePermissions`
(`MainActivity.kt:88-96`).

**Documentation gap found**: `USB_SERIAL_CONTRACT.md:20-23` and
`README.md:24-26,80-82` both state BLE is *"kept in the repo as unused
reference code... not wired into the app's UI."* **That's stale — the
code above shows it is wired into the UI and start flow.** What's
actually true (and what the header comments in the source say) is
narrower: BLE has never been tested against real firmware. I'd suggest
fixing those two docs separately; I haven't touched them here since you
asked me not to modify app-tracked files yet.

**Also missing**: `BLE_CONTRACT.md` is referenced three times
(`sensor/BleSensorSource.kt:21`, `README.md:81`,
`USB_SERIAL_CONTRACT.md:20`) but **does not exist anywhere in the repo**
— either deleted or never committed. Q3 below effectively reconstructs
what it should contain, since `BleSensorSource.kt`'s UUIDs and packet
format (`sensor/BleSensorSource.kt:55-60,142-161`) are the only surviving
source of truth.

### 1f. `USB_SERIAL_CONTRACT.md` (project root)

Already documents the exact line format
`UsbSerialSensorSource.parseLine()` expects: `ir,red,accX,accY,accZ`, 5
comma-separated ASCII fields, newline-terminated, no header, 115200 baud
8N1 (`USB_SERIAL_CONTRACT.md:40-57`), plus an example sketch skeleton at
25 Hz (`USB_SERIAL_CONTRACT.md:81-118`) that Q2 below builds on directly.

---

## Q1 — Do I have to flash the ESP32 first, or can the phone read it as-is?

**You must flash it. The firmware side is the blocker, not the app.**

Here's precisely why, given your CP2102 board:

- The **CP2102 is a separate physical chip** on the board from the ESP32
  itself. It enumerates as a USB-to-UART bridge over USB the instant the
  board is powered, **regardless of what's flashed onto the ESP32** — even
  a factory-blank or bricked ESP32 will still make the CP2102 show up as
  a serial device to Android, because the bridge chip has no idea what
  firmware the ESP32 is running; it just shuttles bytes on the UART pins.
- That means: `device_filter.xml`'s `vendor-id="4292" product-id="60000"`
  match (§1c) will fire, Android will offer the USB permission dialog,
  and `usb-serial-for-android`'s `UsbSerialProber` (`UsbSerialSensorSource.kt:99`)
  will find and open a CP210x port — **no matter what's running on the
  ESP32**, including nothing useful.
- What determines whether **meaningful bytes** come out the other end is
  entirely the ESP32's firmware. Concretely, if you plug in today without
  flashing anything new:
  - If the board has never been flashed / still has factory test
    firmware: you'll typically see either silence, or the factory demo's
    own unrelated output (often garbled at 115200 if the factory
    firmware used a different baud rate — the ESP32 bootloader itself
    prints its boot log at 74880 baud by default, which will look like
    noise at 115200).
  - If it has some previous sketch from earlier project work: you'll see
    whatever that sketch prints, which the app's `parseLine()`
    (`UsbSerialSensorSource.kt:196-215`) will just silently drop as
    malformed (wrong field count / non-numeric) — the app won't crash,
    it'll just show no samples and no HR, with nothing in the on-screen
    status beyond "Connected, reading over USB serial at 115200 baud...".
- Since you told me you're **not sure what's on it now**, step 1 of Q2
  below is: connect a serial monitor and look, *then* flash — flashing is
  a normal, fully reversible operation (it overwrites whatever's there;
  there's nothing to lose by doing it).

**App side**: nothing to build. `UsbSerialSensorSource.kt` is generic —
it doesn't hardcode a chip driver, doesn't care what firmware version is
on the board, and needs zero changes to work with any sketch that emits
the line format in Q2. The only thing that determines success is getting
the ESP32 to emit that exact format.

---

## Q2 — USB: step-by-step

### ESP32 side

**Step 1 — check what's currently on the board before touching anything.**

1. Plug the ESP32 into your computer (not the phone) via USB.
2. Open Arduino IDE → Tools → Port, select the CP2102's port.
3. Open Serial Monitor at **115200 baud**. Press the ESP32's reset
   button. Note what you see for ~5 seconds:
   - Nothing / garbage → board is effectively blank for our purposes,
     proceed to flash.
   - Readable text that isn't `number,number,number,number,number` →
     some other sketch is running, proceed to flash (it'll be
     overwritten).
   - Already `ir,red,accX,accY,accZ`-shaped lines → you may already have
     a working sketch; compare it against Step 2's format before
     deciding whether to reflash.

**Step 2 — flash this sketch.** It matches
`USB_SERIAL_CONTRACT.md`'s line format exactly (`ir,red,accX,accY,accZ`,
5 comma-separated fields, newline-terminated, no header) so
`UsbSerialSensorSource.parseLine()` needs no changes. It reads your
sensors and transmits at a fixed 25 Hz (40 ms period) — per your answer
in Step 3, this deliberately sends every other reading rather than
pushing the app to 50 Hz.

Replace `readIr()`, `readRed()`, and `readAccel()` with your actual
MAX30102/accelerometer driver calls — the frame format and timing below
don't depend on which sensor library you use.

```cpp
// ESP32 (CP2102 board) -> phone, USB-serial sensor stream.
// Frame: "ir,red,accX,accY,accZ\n", 5 comma-separated ASCII fields,
// no header row, no leading timestamp. Matches USB_SERIAL_CONTRACT.md
// and UsbSerialSensorSource.parseLine() exactly - do not add fields
// without updating parseLine() to match (see the OPTIONAL checksum
// variant below if you want to do that later).

const unsigned long SAMPLE_PERIOD_MS = 40; // 25 Hz - matches
                                            // PpgPipeline.FS_HZ; the
                                            // sensors are 50 Hz-capable,
                                            // this deliberately samples
                                            // at half that rate.

void setup() {
  Serial.begin(115200);
  // Give the CP2102 a moment to enumerate before the first line - avoids
  // the phone side seeing a truncated/garbled first read right after
  // the USB permission dialog is accepted.
  delay(200);

  initIrRedSensor();  // e.g. MAX30102 init over I2C
  initAccelerometer(); // e.g. onboard/attached IMU init
}

void loop() {
  static unsigned long lastSampleMs = 0;
  unsigned long now = millis();
  if (now - lastSampleMs < SAMPLE_PERIOD_MS) return;
  lastSampleMs = now;

  // Read everything before printing anything - printing field-by-field
  // with reads interleaved risks a partial line if a read stalls.
  int32_t ir = readIr();
  int32_t red = readRed();
  float accX, accY, accZ;
  readAccel(&accX, &accY, &accZ);

  Serial.print(ir);
  Serial.print(',');
  Serial.print(red);
  Serial.print(',');
  Serial.print(accX, 3);
  Serial.print(',');
  Serial.print(accY, 3);
  Serial.print(',');
  Serial.println(accZ, 3); // println = trailing \n = the frame sync
}
```

**Frame sync / checksum recommendation.** The `\n` terminator *is* the
frame sync — `UsbSerialSensorSource.handleBytes()` already buffers bytes
until a newline and resyncs on the next one if a line is malformed
(`UsbSerialSensorSource.kt:172-189`), and `parseLine()` already drops
anything that isn't exactly 5 numeric fields
(`UsbSerialSensorSource.kt:196-215`). For a short, direct USB-UART cable
run, bit errors are rare and the existing malformed-line handling already
provides silent, resync-safe corruption tolerance — **I'd recommend
shipping the sketch above as-is first** rather than adding a checksum
up front, since it needs zero `parseLine()` changes and gets you moving
fastest.

If you later see corrupted-but-still-5-numeric-fields lines in practice
(rare, but possible — e.g. a dropped digit turning `90312` into `9312`
without breaking the field count), here's the hardening path, kept
**optional and not applied**:

```cpp
// OPTIONAL hardened frame - add only if you observe corrupted-but-
// well-formed lines in practice. Requires updating parseLine() to expect
// 6 fields and verify the checksum (snippet further down).
uint8_t checksum(int32_t ir, int32_t red, float ax, float ay, float az) {
  // Sum of the bytes of the formatted fields before the checksum field
  // itself, mod 256 - cheap, and enough to catch the "one digit flipped"
  // class of corruption that a bare field-count check would miss.
  char buf[64];
  int n = snprintf(buf, sizeof(buf), "%ld,%ld,%.3f,%.3f,%.3f", (long)ir, (long)red, ax, ay, az);
  uint8_t sum = 0;
  for (int i = 0; i < n; i++) sum += (uint8_t)buf[i];
  return sum;
}

// In loop(), after computing ir/red/accX/accY/accZ:
uint8_t cs = checksum(ir, red, accX, accY, accZ);
Serial.print(ir); Serial.print(',');
Serial.print(red); Serial.print(',');
Serial.print(accX, 3); Serial.print(',');
Serial.print(accY, 3); Serial.print(',');
Serial.print(accZ, 3); Serial.print(',');
Serial.println(cs);
```

Matching **app-side snippet** (not applied — for `UsbSerialSensorSource.kt`,
only if you add the checksum above):

```kotlin
// UsbSerialSensorSource.kt - parseLine(), if the 6-field checksummed
// frame above is adopted instead of the 5-field default.
private fun parseLine(line: String): SensorSample? {
    val fields = line.split(',')
    if (fields.size != 6) {
        Log.w(TAG, "Skipping malformed line (expected 6 fields, got ${fields.size}): $line")
        return null
    }
    return try {
        val ir = fields[0].trim().toInt()
        val red = fields[1].trim().toInt()
        val accX = fields[2].trim().toFloat()
        val accY = fields[3].trim().toFloat()
        val accZ = fields[4].trim().toFloat()
        val receivedChecksum = fields[5].trim().toInt()
        val payload = "$ir,$red,${"%.3f".format(accX)},${"%.3f".format(accY)},${"%.3f".format(accZ)}"
        val expected = payload.sumOf { it.code } and 0xFF
        if (receivedChecksum != expected) {
            Log.w(TAG, "Checksum mismatch, dropping line: $line")
            return null
        }
        SensorSample(SystemClock.elapsedRealtime(), ir, red, accX, accY, accZ)
    } catch (e: NumberFormatException) {
        Log.w(TAG, "Skipping malformed line (parse error): $line", e)
        null
    }
}
```

### Phone side

**Everything below already exists in the repo (§1a-1d) — this section is
a checklist to confirm it, not a build list.**

- **OTG cable/adapter**: you confirmed your phone supports USB host mode
  (Step 3, answer 3). You need a USB-C-to-USB-A OTG adapter (or a
  USB-C-to-USB-C cable if the ESP32 board's port is USB-C) between the
  phone and the ESP32's USB port. No special "OTG-certified" labeling is
  required beyond the phone itself supporting host mode — confirm with a
  cheap USB flash-drive/keyboard test on the same port first if you
  haven't already used OTG on this exact phone.
- **Manifest**: `android.hardware.usb.host` feature and the
  `USB_DEVICE_ATTACHED` intent-filter + `device_filter.xml` meta-data are
  already in `AndroidManifest.xml:27,57-62` — no edit needed.
- **Gradle dependency**: `usb-serial-for-android:3.11.0` already in
  `app/build.gradle.kts:37` — no edit needed.
- **`device_filter.xml` VID/PID**: your CP2102/CP2104 chip is already
  listed (`device_filter.xml:12-13`, decimal `4292`/`60000` for hex
  `0x10C4`/`0xEA60`) — no edit needed.
- **USB permission dialog flow** (already implemented in
  `UsbSerialSensorSource.kt:94-129`): two paths depending on how you
  connect:
  1. **App already running, then you plug in the ESP32**: tapping
     "Start" with "ESP32 (USB)" selected calls `start()`
     (`UsbSerialSensorSource.kt:94-112`), which finds the CP210x driver
     via `UsbSerialProber`, and since the app hasn't been granted
     permission for this specific device yet, calls
     `requestPermission()` (`UsbSerialSensorSource.kt:114-129`) — you'll
     see Android's standard "Allow app to access USB device?" dialog.
     Tap Allow.
  2. **ESP32 already plugged in, then you open the app** (or the app is
     closed and you plug in): Android matches the device against
     `device_filter.xml` via the `USB_DEVICE_ATTACHED` intent-filter and
     can auto-launch/foreground `MainActivity` with permission
     **implicitly already granted** for that specific device — no dialog
     in this case. Either way, once permission is granted,
     `openDevice()` runs (`UsbSerialSensorSource.kt:131-170`) and you'll
     see the on-screen status change to *"Connected, reading over USB
     serial at 115200 baud..."*.
- **Reader/parser class**: `UsbSerialSensorSource.kt` already implements
  this exactly against the frame format above — no new class needed, no
  edits needed (unless you adopt the optional checksum variant).
- **Hookup into the DSP chain and UI**: already wired — selecting "ESP32
  (USB)" and tapping Start routes through
  `MainActivity.onStartClicked() → startWithSource(UsbSerialSensorSource(...), ...)`
  (`MainActivity.kt:150`), which is the exact same `startWithSource()`
  path the Simulated/Dataset sources use, so `handleSample()` → `pipeline.process()` →
  waveform/HR/SQI UI updates (`MainActivity.kt:275-295`) require **zero
  changes**.

### Verification, stage by stage

| Stage | What you should see | What failure looks like |
|---|---|---|
| Board plugged into **PC**, Serial Monitor @ 115200 | `ir,red,accX,accY,accZ` lines, ~25/sec | Garbage/boot-log noise at power-up (normal, settles after `setup()`'s 200ms delay); if it never settles, wrong baud or old firmware still running |
| Board plugged into **phone** via OTG, app closed | Phone may auto-offer to open the app (device-attached match) | Nothing happens at all → OTG adapter/cable issue, or phone's host mode isn't actually working despite spec — test with a USB drive |
| App open, "ESP32 (USB)" selected, tap Start | "Allow app to access USB device?" dialog (if not auto-granted) → tap Allow → status becomes "Connected, reading over USB serial at 115200 baud..." | "No USB serial device found - plug in the ESP32 over USB-C" → `UsbSerialProber` found no driver → check cable/adapter, check board is powered (LED on) |
| A few seconds after Connected | Waveform starts scrolling, "Signal quality" updates, BPM appears after ~2s warm-up (`HeartRateEstimator` needs a window of samples first) | Status stuck on "Connected..." with no waveform movement → firmware is running but not emitting the expected 5-field format; re-check with Serial Monitor on the PC side while phone is disconnected |
| Unplug the ESP32 mid-session | Status changes to "ESP32 (USB) disconnected" (`UsbSerialSensorSource.kt:163`) | App hangs or crashes → would indicate a real bug worth reporting, not expected given the current `onRunError` handling |

---

## Q3 — BLE: step-by-step

Reconstructed from `BleSensorSource.kt`'s existing UUIDs and packet
parser, since `BLE_CONTRACT.md` (referenced but missing — §1e) never
made it into the repo. This is the contract the app-side code already
assumes; the sketch below matches it exactly.

### ESP32 side — GATT server sketch

- **Service/characteristic UUIDs**: Nordic UART Service (NUS), matching
  `BleSensorSource.kt:55-58` exactly:
  - Service: `6e400001-b5a3-f393-e0a9-e50e24dcca9e`
  - TX characteristic (ESP32 → phone, **must be Notify**):
    `6e400003-b5a3-f393-e0a9-e50e24dcca9e`
  - RX characteristic (phone → ESP32, unused by this app today, but part
    of the NUS spec — include it for compatibility):
    `6e400002-b5a3-f393-e0a9-e50e24dcca9e`
- **Advertised name**: must contain `"PPG"` — `BleSensorSource`'s scan
  callback matches `result.device.name?.contains("PPG", ignoreCase = true)`
  (`BleSensorSource.kt:49,69-70`). `"PPG-ESP32"` or similar works.
- **Packet layout**: exactly 18 bytes, little-endian, matching
  `BleSensorSource.parsePacket()` (`BleSensorSource.kt:142-161`):

  | Bytes | Field | Type | Notes |
  |---|---|---|---|
  | 0-3 | device timestamp | int32 (`millis()`) | **read but intentionally discarded** by the app (`BleSensorSource.kt:146`) — include it anyway, don't shrink the packet, or every other offset shifts |
  | 4-7 | ir | int32 | raw ADC counts |
  | 8-11 | red | int32 | raw ADC counts |
  | 12-13 | accX | int16 | **value × 1000** — app divides by 1000.0 (`BleSensorSource.kt:149`) |
  | 14-15 | accY | int16 | × 1000, same as above |
  | 16-17 | accZ | int16 | × 1000, same as above |

- **Notify, not indicate**: the app calls
  `gatt.setCharacteristicNotification(txChar, true)` and writes
  `ENABLE_NOTIFICATION_VALUE` to the CCCD (`BleSensorSource.kt:109-116`)
  — Notify (unacknowledged, lower latency) not Indicate (acknowledged,
  higher latency, unnecessary here since occasional dropped samples are
  tolerable for HR estimation).
- **Samples per packet: one.** At 25 Hz, 18 bytes fits comfortably inside
  the **default** BLE ATT MTU (23 bytes total → 20 bytes usable payload
  per notification) with zero MTU negotiation required. Batching 2+
  samples per notification would need a larger MTU (the app doesn't
  currently request one — see the phone-side note below) purely to cut
  radio wake-up overhead; not necessary at this rate, so the sketch below
  sends one 18-byte notification every 40 ms.

```cpp
// ESP32 (Arduino BLE library, part of the standard ESP32 Arduino core)
// GATT server matching BleSensorSource.kt's NUS UUIDs and 18-byte
// packet layout. One notification per sample, 25 Hz (40ms period) -
// same downsample-from-50Hz decision as the USB sketch.

#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

#define SERVICE_UUID        "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
#define CHARACTERISTIC_UUID_TX "6e400003-b5a3-f393-e0a9-e50e24dcca9e"
#define CHARACTERISTIC_UUID_RX "6e400002-b5a3-f393-e0a9-e50e24dcca9e"

BLECharacteristic *txCharacteristic;
bool deviceConnected = false;

class ServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer* server) override { deviceConnected = true; }
  void onDisconnect(BLEServer* server) override {
    deviceConnected = false;
    server->getAdvertising()->start(); // resume advertising for reconnect
  }
};

void setup() {
  Serial.begin(115200);
  initIrRedSensor();
  initAccelerometer();

  BLEDevice::init("PPG-ESP32"); // must contain "PPG" - BleSensorSource scan filter

  BLEServer *server = BLEDevice::createServer();
  server->setCallbacks(new ServerCallbacks());

  BLEService *service = server->createService(SERVICE_UUID);

  txCharacteristic = service->createCharacteristic(
      CHARACTERISTIC_UUID_TX,
      BLECharacteristic::PROPERTY_NOTIFY
  );
  txCharacteristic->addDescriptor(new BLE2902()); // CCCD, enables notify

  BLECharacteristic *rxCharacteristic = service->createCharacteristic(
      CHARACTERISTIC_UUID_RX,
      BLECharacteristic::PROPERTY_WRITE
  ); // present for NUS compatibility; app doesn't write to it today

  service->start();
  server->getAdvertising()->start();
}

void loop() {
  static unsigned long lastSampleMs = 0;
  const unsigned long SAMPLE_PERIOD_MS = 40; // 25 Hz

  unsigned long now = millis();
  if (now - lastSampleMs < SAMPLE_PERIOD_MS) return;
  lastSampleMs = now;

  if (!deviceConnected) return; // no subscriber - skip sensor read/pack work

  int32_t ts = (int32_t)now;
  int32_t ir = readIr();
  int32_t red = readRed();
  float accXf, accYf, accZf;
  readAccel(&accXf, &accYf, &accZf);
  int16_t accX = (int16_t)(accXf * 1000.0f);
  int16_t accY = (int16_t)(accYf * 1000.0f);
  int16_t accZ = (int16_t)(accZf * 1000.0f);

  uint8_t packet[18];
  memcpy(packet + 0, &ts, 4);
  memcpy(packet + 4, &ir, 4);
  memcpy(packet + 8, &red, 4);
  memcpy(packet + 12, &accX, 2);
  memcpy(packet + 14, &accY, 2);
  memcpy(packet + 16, &accZ, 2);

  txCharacteristic->setValue(packet, sizeof(packet));
  txCharacteristic->notify();
}
```

(ESP32 is little-endian, matching `ByteOrder.LITTLE_ENDIAN` in
`BleSensorSource.kt:145` — no byte-swapping needed on either side.)

### Phone side

- **Permissions**: already fully declared
  (`AndroidManifest.xml:8-10,15-18,20`, §1b) — no manifest edit needed.
- **Runtime-permission flow for Android 12+**: already implemented.
  `MainActivity.blePermissionsNeeded()` (`MainActivity.kt:169-178`)
  branches on `Build.VERSION.SDK_INT >= Build.VERSION_CODES.S` (API 31):
  requests `BLUETOOTH_SCAN` + `BLUETOOTH_CONNECT` on 31+, or
  `ACCESS_FINE_LOCATION` below that. `onBleStartRequested()`
  (`MainActivity.kt:159-167`) checks what's missing and launches
  `requestBlePermissions` (`MainActivity.kt:88-96`) only if needed —
  triggered automatically when you tap Start with "ESP32 (BLE)"
  selected. **Verified against the current API**: `BLUETOOTH_SCAN`'s
  `neverForLocation` flag requires `targetSdk` 31+ to take effect (this
  app's `targetSdk = 37`, so it applies) — without it, Android would also
  silently require location permission/services-on for scanning even on
  31+; with it, this app correctly avoids that.
- **Scanning**: `BleSensorSource.start()` grabs
  `BluetoothAdapter.bluetoothLeScanner` and starts a scan, matching any
  device whose advertised name contains `"PPG"`
  (`BleSensorSource.kt:163-177,67-80`) — matches the sketch's
  `BLEDevice::init("PPG-ESP32")` above.
- **Connecting**: on a scan match, `result.device.connectGatt(context, false, gattCallback)`
  (`BleSensorSource.kt:74`) — `autoConnect=false` for a fast initial
  connect (appropriate here since you're connecting to a device you just
  found via active scan, not waiting for one to come into range later).
- **MTU negotiation**: **not currently requested, and not required** — at
  18 bytes/notification you fit inside the default 20-byte payload (see
  ESP32-side note above). If you later batch multiple samples per
  notification, you'd add an explicit request; not applied here since
  it's unnecessary at the current packet size:
  ```kotlin
  // BleSensorSource.kt - gattCallback.onConnectionStateChange(), only
  // needed if you later batch >1 sample per BLE notification.
  BluetoothProfile.STATE_CONNECTED -> {
      onStatusCb?.invoke("Connected, requesting MTU...")
      gatt.requestMtu(247) // then wait for onMtuChanged() before discoverServices()
  }
  ```
- **Enabling notifications**: already implemented —
  `setCharacteristicNotification(txChar, true)` + writing
  `ENABLE_NOTIFICATION_VALUE` to the CCCD descriptor
  (`BleSensorSource.kt:109-116`).
- **Parsing**: already implemented, both the deprecated (API <33) and
  current (API 33+) `onCharacteristicChanged` overloads are handled
  (`BleSensorSource.kt:120-133`) — **verified this dual-path handling is
  correct**: the non-`value`-parameter overload was deprecated in API 33
  in favor of the one that passes `value` directly (avoiding a
  since-fixed race where `characteristic.value` could change between the
  callback firing and you reading it); keeping both, as this code does,
  is the currently-recommended way to support the full `minSdk 26`
  range.
- **Reconnect handling**: **not currently implemented** —
  `onConnectionStateChange`'s `STATE_DISCONNECTED` branch only posts a
  status message (`BleSensorSource.kt:89`); it doesn't retry. For a
  motion-artifact test where the phone might momentarily go out of range
  or the ESP32 brown out, you may want auto-reconnect. Snippet (not
  applied):
  ```kotlin
  // BleSensorSource.kt - gattCallback, add simple reconnect-on-drop.
  BluetoothProfile.STATE_DISCONNECTED -> {
      onStatusCb?.invoke("Disconnected - reconnecting...")
      gatt.close()
      bluetoothGatt = null
      // Re-scan rather than gatt.connect() again - the ESP32's
      // advertising address can rotate, and re-scanning is what the
      // sketch above expects (it resumes advertising in onDisconnect()).
      scanner?.startScan(scanCallback)
  }
  ```
- **Hookup into the DSP chain and UI**: identical to USB — same
  `startWithSource()` path (`MainActivity.kt:151,162` → `startWithSource(BleSensorSource(...), ...)`),
  same `handleSample()` → `pipeline.process()` → UI chain
  (`MainActivity.kt:275-295`). Zero changes needed.

### Verification, stage by stage

| Stage | What you should see | What failure looks like |
|---|---|---|
| ESP32 powered, no phone | Discoverable via a generic BLE scanner app (nRF Connect) as "PPG-ESP32" advertising the NUS service UUID | Not visible → check `BLEDevice::init()` name, check `server->getAdvertising()->start()` was called |
| App open, "ESP32 (BLE)" selected, tap Start | Permission dialog (first run only) → status "Scanning for a device advertising as "PPG*"..." → "Found PPG-ESP32, connecting..." → "Connected, discovering services..." → "Subscribed - waiting for samples" | Stuck on "Scanning..." → check Bluetooth is on, check advertised name; "Service ... not found" → UUID mismatch between sketch and `BleSensorSource.kt:55-58` |
| A few seconds after "Subscribed" | Waveform scrolling, BPM after ~2s warm-up, same as USB | No movement → packet layout mismatch; verify byte layout with nRF Connect's raw hex view against the 18-byte table above |
| Walk out of range / power off ESP32 | Status "Disconnected"; without the reconnect snippet, stays disconnected until you tap Stop/Start again | — |

### Throughput/latency vs. USB

- **Throughput**: trivial on both. 25 samples/sec × 18 bytes = 450 B/s
  for BLE; USB-serial at 115200 baud ≈ 11.5 KB/s raw capacity for a
  ~20-30 byte CSV line at 25 Hz (≈ 500-750 B/s) — neither is anywhere
  near its ceiling; this is not a bottleneck for either transport at this
  sample rate.
- **Latency**: USB-serial is near-immediate (sub-millisecond framing,
  limited mainly by the 40 ms sample period itself). BLE adds the
  connection-interval scheduling on top — Android's default (balanced)
  interval is roughly 30-50 ms, so individual samples can arrive with
  extra jitter on that order; if you need tighter timing you'd add
  `gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)`
  after connecting (not currently in `BleSensorSource.kt`) to request a
  shorter interval, at the cost of higher power draw on the ESP32.
- **Practical trade-off for this project**: USB is tethered — awkward
  for the walk/run motion-artifact scenarios your accelerometer-LMS
  filter is specifically meant to handle (README.md's "motion burst"
  description in `SimulatedSensorSource.kt:20-29` is exactly this case).
  BLE is untethered but adds latency jitter and (until the reconnect
  snippet above is applied) doesn't recover automatically from a dropped
  connection. A reasonable path: **bring up and validate the DSP chain
  over USB first** (more reliable for bench debugging, rules out
  transport issues while you're still verifying the sensor readings
  themselves), **then move to BLE** for actual walking/running trials
  once the pipeline is confirmed working.

---

## Items verified rather than recalled (flagged per your instruction)

- `neverForLocation` on `BLUETOOTH_SCAN` requires `targetSdk` 31+ to take
  effect — checked against this project's `targetSdk = 37`
  (`app/build.gradle.kts:15`); applies correctly.
- The dual `onCharacteristicChanged` overload (deprecated pre-value-param
  version + API 33+ value-param version) is the current recommended
  pattern for supporting `minSdk 26` through the latest API without
  triggering deprecation-related bugs — both are present in
  `BleSensorSource.kt:120-133`, confirmed correct as written.
- `PendingIntent.FLAG_MUTABLE` is required (Android 12+ /
  API 31) when the PendingIntent is used with `UsbManager.requestPermission()`,
  since the OS fills in extras on it — present in
  `UsbSerialSensorSource.kt:124`, confirmed correct.
- `Context.RECEIVER_NOT_EXPORTED` is required starting API 33
  (Tiramisu) for a `registerReceiver()` call that doesn't need to
  receive system broadcasts — `UsbSerialSensorSource.kt:116-121`
  branches correctly on `Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU`.
- Default BLE ATT MTU (23 bytes total / 20 bytes usable payload) is a
  platform/spec constant, not Android-version-dependent — confirmed this
  is why the 18-byte packet needs no MTU negotiation.
