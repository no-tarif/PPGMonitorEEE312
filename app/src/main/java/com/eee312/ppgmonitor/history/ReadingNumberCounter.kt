package com.eee312.ppgmonitor.history

import android.content.Context

/**
 * Issues "Reading N" names with N monotonically increasing and never
 * reused, even after the reading with that number is deleted. Backed by
 * SharedPreferences (not count()+1 against the readings table, which
 * would let a deleted "Reading 2" be reissued the next time a reading is
 * saved).
 */
object ReadingNumberCounter {
    private const val PREFS_NAME = "reading_number_counter"
    private const val KEY_NEXT_NUMBER = "next_number"

    @Synchronized
    fun nextName(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val n = prefs.getInt(KEY_NEXT_NUMBER, 1)
        prefs.edit().putInt(KEY_NEXT_NUMBER, n + 1).apply()
        return "Reading $n"
    }
}
