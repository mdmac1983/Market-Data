package app.orionmd.marketdata.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.orionmd.marketdata.BuildConfig
import app.orionmd.marketdata.data.*
import app.orionmd.marketdata.ui.*
import app.orionmd.marketdata.ui.components.*
import app.orionmd.marketdata.work.Scheduler
import org.json.JSONObject

@Composable
private fun SettingRow(title: String, sub: String? = null, onClick: (() -> Unit)? = null, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing()
    }
}

@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val nav = LocalNav.current
    val s by Prefs.settings.collectAsState()
    var editKey by remember { mutableStateOf<Keys.KeyInfo?>(null) }
    var pinDialog by remember { mutableStateOf(false) }
    var cacheSize by remember { mutableLongStateOf(Net.cacheSizeBytes()) }

    fun reschedule() = runCatching { Scheduler.scheduleAll(ctx) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SectionCard("Appearance") {
                Text("Theme", style = MaterialTheme.typography.labelLarge)
                ChipRow(listOf("Dark", "Light", "System"), s.theme.name.lowercase().replaceFirstChar { it.uppercase() }, { v -> Prefs.update { it.copy(theme = ThemeMode.valueOf(v.uppercase())) } })
                Text("Text size: ${(s.textScale * 100).toInt()}%", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                Slider(s.textScale, { v -> Prefs.update { it.copy(textScale = (v * 20).toInt() / 20f) } }, valueRange = 0.85f..1.3f, steps = 8)
                Text("Watermark in dark mode: ${(s.watermarkAlpha * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
                Slider(s.watermarkAlpha, { v -> Prefs.update { it.copy(watermarkAlpha = v) } }, valueRange = 0f..0.8f)
                Text("Watermark in light mode: ${(s.lightWatermarkAlpha * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
                Slider(s.lightWatermarkAlpha, { v -> Prefs.update { it.copy(lightWatermarkAlpha = v) } }, valueRange = 0f..1f)
                SettingRow("Compact layout", "Tighter rows so more fits on screen") { Switch(s.compact, { v -> Prefs.update { it.copy(compact = v) } }) }
            }
        }
        item {
            SectionCard("Live data") {
                SettingRow("Live streaming", "Tick-by-tick prices (Finnhub for stocks, Coinbase for crypto)") {
                    Switch(s.streaming, { v -> Prefs.update { it.copy(streaming = v) }; if (!v) Stream.stop() else QuoteHub.watch(emptyList()) })
                }
                Text("Refresh every ${s.refreshSec}s", style = MaterialTheme.typography.labelLarge)
                ChipRow(listOf("10", "15", "30", "60", "120", "300"), s.refreshSec.toString(), { v -> Prefs.update { it.copy(refreshSec = v.toInt()) } })
                Text("Faster refresh uses more of the free API limits (Finnhub 60/min).", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SettingRow("Pre-market & after-hours prices", "Shown on symbol pages") { Switch(s.showExtendedHours, { v -> Prefs.update { it.copy(showExtendedHours = v) } }) }
                SettingRow("Offline cache", "${cacheSize / 1024} KB of saved data for offline use") {
                    TextButton(onClick = { Net.clearDisk(); cacheSize = Net.cacheSizeBytes() }) { Text("Clear") }
                }
            }
        }
        item {
            SectionCard("Notifications") {
                SettingRow("Morning brief", "Weekdays 9:00 AM ET: futures, VIX, yields, top movers") {
                    Switch(s.morningSummary, { v -> Prefs.update { it.copy(morningSummary = v) }; reschedule() })
                }
                SettingRow("Closing bell summary", "Weekdays 4:10 PM ET: index closes and movers") {
                    Switch(s.closingSummary, { v -> Prefs.update { it.copy(closingSummary = v) }; reschedule() })
                }
                Text("Scheduled market summary PDF", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                ChipRow(listOf("Off", "Daily", "Weekly"), s.reportSchedule.name.lowercase().replaceFirstChar { it.uppercase() },
                    { v -> Prefs.update { it.copy(reportSchedule = ReportSchedule.valueOf(v.uppercase())) }; reschedule() })
                if (s.reportSchedule != ReportSchedule.OFF) {
                    Text("At ${s.reportHour}:00" + if (s.reportSchedule == ReportSchedule.WEEKLY) " on Fridays" else " every day", style = MaterialTheme.typography.labelMedium)
                    Slider(s.reportHour.toFloat(), { v -> Prefs.update { it.copy(reportHour = v.toInt()) } }, onValueChangeFinished = { reschedule() }, valueRange = 0f..23f, steps = 22)
                    TextButton(onClick = { nav.go("reports") }) { Text("View saved reports") }
                }
                SettingRow("Price alerts", "Manage alerts", onClick = { nav.go("alerts") }) { Icon(Icons.Default.ChevronRight, null) }
            }
        }
        item {
            SectionCard("Security") {
                SettingRow("Lock portfolio & paper trading", "Requires fingerprint, screen lock or PIN") {
                    Switch(s.lockPortfolio, { v -> Prefs.update { it.copy(lockPortfolio = v) }; if (v) Session.unlocked = true })
                }
                SettingRow("Use fingerprint / screen lock") { Switch(s.useBiometric, { v -> Prefs.update { it.copy(useBiometric = v) } }) }
                SettingRow(if (s.pinHash.isBlank()) "Set app PIN" else "Change app PIN", onClick = { pinDialog = true }) { Icon(Icons.Default.Pin, null) }
            }
        }
        item {
            SectionCard("API keys") {
                Text("Keys typed here override the ones built into the app. Free keys unlock extra features.", style = MaterialTheme.typography.bodySmall)
                Keys.all.forEach { k ->
                    val custom = s.keys[k.id]?.isNotBlank() == true
                    val status = when { custom -> "Your key"; k.builtIn.isNotBlank() -> "Built in"; else -> "Not set" }
                    SettingRow(k.label, "${k.adds}\n$status", onClick = { editKey = k }) {
                        Icon(if (Keys.has(k.id)) Icons.Default.CheckCircle else Icons.Default.AddCircleOutline, null, tint = if (Keys.has(k.id)) Up else Gold)
                    }
                }
            }
        }
        item {
            SectionCard("Free data sources (no key needed)") {
                listOf(
                    "Yahoo Finance" to "Indices, futures, commodities, forex, yields, charts, movers, screeners (unofficial)",
                    "CoinGecko" to "Crypto prices, top 100, trending, global stats, coin details, exchanges",
                    "Coinbase" to "Live crypto stream, 24h stats and candles",
                    "alternative.me / CNN" to "Crypto and stock Fear & Greed",
                    "Frankfurter (ECB)" to "Currency conversion rates",
                    "RSS: CNBC, MarketWatch, Yahoo, CoinDesk, Cointelegraph" to "Headlines",
                    "SEC EDGAR" to "Company filings",
                    "PublicNode" to "Ethereum gas price",
                ).forEach { (n, d) -> SettingRow(n, d) }
            }
        }
        item {
            SectionCard("About") {
                Text("Market_Data ${BuildConfig.VERSION_NAME} · OrionMD", fontWeight = FontWeight.Bold)
                Text("Market data may be delayed and is for information only. Not investment advice.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { nav.go("backup") }, contentPadding = PaddingValues(0.dp)) { Text("Backup & export →") }
            }
        }
    }

    editKey?.let { k ->
        var v by remember { mutableStateOf(s.keys[k.id].orEmpty()) }
        AlertDialog(onDismissRequest = { editKey = null }, title = { Text("${k.label} API key") }, text = {
            Column {
                Text(k.adds, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(v, { v = it.trim() }, Modifier.fillMaxWidth().padding(top = 8.dp), singleLine = true, label = { Text("API key") },
                    placeholder = { Text(if (k.builtIn.isNotBlank()) "Using built-in key" else "Paste key") })
                TextButton(onClick = { openUrl(ctx, k.signup) }, contentPadding = PaddingValues(0.dp)) { Text("Get a free key →") }
            }
        }, confirmButton = {
            TextButton(onClick = { Prefs.update { it.copy(keys = if (v.isBlank()) it.keys - k.id else it.keys + (k.id to v)) }; Net.clearMemory(); editKey = null }) { Text("Save") }
        }, dismissButton = { TextButton(onClick = { editKey = null }) { Text("Cancel") } })
    }
    if (pinDialog) {
        var pin by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { pinDialog = false }, title = { Text("App PIN") }, text = {
            OutlinedTextField(pin, { if (it.length <= 8) pin = it.filter { c -> c.isDigit() } }, label = { Text("4–8 digits (blank removes PIN)") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
        }, confirmButton = {
            TextButton(onClick = {
                if (pin.isEmpty()) { Prefs.update { it.copy(pinHash = "") }; pinDialog = false }
                else if (pin.length >= 4) { Prefs.update { it.copy(pinHash = Prefs.hashPin(pin)) }; pinDialog = false; Toast.makeText(ctx, "PIN saved", Toast.LENGTH_SHORT).show() }
            }) { Text("Save") }
        }, dismissButton = { TextButton(onClick = { pinDialog = false }) { Text("Cancel") } })
    }
}

// ============================ Backup ============================

@Composable
fun BackupScreen() {
    val ctx = LocalContext.current
    var includeKeys by remember { mutableStateOf(false) }
    fun toast(m: String) = Toast.makeText(ctx, m, Toast.LENGTH_LONG).show()

    fun backupJson(): String = JSONObject().apply {
        put("app", "Market_Data"); put("version", 1); put("exported", System.currentTimeMillis())
        put("data", Store.toJson(Store.current))
        put("settings", Prefs.current.let { if (includeKeys) it else it.copy(keys = emptyMap()) }.copy(pinHash = "").toJson())
    }.toString(2)

    val saveBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching { ctx.contentResolver.openOutputStream(uri)!!.use { it.write(backupJson().toByteArray()) } }
            .onSuccess { toast("Backup saved") }.onFailure { toast("Couldn't save: ${it.message}") }
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            val o = JSONObject(ctx.contentResolver.openInputStream(uri)!!.bufferedReader().readText())
            val data = Store.fromJson(o.getJSONObject("data"))
            Store.update { data }
            Store.migrate()
            o.optJSONObject("settings")?.let { st ->
                val restored = Settings.fromJson(st)
                Prefs.update { cur -> restored.copy(pinHash = cur.pinHash, keys = if (restored.keys.isEmpty()) cur.keys else restored.keys) }
            }
        }.onSuccess { toast("Restored watchlists, portfolio, alerts, notes and dashboard") }.onFailure { toast("Not a valid Market_Data backup") }
    }
    val csvWatch = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val csv = "watchlist,symbol\n" + Store.current.watchlists.flatMap { w -> w.symbols.map { "\"${w.name}\",$it" } }.joinToString("\n")
        runCatching { ctx.contentResolver.openOutputStream(uri)!!.use { it.write(csv.toByteArray()) } }.onSuccess { toast("Watchlists exported") }
    }
    val csvTxns = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val csv = "date,symbol,action,quantity,price,fees,note,portfolio\n" + Store.current.txns.sortedBy { it.date }.joinToString("\n") {
            "${PortfolioCalc.fmtDate(it.date)},${it.symbol},${it.kind.name},${it.qty},${it.price},${it.fees},\"${it.note}\",\"${Store.current.portfolioName(it.portfolio)}\""
        }
        runCatching { ctx.contentResolver.openOutputStream(uri)!!.use { it.write(csv.toByteArray()) } }.onSuccess { toast("Transactions exported") }
    }
    val importWatch = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            val lines = ctx.contentResolver.openInputStream(uri)!!.bufferedReader().readLines().drop(1).filter { it.isNotBlank() }
            val grouped = lines.map { PortfolioCalc.splitCsv(it) }.filter { it.size >= 2 }.groupBy({ it[0].trim() }, { it[1].trim().uppercase() })
            Store.update { d ->
                var lists = d.watchlists
                grouped.forEach { (name, syms) ->
                    val ex = lists.firstOrNull { it.name == name }
                    lists = if (ex != null) lists.map { if (it.id == ex.id) it.copy(symbols = (it.symbols + syms).distinct()) else it }
                    else lists + Watchlist(Store.newId(), name, syms.distinct())
                }
                d.copy(watchlists = lists)
            }
            grouped.size
        }.onSuccess { toast("Imported $it watchlists") }.onFailure { toast("Couldn't read that CSV") }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SectionCard("Full backup") {
                Text("Watchlists, portfolio, paper trading, alerts, notes, trendlines, dashboard layout, PDF choices and settings.", style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(includeKeys, { includeKeys = it }); Text("Include API keys I entered") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { saveBackup.launch("Market_Data_backup_${java.time.LocalDate.now()}.json") }, Modifier.weight(1f)) { Icon(Icons.Default.Save, null); Text(" Back up") }
                    OutlinedButton(onClick = { restore.launch(arrayOf("application/json", "*/*")) }, Modifier.weight(1f)) { Icon(Icons.Default.Restore, null); Text(" Restore") }
                }
            }
        }
        item {
            SectionCard("CSV export / import") {
                OutlinedButton(onClick = { csvWatch.launch("watchlists.csv") }, Modifier.fillMaxWidth()) { Text("Export watchlists (CSV)") }
                OutlinedButton(onClick = { importWatch.launch(arrayOf("text/*", "*/*")) }, Modifier.fillMaxWidth()) { Text("Import watchlists (CSV: watchlist,symbol)") }
                OutlinedButton(onClick = { csvTxns.launch("transactions.csv") }, Modifier.fillMaxWidth()) { Text("Export portfolio transactions (CSV)") }
                Text("Import broker transactions from the Portfolio screen.", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
