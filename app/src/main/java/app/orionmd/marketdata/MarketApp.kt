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
