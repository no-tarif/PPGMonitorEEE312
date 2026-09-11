package com.eee312.ppgmonitor.history

/** Which [com.eee312.ppgmonitor.sensor.SensorSource] produced a reading.
 * Room stores enum columns as their name() natively - no TypeConverter
 * needed. ESP32_BLE exists now so Phase 4's BLE re-wire lands in the same
 * history/schema without a migration. */
enum class SourceType { SIMULATED, DATASET, ESP32_BLE, ESP32_USB }
