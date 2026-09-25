package app.orionmd.marketdata.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.orionmd.marketdata.data.*
import app.orionmd.marketdata.ui.*
import app.orionmd.marketdata.ui.components.*
import coil.compose.AsyncImage
import kotlinx.coroutines.async

// ============================ Crypto ============================

@Composable
fun CryptoScreen() {
    val nav = LocalNav.current
    var tab by rememberSaveableString("Top 100")
    val coins = rememberLoad("coins", refreshMs = 60_000) { Market.coins(1, 100) }
    Column(Modifier.fillMaxSize()) {
        ChipRow(listOf("Top 100", "Heatmap", "Trending", "Signals", "Overview"), tab, { tab = it }, Modifier.padding(horizontal = 12.dp))
        when (tab) {
            "Top 100" -> {
                // stream prices for the top coins that trade on Coinbase
                val live = rememberLive(coins.data.orEmpty().take(25).map { it.ticker })
                LazyColumn(contentPadding = PaddingValues(12.dp)) {
                    item { LoadContent(coins) {} }
                    items(coins.data.orEmpty(), key = { it.id }) { c -> CoinRow(c, live[c.ticker]) { nav.symbol(c.ticker) } }
                }
            }
            "Heatmap" -> LazyColumn(contentPadding = PaddingValues(12.dp)) {
                item {
                    LoadContent(coins) { list ->
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), maxItemsInEachRow = 4) {
                            list.take(48).forEach { c ->
                                HeatTile(c.symbol.uppercase(), c.changePct24h, Modifier.weight(1f).height(if ((c.rank ?: 99) <= 4) 76.dp else 56.dp), fmtBig(c.marketCap)) { nav.symbol(c.ticker) }
                            }
                        }
                    }
                }
            }
            "Trending" -> {
                val t = rememberLoad("trending") { Market.trendingCoins() }
                LazyColumn(contentPadding = PaddingValues(12.dp)) {
                    item { LoadContent(t) {} }
                    items(t.data.orEmpty(), key = { it.id }) { c -> CoinRow(c, null) { nav.symbol(c.ticker) } }
                }
            }
            "Overview" -> CryptoOverview()
            "Signals" -> LaunchedEffect(Unit) { tab = "Top 100"; nav.go("signals") }
        }
    }
}

@Composable
private fun CoinRow(c: Coin, live: Quote?, onClick: () -> Unit) {
    val price = live?.price ?: c.price
    val pct = live?.changePct ?: c.changePct24h
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("${c.rank ?: ""}", Modifier.width(28.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        AsyncImage(c.image, null, Modifier.size(28.dp).clip(CircleShape))
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.symbol.uppercase(), fontWeight = FontWeight.Bold)
                val sig = remember(c.id, c.sparkline.size) { if (Prefs.current.showSignals) Signals.forCoin(c) else null }
                if (sig != null) { Spacer(Modifier.width(6.dp)); SignalBadge(sig) }
            }
            Text(c.name + (c.marketCap?.let { " · ${fmtBig(it)}" } ?: ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (c.sparkline.size > 2) Sparkline(c.sparkline, Modifier.width(60.dp).height(26.dp).padding(horizontal = 4.dp))
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.widthIn(min = 90.dp)) {
            FlashText(price, "$" + fmtPrice(price))
            ChangePill(pct)
        }
    }
}

@Composable
private fun CryptoOverview() {
    val g = rememberLoad("cglobal", refreshMs = 5 * 60_000) { Market.cryptoGlobal() }
    val fg = rememberLoad("fgc") { Market.cryptoFearGreed() }
    val gas = rememberLoad("gas", refreshMs = 60_000) { Market.ethGasGwei() }
    val nav = LocalNav.current
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SectionCard("Market") {
                LoadContent(g) { d ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatTile("Total market cap", fmtBig(d.totalCap), Modifier.weight(1f), fmtPct(d.capChangePct), changeColor(d.capChangePct))
                            StatTile("24h volume", fmtBig(d.totalVol), Modifier.weight(1f))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatTile("BTC dominance", fmtNum(d.btcDominance) + "%", Modifier.weight(1f))
                            StatTile("ETH dominance", fmtNum(d.ethDominance) + "%", Modifier.weight(1f))
                            StatTile("Coins", "%,d".format(d.activeCoins), Modifier.weight(1f))
                        }
                    }
                }
            }
        }
        item {
            SectionCard("Fear & Greed (crypto)") {
                LoadContent(fg) { f ->
                    FearGreedGauge(f.value, f.label, Modifier.fillMaxWidth().height(110.dp))
                    if (f.history.size > 2) { Text("Last 30 days", style = MaterialTheme.typography.labelSmall); SimpleLineChart(f.history.map { it.toDouble() }, Modifier.fillMaxWidth().height(60.dp), Gold) }
                }
            }
        }
        item {
            SectionCard("Ethereum gas") {
                LoadContent(gas) { v -> Text("${fmtNum(v, 2)} gwei", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(when { v < 5 -> "Low"; v < 20 -> "Normal"; else -> "High" } + " · from a public Ethereum node", style = MaterialTheme.typography.labelSmall) }
            }
        }
        item {
            SectionCard("More") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = { nav.go("converter") }, label = { Text("Converter") })
                    AssistChip(onClick = { nav.go("exchanges") }, label = { Text("Exchanges") })
                    AssistChip(onClick = { nav.go("compare?syms=BTC-USD,ETH-USD,SOL-USD") }, label = { Text("Compare BTC/ETH/SOL") })
                }
            }
        }
    }
}

