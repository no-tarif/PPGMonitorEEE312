package com.eee312.ppgmonitor.history

/** Which [com.eee312.ppgmonitor.sensor.SensorSource] produced a reading.
 * PERSISTED by name: never remove or reorder a value, only append.
 * SIMULATED is no longer offered in the source picker (v3) but stays so
 * readings recorded with the old simulator still open and show their
 * source.
 * Room stores enum columns as their name() natively - no TypeConverter
 * needed. ESP32_BLE exists now so Phase 4's BLE re-wire lands in the same
 * history/schema without a migration. */
enum class SourceType { SIMULATED, DATASET, ESP32_BLE, ESP32_USB, FILE }
