package app.orionmd.marketdata.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.orionmd.marketdata.MainActivity
import app.orionmd.marketdata.R
import app.orionmd.marketdata.data.AlertKind
import app.orionmd.marketdata.data.Catalog
import app.orionmd.marketdata.data.Market
import app.orionmd.marketdata.data.MarketClock
import app.orionmd.marketdata.data.Prefs
import app.orionmd.marketdata.data.Quote
import app.orionmd.marketdata.data.ReportSchedule
import app.orionmd.marketdata.data.Store
import app.orionmd.marketdata.pdf.ReportKind
import app.orionmd.marketdata.pdf.Reports
import app.orionmd.marketdata.ui.fmtPct
import app.orionmd.marketdata.ui.fmtPrice
import java.io.File
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

object Notifier {
    const val ALERTS = "alerts"
    const val SUMMARY = "summary"
    const val REPORTS = "reports"

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(ALERTS, "Price alerts", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(SUMMARY, "Market open/close summaries", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(REPORTS, "Scheduled PDF reports", NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun notify(ctx: Context, channel: String, id: Int, title: String, text: String, open: Intent? = null) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            android.os.Build.VERSION.SDK_INT >= 33) return
        val intent = open ?: Intent(ctx, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        val pi = PendingIntent.getActivity(ctx, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_stat_chart)
            .setColor(Color.parseColor("#3FC8F5"))
            .setContentTitle(title)
            .setContentText(text.lineSequence().firstOrNull() ?: text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(id, n) }
    }
}

object AlertChecker {
    /** Checks alerts against fresh quotes and fires notifications. */
    fun check(ctx: Context, quotes: Map<String, Quote>) {
        val now = System.currentTimeMillis()
        val fired = mutableListOf<String>()
        Store.current.alerts.filter { it.enabled && it.symbol in quotes }.forEach { a ->
            val q = quotes.getValue(a.symbol)
            val hit = when (a.kind) {
                AlertKind.ABOVE -> q.price >= a.value
                AlertKind.BELOW -> q.price <= a.value
                AlertKind.PCT_UP -> q.changePct >= a.value
                AlertKind.PCT_DOWN -> q.changePct <= -a.value
            }
            // one notification per alert per 6 hours when repeating
            if (hit && (a.lastFired == 0L || (a.repeat && now - a.lastFired > 6 * 3600_000))) {
                fired += a.id
                val cond = when (a.kind) {
                    AlertKind.ABOVE -> "rose above ${fmtPrice(a.value)}"
                    AlertKind.BELOW -> "fell below ${fmtPrice(a.value)}"
                    AlertKind.PCT_UP -> "is up ${fmtPct(q.changePct)} today"
                    AlertKind.PCT_DOWN -> "is down ${fmtPct(q.changePct)} today"
                }
                Notifier.notify(ctx, Notifier.ALERTS, a.id.hashCode(), "${Catalog.display(a.symbol)} $cond",
                    "${q.name}: ${fmtPrice(q.price)} (${fmtPct(q.changePct)})",
                    Intent(ctx, MainActivity::class.java).putExtra("symbol", a.symbol))
            }
        }
        if (fired.isNotEmpty()) Store.update { d ->
            d.copy(alerts = d.alerts.map { if (it.id in fired) it.copy(lastFired = now, enabled = it.repeat) else it })
        }
    }
}

class AlertWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val syms = Store.current.alerts.filter { it.enabled }.map { it.symbol }.distinct()
        if (syms.isNotEmpty()) AlertChecker.check(applicationContext, Market.quotes(syms))
        WatchlistWidget.refresh(applicationContext)
        return Result.success()
    }
}

/** Morning / closing summaries and scheduled PDF reports. Each run schedules the next one. */
class DailyWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val task = inputData.getString("task") ?: return Result.success()
        runCatching {
            when (task) {
                "morning" -> summary("Morning market brief", Catalog.futures + listOf("BTC-USD", "^VIX", "^TNX"))
                "closing" -> summary("Closing bell", Catalog.usIndices + listOf("BTC-USD", "^TNX"))
                "report" -> scheduledReport()
            }
        }
        Scheduler.schedule(applicationContext, task)
        return Result.success()
    }

    private suspend fun summary(title: String, symbols: List<String>) {
        val q = Market.quotes(symbols)
        val movers = runCatching { Market.movers(Market.Movers.GAINERS, 3) + Market.movers(Market.Movers.LOSERS, 3) }.getOrDefault(emptyList())
        val text = buildString {
            symbols.mapNotNull { q[it] }.forEach { appendLine("${Catalog.shortName(it.symbol)}  ${fmtPrice(it.price)}  ${fmtPct(it.changePct)}") }
            if (movers.isNotEmpty()) {
                appendLine(); append("Movers: ")
                append(movers.joinToString("  ") { "${it.symbol} ${fmtPct(it.changePct)}" })
            }
        }.trim()
        Notifier.notify(applicationContext, Notifier.SUMMARY, title.hashCode(), title, text)
    }

    private suspend fun scheduledReport() {
        val dir = File(applicationContext.filesDir, "reports").apply { mkdirs() }
        val name = "Market_Summary_${LocalDate.now()}.pdf"
        val out = File(dir, name)
        Reports.generate(applicationContext, ReportKind.MARKET, Reports.loadOptions(ReportKind.MARKET).copy(title = "Market Summary ${LocalDate.now()}"), out) { _, _ -> }
        // keep the 30 newest scheduled reports
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(30)?.forEach { it.delete() }
        Notifier.notify(applicationContext, Notifier.REPORTS, 7001, "Your market report is ready", name,
            Intent(applicationContext, MainActivity::class.java).putExtra("pdf", out.absolutePath))
    }
}