@Composable
fun ExchangesScreen() {
    val ctx = LocalContext.current
    val ex = rememberLoad("exchanges") { Market.exchanges() }
    LazyColumn(contentPadding = PaddingValues(12.dp)) {
        item { LoadContent(ex) {} }
        items(ex.data.orEmpty()) { e ->
            Row(Modifier.fillMaxWidth().clickable { openUrl(ctx, e.url) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(e.image, null, Modifier.size(28.dp).clip(CircleShape))
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(e.name, fontWeight = FontWeight.Bold)
                    Text(e.country.ifBlank { "—" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("₿ ${fmtBig(e.volumeBtc)}", fontWeight = FontWeight.SemiBold)
                    Text("Trust ${e.trust ?: "—"}/10", style = MaterialTheme.typography.labelSmall, color = if ((e.trust ?: 0) >= 8) Up else Gold)
                }
            }
        }
    }
}

@Composable
fun ConverterScreen() {
    val fiat = rememberLoad("fiat") { Market.fiatRates() }
    val coins = rememberLoad("convcoins") { Market.coins(1, 100) }
    var amount by remember { mutableStateOf("1") }
    var from by remember { mutableStateOf("BTC") }
    var to by remember { mutableStateOf("USD") }
    // USD value of one unit
    val usd: Map<String, Double> = remember(fiat.data, coins.data) {
        val m = mutableMapOf<String, Double>()
        fiat.data?.forEach { (k, v) -> if (v > 0) m[k] = 1 / v }
        coins.data?.forEach { c -> m.putIfAbsent(c.symbol.uppercase(), c.price) }
        m
    }
    val codes = listOf("USD", "EUR", "GBP", "JPY", "CAD", "AUD", "CHF", "CNY", "MXN", "INR") +
        (coins.data?.take(30)?.map { it.symbol.uppercase() } ?: listOf("BTC", "ETH", "SOL"))
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard("Currency & crypto converter") {
            OutlinedTextField(amount, { amount = it }, label = { Text("Amount") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CodePicker(from, codes, Modifier.weight(1f)) { from = it }
                IconButton(onClick = { val t = from; from = to; to = t }) { Icon(Icons.Default.SwapVert, "Swap") }
                CodePicker(to, codes, Modifier.weight(1f)) { to = it }
            }
            Spacer(Modifier.height(12.dp))
            val a = amount.replace(",", "").toDoubleOrNull()
            val f = usd[from]; val t = usd[to]
            if (fiat.loading || coins.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (a != null && f != null && t != null) {
                val r = a * f / t
                Text("${fmtNum(a, 4).trimEnd('0').trimEnd('.')} $from =", style = MaterialTheme.typography.bodyLarge)
                Text("${fmtPrice(r)} $to", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text("1 $from = ${fmtPrice(f / t)} $to", style = MaterialTheme.typography.labelMedium)
            }
        }
        Text("Fiat rates: European Central Bank via Frankfurter (daily). Crypto: CoinGecko.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CodePicker(value: String, codes: List<String>, modifier: Modifier, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { open = true }, Modifier.fillMaxWidth()) { Text(value, fontWeight = FontWeight.Bold) }
        DropdownMenu(open, { open = false }, Modifier.heightIn(max = 360.dp)) {
            codes.distinct().forEach { c -> DropdownMenuItem(text = { Text(c) }, onClick = { onPick(c); open = false }) }
        }
    }
}

// ============================ News ============================

@Composable
fun NewsScreen() {
    val cats = linkedMapOf("Top" to "general", "Markets" to "markets", "Business" to "business", "Crypto" to "crypto", "Mergers" to "merger", "Forex" to "forex", "My watchlist" to "watch")
    var tab by rememberSaveableString("Top")
    Column(Modifier.fillMaxSize()) {
        ChipRow(cats.keys.toList(), tab, { tab = it }, Modifier.padding(horizontal = 12.dp))
        val cat = cats.getValue(tab)
        val d = rememberLoad(cat, refreshMs = 5 * 60_000) {
            if (cat == "watch") {
                val syms = Store.current.watchlists.firstOrNull()?.symbols.orEmpty().take(8)
                kotlinx.coroutines.coroutineScope {
                    syms.map { s -> async { runCatching { Market.companyNews(s).take(6) }.getOrDefault(emptyList()) } }
                        .flatMap { it.await() }.distinctBy { it.title }.sortedByDescending { it.time }
                }
            } else Market.news(cat)
        }
        if (!Keys.has("MARKETAUX") && !Keys.has("NEWSAPI") && tab == "Top") {
            Text("Tip: add a free NewsAPI or Marketaux key (More → API keys) for more headlines and sentiment.",
                Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        androidx.compose.material3.pulltorefresh.PullToRefreshBox(d.loading && d.data != null, { Net.clearMemory(); d.reload() }, Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
            item { LoadContent(d, "No headlines right now") {} }
            d.data?.takeIf { it.isNotEmpty() }?.let { list ->
                item {
                    Text("${list.size} headlines · ${list.map { it.source }.distinct().size} sources · pull down to refresh",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(d.data.orEmpty()) { n ->
                SectionCard(modifier = Modifier.padding(vertical = 4.dp)) {
                    NewsRow(n)
                    if (n.summary.isNotBlank()) Text(n.summary, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (n.related.isNotBlank()) {
                        val nav = LocalNav.current
                        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            n.related.split(",").filter { it.isNotBlank() && it.length <= 6 }.take(4).forEach { s ->
                                AssistChip(onClick = { nav.symbol(s.trim()) }, label = { Text(s.trim()) })
                            }
                        }
                    }
                }
            }
        }
        }
    }
}

