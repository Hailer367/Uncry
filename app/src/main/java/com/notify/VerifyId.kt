package com.notify

import android.content.Context
import java.security.SecureRandom

/**
 * Stable per-device verification ID shown on the main screen.
 * 7 chars, unambiguous capitals + digits (no I/O/0/1 to avoid misreads).
 * Generated once, persisted in "notify" prefs, included in register/heartbeat
 * so the operator can match a replying business to its device.
 */
object VerifyId {
    private const val PREF = "notify"
    private const val KEY = "verify_id"
    const val LENGTH = 7
    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    fun getOrCreate(ctx: Context): String {
        val prefs = ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        prefs.getString(KEY, null)?.let {
            if (it.length == LENGTH && it.all { c -> c in 'A'..'Z' || c in '0'..'9' }) return it
        }
        val id = generate()
        prefs.edit().putString(KEY, id).commit()
        return id
    }

    private fun generate(): String {
        val rnd = SecureRandom()
        return (1..LENGTH).map { ALPHABET[rnd.nextInt(ALPHABET.length)] }.joinToString("")
    }
}
