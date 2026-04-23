package dev.c0redev.volter

import android.util.Log

object VolterLog {
    const val TAG = "VolterVPN"

    fun v(msg: String, tr: Throwable? = null) {
        if (tr != null) Log.v(TAG, msg, tr) else Log.v(TAG, msg)
    }

    fun d(msg: String, tr: Throwable? = null) {
        if (tr != null) Log.d(TAG, msg, tr) else Log.d(TAG, msg)
    }

    fun i(msg: String, tr: Throwable? = null) {
        if (tr != null) Log.i(TAG, msg, tr) else Log.i(TAG, msg)
    }

    fun w(msg: String, tr: Throwable? = null) {
        if (tr != null) Log.w(TAG, msg, tr) else Log.w(TAG, msg)
    }

    fun e(msg: String, tr: Throwable? = null) {
        if (tr != null) Log.e(TAG, msg, tr) else Log.e(TAG, msg)
    }
}
