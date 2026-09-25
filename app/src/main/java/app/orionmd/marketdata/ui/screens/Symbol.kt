package app.orionmd.marketdata.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.orionmd.marketdata.data.*
import app.orionmd.marketdata.pdf.Reports
import app.orionmd.marketdata.ui.*
import app.orionmd.marketdata.ui.components.*
import coil.compose.AsyncImage
import androidx.compose.ui.focus.focusRequester
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

@Composable
fun SymbolScreen(symbol: String) {
    val nav = LocalNav.current
    val ctx = LocalContext.current
    LaunchedEffect(symbol) { Store.viewed(symbol) }
    val data by Store.data.collectAsState()
    val q = rememberLive(listOf(symbol))[symbol]
    val type = typeOf(symbol)
    val stock = type == AssetType.STOCK || type == AssetType.ETF
    val crypto = type == AssetType.CRYPTO
    val ext = rememberLoad("ext", symbol, refreshMs = 60_000) { if (stock && Prefs.current.showExtendedHours) Market.yahooQuote(symbol) else null }

    var range by rememberSaveableString("1D")
    var candles by remember { mutableStateOf(false) }
    var indicators by remember { mutableStateOf(setOf(Indicator.VOLUME)) }
    var draw by remember { mutableStateOf(false) }
    var section by rememberSaveableString("Overview")
    var showAlert by remember { mutableStateOf(false) }
    var showNote by remember { mutableStateOf(false) }
    val chartRange = ChartRange.entries.first { it.label == range }
    val chart = rememberLoad(symbol, range, refreshMs = if (chartRange == ChartRange.D1) 60_000 else 0) { Market.chart(symbol, chartRange) }
    val lines = data.trendlines[symbol].orEmpty()

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SectionCard {
                Text(q?.name ?: Catalog.nameOf(symbol) ?: symbol, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.Bottom) {
                    FlashText(q?.price, (if (crypto) "$" else "") + fmtPrice(q?.price), MaterialTheme.typography.headlineLarge, FontWeight.Bold)
                    Spacer(Modifier.width(10.dp))
                    Text("${fmtSigned(q?.change)} (${fmtPct(q?.changePct)})", color = changeColor(q?.change), fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 6.dp))
                }
                ext.data?.takeIf { it.extPrice != null }?.let { e ->
                    Text("${e.extLabel}: ${fmtPrice(e.extPrice)} (${fmtPct(e.extChangePct)})", color = changeColor(e.extChangePct), style = MaterialTheme.typography.labelLarge)
                }
                Text(listOfNotNull(q?.source?.let { "Source: $it" }, q?.proxyNote, q?.time?.let { "Updated ${fmtAgo(it)}" }).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    WatchlistButton(symbol)
                    IconButton(onClick = { showAlert = true }) { Icon(Icons.Default.NotificationAdd, "Alert") }
                    IconButton(onClick = { nav.go("compare?syms=${android.net.Uri.encode(symbol)}") }) { Icon(Icons.AutoMirrored.Filled.CompareArrows, "Compare") }
                    IconButton(onClick = { showNote = true }) { Icon(if (data.notes[symbol] != null) Icons.Default.StickyNote2 else Icons.Default.EditNote, "Note") }
                    IconButton(onClick = { nav.go("pdf/SYMBOL?arg=${android.net.Uri.encode(symbol)}") }) { Icon(Icons.Default.PictureAsPdf, "PDF") }
                    if (stock) IconButton(onClick = { nav.go("paper") }) { Icon(Icons.Default.School, "Paper trade") }
                }
                data.notes[symbol]?.let { Text("📝 $it", style = MaterialTheme.typography.bodySmall, modifier = Modifier.clickable { showNote = true }) }
            }
        }
        item {
            SectionCard {
                ChipRow(ChartRange.entries.map { it.label }, range, { range = it })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(!candles, { candles = false }, { Text("Line") }); Spacer(Modifier.width(6.dp))
                    FilterChip(candles, { candles = true }, { Text("Candles") }); Spacer(Modifier.weight(1f))
                    IconToggleButton(draw, { draw = it }) { Icon(Icons.Default.Draw, "Draw trendline", tint = if (draw) Gold else LocalContentColor.current) }
                    if (lines.isNotEmpty()) IconButton(onClick = { Store.update { it.copy(trendlines = it.trendlines - symbol) } }) { Icon(Icons.Default.LayersClear, "Clear lines") }
                }
                LoadContent(chart, "No chart data for this range") { c ->
                    PriceChart(c, candles, indicators, lines, draw, { tl ->
                        Store.update { d -> d.copy(trendlines = d.trendlines + (symbol to (d.trendlines[symbol].orEmpty() + tl))) }
                        draw = false
                    }, chartRange == ChartRange.D1 || chartRange == ChartRange.W1, if (chartRange == ChartRange.D1) q?.prevClose else null)
                    val first = c.first().c; val last = q?.price ?: c.last().c
                    Text("$range change: ${fmtPct((last / first - 1) * 100)}", color = changeColor(last - first), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Indicator.entries.forEach { ind ->
                        FilterChip(ind in indicators, { indicators = if (ind in indicators) indicators - ind else indicators + ind },
                            { Text(ind.label) }, leadingIcon = { Dot(ind.color) })
                    }
                }
            }
        }
        item {
            val sections = if (stock) listOf("Overview", "Analysts", "Insiders", "Dividends", "News", "Filings") + (if (type == AssetType.ETF) listOf("Holdings") else emptyList())
            else if (crypto) listOf("Overview", "News") else listOf("Overview", "News")
            ChipRow(sections, section, { section = it })
        }
        item {
            when (section) {
                "Overview" -> when {
                    stock -> StockOverview(symbol, q)
                    crypto -> CoinOverview(symbol, q)
                    else -> SectionCard("Statistics") { QuoteStats(q) }
                }
                "Analysts" -> AnalystsSection(symbol, q)
                "Insiders" -> InsidersSection(symbol)
                "Dividends" -> DividendsSection(symbol)
                "News" -> SymbolNews(symbol)
                "Filings" -> SectionCard("SEC filings") {
                    val f = rememberLoad("filings", symbol) { Market.filings(symbol).take(12) }
                    LoadContent(f) { list -> list.forEach { FilingRow(it) } }
                    TextButton(onClick = { nav.go("filings/${android.net.Uri.encode(symbol)}") }) { Text("All filings") }
                    TextButton(onClick = { openUrl(ctx, "https://www.sec.gov/cgi-bin/browse-edgar?action=getcompany&CIK=$symbol&type=&dateb=&owner=include&count=40") }) { Text("Open on SEC EDGAR") }
                }
                "Holdings" -> EtfHoldingsSection(symbol)
            }
        }
    }
    if (showAlert) AddAlertDialog(symbol, q?.price) { showAlert = false }
    if (showNote) NoteDialog(symbol) { showNote = false }
}

