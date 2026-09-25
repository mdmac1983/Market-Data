package app.orionmd.marketdata.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.orionmd.marketdata.data.*
import app.orionmd.marketdata.ui.*
import app.orionmd.marketdata.ui.components.*
import java.time.LocalDate

// ============================ Screener ============================

@Composable
fun ScreenerScreen() {
    val nav = LocalNav.current
    var mode by rememberSaveableString("Presets")
    Column(Modifier.fillMaxSize()) {
        ChipRow(listOf("Presets", "Custom filter"), mode, { mode = it }, Modifier.padding(horizontal = 12.dp))
        if (mode == "Presets") {
            var preset by rememberSaveableString("day_gainers")
            ChipRow(Market.presetScreens.values.toList(), Market.presetScreens.getValue(preset), { v -> preset = Market.presetScreens.entries.first { it.value == v }.key }, Modifier.padding(horizontal = 12.dp))
            val d = rememberLoad(preset) { Market.yahooScreen(preset, 50) }
            LazyColumn(contentPadding = PaddingValues(12.dp)) {
                item { LoadContent(d, "This screen returned no results") {} }
                items(d.data.orEmpty(), key = { it.symbol }) { q ->
                    QuoteRow(q.symbol, q, { nav.symbol(q.symbol) }, showSpark = false)
                    Text(listOfNotNull(q.marketCap?.let { "Cap ${fmtBig(it)}" }, q.volume?.let { "Vol ${fmtBig(it)}" }, q.high52?.let { "52w ${fmtPrice(q.low52)}–${fmtPrice(it)}" }).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
                }
            }
        } else CustomScreener()
    }
}

@Composable
private fun CustomScreener() {
    val nav = LocalNav.current
    var sector by rememberSaveableString("All")
    var minChg by remember { mutableFloatStateOf(-20f) }
    var maxChg by remember { mutableFloatStateOf(20f) }
    var minPrice by remember { mutableStateOf("") }
    var maxPrice by remember { mutableStateOf("") }
    var minVolRatio by remember { mutableFloatStateOf(0f) }
    var near by rememberSaveableString("Any")
    var sort by rememberSaveableString("% change")
    var progress by remember { mutableStateOf("") }
    val d = rememberLoad("universe", refreshMs = 5 * 60_000) { Market.universe { a, b -> progress = "Scanning $a of $b stocks…" } }
    val rows = d.data.orEmpty().filter { r ->
        val q = r.quote ?: return@filter false
        (sector == "All" || r.sector == sector) && q.changePct in minChg.toDouble()..maxChg.toDouble() &&
            (minPrice.toDoubleOrNull()?.let { q.price >= it } ?: true) && (maxPrice.toDoubleOrNull()?.let { q.price <= it } ?: true) &&
            (minVolRatio <= 0f || (r.volRatio ?: 0.0) >= minVolRatio) &&
            when (near) {
                "52-wk high" -> q.high52 != null && q.price >= q.high52 * 0.95
                "52-wk low" -> q.low52 != null && q.price <= q.low52 * 1.05
                else -> true
            }
    }.let { l ->
        when (sort) {
            "% change" -> l.sortedByDescending { it.quote!!.changePct }; "Volume ×" -> l.sortedByDescending { it.volRatio ?: 0.0 }
            "Price" -> l.sortedByDescending { it.quote!!.price }; else -> l.sortedBy { it.symbol }
        }
    }
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            SectionCard("Filters") {
                Text("Sector", style = MaterialTheme.typography.labelMedium)
                ChipRow(listOf("All") + Catalog.sectors, sector, { sector = it })
                Text("Day change: ${fmtNum(minChg.toDouble(), 0)}% to ${fmtNum(maxChg.toDouble(), 0)}%", style = MaterialTheme.typography.labelMedium)
                RangeSlider(minChg..maxChg, { minChg = it.start; maxChg = it.endInclusive }, valueRange = -20f..20f)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(minPrice, { minPrice = it }, Modifier.weight(1f), label = { Text("Min price") }, singleLine = true)
                    OutlinedTextField(maxPrice, { maxPrice = it }, Modifier.weight(1f), label = { Text("Max price") }, singleLine = true)
                }
                Text("Volume at least ${fmtNum(minVolRatio.toDouble(), 1)}× its 50-day average", style = MaterialTheme.typography.labelMedium)
                Slider(minVolRatio, { minVolRatio = it }, valueRange = 0f..5f)
                Text("Near", style = MaterialTheme.typography.labelMedium)
                ChipRow(listOf("Any", "52-wk high", "52-wk low"), near, { near = it })
                Text("Sort by", style = MaterialTheme.typography.labelMedium)
                ChipRow(listOf("% change", "Volume ×", "Price", "Symbol"), sort, { sort = it })
                Text("Scans ${Catalog.universe.size} large US stocks." + if (Keys.has("FMP")) "" else " Add a Financial Modeling Prep key for market-cap screening of the whole market.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (d.loading && d.data == null) item { Text(progress); LinearProgressIndicator(Modifier.fillMaxWidth()) }
        d.error?.let { item { ErrorBox(it, d.reload) } }
        item { Text("${rows.size} matches", fontWeight = FontWeight.Bold) }
        items(rows, key = { it.symbol }) { r ->
            QuoteRow(r.symbol, r.quote, { nav.symbol(r.symbol) })
            Text("${r.sector}${r.volRatio?.let { " · %.1f× avg vol".format(it) } ?: ""}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
        }
        if (Keys.has("FMP")) item { FmpScreener() }
    }
}

@Composable
private fun FmpScreener() {
    val nav = LocalNav.current
    var cap by rememberSaveableString("Large")
    val (lo, hi) = when (cap) { "Mega" -> 2e11 to null; "Large" -> 1e10 to 2e11; "Mid" -> 2e9 to 1e10; "Small" -> 3e8 to 2e9; else -> null to 3e8 }
    val d = rememberLoad("fmpscr", cap) { Market.fmpScreener(null, lo, hi) }
    SectionCard("Whole market by market cap (FMP)") {
        ChipRow(listOf("Mega", "Large", "Mid", "Small", "Micro"), cap, { cap = it })
        LoadContent(d) { list ->
            list.take(40).forEach { q ->
                Row(Modifier.fillMaxWidth().clickable { nav.symbol(q.symbol) }.padding(vertical = 6.dp)) {
                    Text(q.symbol, fontWeight = FontWeight.Bold, modifier = Modifier.width(70.dp))
                    Text(q.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(fmtBig(q.marketCap))
                }
            }
        }
    }
}

// ============================ Calendars ============================

@Composable
fun CalendarsScreen() {
    val nav = LocalNav.current
    var tab by rememberSaveableString("Earnings")
    var days by rememberSaveableString("7")
    val from = LocalDate.now(); val to = from.plusDays(days.toLong())
    Column(Modifier.fillMaxSize()) {
        ChipRow(listOf("Earnings", "IPOs", "Economic", "Dividends", "Holidays"), tab, { tab = it }, Modifier.padding(horizontal = 12.dp))
        if (tab != "Holidays") ChipRow(listOf("7", "14", "30"), days, { days = it }, Modifier.padding(horizontal = 12.dp))
        LazyColumn(contentPadding = PaddingValues(12.dp)) {
            when (tab) {
                "Earnings" -> item {
                    var mine by remember { mutableStateOf(false) }
                    val d = rememberLoad("earn", days) { Market.earnings(from, to) }
                    val my = remember { Store.allWatchedSymbols().toSet() + PortfolioCalc.holdings(Store.current.txns).map { it.symbol } }
                    Row(verticalAlignment = Alignment.CenterVertically) { Switch(mine, { mine = it }); Text("  Watchlist & holdings only") }
                    LoadContent(d, "No earnings scheduled") { list ->
                        list.filter { !mine || it.symbol in my }.take(300).groupBy { it.date }.forEach { (date, evs) ->
                            Text(date, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp), color = MaterialTheme.colorScheme.primary)
                            evs.forEach { e ->
                                Row(Modifier.fillMaxWidth().clickable { nav.symbol(e.symbol) }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(e.symbol, fontWeight = FontWeight.Bold, modifier = Modifier.width(72.dp))
                                    Text(when (e.hour) { "bmo" -> "Before open"; "amc" -> "After close"; "dmh" -> "During hours"; else -> "—" }, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text("EPS est ${fmtNum(e.epsEst)}", style = MaterialTheme.typography.labelSmall)
                                        e.epsActual?.let { Text("Actual ${fmtNum(it)}", style = MaterialTheme.typography.labelSmall, color = changeColor(it - (e.epsEst ?: it))) }
                                    }
                                }
                            }
                        }
                    }
                }
                "IPOs" -> item {
                    val d = rememberLoad("ipo", days) { Market.ipos(from.minusDays(7), to.plusDays(14)) }
                    LoadContent(d, "No IPOs") { list ->
                        list.forEach { i ->
                            SectionCard(modifier = Modifier.padding(vertical = 4.dp)) {
                                Row { Text(i.symbol.ifBlank { "—" }, fontWeight = FontWeight.Bold, modifier = Modifier.width(70.dp)); Text(i.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis); Pill(i.status) }
                                Text("${i.date} · ${i.exchange} · ${i.price.ifBlank { "price TBD" }}" + (i.shares?.let { " · ${fmtBig(it)} shares" } ?: ""), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                "Economic" -> item {
                    val d = rememberLoad("econ", days) { Market.economicCalendar(from, to) }
                    if (d.error != null && !Keys.has("FMP")) KeyNeeded("Financial Modeling Prep", "The economic calendar")
                    else LoadContent(d, "No events") { list ->
                        list.groupBy { it.time.take(10) }.forEach { (date, evs) ->
                            Text(date, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 10.dp), color = MaterialTheme.colorScheme.primary)
                            evs.forEach { e ->
                                Column(Modifier.padding(vertical = 5.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Dot(when (e.impact.lowercase()) { "high" -> Down; "medium" -> Gold; else -> Up.copy(alpha = 0.5f) })
                                        Text(" ${e.country}  ${e.event}", fontWeight = if (e.impact.equals("High", true)) FontWeight.Bold else FontWeight.Normal, maxLines = 2)
                                    }
                                    Text("${e.time.drop(11).take(5)}  Actual ${e.actual.ifBlank { "—" }} · Est ${e.estimate.ifBlank { "—" }} · Prev ${e.previous.ifBlank { "—" }}", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
                "Dividends" -> item {
                    if (!Keys.has("FMP")) {
                        KeyNeeded("Financial Modeling Prep", "The market-wide dividend calendar")
                        Text("Tip: dividend history for any stock is on its symbol page.", style = MaterialTheme.typography.labelSmall)
                    } else {
                        val d = rememberLoad("divcal", days) { Market.dividendCalendar(from, to) }
                        LoadContent(d, "No dividends") { list ->
                            list.take(300).forEach { dv ->
                                Row(Modifier.fillMaxWidth().clickable { nav.symbol(dv.symbol) }.padding(vertical = 5.dp)) {
                                    Text(dv.symbol, fontWeight = FontWeight.Bold, modifier = Modifier.width(72.dp)); Text("Ex ${dv.date}", Modifier.weight(1f))
                                    Text(fmtPrice(dv.amount), fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
                "Holidays" -> item {
                    val d = rememberLoad("holidays") { Market.holidays() }
                    LoadContent(d) { list ->
                        list.forEach { h ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                                Text(h.date, Modifier.width(110.dp)); Text(h.name, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                                Text(h.hours.ifBlank { "Closed" }, color = if (h.hours.isBlank()) Down else Gold)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ============================ Economy ============================

@Composable
fun EconomyScreen() {
    val curve = rememberLoad("curve") { Market.yieldCurve() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SectionCard("Treasury yield curve") {
                LoadContent(curve) { c ->
                    SimpleLineChart(c.map { it.second }, Modifier.fillMaxWidth().height(140.dp), Cyan, true)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        c.forEach { (l, v) -> Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(l, style = MaterialTheme.typography.labelSmall); Text(fmtNum(v), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold) } }
                    }
                    val inv = c.firstOrNull { it.first == "2Y" }?.second?.let { two -> c.firstOrNull { it.first == "10Y" }?.second?.let { it - two } }
                    inv?.let { Text("10Y–2Y spread: ${fmtNum(it)}%" + if (it < 0) " (inverted)" else "", color = if (it < 0) Down else Up, style = MaterialTheme.typography.labelMedium) }
                }
            }
        }
        if (!Keys.has("FRED")) item { SectionCard("Economic indicators") { KeyNeeded("FRED", "The economy dashboard (Fed rate, CPI, jobs, GDP)") } }
        else Market.fredSeries.forEach { (id, title, unit) ->
            item(key = id) {
                val d = rememberLoad("fred", id) { Market.econSeries(id, title, unit) }
                SectionCard(title) {
                    LoadContent(d, isEmpty = { false }) { s ->
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(fmtNum(s.latest) + if (unit == "%") "%" else "", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Text("  as of ${s.date}", style = MaterialTheme.typography.labelSmall)
                        }
                        if (s.history.size > 2) SimpleLineChart(s.history.map { it.second }, Modifier.fillMaxWidth().height(70.dp).padding(top = 6.dp))
                    }
                }
            }
        }
    }
}
