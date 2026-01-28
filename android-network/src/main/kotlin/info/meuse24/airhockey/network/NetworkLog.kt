package info.meuse24.airhockey.network

import android.util.Log

object NetworkLog {
    fun d(tag: String, message: String) {
        if (BuildConfig.DEBUG) {
            Log.d(tag, message)
        }
    }

    fun w(tag: String, message: String, tr: Throwable? = null) {
        if (tr != null) {
            Log.w(tag, message, tr)
        } else {
            Log.w(tag, message)
        }
    }

    fun e(tag: String, message: String, tr: Throwable? = null) {
        if (tr != null) {
            Log.e(tag, message, tr)
        } else {
            Log.e(tag, message)
        }
    }
}