@Composable
private fun QuoteStats(q: Quote?) {
    KeyValueGrid(listOf(
        "Open" to fmtPrice(q?.open), "Prev close" to fmtPrice(q?.prevClose),
        "Day high" to fmtPrice(q?.high), "Day low" to fmtPrice(q?.low),
        "52-wk high" to fmtPrice(q?.high52), "52-wk low" to fmtPrice(q?.low52),
        "Volume" to fmtBig(q?.volume), "Market cap" to fmtBig(q?.marketCap),
    ))
}

@Composable
private fun StockOverview(symbol: String, q: Quote?) {
    val ctx = LocalContext.current
    val nav = LocalNav.current
    val prof = rememberLoad("prof", symbol) { Market.profile(symbol) }
    val met = rememberLoad("met", symbol) { Market.metrics(symbol) }
    val peers = rememberLoad("peers", symbol) { Market.peers(symbol) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard("Key statistics") {
            QuoteStats(q)
            met.data?.let { m -> Spacer(Modifier.height(8.dp)); KeyValueGrid(Reports.keyStats(m)) }
            if (met.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        prof.data?.let { p ->
            SectionCard("Profile") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (p.logo != null) AsyncImage(p.logo, null, Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(Color.White))
                    Column(Modifier.padding(start = 10.dp)) {
                        Text(p.name, fontWeight = FontWeight.Bold)
                        Text("${p.industry} · ${p.exchange}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(8.dp))
                KeyValueGrid(listOf("Country" to p.country, "IPO" to p.ipo, "Market cap" to fmtBig(p.marketCap), "Shares out." to fmtBig(p.shares)))
                if (p.website.isNotBlank()) TextButton(onClick = { openUrl(ctx, p.website) }, contentPadding = PaddingValues(0.dp)) { Text(p.website) }
            }
        }
        peers.data?.takeIf { it.isNotEmpty() }?.let { ps ->
            SectionCard("Peers") {
                val pq = rememberLive(ps.take(8))
                ps.take(8).forEach { s -> QuoteRow(s, pq[s], { nav.symbol(s) }, showSpark = false) }
            }
        }
    }
}

@Composable
private fun CoinOverview(symbol: String, q: Quote?) {
    val d = rememberLoad("coindetail", symbol) { Market.coinDetail(symbol) }
    val ctx = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    SectionCard("Statistics") {
        LoadContent(d, "No details for this coin", isEmpty = { false }) { c ->
            if (c == null) { QuoteStats(q); return@LoadContent }
            KeyValueGrid(listOf(
                "Rank" to "#${c.rank ?: "—"}", "Market cap" to fmtBig(c.marketCap),
                "24h volume" to fmtBig(c.volume), "Circulating" to fmtBig(c.circulating),
                "Total supply" to fmtBig(c.totalSupply), "Max supply" to fmtBig(c.maxSupply),
                "All-time high" to fmtPrice(c.ath), "From ATH" to fmtPct(c.athChangePct),
                "7 days" to fmtPct(c.ch7d), "30 days" to fmtPct(c.ch30d), "1 year" to fmtPct(c.ch1y), "Day range" to "${fmtPrice(q?.low)}–${fmtPrice(q?.high)}",
            ))
            if (c.categories.isNotEmpty()) Text(c.categories.take(4).joinToString(" · "), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 8.dp))
            if (c.description.isNotBlank()) {
                Text(c.description, style = MaterialTheme.typography.bodySmall, maxLines = if (expanded) 100 else 5, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp).clickable { expanded = !expanded })
            }
            if (c.homepage.isNotBlank()) TextButton(onClick = { openUrl(ctx, c.homepage) }, contentPadding = PaddingValues(0.dp)) { Text(c.homepage) }
        }
    }
}

@Composable
private fun AnalystsSection(symbol: String, q: Quote?) {
    val recs = rememberLoad("recs", symbol) { Market.recommendations(symbol) }
    val pt = rememberLoad("pt", symbol) { Market.priceTarget(symbol) }
    val ins = rememberLoad("inssent", symbol) { Market.insiderSentiment(symbol) }
    val sent = rememberLoad("newssent", symbol) { Market.newsSentiment(symbol) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard("Analyst recommendations") {
            LoadContent(recs, "No analyst coverage") { list ->
                val r = list.first()
                val total = (r.strongBuy + r.buy + r.hold + r.sell + r.strongSell).coerceAtLeast(1)
                val score = (r.strongBuy * 5 + r.buy * 4 + r.hold * 3 + r.sell * 2 + r.strongSell).toDouble() / total
                val label = when { score >= 4.3 -> "Strong buy"; score >= 3.6 -> "Buy"; score >= 2.6 -> "Hold"; score >= 1.8 -> "Sell"; else -> "Strong sell" }
                Text("Consensus: $label  (${total} analysts, ${r.period})", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                listOf("Strong buy" to r.strongBuy to Up, "Buy" to r.buy to Color(0xFF84CC16), "Hold" to r.hold to Gold, "Sell" to r.sell to Color(0xFFF97316), "Strong sell" to r.strongSell to Down)
                    .forEach { (lv, c) ->
                        val (l, v) = lv
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                            Text(l, Modifier.width(90.dp), style = MaterialTheme.typography.labelMedium)
                            Box(Modifier.weight(1f).height(14.dp)) {
                                Box(Modifier.fillMaxHeight().fillMaxWidth(v.toFloat() / total).clip(RoundedCornerShape(4.dp)).background(c))
                            }
                            Text("$v", Modifier.width(32.dp), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                if (list.size > 1) Text("History: " + list.take(4).joinToString("  ") { "${it.period.take(7)}: ${it.strongBuy + it.buy}B/${it.hold}H/${it.sell + it.strongSell}S" },
                    style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 6.dp))
            }
        }
        SectionCard("Price target") {
            when {
                pt.loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                pt.data == null -> KeyNeeded("Financial Modeling Prep", "Price targets")
                else -> pt.data.let { t ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatTile("Low", fmtPrice(t.low), Modifier.weight(1f))
                        StatTile("Consensus", fmtPrice(t.consensus), Modifier.weight(1f), q?.let { qq -> t.consensus?.let { fmtPct((it / qq.price - 1) * 100) } }, changeColor(q?.let { t.consensus?.minus(it.price) }))
                        StatTile("High", fmtPrice(t.high), Modifier.weight(1f))
                    }
                }
            }
        }
        SectionCard("Sentiment") {
            Text("Insider sentiment (MSPR, −100 to +100)", style = MaterialTheme.typography.labelMedium)
            LoadContent(ins, "No insider sentiment data") { list ->
                Row(Modifier.fillMaxWidth().height(70.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                    list.takeLast(12).forEach { (_, v) ->
                        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = if (v >= 0) Alignment.BottomCenter else Alignment.TopCenter) {
                            Box(Modifier.fillMaxWidth().fillMaxHeight((kotlin.math.abs(v) / 100).toFloat().coerceIn(0.03f, 0.5f)).background(changeColor(v)))
                        }
                    }
                }
                Text("Latest: ${fmtNum(list.lastOrNull()?.second)} (${list.lastOrNull()?.first ?: ""})", style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.height(8.dp))
            Text("News sentiment", style = MaterialTheme.typography.labelMedium)
            if (!Keys.has("MARKETAUX")) KeyNeeded("Marketaux", "News sentiment")
            else sent.data?.let { s ->
                val (l, c) = when { s > 0.15 -> "Bullish" to Up; s < -0.15 -> "Bearish" to Down; else -> "Neutral" to Gold }
                Text("$l (${fmtNum(s, 2)})", color = c, fontWeight = FontWeight.Bold)
            } ?: Text(if (sent.loading) "Loading…" else "No recent scored news", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun InsidersSection(symbol: String) {
    val d = rememberLoad("ins", symbol) { Market.insiderTrades(symbol) }
    SectionCard("Insider transactions") {
        LoadContent(d, "No insider trades reported") { list ->
            list.take(30).forEach { t ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(t.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${t.date} · ${codeLabel(t.code)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text((if (t.change >= 0) "+" else "") + fmtBig(t.change), color = changeColor(t.change), fontWeight = FontWeight.Bold)
                        Text(t.price?.let { "@ ${fmtPrice(it)}" } ?: "", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

private fun codeLabel(c: String) = when (c) {
    "P" -> "Open-market buy"; "S" -> "Open-market sale"; "M" -> "Option exercise"; "A" -> "Grant/award"; "F" -> "Tax withholding"
    "G" -> "Gift"; "D" -> "Disposition"; "C" -> "Conversion"; "X" -> "Option exercise"; else -> "Code $c"
}

@Composable
private fun DividendsSection(symbol: String) {
    val d = rememberLoad("divs", symbol) { Market.dividends(symbol) }
    SectionCard("Dividend history") {
        LoadContent(d, "No dividends in the last 5 years") { list ->
            val lastYear = list.filter { it.date >= java.time.LocalDate.now().minusYears(1).toString() }.sumOf { it.amount ?: 0.0 }
            Text("Paid in the last 12 months: ${fmtPrice(lastYear)} per share", fontWeight = FontWeight.SemiBold)
            if (list.size > 2) SimpleLineChart(list.reversed().mapNotNull { it.amount }, Modifier.fillMaxWidth().height(70.dp).padding(vertical = 6.dp), Gold, true)
            list.take(20).forEach { dv ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) { Text(dv.date, Modifier.weight(1f)); Text(fmtPrice(dv.amount), fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

@Composable
private fun EtfHoldingsSection(symbol: String) {
    val nav = LocalNav.current
    if (!Keys.has("FMP")) { SectionCard("Top holdings") { KeyNeeded("Financial Modeling Prep", "ETF holdings") }; return }
    val d = rememberLoad("etfh", symbol) { Market.etfHoldings(symbol) }
    SectionCard("Top holdings") {
        LoadContent(d) { list ->
            list.forEach { h ->
                Row(Modifier.fillMaxWidth().clickable { nav.symbol(h.symbol) }.padding(vertical = 5.dp)) {
                    Text(h.symbol, fontWeight = FontWeight.Bold, modifier = Modifier.width(70.dp)); Text(h.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(fmtNum(h.weight) + "%")
                }
            }
        }
    }
}

@Composable
private fun SymbolNews(symbol: String) {
    val d = rememberLoad("cnews", symbol, refreshMs = 5 * 60_000) { Market.companyNews(symbol) }
    SectionCard("News") { LoadContent(d, "No recent news") { list -> list.take(30).forEach { NewsRow(it) } } }
}

@Composable
fun FilingRow(f: Filing) {
    val ctx = LocalContext.current
    Row(Modifier.fillMaxWidth().clickable { openUrl(ctx, f.url) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Pill(f.form.ifBlank { "?" }); Spacer(Modifier.width(10.dp))
        Text(when (f.form) { "10-K" -> "Annual report"; "10-Q" -> "Quarterly report"; "8-K" -> "Current report"; "4" -> "Insider transaction"; "DEF 14A" -> "Proxy statement"; "S-1" -> "Registration"; "13F-HR" -> "Institutional holdings"; else -> "Filing" },
            Modifier.weight(1f))
        Text(f.filed, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun FilingsScreen(symbol: String) {
    var filter by rememberSaveableString("All")
    val d = rememberLoad("filings", symbol) { Market.filings(symbol) }
    Column(Modifier.fillMaxSize()) {
        ChipRow(listOf("All", "10-K", "10-Q", "8-K", "4"), filter, { filter = it }, Modifier.padding(horizontal = 12.dp))
        LazyColumn(contentPadding = PaddingValues(12.dp)) {
            item { LoadContent(d) {} }
            items(d.data.orEmpty().filter { filter == "All" || it.form == filter }) { FilingRow(it) }
        }
    }
}

// ---------- shared symbol actions ----------

@Composable
fun WatchlistButton(symbol: String) {
    val d by Store.data.collectAsState()
    var open by remember { mutableStateOf(false) }
    val inAny = d.watchlists.any { symbol in it.symbols }
    Box {
        IconButton(onClick = { open = true }) { Icon(if (inAny) Icons.Default.Star else Icons.Default.StarBorder, "Watchlist", tint = if (inAny) Gold else LocalContentColor.current) }
        DropdownMenu(open, { open = false }) {
            d.watchlists.forEach { w ->
                val has = symbol in w.symbols
                DropdownMenuItem(text = { Text(w.name) }, leadingIcon = { Icon(if (has) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank, null) },
                    onClick = { if (has) Store.removeFromWatchlist(w.id, symbol) else Store.addToWatchlist(w.id, symbol) })
            }
        }
    }
}

@Composable
fun NoteDialog(symbol: String, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(Store.current.notes[symbol].orEmpty()) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Note · ${Catalog.display(symbol)}") },
        text = { OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().heightIn(min = 140.dp), placeholder = { Text("Your thesis, targets, reminders…") }) },
        confirmButton = { TextButton(onClick = { Store.setNote(symbol, text); onDismiss() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = { Store.setNote(symbol, ""); onDismiss() }) { Text("Delete") } })
}

// ============================ Search ============================

@Composable
fun SearchScreen() {
    val nav = LocalNav.current
    val d by Store.data.collectAsState()
    var q by remember { mutableStateOf("") }
    val res = rememberLoad(q) { delay(300); if (q.isBlank()) emptyList() else Market.search(q) }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth().focusRequester(focus), singleLine = true,
            placeholder = { Text("Stocks, ETFs, indices, crypto…") }, leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = { if (q.isNotEmpty()) IconButton(onClick = { q = "" }) { Icon(Icons.Default.Close, "Clear") } })
        if (res.loading && q.isNotBlank()) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(Modifier.fillMaxSize()) {
            if (q.isBlank()) {
                if (d.recent.isNotEmpty()) {
                    item { Text("Recently viewed", Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.titleSmall) }
                    items(d.recent) { s -> ResultRow(SearchResult(s, Catalog.nameOf(s) ?: "", "")) { nav.symbol(s) } }
                }
                item { Text("Popular", Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.titleSmall) }
                items(listOf("^GSPC", "^DJI", "^IXIC", "SPY", "QQQ", "AAPL", "NVDA", "TSLA", "BTC-USD", "ETH-USD", "GC=F", "CL=F")) { s ->
                    ResultRow(SearchResult(s, Catalog.nameOf(s) ?: "", typeOf(s).name.lowercase())) { nav.symbol(s) }
                }
            } else {
                items(res.data.orEmpty(), key = { it.symbol }) { r -> ResultRow(r) { nav.symbol(r.symbol) } }
                item { TextButton(onClick = { nav.symbol(q.trim().uppercase()) }) { Text("Open \"${q.trim().uppercase()}\"") } }
            }
        }
    }
}

@Composable
private fun ResultRow(r: SearchResult, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(Catalog.display(r.symbol), fontWeight = FontWeight.Bold, modifier = Modifier.width(96.dp))
        Text(r.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (r.kind.isNotBlank()) Pill(r.kind.take(14), MaterialTheme.colorScheme.onSurfaceVariant)
        WatchlistButton(r.symbol)
    }
}

// ============================ Compare ============================

@Composable
fun CompareScreen(initial: List<String>) {
    val nav = LocalNav.current
    var syms by remember { mutableStateOf(initial.ifEmpty { listOf("SPY", "QQQ") }.take(4)) }
    var range by rememberSaveableString("1Y")
    var pick by remember { mutableStateOf(false) }
    val r = ChartRange.entries.first { it.label == range }
    val data = rememberLoad(syms.joinToString(), range) {
        coroutineScope { syms.map { s -> async { s to runCatching { Market.chart(s, r) }.getOrDefault(emptyList<Candle>()) } }.map { it.await() } }
    }
    val q = rememberLive(syms)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SectionCard("Compare up to 4 symbols") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    syms.forEachIndexed { i, s ->
                        InputChip(true, { nav.symbol(s) }, { Text(Catalog.display(s)) }, leadingIcon = { Dot(SeriesColors[i]) },
                            trailingIcon = { Icon(Icons.Default.Close, "Remove", Modifier.size(16.dp).clickable { syms = syms - s }) })
                    }
                    if (syms.size < 4) AssistChip(onClick = { pick = true }, label = { Text("Add") }, leadingIcon = { Icon(Icons.Default.Add, null) })
                }
                ChipRow(ChartRange.entries.map { it.label }, range, { range = it })
                LoadContent(data) { series ->
                    CompareChart(series.filter { it.second.size > 1 })
                    series.forEachIndexed { i, (s, c) ->
                        val ret = if (c.size > 1) (c.last().c / c.first().c - 1) * 100 else null
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Dot(SeriesColors[i], 10.dp); Spacer(Modifier.width(8.dp))
                            Text(Catalog.display(s), fontWeight = FontWeight.Bold, modifier = Modifier.width(80.dp))
                            Text(fmtPrice(q[s]?.price), Modifier.weight(1f))
                            Text("$range ${fmtPct(ret)}", color = changeColor(ret), fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
        item { OutlinedButton(onClick = { nav.go("pdf/COMPARE?arg=${android.net.Uri.encode(syms.joinToString(","))}") }, Modifier.fillMaxWidth()) { Icon(Icons.Default.PictureAsPdf, null); Spacer(Modifier.width(8.dp)); Text("Comparison PDF") } }
    }
    if (pick) SymbolPicker("Add to comparison", { pick = false }) { s -> if (s !in syms) syms = syms + s; pick = false }
}
