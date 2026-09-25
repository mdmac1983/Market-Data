package app.orionmd.marketdata.ui.screens

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.orionmd.marketdata.data.*
import app.orionmd.marketdata.ui.*
import app.orionmd.marketdata.ui.components.*
import kotlinx.coroutines.launch
import org.json.JSONObject

// ============================ Glossary ============================

@Composable
fun GlossaryScreen() {
    var q by remember { mutableStateOf("") }
    var cat by rememberSaveableString("All")
    var open by remember { mutableStateOf<String?>(null) }
    val list = Glossary.terms.filter { t ->
        (cat == "All" || t.category == cat) &&
            (q.isBlank() || t.title.contains(q, true) || t.text.contains(q, true) || t.aliases.any { it.contains(q, true) })
    }.sortedBy { it.title.lowercase() }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth().padding(horizontal = 12.dp), singleLine = true,
            placeholder = { Text("Search terms, e.g. RSI, P/E, yield curve") }, leadingIcon = { Icon(Icons.Default.Search, null) })
        ChipRow(listOf("All") + Glossary.categories, cat, { cat = it }, Modifier.padding(12.dp, 6.dp))
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                Text("Tip: tap any ⓘ next to a card title, stat or label to see its explanation.", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(list, key = { it.key }) { t ->
                SectionCard(modifier = Modifier.clickable { open = if (open == t.key) null else t.key }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(t.title, fontWeight = FontWeight.Bold)
                            Text(t.category, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(if (open == t.key) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                    }
                    Text(t.text, style = MaterialTheme.typography.bodyMedium, maxLines = if (open == t.key) 40 else 2,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}

// ============================ Signals ============================

@Composable
fun SignalsScreen() {
    val nav = LocalNav.current
    var tab by rememberSaveableString("Watchlist")
    var filter by rememberSaveableString("Both")
    var progress by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        ChipRow(listOf("Watchlist", "Stocks", "Crypto"), tab, { tab = it }, Modifier.padding(horizontal = 12.dp))
        ChipRow(listOf("Both", "Overbought", "Oversold", "All"), filter, { filter = it }, Modifier.padding(horizontal = 12.dp))
        val d = rememberLoad("signals", tab, refreshMs = 10 * 60_000) {
            when (tab) {
                "Watchlist" -> {
                    val syms = (Store.allWatchedSymbols() + PortfolioCalc.holdings(Store.current.txns).filter { it.qty > 0 }.map { it.symbol }).distinct()
                    Signals.forSymbols(syms) { a, b -> progress = "Checking $a of $b…" }.values.map { it to (Catalog.nameOf(it.symbol) ?: "") }
                }
                "Stocks" -> Signals.forUniverse { a, b -> progress = "Scanning $a of $b stocks…" }.map { (r, s) -> s to r.name }
                else -> Market.coins(1, 100).mapNotNull { c -> Signals.forCoin(c)?.let { it to c.name } }
            }
        }
        val rows = d.data.orEmpty().filter { (s, _) ->
            when (filter) { "Overbought" -> s.state.isOverbought; "Oversold" -> s.state.isOversold; "Both" -> s.state != SignalState.NEUTRAL; else -> true }
        }.sortedByDescending { (s, _) -> kotlin.math.abs(s.rsi - 50) }
        val q = rememberLive(rows.take(40).map { it.first.symbol })
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(when (tab) {
                        "Watchlist" -> "Your watchlists and holdings · daily RSI(14), Stochastic and Bollinger %B"
                        "Stocks" -> "${Catalog.universe.size} large US stocks · daily readings"
                        else -> "Top 100 coins · 4-hour readings from the last 7 days"
                    }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    InfoIcon("Overbought / oversold")
                }
            }
            if (d.loading && d.data == null) item { Text(progress, style = MaterialTheme.typography.labelMedium); LinearProgressIndicator(Modifier.fillMaxWidth()) }
            d.error?.let { item { ErrorBox(it, d.reload) } }
            if (!d.loading && rows.isEmpty()) item { Text(if (filter == "All") "No data yet." else "Nothing is overbought or oversold right now.", Modifier.padding(8.dp)) }
            items(rows, key = { it.first.symbol }) { (s, name) ->
                val quote = q[s.symbol]
                Row(Modifier.fillMaxWidth().clickable { nav.symbol(s.symbol) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(Catalog.display(s.symbol), fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(6.dp)); SignalBadge(s, showNeutral = true)
                        }
                        Text(name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        RsiBar(s.rsi, Modifier.fillMaxWidth(0.8f).padding(top = 4.dp).height(8.dp))
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("RSI ${"%.0f".format(s.rsi)}", fontWeight = FontWeight.SemiBold, color = signalColor(s.state))
                        if (quote != null) Text(fmtPct(quote.changePct), color = changeColor(quote.changePct), style = MaterialTheme.typography.labelMedium)
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            }
        }
    }
}

/** Dashboard card: overbought / oversold symbols among your watchlists and holdings. */
@Composable
fun SignalsDashCard() {
    val nav = LocalNav.current
    val d = rememberLoad("dashsignals", refreshMs = 15 * 60_000) {
        val syms = (Store.allWatchedSymbols() + PortfolioCalc.holdings(Store.current.txns).filter { it.qty > 0 }.map { it.symbol }).distinct().take(40)
        Signals.forSymbols(syms).values.toList()
    }
    SectionCard("Overbought / oversold", action = { TextButton(onClick = { nav.go("signals") }) { Text("More") } }) {
        LoadContent(d, "Add symbols to a watchlist to see signals", isEmpty = { false }) { list ->
            val ob = list.filter { it.state.isOverbought }.sortedByDescending { it.rsi }
            val os = list.filter { it.state.isOversold }.sortedBy { it.rsi }
            if (ob.isEmpty() && os.isEmpty()) Text("Nothing in your watchlists is overbought or oversold right now.", style = MaterialTheme.typography.bodySmall)
            (ob.take(4) + os.take(4)).forEach { s ->
                Row(Modifier.fillMaxWidth().clickable { nav.symbol(s.symbol) }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(Catalog.display(s.symbol), fontWeight = FontWeight.Bold, modifier = Modifier.width(80.dp))
                    Box(Modifier.weight(1f)) { SignalBadge(s) }
                    Text("RSI ${"%.0f".format(s.rsi)}", color = signalColor(s.state), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

/** Symbol-page card: the full overbought / oversold reading with an alert shortcut. */
@Composable
fun SignalCard(symbol: String, price: Double?) {
    val d = rememberLoad("signal", symbol) { Signals.forSymbol(symbol) }
    var alert by remember { mutableStateOf(false) }
    SectionCard("Overbought / oversold signal") {
        LoadContent(d, "Not enough price history for a signal", isEmpty = { false }) { sig ->
            if (sig == null) Text("Not enough price history for a signal.", style = MaterialTheme.typography.bodySmall)
            else {
                SignalDetail(sig)
                TextButton(onClick = { alert = true }, contentPadding = PaddingValues(0.dp)) { Icon(Icons.Default.NotificationAdd, null); Text(" Alert me when overbought / oversold") }
            }
        }
    }
    if (alert) AddAlertDialog(symbol, price, initialKind = AlertKind.OVERSOLD) { alert = false }
}

// ============================ API keys ============================

@Composable
fun ApiKeysScreen() {
    val ctx = LocalContext.current
    val s by Prefs.settings.collectAsState()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            SectionCard {
                Text("Paste a key into any box and tap Save. Keys you enter here override the ones built into the app, and stay on this phone.",
                    style = MaterialTheme.typography.bodySmall)
                Text("All of these have free plans. \"Get a free key\" opens the sign-up page.", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
        }
        items(Keys.all, key = { it.id }) { k -> KeyEditor(k, s.keys[k.id].orEmpty(), ctx) }
    }
}

@Composable
private fun KeyEditor(k: Keys.KeyInfo, saved: String, ctx: Context) {
    val scope = rememberCoroutineScope()
    var text by remember(saved) { mutableStateOf(saved) }
    var show by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var testing by remember { mutableStateOf(false) }
    val active = Keys.has(k.id)
    SectionCard(k.label, action = {
        Icon(if (active) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked, null, tint = if (active) Up else MaterialTheme.colorScheme.onSurfaceVariant)
    }) {
        Text(k.adds, style = MaterialTheme.typography.bodySmall)
        Text(when {
            saved.isNotBlank() -> "Using your key"
            k.builtIn.isNotBlank() -> "Using the key built into the app"
            else -> "Not set"
        }, style = MaterialTheme.typography.labelSmall, color = if (active) Up else Gold, modifier = Modifier.padding(vertical = 4.dp))
        OutlinedTextField(text, { text = it.trim() }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("API key") },
            visualTransformation = if (show || text.isEmpty()) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                Row {
                    IconButton(onClick = { show = !show }) { Icon(if (show) Icons.Default.VisibilityOff else Icons.Default.Visibility, "Show key") }
                    IconButton(onClick = {
                        val clip = (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
                        clip?.getItemAt(0)?.coerceToText(ctx)?.toString()?.trim()?.let { text = it }
                    }) { Icon(Icons.Default.ContentPaste, "Paste") }
                }
            })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
            Button(onClick = {
                Prefs.update { it.copy(keys = if (text.isBlank()) it.keys - k.id else it.keys + (k.id to text)) }
                Net.clearMemory(); status = null
            }, enabled = text != saved) { Text("Save") }
            OutlinedButton(onClick = {
                testing = true; status = null
                scope.launch {
                    status = runCatching { testKey(k.id, text.ifBlank { Keys.get(k.id) }) }.getOrElse { false to (it.message ?: "Failed") }
                    testing = false
                }
            }, enabled = !testing && (text.isNotBlank() || active)) { Text(if (testing) "Testing…" else "Test") }
            if (saved.isNotBlank()) TextButton(onClick = { text = ""; Prefs.update { it.copy(keys = it.keys - k.id) } }) { Text("Remove") }
        }
        status?.let { (ok, msg) -> Text((if (ok) "✓ " else "✗ ") + msg, color = if (ok) Up else Down, style = MaterialTheme.typography.labelMedium) }
        TextButton(onClick = { openUrl(ctx, k.signup) }, contentPadding = PaddingValues(0.dp)) { Text("Get a free key →") }
    }
}

/** Makes one small request with [key] to confirm it works. */
private suspend fun testKey(id: String, key: String): Pair<Boolean, String> {
    if (key.isBlank()) return false to "No key entered"
    val url = when (id) {
        "FINNHUB" -> "https://finnhub.io/api/v1/quote?symbol=AAPL&token=$key"
        "TWELVEDATA" -> "https://api.twelvedata.com/quote?symbol=AAPL&apikey=$key"
        "COINSTATS" -> "https://openapiv1.coinstats.app/coins?limit=1"
        "FRED" -> "https://api.stlouisfed.org/fred/series/observations?series_id=FEDFUNDS&api_key=$key&file_type=json&limit=1&sort_order=desc"
        "FMP" -> "https://financialmodelingprep.com/stable/quote?symbol=AAPL&apikey=$key"
        "ALPHAVANTAGE" -> "https://www.alphavantage.co/query?function=GLOBAL_QUOTE&symbol=IBM&apikey=$key"
        "NEWSAPI" -> "https://newsapi.org/v2/top-headlines?country=us&pageSize=1&apiKey=$key"
        "MARKETAUX" -> "https://api.marketaux.com/v1/news/all?language=en&limit=1&api_token=$key"
        "POLYGON" -> "https://api.polygon.io/v2/aggs/ticker/AAPL/prev?apiKey=$key"
        else -> return false to "Unknown service"
    }
    val body = Net.get(url + (if (url.contains("?")) "&" else "?") + "_t=${System.currentTimeMillis()}", ttlMs = 0, allowStale = false,
        headers = if (id == "COINSTATS") mapOf("X-API-KEY" to key) else emptyMap())
    val low = body.lowercase()
    val bad = listOf("invalid api", "invalid key", "apikey is invalid", "error message", "\"status\":\"error\"", "not authorized", "unauthorized", "\"error\":", "the api key")
    if (bad.any { low.contains(it) } && !low.contains("\"c\":")) {
        val msg = runCatching { JSONObject(body).let { o -> o.optString("message").ifBlank { o.optString("Error Message") }.ifBlank { o.optString("error") } } }.getOrNull()
        return false to (msg?.takeIf { it.isNotBlank() }?.take(140) ?: "The service rejected this key")
    }
    if (low.contains("\"note\"") && id == "ALPHAVANTAGE") return true to "Key works (rate limit reached for now)"
    return true to "Key works"
}
