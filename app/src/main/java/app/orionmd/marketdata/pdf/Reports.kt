package app.orionmd.marketdata.pdf

import android.content.Context
import app.orionmd.marketdata.data.*
import app.orionmd.marketdata.pdf.PdfWriter.Companion.chg
import app.orionmd.marketdata.ui.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

enum class ReportKind(val title: String, val desc: String) {
    MARKET("Market summary", "Indices, futures, movers, sectors, crypto, forex, commodities, yields and headlines"),
    WATCHLIST("Watchlist report", "Every symbol in a watchlist with prices, ranges and mini charts"),
    SYMBOL("Symbol report", "One stock or coin: chart, profile, key stats, analysts, insiders, dividends, news"),
    PORTFOLIO("Portfolio report", "Holdings, gains and losses, allocation, dividends and transactions"),
    CRYPTO("Crypto report", "Top coins, market cap, dominance, Fear & Greed, trending coins, exchanges"),
    SECTORS("Sector performance", "S&P sectors heatmap and the biggest movers in each sector"),
    MOVERS("Top movers", "Gainers, losers, most active, 52-week highs/lows and unusual volume"),
    NEWS("News digest", "Business, market and crypto headlines with summaries"),
    CALENDARS("Calendars", "Earnings, IPOs, economic events, dividends and market holidays"),
    ECONOMY("Economy report", "Fed rate, inflation, jobs, GDP and the Treasury yield curve"),
    GLOBAL("Global markets", "World indices, currencies and commodities"),
    COMPARE("Comparison report", "Performance of 2–4 symbols side by side"),
    PAPER("Paper trading", "Practice account positions and trade history"),
}

sealed class Opt(val key: String, val label: String) {
    class Toggle(key: String, label: String, val default: Boolean) : Opt(key, label)
    class Choice(key: String, label: String, val choices: List<String>, val default: String) : Opt(key, label)
    class Text(key: String, label: String, val default: String, val hint: String = "") : Opt(key, label)
}

data class ReportOptions(val title: String, val values: Map<String, String>) {
    fun on(k: String) = values[k] == "true"
    fun str(k: String) = values[k].orEmpty()
    fun int(k: String, def: Int) = values[k]?.filter { it.isDigit() }?.toIntOrNull() ?: def
}

object Reports {
    val common = listOf(
        Opt.Toggle("summary", "Summary (totals)", true),
        Opt.Choice("paper", "Paper size", listOf("US Letter", "A4"), "US Letter"),
        Opt.Choice("orientation", "Orientation", listOf("Portrait", "Landscape"), "Portrait"),
        Opt.Toggle("charts", "Include charts", true),
        Opt.Choice("watermark", "Watermark", listOf("Light", "Normal", "Strong"), "Normal"),
    )

    fun specs(kind: ReportKind): List<Opt> = when (kind) {
        ReportKind.MARKET -> listOf(
            Opt.Toggle("indices", "Major indices", true), Opt.Toggle("futures", "Stock futures", true),
            Opt.Toggle("movers", "Top movers", true), Opt.Choice("moversCount", "Movers per list", listOf("5", "10", "25"), "10"),
            Opt.Toggle("sectors", "Sector performance", true), Opt.Toggle("crypto", "Crypto", true),
            Opt.Choice("cryptoCount", "Coins", listOf("10", "20", "50"), "20"), Opt.Toggle("fg", "Fear & Greed", true),
            Opt.Toggle("forex", "Forex", true), Opt.Toggle("commodities", "Commodities", true), Opt.Toggle("yields", "Treasury yields", true),
            Opt.Toggle("global", "Global markets", false), Opt.Toggle("news", "Headlines", true),
            Opt.Choice("newsCount", "Headlines", listOf("10", "20", "40"), "10"),
        )
        ReportKind.WATCHLIST -> listOf(
            Opt.Choice("list", "Watchlist", Store.current.watchlists.map { it.name } + "All watchlists", Store.current.watchlists.firstOrNull()?.name ?: "All watchlists"),
            Opt.Choice("sort", "Sort by", listOf("Watchlist order", "Symbol", "% change", "Price"), "Watchlist order"),
            Opt.Toggle("range", "Day range column", true), Opt.Toggle("volume", "Volume column", true),
            Opt.Toggle("w52", "52-week range column", false), Opt.Toggle("notes", "Include my notes", true),
            Opt.Choice("chartRange", "Mini chart range", listOf("1M", "3M", "1Y"), "1M"),
        )
        ReportKind.SYMBOL -> listOf(
            Opt.Text("symbol", "Symbol", "AAPL", "e.g. AAPL or BTC-USD"),
            Opt.Choice("chartRange", "Chart range", listOf("1M", "3M", "1Y", "5Y"), "1Y"),
            Opt.Toggle("profile", "Company profile", true), Opt.Toggle("stats", "Key statistics", true),
            Opt.Toggle("analysts", "Analyst ratings & price target", true), Opt.Toggle("insiders", "Insider trades", true),
            Opt.Toggle("dividends", "Dividends", true), Opt.Toggle("peers", "Peers", true), Opt.Toggle("notes", "My notes", true),
            Opt.Toggle("news", "News", true), Opt.Choice("newsCount", "News items", listOf("5", "10", "20"), "10"),
        )
        ReportKind.PORTFOLIO -> listOf(
            Opt.Choice("portfolio", "Portfolio", listOf("All portfolios") + Store.current.portfolios.map { it.name }, "All portfolios"),
            Opt.Toggle("holdings", "Holdings table", true), Opt.Choice("sort", "Sort holdings by", listOf("Value", "Symbol", "Gain %", "Day change"), "Value"),
            Opt.Toggle("allocation", "Allocation chart", true), Opt.Toggle("dividends", "Dividend income", true),
            Opt.Toggle("txns", "Transaction history", true), Opt.Toggle("closed", "Closed positions", false),
        )
        ReportKind.CRYPTO -> listOf(
            Opt.Choice("count", "Top coins", listOf("20", "50", "100"), "50"), Opt.Toggle("global", "Global market stats", true),
            Opt.Toggle("fg", "Fear & Greed", true), Opt.Toggle("heat", "Heat map", true), Opt.Toggle("trending", "Trending coins", true),
            Opt.Toggle("exchanges", "Top exchanges", false),
        )
        ReportKind.SECTORS -> listOf(Opt.Toggle("heat", "Heat map", true), Opt.Toggle("leaders", "Best & worst stock in each sector", true))
        ReportKind.MOVERS -> listOf(
            Opt.Toggle("gainers", "Gainers", true), Opt.Toggle("losers", "Losers", true), Opt.Toggle("active", "Most active", true),
            Opt.Toggle("highs", "Near 52-week highs", true), Opt.Toggle("lows", "Near 52-week lows", true), Opt.Toggle("volume", "Unusual volume", true),
            Opt.Choice("count", "Rows per list", listOf("10", "25"), "10"),
        )
        ReportKind.NEWS -> listOf(
            Opt.Choice("category", "Category", listOf("All", "General", "Markets", "Business", "Crypto", "Mergers"), "All"),
            Opt.Choice("count", "Headlines", listOf("20", "50", "100"), "50"), Opt.Toggle("summaries", "Summaries", true),
            Opt.Toggle("watchlist", "Watchlist news", true),
        )
        ReportKind.CALENDARS -> listOf(
            Opt.Choice("days", "Days ahead", listOf("7", "14", "30"), "7"), Opt.Toggle("earnings", "Earnings", true),
            Opt.Toggle("watchOnly", "Earnings: watchlist & holdings only", false), Opt.Toggle("ipo", "IPOs", true),
            Opt.Toggle("econ", "Economic events", true), Opt.Toggle("divs", "Dividends", true), Opt.Toggle("holidays", "Market holidays", true),
        )
        ReportKind.ECONOMY -> listOf(Opt.Toggle("indicators", "Economic indicators", true), Opt.Toggle("curve", "Yield curve", true), Opt.Toggle("history", "History charts", true))
        ReportKind.GLOBAL -> listOf(Opt.Toggle("indices", "World indices", true), Opt.Toggle("forex", "Currencies", true), Opt.Toggle("commodities", "Commodities", true), Opt.Toggle("us", "US indices", true))
        ReportKind.COMPARE -> listOf(
            Opt.Text("symbols", "Symbols (2–4, comma separated)", "SPY,QQQ,DIA", "e.g. AAPL,MSFT,NVDA"),
            Opt.Choice("chartRange", "Range", listOf("1M", "3M", "1Y", "5Y"), "1Y"),
        )
        ReportKind.PAPER -> listOf(Opt.Toggle("positions", "Positions", true), Opt.Toggle("trades", "Trade history", true))
    }

