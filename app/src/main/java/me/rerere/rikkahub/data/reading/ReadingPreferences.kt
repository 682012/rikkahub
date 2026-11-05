package me.rerere.rikkahub.data.reading

import android.content.Context

/** Kept outside global Settings so unrelated provider/config writes cannot overwrite this switch. */
object ReadingPreferences {
    private const val FILE = "co_reading"
    fun enabled(context: Context): Boolean = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        .getBoolean("automatic_links", true)
    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean("automatic_links", enabled).apply()
    }
}
