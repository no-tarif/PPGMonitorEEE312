# USB serial contract — phone ⇄ ESP32

This is the frame `PpgStream.ino` (v1.1, in the course folder under
`hardware_guide/firmware/PpgStream/`, built per `HARDWARE_SETUP_GUIDE.md`)
sends, and what `sensor/SerialLineParser.kt` accepts. The two are
tested together without hardware in `FirmwareStreamTest.kt`, which feeds
lines formatted exactly like the sketch's `snprintf` through the parser,
the rate estimator and the DSP pipeline.

## Transport

- **Library**: [usb-serial-for-android](https://github.com/mik3y/usb-serial-for-android)
  3.11.0, generic `UsbSerialProber`. The guide's boards use a CP2102
  (`10C4:EA60`) or CH340 (`1A86:7523`), both in the library's own driver
  table, or a CH9102 (`1A86:55D4`), which enumerates as standard CDC-ACM
  and is picked up by the library's CDC-ACM probe. All three are in
  `res/xml/device_filter.xml`.
- **Link**: 115200 baud, 8N1.
- **Permission**: Android's own USB permission dialog, requested on Start.

## Line format

One sample per line, ASCII, `\n`-terminated (`\r\n` is fine), no header:

```
ir,red,accX,accY,accZ,green,seq        PpgStream.ino v1.1 (always 7 fields)
115684,84143,-8.138,-1.815,5.818,,42   SEND_GREEN 0: green is empty
115684,84143,nan,nan,nan,,43           MPU6050 read failed for this sample
```

| # | Field | Type | Notes |
|---|---|---|---|
| 1 | ir | integer | raw 18-bit ADC counts (MAX30105 slot 2, 880 nm) |
| 2 | red | integer | raw 18-bit ADC counts (slot 1, 660 nm) |
| 3–5 | accX, accY, accZ | decimal or `nan` | m/s² (±8 g, 3 decimals). `nan` = that sample's accelerometer read failed; the PPG values are still real, and the app freezes LMS adaptation for it |
| 6 | green | integer or empty | slot 3, 537 nm; empty unless `SEND_GREEN 1` |
| 7 | seq | integer | +1 per line; a jump means lines were lost |

Older 5-field (`ir,red,accX,accY,accZ`) and 6-field (`…,green`) frames
are still accepted. Anything else is dropped and logged under the tag
`UsbSerialSensorSource`. Lines starting with `#` are the firmware's own
messages (e.g. `# INIT FAILED: …`) and are shown as the app's status.

## Timing and sample rate

The frame carries no timestamp. The phone stamps each line on arrival
(`elapsedRealtimeNanos`), and USB delivers lines in bursts, so single
intervals mean nothing. The app instead fits a straight line through
*arrival time vs seq* (`dsp/SampleRateEstimator.kt`): after 10 s this is
accurate to well under 0.1 %. If the measured rate is more than 1 % off
the nominal 25 Hz, the whole DSP chain (filters, windows, LMS length,
SpO2 windows) is rebuilt at the measured rate and the status line says
so. The saved reading's time base and "Sample rate" use the measured
rate.

## Signal levels (`sensor/HardwareProfile.kt`)

- ADC full scale 262 143; ≥ 262 000 counts is treated as saturated.
- IR below 50 000 counts = no finger (the guide's LED current 0x1F and
  4096 nA range; a covered fingertip reads ~80 000–115 000).