    fun allSpecs(kind: ReportKind) = common + specs(kind)

    fun defaultTitle(kind: ReportKind, arg: String?): String = when (kind) {
        ReportKind.SYMBOL -> "${Catalog.display(arg ?: "Symbol")} Report"
        ReportKind.WATCHLIST -> "Watchlist Report"
        else -> kind.title.split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
    } + " — " + LocalDate.now()

    /** Saved choices for [kind] (the title is never remembered). */
    fun loadOptions(kind: ReportKind, arg: String? = null): ReportOptions {
        val saved = Store.current.pdfOptions[kind.name]?.let { runCatching { JSONObject(it) }.getOrNull() }
        val values = allSpecs(kind).associate { o ->
            val def = when (o) { is Opt.Toggle -> o.default.toString(); is Opt.Choice -> o.default; is Opt.Text -> o.default }
            o.key to (saved?.optString(o.key)?.takeIf { it.isNotBlank() } ?: def)
        }.toMutableMap()
        if (kind == ReportKind.SYMBOL && arg != null) values["symbol"] = arg
        if (kind == ReportKind.COMPARE && arg != null) values["symbols"] = arg
        if (kind == ReportKind.PORTFOLIO && arg != null) values["portfolio"] = Store.current.portfolioName(arg)
        if (kind == ReportKind.PORTFOLIO && values["portfolio"] != "All portfolios" && Store.current.portfolios.none { it.name == values["portfolio"] }) values["portfolio"] = "All portfolios"
        return ReportOptions(defaultTitle(kind, arg ?: values["symbol"].takeIf { kind == ReportKind.SYMBOL }), values)
    }

    fun saveOptions(kind: ReportKind, o: ReportOptions) {
        Store.update { it.copy(pdfOptions = it.pdfOptions + (kind.name to JSONObject(o.values as Map<*, *>).toString())) }
    }

    fun tempDir(ctx: Context) = File(ctx.cacheDir, "reports")

    /** Temporary PDFs are removed when the viewer closes or the app next opens. */
    fun cleanTemp(ctx: Context) { tempDir(ctx).listFiles()?.forEach { it.delete() } }

    fun fileName(title: String) = title.replace(Regex("[^A-Za-z0-9 ._-]"), "").trim().replace(" ", "_").ifBlank { "Report" } + ".pdf"

    private fun range(s: String) = when (s) { "1M" -> ChartRange.M1; "3M" -> ChartRange.M3; "5Y" -> ChartRange.Y5; else -> ChartRange.Y1 }

    // ------------------------------------------------------------------

    suspend fun generate(ctx: Context, kind: ReportKind, o: ReportOptions, out: File, progress: (String, Float) -> Unit) {
        val wm = when (o.str("watermark")) { "Light" -> 60; "Strong" -> 150; else -> 100 }
        val w = PdfWriter(ctx, o.title, o.str("orientation") == "Landscape", o.str("paper") == "A4", wm)
        try {
            when (kind) {
                ReportKind.MARKET -> market(w, o, progress)
                ReportKind.WATCHLIST -> watchlist(w, o, progress)
                ReportKind.SYMBOL -> symbol(w, o, progress)
                ReportKind.PORTFOLIO -> portfolio(w, o, progress)
                ReportKind.CRYPTO -> crypto(w, o, progress)
                ReportKind.SECTORS -> sectors(w, o, progress)
                ReportKind.MOVERS -> movers(w, o, progress)
                ReportKind.NEWS -> news(w, o, progress)
                ReportKind.CALENDARS -> calendars(w, o, progress)
                ReportKind.ECONOMY -> economy(w, o, progress)
                ReportKind.GLOBAL -> global(w, o, progress)
                ReportKind.COMPARE -> compare(w, o, progress)
                ReportKind.PAPER -> paper(w, o, progress)
            }
            progress("Writing PDF", 1f)
            w.finish(out)
        } catch (e: Exception) {
            w.abort(); throw e
        }
    }

    private val quoteHeaders = listOf("Symbol", "Name", "Price", "Change", "% Chg")
    private val quoteWeights = listOf(1.1f, 2.6f, 1.2f, 1.1f, 1f)

    private fun quoteRows(symbols: List<String>, q: Map<String, Quote>) = symbols.mapNotNull { s ->
        val x = q[s] ?: return@mapNotNull null
        listOf(Cell(Catalog.display(s), bold = true), Cell(Catalog.nameOf(s) ?: x.name), Cell(fmtPrice(x.price), right = true),
            Cell(fmtSigned(x.change), chg(x.change), right = true), Cell(fmtPct(x.changePct), chg(x.changePct), true, true))
    }

    private fun moverRows(list: List<Quote>) = list.map { x ->
        listOf(Cell(x.symbol, bold = true), Cell(x.name), Cell(fmtPrice(x.price), right = true), Cell(fmtSigned(x.change), chg(x.change), right = true),
            Cell(fmtPct(x.changePct), chg(x.changePct), true, true), Cell(fmtBig(x.volume), right = true))
    }