object Scheduler {
    private val et = MarketClock.ET

    fun scheduleAll(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        wm.enqueueUniquePeriodicWork("alerts", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<AlertWorker>(15, TimeUnit.MINUTES).setConstraints(net).build())
        listOf("morning", "closing", "report").forEach { schedule(ctx, it) }
    }

    fun schedule(ctx: Context, task: String) {
        val s = Prefs.current
        val wm = WorkManager.getInstance(ctx)
        val enabled = when (task) {
            "morning" -> s.morningSummary
            "closing" -> s.closingSummary
            "report" -> s.reportSchedule != ReportSchedule.OFF
            else -> false
        }
        if (!enabled) { wm.cancelUniqueWork("daily_$task"); return }
        val now = ZonedDateTime.now(et)
        val next: ZonedDateTime = when (task) {
            "morning" -> nextWeekday(now, LocalTime.of(9, 0), et)
            "closing" -> nextWeekday(now, LocalTime.of(16, 10), et)
            else -> {
                val local = ZonedDateTime.now(ZoneId.systemDefault())
                var t = local.toLocalDate().atTime(s.reportHour, 0).atZone(ZoneId.systemDefault())
                if (!t.isAfter(local)) t = t.plusDays(1)
                if (s.reportSchedule == ReportSchedule.WEEKLY) while (t.dayOfWeek != DayOfWeek.FRIDAY) t = t.plusDays(1)
                t
            }
        }
        val delay = Duration.between(now, next).toMillis().coerceAtLeast(60_000)
        wm.enqueueUniqueWork("daily_$task", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<DailyWorker>().setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setInputData(workDataOf("task" to task))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }

    private fun nextWeekday(now: ZonedDateTime, at: LocalTime, zone: ZoneId): ZonedDateTime {
        var t = now.toLocalDate().atTime(at).atZone(zone)
        if (!t.isAfter(now)) t = t.plusDays(1)
        while (t.dayOfWeek == DayOfWeek.SATURDAY || t.dayOfWeek == DayOfWeek.SUNDAY) t = t.plusDays(1)
        return t
    }
}

class WidgetWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val list = Store.current.watchlists.firstOrNull() ?: return Result.success()
        val q = Market.quotes(list.symbols.take(6))
        WatchlistWidget.render(applicationContext, list.name, list.symbols.take(6), q)
        return Result.success()
    }
}

class WatchlistWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        refresh(context)
    }

    companion object {
        private val rows = listOf(
            Triple(R.id.sym0, R.id.price0, R.id.chg0), Triple(R.id.sym1, R.id.price1, R.id.chg1), Triple(R.id.sym2, R.id.price2, R.id.chg2),
            Triple(R.id.sym3, R.id.price3, R.id.chg3), Triple(R.id.sym4, R.id.price4, R.id.chg4), Triple(R.id.sym5, R.id.price5, R.id.chg5),
        )
        private val rowIds = listOf(R.id.row0, R.id.row1, R.id.row2, R.id.row3, R.id.row4, R.id.row5)

        fun refresh(ctx: Context) {
            if (!hasWidgets(ctx)) return
            WorkManager.getInstance(ctx).enqueueUniqueWork("widget", ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<WidgetWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
        }

        fun hasWidgets(ctx: Context) =
            AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, WatchlistWidget::class.java)).isNotEmpty()

        /** Called by the app whenever it has fresh watchlist quotes. */
        fun push(ctx: Context, quotes: Map<String, Quote>) {
            if (!hasWidgets(ctx)) return
            val list = Store.current.watchlists.firstOrNull() ?: return
            val syms = list.symbols.take(6)
            if (syms.none { it in quotes }) return
            render(ctx, list.name, syms, quotes)
        }

        fun render(ctx: Context, title: String, symbols: List<String>, quotes: Map<String, Quote>) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, WatchlistWidget::class.java))
            if (ids.isEmpty()) return
            val v = RemoteViews(ctx.packageName, R.layout.widget_watchlist)
            v.setTextViewText(R.id.widget_title, title)
            v.setTextViewText(R.id.widget_time, LocalTime.now().format(DateTimeFormatter.ofPattern("h:mm a")))
            rows.forEachIndexed { i, (s, p, c) ->
                val sym = symbols.getOrNull(i)
                v.setViewVisibility(rowIds[i], if (sym == null) android.view.View.GONE else android.view.View.VISIBLE)
                if (sym != null) {
                    val q = quotes[sym]
                    v.setTextViewText(s, Catalog.display(sym))
                    v.setTextViewText(p, q?.let { fmtPrice(it.price) } ?: "—")
                    v.setTextViewText(c, q?.let { fmtPct(it.changePct) } ?: "")
                    v.setTextColor(c, if ((q?.changePct ?: 0.0) >= 0) Color.parseColor("#22C55E") else Color.parseColor("#EF4444"))
                }
            }
            val pi = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            v.setOnClickPendingIntent(R.id.widget_root, pi)
            mgr.updateAppWidget(ids, v)
        }
    }
}
