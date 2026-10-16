package com.hpn.app

import android.app.Application
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HpnApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
                File(filesDir, CRASH_FILE).writeText(
                    "time=$stamp\nthread=${thread.name}\n${Log.getStackTraceString(error)}"
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }

    companion object {
        private const val CRASH_FILE = "last-crash.txt"

        fun readLastCrash(application: Application): String? = runCatching {
            val file = File(application.filesDir, CRASH_FILE)
            if (!file.exists()) null else file.readText().takeLast(1800)
        }.getOrNull()

        fun clearLastCrash(application: Application) = runCatching {
            File(application.filesDir, CRASH_FILE).delete()
        }
    }
}