    private suspend fun market(w: PdfWriter, o: ReportOptions, p: (String, Float) -> Unit) = coroutineScope {
        p("Getting quotes", 0.1f)
        val syms = Catalog.usIndices + Catalog.futures + Catalog.commodities + Catalog.forex + Catalog.yields + Catalog.sectorEtfs.keys + Catalog.globalIndices
        val qJob = async { Market.quotes(syms) }
        val movers = asyncIf(o.on("movers")) { Market.Movers.entries.associateWith { runCatching { Market.movers(it, o.int("moversCount", 10)) }.getOrDefault(emptyList()) } }
        val coins = asyncIf(o.on("crypto")) { runCatching { Market.coins(1, o.int("cryptoCount", 20)) }.getOrDefault(emptyList()) }
        val fg = asyncIf(o.on("fg")) { runCatching { Market.stockFearGreed() }.getOrNull() to runCatching { Market.cryptoFearGreed() }.getOrNull() }
        val news = asyncIf(o.on("news")) { runCatching { Market.news("general").take(o.int("newsCount", 10)) }.getOrDefault(emptyList()) }
        val spx = asyncIf(o.on("charts")) { runCatching { Market.chart("^GSPC", ChartRange.D1) }.getOrDefault(emptyList()) }
        val q = qJob.await()
        p("Building report", 0.6f)
        val status = MarketClock.state()
        if (o.on("summary")) w.tiles(Catalog.usIndices.take(4).map { s -> q[s].let { PdfWriter.Tile(Catalog.shortName(s), fmtPrice(it?.price), fmtPct(it?.changePct), chg(it?.changePct)) } } +
                listOf(PdfWriter.Tile("Market", status.session, "${status.nextLabel} ${MarketClock.fmt(status.countdown)}")))
        if (o.on("indices")) {
            w.h1("Major indices"); w.table(quoteHeaders, quoteWeights, quoteRows(Catalog.usIndices, q))
            spx?.await()?.takeIf { it.size > 2 }?.let { c -> w.lineChart(c.map { it.c }, 120f, label = "S&P 500 — today", baseline = q["^GSPC"]?.prevClose) }
        }
        if (o.on("futures")) { w.h1("Stock futures"); w.table(quoteHeaders, quoteWeights, quoteRows(Catalog.futures, q)) }
        movers?.await()?.forEach { (k, list) ->
            if (list.isNotEmpty()) { w.h1("Top ${k.label.lowercase()}"); w.table(listOf("Symbol", "Name", "Price", "Change", "% Chg", "Volume"), listOf(1f, 2.6f, 1f, 1f, 1f, 1f), moverRows(list)) }
        }
        if (o.on("sectors")) {
            w.h1("Sector performance")
            val items = Catalog.sectorEtfs.mapNotNull { (s, n) -> q[s]?.let { n to it.changePct } }.sortedByDescending { it.second }
            if (o.on("charts")) w.heatGrid(items, 4) else w.bars(items)
        }
        coins?.await()?.takeIf { it.isNotEmpty() }?.let { cs ->
            w.h1("Crypto")
            w.table(listOf("#", "Coin", "Price", "24h %", "Market cap", "Volume"), listOf(0.5f, 2.2f, 1.2f, 1f, 1.3f, 1.3f), cs.map { c ->
                listOf(Cell("${c.rank ?: ""}"), Cell("${c.name} (${c.symbol.uppercase()})", bold = true), Cell(fmtPrice(c.price), right = true),
                    Cell(fmtPct(c.changePct24h), chg(c.changePct24h), true, true), Cell(fmtBig(c.marketCap), right = true), Cell(fmtBig(c.volume), right = true))
            })
        }
        fg?.await()?.let { (s, c) ->
            if (s != null || c != null) {
                w.h1("Fear & Greed")
                w.tiles(listOfNotNull(s?.let { PdfWriter.Tile("Stocks (CNN)", "${it.value}", it.label) }, c?.let { PdfWriter.Tile("Crypto", "${it.value}", it.label) }), 2)
            }
        }
        if (o.on("forex")) { w.h1("Forex"); w.table(quoteHeaders, quoteWeights, quoteRows(Catalog.forex, q)) }
        if (o.on("commodities")) { w.h1("Commodities"); w.table(quoteHeaders, quoteWeights, quoteRows(Catalog.commodities, q)) }
        if (o.on("yields")) { w.h1("Treasury yields"); w.table(quoteHeaders, quoteWeights, quoteRows(Catalog.yields, q)); w.note("Yields shown in percent.") }
        if (o.on("global")) { w.h1("Global markets"); w.table(quoteHeaders, quoteWeights, quoteRows(Catalog.globalIndices, q)) }
        news?.await()?.takeIf { it.isNotEmpty() }?.let { ns ->
            w.h1("Headlines")
            ns.forEach { n -> w.para(n.title, 9.5f, bold = true); w.note("${n.source} · ${fmtAgo(n.time)}") }
        }
    }

    private suspend fun watchlist(w: PdfWriter, o: ReportOptions, p: (String, Float) -> Unit) {
        val lists = Store.current.watchlists.let { all -> if (o.str("list") == "All watchlists") all else all.filter { it.name == o.str("list") }.ifEmpty { all.take(1) } }
        val syms = lists.flatMap { it.symbols }.distinct()
        p("Getting quotes", 0.1f)
        val q = Market.quotes(syms)
        if (o.on("summary")) {
            val ups = q.values.count { it.changePct >= 0 }
            val best = q.values.maxByOrNull { it.changePct }; val worst = q.values.minByOrNull { it.changePct }
            w.tiles(listOf(PdfWriter.Tile("Symbols", "${syms.size}"), PdfWriter.Tile("Up / Down", "$ups / ${q.size - ups}"),
                PdfWriter.Tile("Best", best?.let { Catalog.display(it.symbol) } ?: "—", fmtPct(best?.changePct), chg(best?.changePct)),
                PdfWriter.Tile("Worst", worst?.let { Catalog.display(it.symbol) } ?: "—", fmtPct(worst?.changePct), chg(worst?.changePct))))
        }
        lists.forEach { l ->
            w.h1(l.name)
            var s = l.symbols
            s = when (o.str("sort")) {
                "Symbol" -> s.sorted(); "% change" -> s.sortedByDescending { q[it]?.changePct ?: -999.0 }; "Price" -> s.sortedByDescending { q[it]?.price ?: 0.0 }; else -> s
            }
            val headers = mutableListOf("Symbol", "Name", "Price", "% Chg"); val weights = mutableListOf(1f, 2.4f, 1.1f, 0.9f)
            if (o.on("range")) { headers += "Day range"; weights += 1.8f }
            if (o.on("volume")) { headers += "Volume"; weights += 1f }
            if (o.on("w52")) { headers += "52-wk range"; weights += 1.8f }
            w.table(headers, weights, s.map { sym ->
                val x = q[sym]
                buildList {
                    add(Cell(Catalog.display(sym), bold = true)); add(Cell(x?.name ?: Catalog.nameOf(sym) ?: ""))
                    add(Cell(fmtPrice(x?.price), right = true)); add(Cell(fmtPct(x?.changePct), chg(x?.changePct), true, true))
                    if (o.on("range")) add(Cell("${fmtPrice(x?.low)} – ${fmtPrice(x?.high)}", right = true))
                    if (o.on("volume")) add(Cell(fmtBig(x?.volume), right = true))
                    if (o.on("w52")) add(Cell("${fmtPrice(x?.low52)} – ${fmtPrice(x?.high52)}", right = true))
                }
            })
            if (o.on("charts")) {
                val r = range(o.str("chartRange"))
                s.forEachIndexed { i, sym ->
                    p("Chart ${i + 1}/${s.size}", 0.3f + 0.6f * i / s.size)
                    val c = runCatching { Market.chart(sym, r) }.getOrDefault(emptyList())
                    if (c.size > 2) w.lineChart(c.map { it.c }, 70f, label = "${Catalog.display(sym)} · ${o.str("chartRange")} ${fmtPct((c.last().c / c.first().c - 1) * 100)}")
                }
            }
            if (o.on("notes")) s.mapNotNull { sym -> Store.current.notes[sym]?.let { sym to it } }.takeIf { it.isNotEmpty() }?.let { notes ->
                w.h2("Notes"); notes.forEach { (sym, n) -> w.para("${Catalog.display(sym)}: $n", 9f) }
            }
        }
    }

