# USB serial contract — phone ⇄ ESP32

Share this file with whoever owns the ESP32 firmware. **Nothing here is
confirmed** — it's a best guess based on the format of the existing
hardware-captured CSVs (`walk_clean.csv`, `class_1_clean.csv`: plain-text
columns `time, ir, red, accX, accY, accZ`), made in the absence of an
agreed contract, pending real testing against actual firmware. If the
firmware needs a different layout, change this file and
`UsbSerialSensorSource.kt`'s `parseLine()` together — nothing else in the
app depends on the exact line format.

## Why wired USB instead of BLE

The hardware setup connects the ESP32 to the phone over a USB-C cable —
the phone is the USB host, the ESP32 is a USB-serial device (either
through a bridge chip such as the CP2102/CH340, or native USB-CDC on
ESP32-S2/S3/C3 boards that expose serial directly without a bridge chip).
There is no Bluetooth involved in the sensor link.

`BleSensorSource.kt` and `BLE_CONTRACT.md` are kept in the repo as unused
reference code in case BLE is revisited later, but they are **not** wired
into the app's UI — "ESP32 (USB)" in the source picker uses
`UsbSerialSensorSource.kt` instead.

## Transport

- **Library**: [usb-serial-for-android](https://github.com/mik3y/usb-serial-for-android),
  via its generic `UsbSerialProber` — this auto-detects CP210x, CH34x,
  FTDI, and PL2303 bridge chips, plus native USB-CDC, so the app doesn't
  need to know in advance which ESP32 board/chip is actually in use.
- **Baud rate**: **115200**, 8 data bits, 1 stop bit, no parity (8N1).
  This is the ESP32 Arduino core's default `Serial.begin()` rate and what
  most example sketches use. If the firmware uses a different rate,
  update `UsbSerialSensorSource.BAUD_RATE`.
- **Permission model**: USB device access uses Android's own
  `UsbManager`-mediated permission dialog (shown the first time the app
  tries to open the device) — this is separate from, and does not need,
  any of the Bluetooth runtime permissions BLE would have required.

## Line format

One sample per line, newline-terminated (`\n`), plain ASCII text,
**no header row**, either:

- **5 comma-separated fields** (unchanged, still the default):
  ```
  ir,red,accX,accY,accZ
  ```
- **6 comma-separated fields**, for SpO2-era hardware that also exposes a
  green LED (green appended LAST, same convention `CsvDatasetSource` uses
  for the bundled sep16 dataset):
  ```
  ir,red,accX,accY,accZ,green
  ```

`UsbSerialSensorSource.parseLine()` accepts either length; a line that is
neither 5 nor 6 fields is dropped as malformed.

| Field | Type | Notes |
|-------|------|-------|
| ir    | integer | raw ADC counts, unscaled |
| red   | integer | raw ADC counts, unscaled |
| accX  | float | same unit convention as the simulator/BLE path (g or m/s² — the DSP pipeline only cares about relative motion) |
| accY  | float | same |
| accZ  | float | same |
| green | integer | optional, 6th field only - raw ADC counts, unscaled |

Example lines: `90312,55480,0.02,-0.01,9.81` (5-field) or
`90312,55480,0.02,-0.01,9.81,120` (6-field, with green).

There is deliberately **no leading timestamp field** in either form - see
the next paragraph for why. (An earlier draft of this document said the
6-field form would be `timestamp_ms,ir,red,accX,accY,accZ,green` - that
was wrong; it would have contradicted the very next paragraph, which the
actual code has always followed. Fixed here.)

The app timestamps each sample on arrival using the phone's own clock
(`SystemClock.elapsedRealtime()`) rather than expecting the firmware to
send a synchronized timestamp — same reasoning as `BLE_CONTRACT.md`: the
DSP pipeline only needs consistent *spacing* between samples, not
absolute alignment with the microcontroller's clock. If the firmware
sends a leading timestamp field too, that's a one-line change to
`parseLine()` (skip/ignore the extra field) — flag it here first.

Malformed lines (wrong field count, non-numeric values, partial reads
from a mid-line connect) are logged and silently dropped, not treated as
fatal — the app resyncs on the next newline.

## Sample rate

The app is currently configured for **25 Hz**
(`UsbSerialSensorSource.sampleRateHz` and `PpgPipeline.FS_HZ`), matching
the existing hardware captures. If the firmware ends up at a different
fixed rate, update both of those constants together — the band-pass
filter coefficients in `FilterCoefficients.kt` are rate-specific (25 Hz
and 50 Hz are both precomputed there; anything else needs new
coefficients generated offline, not guessed).

## Example ESP32 sketch fragment

Minimal sketch skeleton emitting the line format above at roughly 25 Hz
(40 ms per sample) - swap `readIr()`/`readRed()`/`readAccel()` for your
actual sensor reads:

```cpp
void setup() {
  Serial.begin(115200);
  // Give the USB-UART bridge a moment before the first line - avoids
  // the phone side seeing a truncated/garbled first read.
  delay(200);
}

void loop() {
  static unsigned long lastSampleMs = 0;
  const unsigned long periodMs = 40; // 25 Hz

  unsigned long now = millis();
  if (now - lastSampleMs < periodMs) return;
  lastSampleMs = now;

  int ir = readIr();
  int red = readRed();
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
  Serial.println(accZ, 3);

  // OPTIONAL 6th field, only if your hardware has a green LED (e.g. a
  // MAX30105 instead of a MAX30102/30100) and you want SpO2-era per-
  // sample green data. If added, use Serial.print(accZ, 3) (not println)
  // above and append:
  //   Serial.print(',');
  //   Serial.println(readGreen());
  // UsbSerialSensorSource.parseLine() already accepts either 5 or 6
  // fields - no app-side change needed either way.
}
```

Notes for whoever writes the real firmware:

- Do all sensor reads before printing, then print the whole line in one
  burst - printing field-by-field with reads interleaved risks a
  partial line if something stalls mid-sample.
- Don't `Serial.print` anything else once the stream starts (no debug
  logging on the same UART) - `UsbSerialSensorSource.parseLine()` will
  silently drop any line that isn't exactly 5 (or, with green, 6)
  comma-separated numbers, so stray text just becomes lost samples rather
  than a crash, but it's still wasted bandwidth and confusing to debug.
- Boot-log noise from the Arduino core itself (before `setup()` reaches
  the code above) is fine - it arrives before the app starts reading
  and/or gets dropped by the same malformed-line check.

## What's NOT decided yet (flag these with the firmware owner)

1. Is the assumed `ir,red,accX,accY,accZ` field order and comma-separated
   text format actually what the firmware sends, or something else
   entirely (binary, different field order, extra fields)?
2. Does the firmware send a leading timestamp field (like the BLE
   contract's `millis()` field did)?
3. Is 115200 baud correct, or does the firmware configure a different
   rate?
4. Is 25 Hz still the intended rate, same open question as in
   `BLE_CONTRACT.md`.
