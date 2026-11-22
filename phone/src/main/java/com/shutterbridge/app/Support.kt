package com.shutterbridge.app

import android.content.Context
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Const {
    const val PATH_CAPTURE = "/shutter/capture"
    const val PATH_RESULT = "/shutter/result"
}

/** Small in-memory log shown on the phone app's main screen (handy for diagnosis). */
object AppLog {
    private val lines = ArrayList<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun add(msg: String) {
        lines.add(fmt.format(Date()) + "  " + msg)
        while (lines.size > 300) lines.removeAt(0)
        Log.d("ShutterBridge", msg)
    }

    @Synchronized
    fun dump(): String = lines.joinToString("\n")
}

/** Fallback tap position, as a percentage of the screen, used when the shutter node can't be found. */
object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    fun fx(c: Context): Float = sp(c).getFloat("fx", 50f)
    fun fy(c: Context): Float = sp(c).getFloat("fy", 86f)

    fun save(c: Context, x: Float, y: Float) {
        sp(c).edit().putFloat("fx", x).putFloat("fy", y).apply()
    }
}