    private suspend fun symbol(w: PdfWriter, o: ReportOptions, p: (String, Float) -> Unit) = coroutineScope {
        val sym = o.str("symbol").trim().uppercase().ifBlank { "AAPL" }
        val crypto = isCrypto(sym)
        val stock = typeOf(sym) == AssetType.STOCK || typeOf(sym) == AssetType.ETF
        p("Getting data for $sym", 0.1f)
        val q = async { Market.quote(sym) }
        val chart = asyncIf(o.on("charts")) { runCatching { Market.chart(sym, range(o.str("chartRange"))) }.getOrDefault(emptyList()) }
        val prof = asyncIf(o.on("profile") && stock) { runCatching { Market.profile(sym) }.getOrNull() }
        val met = asyncIf(o.on("stats") && stock) { runCatching { Market.metrics(sym) }.getOrDefault(emptyMap()) }
        val coin = asyncIf(crypto) { runCatching { Market.coinDetail(sym) }.getOrNull() }
        val recs = asyncIf(o.on("analysts") && stock) { runCatching { Market.recommendations(sym) }.getOrDefault(emptyList()) }
        val pt = asyncIf(o.on("analysts") && stock) { runCatching { Market.priceTarget(sym) }.getOrNull() }
        val ins = asyncIf(o.on("insiders") && stock) { runCatching { Market.insiderTrades(sym) }.getOrDefault(emptyList()) }
        val divs = asyncIf(o.on("dividends") && stock) { runCatching { Market.dividends(sym) }.getOrDefault(emptyList()) }
        val peers = asyncIf(o.on("peers") && stock) { runCatching { Market.peers(sym) }.getOrDefault(emptyList()) }
        val news = asyncIf(o.on("news")) { runCatching { Market.companyNews(sym).take(o.int("newsCount", 10)) }.getOrDefault(emptyList()) }
        val quote = q.await()
        p("Building report", 0.6f)
        w.h1("${Catalog.display(sym)} — ${quote?.name ?: prof?.await()?.name ?: ""}")
        if (o.on("summary") && quote != null) w.tiles(listOf(
            PdfWriter.Tile("Price", fmtPrice(quote.price), "${fmtSigned(quote.change)} (${fmtPct(quote.changePct)})", chg(quote.change)),
            PdfWriter.Tile("Day range", "${fmtPrice(quote.low)} – ${fmtPrice(quote.high)}"),
            PdfWriter.Tile("Prev close", fmtPrice(quote.prevClose)),
            PdfWriter.Tile(if (crypto) "Market cap" else "Volume", if (crypto) fmtBig(quote.marketCap) else fmtBig(quote.volume)),
        ))
        runCatching { Signals.forSymbol(sym) }.getOrNull()?.let { sig ->
            w.h2("Overbought / oversold: ${sig.state.label}")
            w.note("${sig.timeframe} · ${sig.reason()}")
        }
        chart?.await()?.takeIf { it.size > 2 }?.let { c ->
            w.lineChart(c.map { it.c }, 170f, label = "Price · ${o.str("chartRange")} (${fmtPct((c.last().c / c.first().c - 1) * 100)})",
                xLabels = fmtTime(c.first().t * 1000, "MMM d, yyyy") to fmtTime(c.last().t * 1000, "MMM d, yyyy"))
        }
        prof?.await()?.let { pr ->
            w.h1("Company profile")
            w.table(listOf("Field", "Value"), listOf(1f, 3f), listOf(
                "Name" to pr.name, "Exchange" to pr.exchange, "Industry" to pr.industry, "Country" to pr.country, "IPO" to pr.ipo,
                "Market cap" to fmtBig(pr.marketCap), "Shares out." to fmtBig(pr.shares), "Website" to pr.website,
            ).map { listOf(Cell(it.first, bold = true), Cell(it.second)) })
        }
        coin?.await()?.let { c ->
            w.h1("Coin details")
            w.tiles(listOf(PdfWriter.Tile("Rank", "#${c.rank ?: "—"}"), PdfWriter.Tile("Market cap", fmtBig(c.marketCap)), PdfWriter.Tile("24h volume", fmtBig(c.volume)),
                PdfWriter.Tile("Circulating", fmtBig(c.circulating)), PdfWriter.Tile("Max supply", fmtBig(c.maxSupply)), PdfWriter.Tile("All-time high", fmtPrice(c.ath), fmtPct(c.athChangePct), chg(c.athChangePct)),
                PdfWriter.Tile("7 days", fmtPct(c.ch7d), null), PdfWriter.Tile("1 year", fmtPct(c.ch1y))))
            if (c.description.isNotBlank()) w.para(c.description.take(1500), 9f)
        }
        met?.await()?.takeIf { it.isNotEmpty() }?.let { m ->
            w.h1("Key statistics")
            w.table(listOf("Metric", "Value", "Metric", "Value"), listOf(1.6f, 1f, 1.6f, 1f), keyStats(m).chunked(2).map { pair ->
                pair.flatMap { listOf(Cell(it.first), Cell(it.second, bold = true, right = true)) }
            })
        }
        recs?.await()?.firstOrNull()?.let { r ->
            w.h1("Analyst ratings (${r.period})")
            w.bars(listOf("Strong buy" to r.strongBuy.toDouble(), "Buy" to r.buy.toDouble(), "Hold" to r.hold.toDouble(), "Sell" to r.sell.toDouble(), "Strong sell" to r.strongSell.toDouble()),
                signed = false, suffix = "") { i, _ -> listOf(PdfWriter.UP, android.graphics.Color.rgb(132, 204, 22), PdfWriter.GOLD, android.graphics.Color.rgb(249, 115, 22), PdfWriter.DOWN)[i] }
        }
        pt?.await()?.let { t ->
            w.h2("Price target")
            w.tiles(listOf(PdfWriter.Tile("Low", fmtPrice(t.low)), PdfWriter.Tile("Consensus", fmtPrice(t.consensus), quote?.let { q2 -> t.consensus?.let { fmtPct((it / q2.price - 1) * 100) + " upside" } }),
                PdfWriter.Tile("Median", fmtPrice(t.median)), PdfWriter.Tile("High", fmtPrice(t.high))))
        }
        ins?.await()?.takeIf { it.isNotEmpty() }?.let { list ->
            w.h1("Insider trades")
            w.table(listOf("Date", "Insider", "Change", "Price", "Holding"), listOf(1f, 2.4f, 1f, 1f, 1.1f), list.take(20).map {
                listOf(Cell(it.date), Cell(it.name), Cell(fmtBig(it.change), chg(it.change), right = true), Cell(fmtPrice(it.price), right = true), Cell(fmtBig(it.shares), right = true))
            })
        }
        divs?.await()?.takeIf { it.isNotEmpty() }?.let { list ->
            w.h1("Dividends")
            w.table(listOf("Ex-date", "Amount"), listOf(1f, 1f), list.take(16).map { listOf(Cell(it.date), Cell(fmtPrice(it.amount), right = true)) })
        }
        peers?.await()?.takeIf { it.isNotEmpty() }?.let { ps ->
            w.h1("Peers")
            val pq = Market.quotes(ps.take(10))
            w.table(quoteHeaders, quoteWeights, quoteRows(ps.take(10), pq))
        }
        if (o.on("notes")) Store.current.notes[sym]?.let { w.h1("My notes"); w.para(it) }
        news?.await()?.takeIf { it.isNotEmpty() }?.let { ns ->
            w.h1("News"); ns.forEach { n -> w.para(n.title, 9.5f, bold = true); w.note("${n.source} · ${fmtAgo(n.time)}") }
        }
    }

