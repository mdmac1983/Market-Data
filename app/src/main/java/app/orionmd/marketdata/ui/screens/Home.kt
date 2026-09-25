package app.orionmd.marketdata.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.orionmd.marketdata.data.*
import app.orionmd.marketdata.ui.*
import app.orionmd.marketdata.ui.components.*
import kotlinx.coroutines.delay
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import java.time.ZonedDateTime

// ============================ Dashboard ============================

@Composable
fun DashboardScreen() {
    val data by Store.data.collectAsState()
    val settings by Prefs.settings.collectAsState()
    var editing by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableIntStateOf(0) }
    LaunchedEffect(refreshing) { if (refreshing) { QuoteHub.refreshNow(); refreshKey++; delay(900); refreshing = false } }

    if (editing) { DashboardEditor(data.dashboard) { editing = false }; return }

    val cards = data.dashboard.mapNotNull { n -> DashboardCard.entries.firstOrNull { it.name == n } }
    PullToRefreshBox(refreshing, { refreshing = true }, Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val cols = dashColumns(settings, maxWidth)
            LazyVerticalGrid(
                GridCells.Fixed(cols), Modifier.fillMaxSize(),
                contentPadding = PaddingValues(if (settings.cardSize == 2) 8.dp else 12.dp),
                horizontalArrangement = Arrangement.spacedBy(LocalSpacing.current.gap),
                verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.gap),
            ) {
                items(cards, key = { it.name }, span = { c -> GridItemSpan(if (cols > 1 && c.name in data.dashHalf) 1 else maxLineSpan) }) { c ->
                    val collapsed = c.name in data.dashCollapsed
                    CompositionLocalProvider(LocalCardCollapse provides CardCollapse(collapsed) {
                        Store.update { d -> d.copy(dashCollapsed = if (c.name in d.dashCollapsed) d.dashCollapsed - c.name else d.dashCollapsed + c.name) }
                    }) { key(refreshKey) { DashCard(c) } }
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        CardSizeControls(settings)
                        OutlinedButton(onClick = { editing = true }, Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Edit, null); Spacer(Modifier.width(8.dp)); Text("Edit dashboard")
                        }
                    }
                }
            }
        }
    }
}

/** Columns for the dashboard grid: the user's choice, or automatic from width and card size. */
fun dashColumns(s: Settings, width: androidx.compose.ui.unit.Dp): Int {
    if (s.dashColumns > 0) return s.dashColumns
    val min = when (s.cardSize) { 2 -> 170.dp; 1 -> 300.dp; else -> 360.dp }
    return ((width - 24.dp) / min).toInt().coerceIn(1, 4)
}

/** Card size (Large / Medium / Small) and column count, shown on the dashboard and in Settings. */
@Composable
fun CardSizeControls(s: Settings) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Card size", Modifier.width(80.dp), style = MaterialTheme.typography.labelLarge)
        ChipRow(listOf("Large", "Medium", "Small"), listOf("Large", "Medium", "Small")[s.cardSize.coerceIn(0, 2)],
            { v -> Prefs.update { it.copy(cardSize = listOf("Large", "Medium", "Small").indexOf(v)) } })
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Columns", Modifier.width(80.dp), style = MaterialTheme.typography.labelLarge)
        ChipRow(listOf("Auto", "1", "2", "3"), if (s.dashColumns == 0) "Auto" else s.dashColumns.toString(),
            { v -> Prefs.update { it.copy(dashColumns = if (v == "Auto") 0 else v.toInt()) } })
    }
}

