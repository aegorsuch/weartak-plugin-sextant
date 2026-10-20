package com.weartak.plugin.sextant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log

/**
 * Turns the system Wi-Fi scan throttle off only while Sextant is recording, then restores the
 * user's original value. Requires WRITE_SECURE_SETTINGS, granted once via
 * `adb shell pm grant com.weartak.plugin.sextant android.permission.WRITE_SECURE_SETTINGS`.
 * Without it, recording still works at the throttled rate.
 */
object ScanThrottle {
    private const val TAG = "SextantThrottle"
    private const val KEY = "wifi_scan_throttle_enabled"
    private const val PREFS = "sextant_throttle"
    private const val SAVED = "saved"          // original value as string; "null" when it was unset
    private const val UNSET = "null"

    fun canWrite(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun isThrottled(ctx: Context) = Settings.Global.getInt(ctx.contentResolver, KEY, 1) != 0

    /** Disable throttling for this recording; remembers the original value for [restore]. */
    fun disable(ctx: Context) {
        if (!canWrite(ctx)) return
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(SAVED)) {
            val original = Settings.Global.getString(ctx.contentResolver, KEY) ?: UNSET
            prefs.edit().putString(SAVED, original).commit()
        }
        runCatching { Settings.Global.putInt(ctx.contentResolver, KEY, 0) }
            .onSuccess { Log.i(TAG, "scan throttle off while recording") }
            .onFailure { Log.w(TAG, "could not disable throttle", it) }
    }

    /** Put back the value saved by [disable]. Safe to call any time (no-op if nothing saved). */
    fun restore(ctx: Context) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val original = prefs.getString(SAVED, null) ?: return
        if (!canWrite(ctx)) return
        runCatching {
            // An unset value means the platform default (throttled); write 1 explicitly.
            Settings.Global.putInt(ctx.contentResolver, KEY, if (original == UNSET) 1 else original.toInt())
        }.onSuccess {
            prefs.edit().remove(SAVED).commit()
            Log.i(TAG, "scan throttle restored to $original")
        }.onFailure { Log.w(TAG, "could not restore throttle", it) }
    }
}