    fun keyStats(m: Map<String, Double>): List<Pair<String, String>> = listOfNotNull(
        m["peTTM"]?.let { "P/E (TTM)" to fmtNum(it) } ?: m["peBasicExclExtraTTM"]?.let { "P/E (TTM)" to fmtNum(it) },
        m["epsTTM"]?.let { "EPS (TTM)" to fmtNum(it) },
        m["marketCapitalization"]?.let { "Market cap" to fmtBig(it * 1e6) },
        m["beta"]?.let { "Beta" to fmtNum(it) },
        m["52WeekHigh"]?.let { "52-wk high" to fmtPrice(it) }, m["52WeekLow"]?.let { "52-wk low" to fmtPrice(it) },
        m["dividendYieldIndicatedAnnual"]?.let { "Dividend yield" to fmtNum(it) + "%" },
        m["pbAnnual"]?.let { "Price/Book" to fmtNum(it) }, m["psTTM"]?.let { "Price/Sales" to fmtNum(it) },
        m["roeTTM"]?.let { "ROE" to fmtNum(it) + "%" }, m["netProfitMarginTTM"]?.let { "Net margin" to fmtNum(it) + "%" },
        m["revenueGrowthTTMYoy"]?.let { "Revenue growth" to fmtNum(it) + "%" }, m["totalDebt/totalEquityAnnual"]?.let { "Debt/Equity" to fmtNum(it) },
        m["10DayAverageTradingVolume"]?.let { "Avg vol (10d)" to fmtBig(it * 1e6) }, m["52WeekPriceReturnDaily"]?.let { "52-wk return" to fmtPct(it) },
        m["currentRatioAnnual"]?.let { "Current ratio" to fmtNum(it) },
    )

    private suspend fun portfolio(w: PdfWriter, o: ReportOptions, p: (String, Float) -> Unit) {
        val pid = Store.current.portfolios.firstOrNull { it.name == o.str("portfolio") }?.id ?: ALL_PORTFOLIOS
        val txns = Store.current.txnsOf(pid)
        if (pid != ALL_PORTFOLIOS || Store.current.portfolios.size > 1) w.h2("Portfolio: ${Store.current.portfolioName(pid)}")
        val hs = PortfolioCalc.holdings(txns)
        p("Getting quotes", 0.2f)
        val q = Market.quotes(hs.filter { it.qty > 0 }.map { it.symbol })
        val s = PortfolioCalc.summary(txns, q)
        if (o.on("summary")) w.tiles(listOf(
            PdfWriter.Tile("Value", fmtMoney(s.value)), PdfWriter.Tile("Day change", fmtSignedMoney(s.dayChange), fmtPct(s.dayPct), chg(s.dayChange)),
            PdfWriter.Tile("Unrealized", fmtSignedMoney(s.unrealized), fmtPct(s.totalReturnPct), chg(s.unrealized)),
            PdfWriter.Tile("Realized", fmtSignedMoney(s.realized), null), PdfWriter.Tile("Cost basis", fmtMoney(s.cost)),
            PdfWriter.Tile("Dividends", fmtMoney(s.dividends)), PdfWriter.Tile("Positions", "${s.holdings.count { it.qty > 0 }}"),
            PdfWriter.Tile("Total return", fmtSignedMoney(s.totalReturn), null, chg(s.totalReturn)),
            PdfWriter.Tile("Cash", fmtMoney(s.cash.balance), "from sales ${fmtMoney(s.cash.saleProceeds)}"),
            PdfWriter.Tile("Invested", fmtMoney(s.investedValue)),
        ))
        val open = hs.filter { it.qty > 1e-9 }.let { l ->
            when (o.str("sort")) {
                "Symbol" -> l.sortedBy { it.symbol }
                "Gain %" -> l.sortedByDescending { h -> q[h.symbol]?.let { (it.price / h.avgCost - 1) } ?: 0.0 }
                "Day change" -> l.sortedByDescending { it.dayChange(q[it.symbol]) ?: 0.0 }
                else -> l.sortedByDescending { it.value(q[it.symbol]) ?: it.costBasis }
            }
        }
        if (o.on("holdings")) {
            w.h1("Holdings")
            w.table(listOf("Symbol", "Qty", "Avg cost", "Price", "Value", "Day", "Gain", "Gain %"), listOf(1f, 0.8f, 1f, 1f, 1.2f, 1f, 1.1f, 0.9f), open.map { h ->
                val x = q[h.symbol]; val un = h.unrealized(x)
                listOf(Cell(Catalog.display(h.symbol), bold = true), Cell(fmtNum(h.qty, 4).trimEnd('0').trimEnd('.'), right = true), Cell(fmtPrice(h.avgCost), right = true),
                    Cell(fmtPrice(x?.price), right = true), Cell(fmtMoney(h.value(x)), right = true), Cell(fmtSignedMoney(h.dayChange(x)), chg(h.dayChange(x)), right = true),
                    Cell(fmtSignedMoney(un), chg(un), right = true), Cell(fmtPct(un?.let { it / h.costBasis * 100 }), chg(un), true, true))
            })
        }
        if (o.on("allocation") && o.on("charts")) {
            w.h1("Allocation")
            val tot = (open.sumOf { it.value(q[it.symbol]) ?: it.costBasis } + s.cash.balance).takeIf { it > 0 } ?: 1.0
            w.bars((open.map { Catalog.display(it.symbol) to (it.value(q[it.symbol]) ?: it.costBasis) / tot * 100 } +
                listOfNotNull(s.cash.balance.takeIf { it > 0.005 }?.let { "Cash" to it / tot * 100 })).sortedByDescending { it.second }, signed = false) { _, _ -> PdfWriter.ACCENT }
        }
        if (o.on("closed")) hs.filter { it.qty <= 1e-9 }.takeIf { it.isNotEmpty() }?.let { cl ->
            w.h1("Closed positions")
            w.table(listOf("Symbol", "Realized P/L", "Dividends"), listOf(1f, 1f, 1f), cl.map { listOf(Cell(it.symbol, bold = true), Cell(fmtSignedMoney(it.realized), chg(it.realized), right = true), Cell(fmtMoney(it.dividends), right = true)) })
        }
        if (o.on("dividends")) txns.filter { it.kind == TxnKind.DIVIDEND }.takeIf { it.isNotEmpty() }?.let { d ->
            w.h1("Dividend income")
            w.table(listOf("Date", "Symbol", "Amount"), listOf(1f, 1f, 1f), d.sortedByDescending { it.date }.map {
                listOf(Cell(PortfolioCalc.fmtDate(it.date)), Cell(it.symbol, bold = true), Cell(fmtMoney(if (it.qty > 0) it.qty * it.price else it.price), right = true))
            })
        }
        if (o.on("txns") && txns.isNotEmpty()) {
            w.h1("Transactions")
            w.table(listOf("Date", "Type", "Symbol", "Qty", "Price", "Fees", "Total"), listOf(1.1f, 0.9f, 1f, 0.8f, 1f, 0.8f, 1.2f), txns.sortedByDescending { it.date }.map {
                listOf(Cell(PortfolioCalc.fmtDate(it.date)), Cell(it.kind.label), Cell(if (it.kind.isCash) "Cash" else it.symbol, bold = true),
                    Cell(fmtNum(it.qty, 4).trimEnd('0').trimEnd('.'), right = true), Cell(fmtPrice(it.price), right = true), Cell(fmtMoney(it.fees), right = true),
                    Cell(fmtMoney(if (it.kind.amountOnly && it.qty == 0.0) it.price else it.qty * it.price), right = true))
            })
        }
        if (txns.isEmpty()) w.para("No transactions yet. Add holdings in the Portfolio screen.")
    }