@Composable
private fun DashboardEditor(current: List<String>, done: () -> Unit) {
    val data by Store.data.collectAsState()
    val settings by Prefs.settings.collectAsState()
    var list by remember { mutableStateOf(current) }
    val state = rememberLazyListState()
    val reorder = rememberReorderableLazyListState(state) { from, to ->
        val f = list.indexOf(from.key as String); val t = list.indexOf(to.key as String)
        if (f >= 0 && t >= 0) list = list.toMutableList().apply { add(t, removeAt(f)) }
    }
    val hidden = DashboardCard.entries.filter { it.name !in list }
    fun toggle(sel: (AppData) -> List<String>, set: (AppData, List<String>) -> AppData, name: String) =
        Store.update { d -> set(d, if (name in sel(d)) sel(d) - name else sel(d) + name) }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Drag ≡ to reorder, − to remove, ◧ for half width, ˄ to collapse.", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { list = DashboardCard.defaults }) { Text("Reset") }
            Button(onClick = { Store.update { it.copy(dashboard = list) }; done() }) { Text("Done") }
        }
        CardSizeControls(settings)
        LazyColumn(Modifier.weight(1f), state = state, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(list, key = { it }) { name ->
                ReorderableItem(reorder, key = name) { dragging ->
                    val card = DashboardCard.valueOf(name)
                    val half = name in data.dashHalf; val collapsed = name in data.dashCollapsed
                    Surface(tonalElevation = if (dragging) 8.dp else 1.dp, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = {}, modifier = Modifier.draggableHandle()) { Icon(Icons.Default.DragHandle, "Drag") }
                            Column(Modifier.weight(1f)) {
                                Text(card.title, fontWeight = FontWeight.SemiBold)
                                Text(listOf(if (half) "Half width" else "Full width", if (collapsed) "collapsed" else "").filter { it.isNotBlank() }.joinToString(" · "),
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconToggleButton(half, { toggle({ it.dashHalf }, { d, l -> d.copy(dashHalf = l) }, name) }) {
                                Icon(Icons.Default.VerticalSplit, "Half width", tint = if (half) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconToggleButton(collapsed, { toggle({ it.dashCollapsed }, { d, l -> d.copy(dashCollapsed = l) }, name) }) {
                                Icon(if (collapsed) Icons.Default.ExpandMore else Icons.Default.ExpandLess, "Collapse",
                                    tint = if (collapsed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { list = list - name }) { Icon(Icons.Default.RemoveCircleOutline, "Remove", tint = Down) }
                        }
                    }
                }
            }
            if (hidden.isNotEmpty()) {
                item(key = "__add_header") { Text("Add cards", Modifier.padding(top = 16.dp, bottom = 4.dp), style = MaterialTheme.typography.titleSmall) }
                items(hidden, key = { "add_" + it.name }) { c ->
                    Surface(shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().clickable { list = list + c.name }) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.AddCircleOutline, null, tint = Up); Spacer(Modifier.width(12.dp)); Text(c.title)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DashCard(c: DashboardCard) {
    val nav = LocalNav.current
    when (c) {
        DashboardCard.STATUS -> MarketStatusCard()
        DashboardCard.INDICES -> SectionCard(c.title, onTitleClick = { nav.go("markets") }) {
            val q = rememberLive(Catalog.usIndices); QuoteTileGrid(Catalog.usIndices, q)
        }
        DashboardCard.FUTURES -> SectionCard(c.title) { val q = rememberLive(Catalog.futures); QuoteTileGrid(Catalog.futures, q, 2) }
        DashboardCard.WATCHLIST -> {
            val lists by Store.data.collectAsState()
            val wl = lists.watchlists.firstOrNull()
            SectionCard(wl?.name ?: c.title, onTitleClick = { nav.go("watchlists") }, action = { TextButton(onClick = { nav.go("watchlists") }) { Text("All") } }) {
                val syms = wl?.symbols.orEmpty()
                val q = rememberLive(syms)
                if (syms.isEmpty()) Text("Your watchlist is empty.")
                syms.take(10).forEach { s -> QuoteRow(s, q[s], { nav.symbol(s) }) }
            }
        }
        DashboardCard.MOVERS -> MoversCard()
        DashboardCard.SECTORS -> SectorHeatCard()
        DashboardCard.CRYPTO -> SectionCard(c.title, onTitleClick = { nav.go("crypto") }) {
            val q = rememberLive(Catalog.defaultCrypto); QuoteTileGrid(Catalog.defaultCrypto, q)
        }
        DashboardCard.FEAR_GREED -> FearGreedCard()
        DashboardCard.PORTFOLIO -> PortfolioMiniCard()
        DashboardCard.FOREX -> SectionCard(c.title) { val q = rememberLive(Catalog.forex.take(6)); QuoteTileGrid(Catalog.forex.take(6), q) }
        DashboardCard.COMMODITIES -> SectionCard(c.title) { val s = Catalog.commodities.take(6); val q = rememberLive(s); QuoteTileGrid(s, q) }
        DashboardCard.YIELDS -> SectionCard(c.title, onTitleClick = { nav.go("economy") }) { val q = rememberLive(Catalog.yields); QuoteTileGrid(Catalog.yields, q, 4) }
        DashboardCard.GLOBAL -> SectionCard(c.title, onTitleClick = { nav.go("global") }) { val s = Catalog.globalIndices.take(6); val q = rememberLive(s); QuoteTileGrid(s, q) }
        DashboardCard.NEWS -> SectionCard(c.title, action = { TextButton(onClick = { nav.go("news") }) { Text("More") } }) {
            val n = rememberLoad("dashnews", refreshMs = 5 * 60_000) { Market.news("general") }
            LoadContent(n) { list -> list.take(6).forEach { NewsRow(it, showImage = false) } }
        }
        DashboardCard.EARNINGS -> SectionCard(c.title, onTitleClick = { nav.go("calendars") }) {
            val e = rememberLoad("dashearn") {
                val mine = Store.allWatchedSymbols().toSet() + PortfolioCalc.holdings(Store.current.txns).map { it.symbol }
                val all = Market.earnings()
                all.filter { it.symbol in mine }.ifEmpty { all.filter { it.symbol in Catalog.universeNames } }.take(8)
            }
            LoadContent(e, "No upcoming earnings this week") { list ->
                list.forEach { ev ->
                    Row(Modifier.fillMaxWidth().clickable { nav.symbol(ev.symbol) }.padding(vertical = 6.dp)) {
                        Text(ev.symbol, fontWeight = FontWeight.Bold, modifier = Modifier.width(70.dp))
                        Text(ev.date, Modifier.weight(1f))
                        Text(when (ev.hour) { "bmo" -> "Before open"; "amc" -> "After close"; else -> "" }, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
        DashboardCard.RECENT -> {
            val d by Store.data.collectAsState()
            SectionCard(c.title) {
                if (d.recent.isEmpty()) Text("Symbols you open will appear here.", style = MaterialTheme.typography.bodySmall)
                val q = rememberLive(d.recent.take(8))
                d.recent.take(8).forEach { s -> QuoteRow(s, q[s], { nav.symbol(s) }, showSpark = false) }
            }
        }
    }
}

@Composable
fun MarketStatusCard() {
    var now by remember { mutableStateOf(ZonedDateTime.now(MarketClock.ET)) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = ZonedDateTime.now(MarketClock.ET) } }
    val hol = rememberLoad("holidays") { Market.holidays() }
    val st = MarketClock.state(now, hol.data.orEmpty().filter { it.hours.isBlank() }.map { it.date }.toSet())
    val nav = LocalNav.current
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(if (st.isOpen) Up else if (st.session.contains("Pre") || st.session.contains("After")) Gold else Down, 12.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(st.session, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("NYSE · NASDAQ  ·  ${now.format(java.time.format.DateTimeFormatter.ofPattern("EEE h:mm:ss a"))} ET", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(st.nextLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(MarketClock.fmt(st.countdown), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
        }
        hol.data?.firstOrNull()?.let { h ->
            Text("Next holiday: ${h.name} · ${h.date}${if (h.hours.isNotBlank()) " (early close ${h.hours})" else ""}",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp).clickable { nav.go("calendars") })
        }
    }
}

@Composable
fun MoversCard() {
    val nav = LocalNav.current
    var tab by rememberSaveableString("Gainers")
    val kind = Market.Movers.entries.first { it.label == tab }
    val data = rememberLoad(kind, refreshMs = 2 * 60_000) { Market.movers(kind, 8) }
    SectionCard("Top movers", action = { TextButton(onClick = { nav.go("movers/${kind.name}") }) { Text("More") } }) {
        ChipRow(Market.Movers.entries.map { it.label }, tab, { tab = it })
        LoadContent(data) { list -> list.forEach { q -> QuoteRow(q.symbol, q, { nav.symbol(q.symbol) }, showSpark = false) } }
    }
}

@Composable
fun rememberSaveableString(init: String): MutableState<String> = androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(init) }

@Composable
fun SectorHeatCard() {
    val nav = LocalNav.current
    val q = rememberLive(Catalog.sectorEtfs.keys.toList())
    SectionCard("Sector heatmap") {
        val items = Catalog.sectorEtfs.entries.sortedByDescending { q[it.key]?.changePct ?: 0.0 }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { (s, n) -> HeatTile(n, q[s]?.changePct, Modifier.weight(1f).height(56.dp), s) { nav.symbol(s) } }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
fun FearGreedCard() {
    val stock = rememberLoad("fgs") { Market.stockFearGreed() }
    val crypto = rememberLoad("fgc") { Market.cryptoFearGreed() }
    SectionCard("Fear & Greed") {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Stocks", style = MaterialTheme.typography.labelLarge)
                stock.data?.let { FearGreedGauge(it.value, it.label, Modifier.fillMaxWidth().height(90.dp)) }
                    ?: if (stock.loading) CircularProgressIndicator(Modifier.size(24.dp)) else Text("Unavailable", style = MaterialTheme.typography.bodySmall)
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Crypto", style = MaterialTheme.typography.labelLarge)
                crypto.data?.let { FearGreedGauge(it.value, it.label, Modifier.fillMaxWidth().height(90.dp)) }
                    ?: if (crypto.loading) CircularProgressIndicator(Modifier.size(24.dp)) else Text("Unavailable", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

// ============================ Markets ============================

@Composable
fun MarketsScreen() {
    val nav = LocalNav.current
    var tab by rememberSaveableString("US")
    Column(Modifier.fillMaxSize()) {
        ChipRow(listOf("US", "NYSE", "NASDAQ", "Sectors", "Movers", "Futures", "Rates & FX", "Commodities"), tab, { tab = it }, Modifier.padding(horizontal = 12.dp))
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (tab) {
                "US" -> {
                    item { MarketStatusCard() }
                    item { SectionCard("Major indices") { val q = rememberLive(Catalog.usIndices); QuoteTileGrid(Catalog.usIndices, q) } }
                    item {
                        SectionCard("S&P 500 today") {
                            val c = rememberLoad("spxd", refreshMs = 60_000) { Market.chart("^GSPC", ChartRange.D1) }
                            LoadContent(c) { PriceChart(it, false, emptySet(), emptyList(), false, {}, true, null) }
                        }
                    }
                    item { MoversCard() }
                    item { SectorHeatCard() }
                    item { ToolLinks() }
                }
                "NYSE", "NASDAQ" -> {
                    val idx = if (tab == "NYSE") listOf("^NYA", "^DJI") else listOf("^IXIC", "NQ=F")
                    item { SectionCard("$tab indices") { val q = rememberLive(idx); QuoteTileGrid(idx, q, 2) } }
                    item { ExchangeMovers(tab) }
                }
                "Sectors" -> {
                    item { SectorHeatCard() }
                    item {
                        SectionCard("Sector ETFs") {
                            val syms = Catalog.sectorEtfs.keys.toList(); val q = rememberLive(syms)
                            syms.sortedByDescending { q[it]?.changePct ?: 0.0 }.forEach { s -> QuoteRow(s, q[s], { nav.symbol(s) }) }
                        }
                    }
                }
                "Movers" -> Market.Movers.entries.forEach { k ->
                    item(key = k.name) {
                        SectionCard(k.label, action = { TextButton(onClick = { nav.go("movers/${k.name}") }) { Text("More") } }) {
                            val d = rememberLoad(k, refreshMs = 2 * 60_000) { Market.movers(k, 10) }
                            LoadContent(d) { l -> l.forEach { QuoteRow(it.symbol, it, { nav.symbol(it.symbol) }, showSpark = false) } }
                        }
                    }
                }
                "Futures" -> item { SectionCard("US stock futures") { val q = rememberLive(Catalog.futures); Catalog.futures.forEach { s -> QuoteRow(s, q[s], { nav.symbol(s) }) } } }
                "Rates & FX" -> {
                    item { SectionCard("Treasury yields") { val q = rememberLive(Catalog.yields); Catalog.yields.forEach { s -> QuoteRow(s, q[s], { nav.symbol(s) }) } } }
                    item { SectionCard("Forex") { val q = rememberLive(Catalog.forex); Catalog.forex.forEach { s -> QuoteRow(s, q[s], { nav.symbol(s) }) } } }
                }
                "Commodities" -> item { SectionCard("Commodities") { val q = rememberLive(Catalog.commodities); Catalog.commodities.forEach { s -> QuoteRow(s, q[s], { nav.symbol(s) }) } } }
            }
        }
    }
}

@Composable
private fun ToolLinks() {
    val nav = LocalNav.current
    SectionCard("Tools") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Screener" to "screener", "Calendars" to "calendars", "Economy" to "economy", "Global" to "global", "Compare" to "compare", "Portfolio" to "portfolio")
                .forEach { (l, r) -> AssistChip(onClick = { nav.go(r) }, label = { Text(l) }) }
        }
    }
}

/** Movers restricted to one exchange, using the stock universe snapshot plus profile exchange info. */
@Composable
private fun ExchangeMovers(exchange: String) {
    val nav = LocalNav.current
    var progress by remember { mutableStateOf("") }
    val d = rememberLoad("exmov", exchange, refreshMs = 5 * 60_000) {
        val rows = Market.universe { a, b -> progress = "Scanning $a/$b" }.filter { it.quote != null }
        val nasdaq = setOf("AAPL", "MSFT", "NVDA", "AVGO", "AMD", "ADBE", "CSCO", "INTC", "QCOM", "TXN", "INTU", "AMAT", "MU", "PLTR", "PANW", "SMCI", "ANET", "CRWD",
            "GOOGL", "META", "NFLX", "CMCSA", "TMUS", "AMZN", "TSLA", "SBUX", "BKNG", "ABNB", "COST", "PEP", "PYPL", "COIN", "HOOD", "SOFI", "AMGN", "ISRG", "MRNA",
            "HON", "RIVN", "LCID", "ASML", "ARM", "MSTR", "DELL", "SHOP")
        rows.filter { (it.symbol in nasdaq) == (exchange == "NASDAQ") }
    }
    SectionCard("$exchange movers (large caps)") {
        if (d.loading && d.data == null) Text(progress, style = MaterialTheme.typography.labelSmall)
        LoadContent(d) { rows ->
            Text("Gainers", style = MaterialTheme.typography.titleSmall, color = Up)
            rows.sortedByDescending { it.quote!!.changePct }.take(6).forEach { QuoteRow(it.symbol, it.quote, { nav.symbol(it.symbol) }) }
            Text("Losers", style = MaterialTheme.typography.titleSmall, color = Down, modifier = Modifier.padding(top = 8.dp))
            rows.sortedBy { it.quote!!.changePct }.take(6).forEach { QuoteRow(it.symbol, it.quote, { nav.symbol(it.symbol) }) }
        }
    }
}

// ============================ Movers (full) ============================

@Composable
fun MoversScreen(initial: String) {
    val nav = LocalNav.current
    val tabs = listOf("Gainers", "Losers", "Most active", "52-wk highs", "52-wk lows", "Unusual volume")
    var tab by rememberSaveableString(Market.Movers.entries.firstOrNull { it.name == initial }?.label ?: "Gainers")
    var progress by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        ChipRow(tabs, tab, { tab = it }, Modifier.padding(horizontal = 12.dp))
        val kind = Market.Movers.entries.firstOrNull { it.label == tab }
        val d = rememberLoad(tab, refreshMs = 2 * 60_000) {
            if (kind != null) Market.movers(kind, 50).map { it to "" }
            else {
                val rows = Market.universe { a, b -> progress = "Scanning stocks $a/$b…" }.filter { it.quote != null }
                when (tab) {
                    "52-wk highs" -> rows.filter { it.quote!!.high52 != null }.sortedBy { 1 - it.quote!!.price / it.quote.high52!! }.take(30)
                        .map { it.quote!! to "${fmtPct((it.quote.price / it.quote.high52!! - 1) * 100)} from high" }
                    "52-wk lows" -> rows.filter { it.quote!!.low52 != null }.sortedBy { it.quote!!.price / it.quote.low52!! - 1 }.take(30)
                        .map { it.quote!! to "${fmtPct((it.quote.price / it.quote.low52!! - 1) * 100)} from low" }
                    else -> rows.filter { it.volRatio != null }.sortedByDescending { it.volRatio }.take(30)
                        .map { it.quote!! to "%.1f× avg volume".format(it.volRatio) }
                }
            }
        }
        if (d.loading && d.data == null && progress.isNotBlank()) Text(progress, Modifier.padding(12.dp), style = MaterialTheme.typography.labelMedium)
        LazyColumn(contentPadding = PaddingValues(12.dp)) {
            item { LoadContent(d) { } }
            items(d.data.orEmpty(), key = { it.first.symbol }) { (q, note) ->
                Column {
                    QuoteRow(q.symbol, q, { nav.symbol(q.symbol) }, showSpark = true)
                    val info = listOfNotNull(note.ifBlank { null }, q.volume?.let { "Vol ${fmtBig(it)}" }, q.marketCap?.let { "Cap ${fmtBig(it)}" }).joinToString(" · ")
                    if (info.isNotBlank()) Text(info, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                }
            }
        }
    }
}

// ============================ Global ============================

@Composable
fun GlobalScreen() {
    val nav = LocalNav.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SectionCard("World indices") {
                val q = rememberLive(Catalog.globalIndices)
                Catalog.globalIndices.forEach { s -> QuoteRow(s, q[s], { nav.symbol(s) }) }
            }
        }
        item {
            SectionCard("World heatmap") {
                val q = rememberLive(Catalog.globalIndices)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), maxItemsInEachRow = 3) {
                    Catalog.globalIndices.forEach { s ->
                        HeatTile(Catalog.nameOf(s)!!.substringBefore(" ("), q[s]?.changePct, Modifier.weight(1f).height(56.dp), Catalog.nameOf(s)!!.substringAfter("(", "").removeSuffix(")")) { nav.symbol(s) }
                    }
                }
            }
        }
        item { SectionCard("Currencies") { val q = rememberLive(Catalog.forex); Catalog.forex.forEach { s -> QuoteRow(s, q[s], { nav.symbol(s) }) } } }
        item { SectionCard("Commodities") { val q = rememberLive(Catalog.commodities); Catalog.commodities.forEach { s -> QuoteRow(s, q[s], { nav.symbol(s) }) } } }
    }
}
