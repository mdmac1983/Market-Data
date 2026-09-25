package app.orionmd.marketdata

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import app.orionmd.marketdata.data.Net
import app.orionmd.marketdata.data.Prefs
import app.orionmd.marketdata.data.QuoteHub
import app.orionmd.marketdata.data.Store
import app.orionmd.marketdata.pdf.Reports
import app.orionmd.marketdata.work.AlertChecker
import app.orionmd.marketdata.work.Notifier
import app.orionmd.marketdata.work.Scheduler
import app.orionmd.marketdata.work.WatchlistWidget

class MarketApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        Prefs.init(this)
        Store.init(this)
        Net.init(this)
        Notifier.channels(this)
        Reports.cleanTemp(this)
        runCatching { Scheduler.scheduleAll(this) }

        QuoteHub.onQuotes = { q ->
            AlertChecker.check(this, q)
            WatchlistWidget.push(this, q)
        }
        // Poll and stream only while the app is in the foreground; WorkManager covers the background.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = QuoteHub.start()
            override fun onStop(owner: LifecycleOwner) = QuoteHub.stop()
        })
    }
}

/** Saves the last crash so the app can show the exact error on the next launch. */
object CrashLog {
    private fun file(ctx: android.content.Context) = java.io.File(ctx.filesDir, "last_crash.txt")

    fun install(ctx: android.content.Context) {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val sw = java.io.StringWriter(); e.printStackTrace(java.io.PrintWriter(sw))
                file(ctx).writeText("Market_Data ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · Android ${android.os.Build.VERSION.RELEASE} · ${android.os.Build.MODEL}\nThread: ${t.name}\n\n$sw")
            }
            prev?.uncaughtException(t, e)
        }
    }

    fun take(ctx: android.content.Context): String? = file(ctx).takeIf { it.exists() }?.let { f -> f.readText().also { f.delete() } }
}