    private suspend fun crypto(w: PdfWriter, o: ReportOptions, p: (String, Float) -> Unit) = coroutineScope {
        p("Getting crypto data", 0.1f)
        val coins = async { Market.coins(1, o.int("count", 50)) }
        val glob = asyncIf(o.on("global")) { runCatching { Market.cryptoGlobal() }.getOrNull() }
        val fg = asyncIf(o.on("fg")) { runCatching { Market.cryptoFearGreed() }.getOrNull() }
        val tr = asyncIf(o.on("trending")) { runCatching { Market.trendingCoins() }.getOrDefault(emptyList()) }
        val ex = asyncIf(o.on("exchanges")) { runCatching { Market.exchanges() }.getOrDefault(emptyList()) }
        val cs = coins.await()
        glob?.await()?.let { g ->
            if (o.on("summary")) w.tiles(listOf(PdfWriter.Tile("Total market cap", fmtBig(g.totalCap), fmtPct(g.capChangePct), chg(g.capChangePct)),
                PdfWriter.Tile("24h volume", fmtBig(g.totalVol)), PdfWriter.Tile("BTC dominance", fmtNum(g.btcDominance) + "%"), PdfWriter.Tile("ETH dominance", fmtNum(g.ethDominance) + "%")))
        }
        fg?.await()?.let { f ->
            w.h2("Crypto Fear & Greed: ${f.value} (${f.label})")
            if (o.on("charts") && f.history.size > 2) w.lineChart(f.history.map { it.toDouble() }, 70f, PdfWriter.GOLD, "Last 30 days")
        }
        if (o.on("heat") && o.on("charts")) { w.h1("Heat map (24h)"); w.heatGrid(cs.take(36).map { it.symbol.uppercase() to it.changePct24h }, 6) }
        w.h1("Top ${cs.size} coins")
        w.table(listOf("#", "Coin", "Price", "24h %", "Market cap", "Volume", "7d trend"), listOf(0.5f, 2f, 1.1f, 0.9f, 1.2f, 1.2f, 0.9f), cs.map { c ->
            val t = if (c.sparkline.size > 1) (c.sparkline.last() / c.sparkline.first() - 1) * 100 else null
            listOf(Cell("${c.rank ?: ""}"), Cell("${c.name} (${c.symbol.uppercase()})", bold = true), Cell(fmtPrice(c.price), right = true),
                Cell(fmtPct(c.changePct24h), chg(c.changePct24h), true, true), Cell(fmtBig(c.marketCap), right = true), Cell(fmtBig(c.volume), right = true),
                Cell(fmtPct(t), chg(t), right = true))
        })
        tr?.await()?.takeIf { it.isNotEmpty() }?.let { t ->
            w.h1("Trending")
            w.table(listOf("Coin", "Rank", "Price", "24h %"), listOf(2f, 0.7f, 1f, 1f), t.map {
                listOf(Cell("${it.name} (${it.symbol.uppercase()})", bold = true), Cell("${it.rank ?: "—"}"), Cell(fmtPrice(it.price), right = true), Cell(fmtPct(it.changePct24h), chg(it.changePct24h), true, true))
            })
        }
        ex?.await()?.takeIf { it.isNotEmpty() }?.let { e ->
            w.h1("Top exchanges")
            w.table(listOf("Exchange", "Country", "Trust", "24h volume (BTC)"), listOf(2f, 1.4f, 0.7f, 1.3f), e.take(20).map {
                listOf(Cell(it.name, bold = true), Cell(it.country), Cell("${it.trust ?: "—"}/10", right = true), Cell(fmtBig(it.volumeBtc), right = true))
            })
        }
    }

    private suspend fun sectors(w: PdfWriter, o: ReportOptions, p: (String, Float) -> Unit) {
        p("Getting sector data", 0.1f)
        val q = Market.quotes(Catalog.sectorEtfs.keys + "SPY")
        val items = Catalog.sectorEtfs.mapNotNull { (s, n) -> q[s]?.let { n to it.changePct } }.sortedByDescending { it.second }
        if (o.on("summary")) w.tiles(listOf(
            PdfWriter.Tile("S&P 500 (SPY)", fmtPrice(q["SPY"]?.price), fmtPct(q["SPY"]?.changePct), chg(q["SPY"]?.changePct)),
            PdfWriter.Tile("Best sector", items.firstOrNull()?.first ?: "—", fmtPct(items.firstOrNull()?.second), chg(items.firstOrNull()?.second)),
            PdfWriter.Tile("Worst sector", items.lastOrNull()?.first ?: "—", fmtPct(items.lastOrNull()?.second), chg(items.lastOrNull()?.second)),
            PdfWriter.Tile("Sectors up", "${items.count { it.second >= 0 }} / ${items.size}"),
        ))
        w.h1("Sector performance")
        if (o.on("heat") && o.on("charts")) w.heatGrid(items, 4)
        w.bars(items)
        w.table(listOf("ETF", "Sector", "Price", "Change", "% Chg"), quoteWeights, Catalog.sectorEtfs.keys.sortedByDescending { q[it]?.changePct ?: 0.0 }.mapNotNull { s ->
            q[s]?.let { x -> listOf(Cell(s, bold = true), Cell(Catalog.sectorEtfs[s]!!), Cell(fmtPrice(x.price), right = true), Cell(fmtSigned(x.change), chg(x.change), right = true), Cell(fmtPct(x.changePct), chg(x.changePct), true, true)) }
        })
        if (o.on("leaders")) {
            p("Scanning stocks", 0.4f)
            val rows = Market.universe { d, t -> p("Scanning stocks $d/$t", 0.4f + 0.5f * d / t) }.filter { it.quote != null }
            w.h1("Best and worst stock in each sector")
            w.table(listOf("Sector", "Best", "% Chg", "Worst", "% Chg"), listOf(1.6f, 1f, 0.9f, 1f, 0.9f), rows.groupBy { it.sector }.toSortedMap().map { (sec, list) ->
                val b = list.maxBy { it.quote!!.changePct }; val wst = list.minBy { it.quote!!.changePct }
                listOf(Cell(sec, bold = true), Cell(b.symbol), Cell(fmtPct(b.quote!!.changePct), chg(b.quote.changePct), right = true),
                    Cell(wst.symbol), Cell(fmtPct(wst.quote!!.changePct), chg(wst.quote.changePct), right = true))
            })
        }
    }

    private suspend fun movers(w: PdfWriter, o: ReportOptions, p: (String, Float) -> Unit) {
        val n = o.int("count", 10)
        val headers = listOf("Symbol", "Name", "Price", "Change", "% Chg", "Volume"); val weights = listOf(1f, 2.6f, 1f, 1f, 1f, 1f)
        listOf(Market.Movers.GAINERS to "gainers", Market.Movers.LOSERS to "losers", Market.Movers.ACTIVE to "active").forEachIndexed { i, (k, key) ->
            if (o.on(key)) {
                p("Getting ${k.label.lowercase()}", 0.1f + i * 0.1f)
                val l = runCatching { Market.movers(k, n) }.getOrDefault(emptyList())
                w.h1(k.label); w.table(headers, weights, moverRows(l))
            }
        }
        if (o.on("highs") || o.on("lows") || o.on("volume")) {
            val rows = Market.universe { d, t -> p("Scanning stocks $d/$t", 0.4f + 0.5f * d / t) }.filter { it.quote != null }
            fun near(hi: Boolean) = rows.filter { r -> val q = r.quote!!; (if (hi) q.high52 else q.low52) != null }
                .sortedBy { r -> val q = r.quote!!; if (hi) 1 - q.price / q.high52!! else q.price / q.low52!! - 1 }.take(n)
            if (o.on("highs")) { w.h1("Near 52-week highs"); w.table(listOf("Symbol", "Name", "Price", "52-wk high", "From high", "% Chg"), listOf(1f, 2.4f, 1f, 1f, 1f, 1f), near(true).map { r ->
                val q = r.quote!!; listOf(Cell(r.symbol, bold = true), Cell(r.name), Cell(fmtPrice(q.price), right = true), Cell(fmtPrice(q.high52), right = true),
                    Cell(fmtPct((q.price / q.high52!! - 1) * 100), right = true), Cell(fmtPct(q.changePct), chg(q.changePct), right = true)) }) }
            if (o.on("lows")) { w.h1("Near 52-week lows"); w.table(listOf("Symbol", "Name", "Price", "52-wk low", "From low", "% Chg"), listOf(1f, 2.4f, 1f, 1f, 1f, 1f), near(false).map { r ->
                val q = r.quote!!; listOf(Cell(r.symbol, bold = true), Cell(r.name), Cell(fmtPrice(q.price), right = true), Cell(fmtPrice(q.low52), right = true),
                    Cell(fmtPct((q.price / q.low52!! - 1) * 100), right = true), Cell(fmtPct(q.changePct), chg(q.changePct), right = true)) }) }
            if (o.on("volume")) { w.h1("Unusual volume"); w.table(listOf("Symbol", "Name", "Volume", "Avg volume", "× Avg", "% Chg"), listOf(1f, 2.4f, 1f, 1f, 0.8f, 1f),
                rows.filter { it.volRatio != null }.sortedByDescending { it.volRatio }.take(n).map { r ->
                    val q = r.quote!!; listOf(Cell(r.symbol, bold = true), Cell(r.name), Cell(fmtBig(q.volume), right = true), Cell(fmtBig(r.avgVol), right = true),
                        Cell("%.1f×".format(r.volRatio), bold = true, right = true), Cell(fmtPct(q.changePct), chg(q.changePct), right = true)) }) }
        }
    }

    private suspend fun news(w: PdfWriter, o: ReportOptions, p: (String, Float) -> Unit) {
        val cats = when (o.str("category")) { "All" -> listOf("general", "markets", "business", "crypto"); "Mergers" -> listOf("merger"); else -> listOf(o.str("category").lowercase()) }
        val count = o.int("count", 50)
        cats.forEachIndexed { i, c ->
            p("Getting $c news", 0.1f + 0.2f * i)
            val list = runCatching { Market.news(c) }.getOrDefault(emptyList()).take(if (cats.size > 1) count / cats.size + 1 else count)
            if (list.isEmpty()) return@forEachIndexed
            w.h1(c.replaceFirstChar { it.uppercase() } + " news")
            list.forEach { n ->
                w.para(n.title, 10f, bold = true)
                if (o.on("summaries") && n.summary.isNotBlank()) w.para(n.summary.take(360), 8.5f, PdfWriter.MUTED)
                w.note("${n.source} · ${fmtAgo(n.time)}"); w.space(2f)
            }
        }
        if (o.on("watchlist")) {
            val syms = Store.current.watchlists.firstOrNull()?.symbols.orEmpty().filter { !isCrypto(it) }.take(8)
            if (syms.isNotEmpty()) {
                w.h1("Watchlist news")
                syms.forEachIndexed { i, s ->
                    p("News for $s", 0.8f + 0.15f * i / syms.size)
                    val l = runCatching { Market.companyNews(s).take(3) }.getOrDefault(emptyList())
                    if (l.isNotEmpty()) { w.h2(s); l.forEach { n -> w.para(n.title, 9f, bold = true); w.note("${n.source} · ${fmtAgo(n.time)}") } }
                }
            }
        }
    }

    private suspend fun calendars(w: PdfWriter, o: ReportOptions, p: (String, Float) -> Unit) {
        val days = o.int("days", 7).toLong()
        val today = LocalDate.now(); val end = today.plusDays(days)
        if (o.on("earnings")) {
            p("Earnings", 0.1f)
            var e = runCatching { Market.earnings(today, end) }.getOrDefault(emptyList())
            if (o.on("watchOnly")) { val mine = Store.allWatchedSymbols().toSet() + PortfolioCalc.holdings(Store.current.txns).map { it.symbol }; e = e.filter { it.symbol in mine } }
            w.h1("Earnings")
            w.table(listOf("Date", "Symbol", "Time", "EPS est.", "EPS actual", "Revenue est."), listOf(1f, 0.9f, 0.7f, 0.9f, 0.9f, 1.2f), e.take(150).map {
                listOf(Cell(it.date), Cell(it.symbol, bold = true), Cell(when (it.hour) { "bmo" -> "Before open"; "amc" -> "After close"; else -> "—" }),
                    Cell(fmtNum(it.epsEst), right = true), Cell(fmtNum(it.epsActual), right = true), Cell(fmtBig(it.revEst), right = true))
            })
        }
        if (o.on("ipo")) {
            p("IPOs", 0.3f)
            val l = runCatching { Market.ipos(today, end.plusDays(14)) }.getOrDefault(emptyList())
            w.h1("IPOs")
            w.table(listOf("Date", "Symbol", "Company", "Exchange", "Price", "Status"), listOf(1f, 0.8f, 2.2f, 1.4f, 0.9f, 0.9f), l.map {
                listOf(Cell(it.date), Cell(it.symbol, bold = true), Cell(it.name), Cell(it.exchange), Cell(it.price), Cell(it.status))
            })
        }
        if (o.on("econ")) {
            p("Economic events", 0.5f)
            w.h1("Economic calendar")
            val l = runCatching { Market.economicCalendar(today, end) }.getOrNull()
            if (l == null) w.para("Add a free Financial Modeling Prep key in Settings to include the economic calendar.", 9f, PdfWriter.MUTED)
            else w.table(listOf("Time", "Ctry", "Event", "Actual", "Est.", "Prev.", "Impact"), listOf(1.3f, 0.5f, 2.6f, 0.8f, 0.8f, 0.8f, 0.8f), l.map {
                listOf(Cell(it.time.take(16)), Cell(it.country), Cell(it.event, bold = it.impact.equals("High", true)), Cell(it.actual), Cell(it.estimate), Cell(it.previous), Cell(it.impact))
            })
        }
        if (o.on("divs")) {
            p("Dividends", 0.7f)
            val l = runCatching { Market.dividendCalendar(today, end) }.getOrDefault(emptyList())
            w.h1("Dividends")
            if (l.isEmpty()) w.para(if (Keys.has("FMP")) "No dividends found." else "Add a free Financial Modeling Prep key in Settings to include the dividend calendar.", 9f, PdfWriter.MUTED)
            else w.table(listOf("Ex-date", "Symbol", "Amount", "Pay date"), listOf(1f, 1f, 1f, 1f), l.take(150).map { listOf(Cell(it.date), Cell(it.symbol, bold = true), Cell(fmtPrice(it.amount), right = true), Cell(it.payDate)) })
        }
        if (o.on("holidays")) {
            p("Holidays", 0.9f)
            val l = runCatching { Market.holidays() }.getOrDefault(emptyList())
            w.h1("US market holidays")
            w.table(listOf("Date", "Holiday", "Hours"), listOf(1f, 2f, 1.2f), l.map { listOf(Cell(it.date), Cell(it.name, bold = true), Cell(it.hours.ifBlank { "Closed" })) })
        }
    }

    private suspend fun economy(w: PdfWriter, o: ReportOptions, p: (String, Float) -> Unit) {
        if (!Keys.has("FRED")) {
            w.para("The full economy report needs a free FRED API key (Settings → API keys). Showing Treasury yields only.", 9.5f, PdfWriter.MUTED)
        }
        if (o.on("curve")) {
            p("Yield curve", 0.1f)
            val c = runCatching { Market.yieldCurve() }.getOrDefault(emptyList())
            w.h1("Treasury yield curve")
            if (o.on("charts") && c.size > 1) w.lineChart(c.map { it.second }, 110f, PdfWriter.ACCENT, c.joinToString("   ") { "${it.first}: ${fmtNum(it.second)}%" })
            else w.table(listOf("Maturity", "Yield"), listOf(1f, 1f), c.map { listOf(Cell(it.first), Cell(fmtNum(it.second) + "%", right = true)) })
        }
        if (o.on("indicators") && Keys.has("FRED")) {
            val series = Market.fredSeries.mapIndexedNotNull { i, (id, t, u) ->
                p("Loading $t", 0.2f + 0.7f * i / Market.fredSeries.size)
                runCatching { Market.econSeries(id, t, u) }.getOrNull()
            }
            w.h1("Economic indicators")
            w.table(listOf("Indicator", "Latest", "As of", "Year ago"), listOf(2.4f, 1f, 1f, 1f), series.map { s ->
                val ago = s.history.getOrNull(s.history.size - 13)?.second
                listOf(Cell(s.title, bold = true), Cell(fmtNum(s.latest), right = true), Cell(s.date), Cell(fmtNum(ago), right = true))
            })
            if (o.on("history") && o.on("charts")) series.forEach { s -> if (s.history.size > 2) w.lineChart(s.history.map { it.second }, 80f, PdfWriter.ACCENT, "${s.title} — ${s.history.first().first} to ${s.date}") }
        }
    }

    private suspend fun global(w: PdfWriter, o: ReportOptions, p: (String, Float) -> Unit) {
        p("Getting quotes", 0.2f)
        val q = Market.quotes(Catalog.globalIndices + Catalog.forex + Catalog.commodities + Catalog.usIndices)
        if (o.on("us")) { w.h1("US indices"); w.table(quoteHeaders, quoteWeights, quoteRows(Catalog.usIndices, q)) }
        if (o.on("indices")) {
            w.h1("World indices"); w.table(quoteHeaders, quoteWeights, quoteRows(Catalog.globalIndices, q))
            if (o.on("charts")) w.bars(Catalog.globalIndices.mapNotNull { s -> q[s]?.let { Catalog.nameOf(s)!! to it.changePct } })
        }
        if (o.on("forex")) { w.h1("Currencies"); w.table(quoteHeaders, quoteWeights, quoteRows(Catalog.forex, q)) }
        if (o.on("commodities")) { w.h1("Commodities"); w.table(quoteHeaders, quoteWeights, quoteRows(Catalog.commodities, q)) }
    }

    private suspend fun compare(w: PdfWriter, o: ReportOptions, p: (String, Float) -> Unit) {
        val syms = o.str("symbols").split(",", " ").map { it.trim().uppercase() }.filter { it.isNotBlank() }.distinct().take(4)
        val r = range(o.str("chartRange"))
        val series = syms.mapIndexed { i, s -> p("Loading $s", 0.1f + 0.7f * i / syms.size); s to runCatching { Market.chart(s, r) }.getOrDefault(emptyList()) }
        val q = Market.quotes(syms)
        w.h1("Performance · ${o.str("chartRange")}")
        val colors = listOf(PdfWriter.ACCENT, PdfWriter.GOLD, android.graphics.Color.rgb(219, 39, 119), android.graphics.Color.rgb(5, 150, 105))
        if (o.on("charts")) w.multiLineChart(series.map { (s, c) -> Catalog.display(s) to c.map { it.c } }, colors)
        w.table(listOf("Symbol", "Name", "Price", "Today", "Period return", "Period high", "Period low"), listOf(0.9f, 2f, 1f, 0.9f, 1f, 1f, 1f), series.map { (s, c) ->
            val x = q[s]; val ret = if (c.size > 1) (c.last().c / c.first().c - 1) * 100 else null
            listOf(Cell(Catalog.display(s), bold = true), Cell(x?.name ?: ""), Cell(fmtPrice(x?.price), right = true), Cell(fmtPct(x?.changePct), chg(x?.changePct), right = true),
                Cell(fmtPct(ret), chg(ret), true, true), Cell(fmtPrice(c.maxOfOrNull { it.h }), right = true), Cell(fmtPrice(c.minOfOrNull { it.l }), right = true))
        })
    }

    private suspend fun paper(w: PdfWriter, o: ReportOptions, p: (String, Float) -> Unit) {
        val pa = Store.current.paper
        p("Getting quotes", 0.2f)
        val q = Market.quotes(pa.positions.keys)
        val posVal = pa.positions.entries.sumOf { (s, ps) -> (q[s]?.price ?: ps.avg) * ps.qty }
        val total = pa.cash + posVal
        if (o.on("summary")) w.tiles(listOf(PdfWriter.Tile("Account value", fmtMoney(total), fmtPct((total / pa.start - 1) * 100), chg(total - pa.start)),
            PdfWriter.Tile("Cash", fmtMoney(pa.cash)), PdfWriter.Tile("Positions", fmtMoney(posVal)), PdfWriter.Tile("Trades", "${pa.history.size}")))
        if (o.on("positions")) {
            w.h1("Positions")
            w.table(listOf("Symbol", "Qty", "Avg", "Price", "Value", "P/L", "P/L %"), listOf(1f, 0.8f, 1f, 1f, 1.1f, 1.1f, 0.9f), pa.positions.map { (s, ps) ->
                val px = q[s]?.price; val pl = px?.let { (it - ps.avg) * ps.qty }
                listOf(Cell(Catalog.display(s), bold = true), Cell(fmtNum(ps.qty, 4).trimEnd('0').trimEnd('.'), right = true), Cell(fmtPrice(ps.avg), right = true), Cell(fmtPrice(px), right = true),
                    Cell(fmtMoney(px?.let { it * ps.qty }), right = true), Cell(fmtSignedMoney(pl), chg(pl), right = true), Cell(fmtPct(px?.let { (it / ps.avg - 1) * 100 }), chg(pl), true, true))
            })
        }
        if (o.on("trades") && pa.history.isNotEmpty()) {
            w.h1("Trade history")
            w.table(listOf("Time", "Side", "Symbol", "Qty", "Price", "Total"), listOf(1.4f, 0.7f, 1f, 0.8f, 1f, 1.1f), pa.history.sortedByDescending { it.time }.map {
                listOf(Cell(fmtTime(it.time)), Cell(it.side, if (it.side == "BUY") PdfWriter.UP else PdfWriter.DOWN, true), Cell(Catalog.display(it.symbol), bold = true),
                    Cell(fmtNum(it.qty, 4).trimEnd('0').trimEnd('.'), right = true), Cell(fmtPrice(it.price), right = true), Cell(fmtMoney(it.qty * it.price), right = true))
            })
        }
    }
}

private fun <T> CoroutineScope.asyncIf(cond: Boolean, block: suspend () -> T): Deferred<T>? = if (cond) async { block() } else null
