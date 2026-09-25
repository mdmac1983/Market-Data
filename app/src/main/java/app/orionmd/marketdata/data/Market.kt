package app.orionmd.marketdata.data

import android.util.Xml
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

enum class ChartRange(val label: String, val yRange: String, val yInterval: String, val tdInterval: String, val tdSize: Int, val cbGran: Int, val days: Int) {
    D1("1D", "1d", "5m", "5min", 80, 300, 1),
    W1("1W", "5d", "30m", "30min", 70, 3600, 7),
    M1("1M", "1mo", "1d", "1day", 23, 21600, 30),
    M3("3M", "3mo", "1d", "1day", 64, 86400, 90),
    Y1("1Y", "1y", "1d", "1day", 252, 86400, 365),
    Y5("5Y", "5y", "1wk", "1week", 260, 0, 1825),
}

private fun JSONObject.d(k: String): Double? =
    if (has(k) && !isNull(k)) optDouble(k).takeIf { !it.isNaN() && !it.isInfinite() } else null
private fun JSONObject.s(k: String): String = if (has(k) && !isNull(k)) optString(k) else ""
private fun JSONArray.objs(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

/** All market data access. Each call tries the best source first and falls back to others. */
object Market {
    private val gate = Semaphore(8)
    private val today get() = LocalDate.now(ZoneId.of("America/New_York"))
    private val finnhubBase = "https://finnhub.io/api/v1"
    private val yahoo = "https://query1.finance.yahoo.com"

    // ---------------- Quotes ----------------

    suspend fun quotes(symbols: Collection<String>): Map<String, Quote> = coroutineScope {
        symbols.distinct().map { s -> async { gate.withPermit { runCatching { quote(s) }.getOrNull()?.let { s to it } } } }
            .awaitAll().filterNotNull().toMap()
    }

    suspend fun quote(symbol: String): Quote? = when (typeOf(symbol)) {
        AssetType.STOCK, AssetType.ETF ->
            (if (Keys.has("FINNHUB") && !Net.busy("finnhub.io")) runCatching { finnhubQuote(symbol) }.getOrNull() else null)
                ?: runCatching { yahooQuote(symbol) }.getOrNull()
                ?: runCatching { finnhubQuote(symbol) }.getOrNull()
                ?: runCatching { alphaQuote(symbol) }.getOrNull()
        AssetType.CRYPTO ->
            runCatching { coinbaseQuote(symbol) }.getOrNull()
                ?: runCatching { geckoQuote(symbol) }.getOrNull()
                ?: runCatching { coinstatsQuote(symbol) }.getOrNull()
                ?: runCatching { yahooQuote(symbol) }.getOrNull()
        else ->
            runCatching { yahooQuote(symbol) }.getOrNull()
                ?: Catalog.twelveData[symbol]?.let { runCatching { twelveQuote(symbol, it) }.getOrNull() }
                ?: Catalog.proxies[symbol]?.let { p ->
                    runCatching { finnhubQuote(p) }.getOrNull()?.copy(symbol = symbol, name = Catalog.nameOf(symbol) ?: symbol, proxyNote = "via $p ETF")
                }
    }

    private val nameCache = ConcurrentHashMap<String, String>()

    private suspend fun finnhubQuote(symbol: String): Quote? {
        if (!Keys.has("FINNHUB")) return null
        val o = JSONObject(Net.get("$finnhubBase/quote?symbol=${enc(symbol)}&token=${Keys.finnhub}", ttlMs = 10_000))
        val c = o.d("c") ?: return null
        if (c == 0.0) return null
        return Quote(
            symbol = symbol, name = Catalog.nameOf(symbol) ?: nameCache[symbol] ?: symbol, price = c,
            change = o.d("d") ?: 0.0, changePct = o.d("dp") ?: 0.0, open = o.d("o"), high = o.d("h"), low = o.d("l"),
            prevClose = o.d("pc"), time = o.optLong("t"), source = "Finnhub",
        )
    }

    private fun yahooSym(s: String) = if (s == "BRK-B") "BRK-B" else s

    suspend fun yahooQuote(symbol: String): Quote? {
        val url = "$yahoo/v8/finance/chart/${enc(yahooSym(symbol))}?range=1d&interval=5m&includePrePost=true"
        val r = JSONObject(Net.get(url, ttlMs = 15_000)).getJSONObject("chart").getJSONArray("result").getJSONObject(0)
        val m = r.getJSONObject("meta")
        val price = m.d("regularMarketPrice") ?: return null
        val prev = m.d("previousClose") ?: m.d("chartPreviousClose") ?: price
        val ts = r.optJSONArray("timestamp")
        val closes = r.optJSONObject("indicators")?.optJSONArray("quote")?.optJSONObject(0)?.optJSONArray("close")
        val spark = mutableListOf<Double>()
        var ext: Double? = null
        var extLabel: String? = null
        val regular = m.optJSONObject("currentTradingPeriod")?.optJSONObject("regular")
        val rs = regular?.optLong("start") ?: 0; val re = regular?.optLong("end") ?: Long.MAX_VALUE
        if (ts != null && closes != null) {
            for (i in 0 until minOf(ts.length(), closes.length())) {
                if (closes.isNull(i)) continue
                val c = closes.optDouble(i); val t = ts.optLong(i)
                if (t in rs..re) spark += c
                else if (t > re) { ext = c; extLabel = "After hours" }
                else if (t < rs) { ext = c; extLabel = "Pre-market" }
            }
        }
        val now = System.currentTimeMillis() / 1000
        if (extLabel == "Pre-market" && now > re) { ext = null; extLabel = null }
        return Quote(
            symbol = symbol, name = Catalog.nameOf(symbol) ?: m.s("longName").ifBlank { m.s("shortName") }.ifBlank { symbol },
            price = price, change = price - prev, changePct = if (prev != 0.0) (price - prev) / prev * 100 else 0.0,
            high = m.d("regularMarketDayHigh"), low = m.d("regularMarketDayLow"), prevClose = prev,
            volume = m.d("regularMarketVolume"), high52 = m.d("fiftyTwoWeekHigh"), low52 = m.d("fiftyTwoWeekLow"),
            extPrice = ext, extChangePct = ext?.let { (it - price) / price * 100 }, extLabel = extLabel,
            time = m.optLong("regularMarketTime", now), source = "Yahoo", spark = spark,
        ).also { nameCache[symbol] = it.name }
    }

    private suspend fun alphaQuote(symbol: String): Quote? {
        if (!Keys.has("ALPHAVANTAGE")) return null
        val o = JSONObject(Net.get("https://www.alphavantage.co/query?function=GLOBAL_QUOTE&symbol=${enc(symbol)}&apikey=${Keys.alpha}", ttlMs = 60_000))
            .optJSONObject("Global Quote") ?: return null
        val p = o.d("05. price") ?: return null
        return Quote(symbol, Catalog.nameOf(symbol) ?: symbol, p, o.d("09. change") ?: 0.0,
            o.s("10. change percent").removeSuffix("%").toDoubleOrNull() ?: 0.0, o.d("02. open"), o.d("03. high"), o.d("04. low"),
            o.d("08. previous close"), o.d("06. volume"), source = "Alpha Vantage")
    }

    private suspend fun twelveQuote(symbol: String, td: String): Quote? {
        if (!Keys.has("TWELVEDATA")) return null
        val o = JSONObject(Net.get("https://api.twelvedata.com/quote?symbol=${enc(td)}&apikey=${Keys.twelve}", ttlMs = 60_000))
        val c = o.d("close") ?: return null
        return Quote(symbol, Catalog.nameOf(symbol) ?: o.s("name"), c, o.d("change") ?: 0.0, o.d("percent_change") ?: 0.0,
            o.d("open"), o.d("high"), o.d("low"), o.d("previous_close"), o.d("volume"),
            high52 = o.optJSONObject("fifty_two_week")?.d("high"), low52 = o.optJSONObject("fifty_two_week")?.d("low"), source = "Twelve Data")
    }

    private suspend fun coinbaseQuote(symbol: String): Quote? {
        val o = JSONObject(Net.get("https://api.exchange.coinbase.com/products/${enc(symbol)}/stats", ttlMs = 10_000))
        val last = o.d("last") ?: return null
        val open = o.d("open") ?: last
        val coin = coinBySymbol(cryptoBase(symbol))
        return Quote(symbol, coin?.name ?: Catalog.nameOf(symbol) ?: cryptoBase(symbol), last, last - open,
            if (open != 0.0) (last - open) / open * 100 else 0.0, open, o.d("high"), o.d("low"), open,
            volume = o.d("volume")?.times(last), marketCap = coin?.marketCap, high52 = null, source = "Coinbase",
            spark = coin?.sparkline?.takeLast(24) ?: emptyList())
    }

    private suspend fun geckoQuote(symbol: String): Quote? {
        val c = coinBySymbol(cryptoBase(symbol)) ?: return null
        return coinToQuote(c)
    }

    fun coinToQuote(c: Coin) = Quote(
        c.ticker, c.name, c.price, c.price - c.price / (1 + c.changePct24h / 100), c.changePct24h,
        high = c.high24, low = c.low24, prevClose = c.price / (1 + c.changePct24h / 100), volume = c.volume,
        marketCap = c.marketCap, source = "CoinGecko", spark = c.sparkline.takeLast(24),
    )

    private suspend fun coinstatsQuote(symbol: String): Quote? {
        if (!Keys.has("COINSTATS")) return null
        val arr = JSONObject(Net.get("https://openapiv1.coinstats.app/coins?limit=300", ttlMs = 60_000, headers = mapOf("X-API-KEY" to Keys.coinstats)))
            .optJSONArray("result") ?: return null
        val o = arr.objs().firstOrNull { it.s("symbol").equals(cryptoBase(symbol), true) } ?: return null
        val p = o.d("price") ?: return null
        val pct = o.d("priceChange1d") ?: 0.0
        return Quote(symbol, o.s("name"), p, p - p / (1 + pct / 100), pct, volume = o.d("volume"), marketCap = o.d("marketCap"), source = "CoinStats")
    }

    // ---------------- Charts ----------------

    suspend fun chart(symbol: String, range: ChartRange): List<Candle> {
        runCatching { yahooChart(symbol, range) }.getOrNull()?.takeIf { it.size > 2 }?.let { return it }
        if (isCrypto(symbol)) {
            runCatching { coinbaseCandles(symbol, range) }.getOrNull()?.takeIf { it.size > 2 }?.let { return it }
            runCatching { geckoOhlc(symbol, range) }.getOrNull()?.takeIf { it.size > 2 }?.let { return it }
        }
        val td = when (typeOf(symbol)) {
            AssetType.STOCK, AssetType.ETF -> symbol
            AssetType.CRYPTO -> cryptoBase(symbol) + "/USD"
            else -> Catalog.twelveData[symbol] ?: Catalog.proxies[symbol]
        }
        if (td != null) runCatching { twelveSeries(td, range) }.getOrNull()?.takeIf { it.size > 2 }?.let { return it }
        runCatching { polygonAggs(Catalog.proxies[symbol] ?: symbol, range) }.getOrNull()?.takeIf { it.size > 2 }?.let { return it }
        return emptyList()
    }

    suspend fun yahooChart(symbol: String, range: ChartRange, events: Boolean = false): List<Candle> {
        val url = "$yahoo/v8/finance/chart/${enc(yahooSym(symbol))}?range=${range.yRange}&interval=${range.yInterval}" + if (events) "&events=div" else ""
        val r = JSONObject(Net.get(url, ttlMs = if (range == ChartRange.D1) 30_000 else 10 * 60_000))
            .getJSONObject("chart").getJSONArray("result").getJSONObject(0)
        return parseYahooCandles(r)
    }

    private fun parseYahooCandles(r: JSONObject): List<Candle> {
        val ts = r.optJSONArray("timestamp") ?: return emptyList()
        val q = r.getJSONObject("indicators").getJSONArray("quote").getJSONObject(0)
        val o = q.optJSONArray("open"); val h = q.optJSONArray("high"); val l = q.optJSONArray("low")
        val c = q.optJSONArray("close"); val v = q.optJSONArray("volume")
        val out = ArrayList<Candle>(ts.length())
        for (i in 0 until ts.length()) {
            if (c == null || c.isNull(i)) continue
            val close = c.optDouble(i)
            out += Candle(ts.optLong(i), o?.optDouble(i, close) ?: close, h?.optDouble(i, close) ?: close,
                l?.optDouble(i, close) ?: close, close, v?.optDouble(i, 0.0) ?: 0.0)
        }
        return out.filter { !it.o.isNaN() && !it.h.isNaN() && !it.l.isNaN() }
    }

    private suspend fun coinbaseCandles(symbol: String, range: ChartRange): List<Candle> {
        if (range.cbGran == 0) return emptyList()
        val arr = JSONArray(Net.get("https://api.exchange.coinbase.com/products/${enc(symbol)}/candles?granularity=${range.cbGran}", ttlMs = 60_000))
        val cutoff = System.currentTimeMillis() / 1000 - range.days * 86400L
        return (0 until arr.length()).map { arr.getJSONArray(it) }
            .map { Candle(it.getLong(0), it.getDouble(3), it.getDouble(2), it.getDouble(1), it.getDouble(4), it.getDouble(5)) }
            .filter { it.t >= cutoff }.sortedBy { it.t }
    }

    private suspend fun geckoOhlc(symbol: String, range: ChartRange): List<Candle> {
        val id = coinIdFor(symbol) ?: return emptyList()
        val days = when { range.days <= 1 -> 1; range.days <= 7 -> 7; range.days <= 30 -> 30; range.days <= 90 -> 90; range.days <= 365 -> 365; else -> 365 }
        val arr = JSONArray(Net.get("https://api.coingecko.com/api/v3/coins/$id/ohlc?vs_currency=usd&days=$days", ttlMs = 5 * 60_000))
        return (0 until arr.length()).map { arr.getJSONArray(it) }
            .map { Candle(it.getLong(0) / 1000, it.getDouble(1), it.getDouble(2), it.getDouble(3), it.getDouble(4), 0.0) }
    }

    private suspend fun twelveSeries(td: String, range: ChartRange): List<Candle> {
        if (!Keys.has("TWELVEDATA")) return emptyList()
        val o = JSONObject(Net.get("https://api.twelvedata.com/time_series?symbol=${enc(td)}&interval=${range.tdInterval}&outputsize=${range.tdSize}&apikey=${Keys.twelve}", ttlMs = 10 * 60_000))
        val zone = runCatching { ZoneId.of(o.optJSONObject("meta")?.s("exchange_timezone")) }.getOrDefault(ZoneId.of("America/New_York"))
        val values = o.optJSONArray("values") ?: return emptyList()
        return values.objs().mapNotNull { v ->
            val dt = v.s("datetime")
            val t = runCatching {
                if (dt.length > 10) LocalDateTime.parse(dt, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).atZone(zone).toEpochSecond()
                else LocalDate.parse(dt).atStartOfDay(zone).toEpochSecond()
            }.getOrNull() ?: return@mapNotNull null
            Candle(t, v.d("open") ?: return@mapNotNull null, v.d("high") ?: 0.0, v.d("low") ?: 0.0, v.d("close") ?: 0.0, v.d("volume") ?: 0.0)
        }.sortedBy { it.t }
    }

    private suspend fun polygonAggs(symbol: String, range: ChartRange): List<Candle> {
        if (!Keys.has("POLYGON") || symbol.startsWith("^") || symbol.contains("=")) return emptyList()
        val (mult, span) = when (range) { ChartRange.D1 -> 5 to "minute"; ChartRange.W1 -> 30 to "minute"; ChartRange.Y5 -> 1 to "week"; else -> 1 to "day" }
        val from = today.minusDays(range.days.toLong() + if (range == ChartRange.D1) 4 else 0)
        val o = JSONObject(Net.get("https://api.polygon.io/v2/aggs/ticker/${enc(symbol)}/range/$mult/$span/$from/$today?adjusted=true&sort=asc&limit=5000&apiKey=${Keys.polygon}", ttlMs = 10 * 60_000))
        return (o.optJSONArray("results") ?: return emptyList()).objs().map {
            Candle(it.optLong("t") / 1000, it.optDouble("o"), it.optDouble("h"), it.optDouble("l"), it.optDouble("c"), it.optDouble("v"))
        }
    }

    // ---------------- Movers & screeners ----------------

    enum class Movers(val label: String, val yahooId: String, val fmp: String?) {
        GAINERS("Gainers", "day_gainers", "biggest-gainers"),
        LOSERS("Losers", "day_losers", "biggest-losers"),
        ACTIVE("Most active", "most_actives", "most-actives"),
    }

    val presetScreens = linkedMapOf(
        "day_gainers" to "Day gainers", "day_losers" to "Day losers", "most_actives" to "Most active",
        "undervalued_growth_stocks" to "Undervalued growth", "growth_technology_stocks" to "Growth tech",
        "undervalued_large_caps" to "Undervalued large caps", "aggressive_small_caps" to "Aggressive small caps",
        "small_cap_gainers" to "Small-cap gainers", "most_shorted_stocks" to "Most shorted",
        "portfolio_anchors" to "Portfolio anchors", "solid_large_growth_funds" to "Large growth funds",
    )

    suspend fun movers(kind: Movers, count: Int = 25): List<Quote> =
        runCatching { yahooScreen(kind.yahooId, count) }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: runCatching { fmpMovers(kind.fmp!!) }.getOrNull()?.takeIf { it.isNotEmpty() }?.take(count)
            ?: runCatching { alphaMovers(kind) }.getOrNull()?.takeIf { it.isNotEmpty() }?.take(count)
            ?: universeMovers(kind).take(count)

    suspend fun yahooScreen(id: String, count: Int = 25): List<Quote> {
        val o = JSONObject(Net.get("$yahoo/v1/finance/screener/predefined/saved?scrIds=$id&count=$count", ttlMs = 60_000))
        val quotes = o.getJSONObject("finance").getJSONArray("result").getJSONObject(0).getJSONArray("quotes")
        return quotes.objs().mapNotNull { q ->
            val p = q.d("regularMarketPrice") ?: return@mapNotNull null
            Quote(q.s("symbol"), q.s("shortName").ifBlank { q.s("longName") }, p, q.d("regularMarketChange") ?: 0.0,
                q.d("regularMarketChangePercent") ?: 0.0, q.d("regularMarketOpen"), q.d("regularMarketDayHigh"), q.d("regularMarketDayLow"),
                q.d("regularMarketPreviousClose"), q.d("regularMarketVolume"), q.d("marketCap"), q.d("fiftyTwoWeekHigh"), q.d("fiftyTwoWeekLow"),
                q.d("averageDailyVolume3Month"), source = "Yahoo")
        }
    }

    private suspend fun fmpMovers(path: String): List<Quote> {
        if (!Keys.has("FMP")) return emptyList()
        return JSONArray(Net.get("https://financialmodelingprep.com/stable/$path?apikey=${Keys.fmp}", ttlMs = 60_000)).objs().mapNotNull {
            val p = it.d("price") ?: return@mapNotNull null
            Quote(it.s("symbol"), it.s("name"), p, it.d("change") ?: 0.0, it.d("changesPercentage") ?: 0.0, source = "FMP")
        }
    }

    private suspend fun alphaMovers(kind: Movers): List<Quote> {
        if (!Keys.has("ALPHAVANTAGE")) return emptyList()
        val o = JSONObject(Net.get("https://www.alphavantage.co/query?function=TOP_GAINERS_LOSERS&apikey=${Keys.alpha}", ttlMs = 10 * 60_000))
        val key = when (kind) { Movers.GAINERS -> "top_gainers"; Movers.LOSERS -> "top_losers"; Movers.ACTIVE -> "most_actively_traded" }
        return (o.optJSONArray(key) ?: return emptyList()).objs().mapNotNull {
            val p = it.d("price") ?: return@mapNotNull null
            Quote(it.s("ticker"), it.s("ticker"), p, it.d("change_amount") ?: 0.0, it.s("change_percentage").removeSuffix("%").toDoubleOrNull() ?: 0.0,
                volume = it.d("volume"), source = "Alpha Vantage")
        }
    }

    private suspend fun universeMovers(kind: Movers): List<Quote> {
        val rows = universe().mapNotNull { it.quote }
        return when (kind) {
            Movers.GAINERS -> rows.sortedByDescending { it.changePct }
            Movers.LOSERS -> rows.sortedBy { it.changePct }
            Movers.ACTIVE -> rows.sortedByDescending { (it.volume ?: 0.0) * it.price }
        }
    }

    @Volatile private var universeCache: Pair<Long, List<UniverseRow>>? = null

    /** Snapshot of the built-in stock universe: price, change, volume vs 3-month average, 52-week range. */
    suspend fun universe(onProgress: (Int, Int) -> Unit = { _, _ -> }): List<UniverseRow> {
        universeCache?.let { (t, rows) -> if (System.currentTimeMillis() - t < 5 * 60_000) return rows }
        val list = Catalog.universe
        var done = 0
        val sem = Semaphore(6)
        val rows = coroutineScope {
            list.map { (sym, name, sector) ->
                async {
                    sem.withPermit {
                        val r = runCatching { yahoo3mo(sym, name, sector) }.getOrNull()
                            ?: UniverseRow(sym, name, sector, runCatching { finnhubQuote(sym) }.getOrNull()?.copy(name = name), null, null)
                        synchronized(this@Market) { done++ }
                        onProgress(done, list.size)
                        r
                    }
                }
            }.awaitAll()
        }
        if (rows.count { it.quote != null } > list.size / 2) universeCache = System.currentTimeMillis() to rows
        return rows
    }

    private suspend fun yahoo3mo(sym: String, name: String, sector: String): UniverseRow {
        val r = JSONObject(Net.get("$yahoo/v8/finance/chart/${enc(sym)}?range=3mo&interval=1d", ttlMs = 5 * 60_000))
            .getJSONObject("chart").getJSONArray("result").getJSONObject(0)
        val m = r.getJSONObject("meta")
        val candles = parseYahooCandles(r)
        val price = m.d("regularMarketPrice") ?: candles.last().c
        val prev = if (candles.size >= 2) candles[candles.size - 2].c else m.d("chartPreviousClose") ?: price
        val vol = m.d("regularMarketVolume") ?: candles.lastOrNull()?.v
        val avg = candles.dropLast(1).takeLast(50).map { it.v }.filter { it > 0 }.average().takeIf { !it.isNaN() }
        val q = Quote(sym, name, price, price - prev, if (prev != 0.0) (price - prev) / prev * 100 else 0.0,
            high = m.d("regularMarketDayHigh"), low = m.d("regularMarketDayLow"), prevClose = prev, volume = vol,
            high52 = m.d("fiftyTwoWeekHigh"), low52 = m.d("fiftyTwoWeekLow"), avgVolume = avg, source = "Yahoo",
            spark = candles.takeLast(30).map { it.c })
        return UniverseRow(sym, name, sector, q, avg, if (vol != null && avg != null && avg > 0) vol / avg else null, candles.map { it.c })
    }

    suspend fun fmpScreener(sector: String?, minCap: Double?, maxCap: Double?): List<Quote> {
        if (!Keys.has("FMP")) return emptyList()
        val params = buildString {
            append("limit=100&isActivelyTrading=true&country=US")
            sector?.let { append("&sector=${enc(it)}") }
            minCap?.let { append("&marketCapMoreThan=${it.toLong()}") }
            maxCap?.let { append("&marketCapLowerThan=${it.toLong()}") }
        }
        return JSONArray(Net.get("https://financialmodelingprep.com/stable/company-screener?$params&apikey=${Keys.fmp}", ttlMs = 10 * 60_000)).objs().mapNotNull {
            val p = it.d("price") ?: return@mapNotNull null
            Quote(it.s("symbol"), it.s("companyName"), p, 0.0, 0.0, volume = it.d("volume"), marketCap = it.d("marketCap"), source = "FMP")
        }
    }

    // ---------------- Crypto ----------------

    suspend fun coins(page: Int = 1, perPage: Int = 100): List<Coin> =
        runCatching { geckoMarkets(page, perPage) }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: runCatching { coinstatsCoins(perPage) }.getOrNull().orEmpty()

    private suspend fun geckoMarkets(page: Int, perPage: Int): List<Coin> {
        val arr = JSONArray(Net.get("https://api.coingecko.com/api/v3/coins/markets?vs_currency=usd&order=market_cap_desc&per_page=$perPage&page=$page&sparkline=true&price_change_percentage=24h", ttlMs = 60_000))
        return arr.objs().map { o ->
            Coin(o.s("id"), o.s("symbol"), o.s("name"), o.s("image").ifBlank { null }, o.d("current_price") ?: 0.0,
                o.d("price_change_percentage_24h") ?: 0.0, o.d("market_cap"), o.d("total_volume"), o.optInt("market_cap_rank"),
                o.optJSONObject("sparkline_in_7d")?.optJSONArray("price")?.let { a -> (0 until a.length()).map { a.optDouble(it) }.filter { !it.isNaN() } } ?: emptyList(),
                o.d("high_24h"), o.d("low_24h"), o.d("ath"), o.d("circulating_supply"))
        }
    }

    private suspend fun coinstatsCoins(limit: Int): List<Coin> {
        if (!Keys.has("COINSTATS")) return emptyList()
        val arr = JSONObject(Net.get("https://openapiv1.coinstats.app/coins?limit=$limit", ttlMs = 60_000, headers = mapOf("X-API-KEY" to Keys.coinstats)))
            .optJSONArray("result") ?: return emptyList()
        return arr.objs().map { o ->
            Coin(o.s("id"), o.s("symbol"), o.s("name"), o.s("icon").ifBlank { null }, o.d("price") ?: 0.0, o.d("priceChange1d") ?: 0.0,
                o.d("marketCap"), o.d("volume"), o.optInt("rank"))
        }
    }

    private val coinIndex = ConcurrentHashMap<String, Coin>()

    suspend fun coinBySymbol(base: String): Coin? {
        coinIndex[base.lowercase()]?.let { return it }
        runCatching { coins(1, 250) }.getOrNull()?.forEach { c -> coinIndex.putIfAbsent(c.symbol.lowercase(), c) }
        return coinIndex[base.lowercase()]
    }

    suspend fun coinIdFor(symbol: String): String? {
        coinBySymbol(cryptoBase(symbol))?.let { return it.id }
        val o = JSONObject(Net.get("https://api.coingecko.com/api/v3/search?query=${enc(cryptoBase(symbol))}", ttlMs = 24 * 3600_000L))
        return o.optJSONArray("coins")?.objs()?.firstOrNull { it.s("symbol").equals(cryptoBase(symbol), true) }?.s("id")
    }

    data class CoinDetail(val description: String, val homepage: String, val totalSupply: Double?, val maxSupply: Double?,
                          val ath: Double?, val athChangePct: Double?, val atl: Double?, val ch7d: Double?, val ch30d: Double?, val ch1y: Double?,
                          val marketCap: Double?, val volume: Double?, val circulating: Double?, val rank: Int?, val categories: List<String>)

    suspend fun coinDetail(symbol: String): CoinDetail? {
        val id = coinIdFor(symbol) ?: return null
        val o = JSONObject(Net.get("https://api.coingecko.com/api/v3/coins/$id?localization=false&tickers=false&community_data=false&developer_data=false", ttlMs = 10 * 60_000))
        val md = o.optJSONObject("market_data") ?: JSONObject()
        fun usd(k: String) = md.optJSONObject(k)?.d("usd")
        return CoinDetail(
            o.optJSONObject("description")?.s("en").orEmpty().replace(Regex("<[^>]+>"), ""),
            o.optJSONObject("links")?.optJSONArray("homepage")?.optString(0).orEmpty(),
            md.d("total_supply"), md.d("max_supply"), usd("ath"), usd("ath_change_percentage"), usd("atl"),
            md.d("price_change_percentage_7d"), md.d("price_change_percentage_30d"), md.d("price_change_percentage_1y"),
            usd("market_cap"), usd("total_volume"), md.d("circulating_supply"), o.optInt("market_cap_rank"),
            o.optJSONArray("categories")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } } ?: emptyList(),
        )
    }

    suspend fun trendingCoins(): List<Coin> {
        val o = JSONObject(Net.get("https://api.coingecko.com/api/v3/search/trending", ttlMs = 10 * 60_000))
        return o.getJSONArray("coins").objs().map { it.getJSONObject("item") }.map { i ->
            val data = i.optJSONObject("data")
            Coin(i.s("id"), i.s("symbol"), i.s("name"), i.s("small").ifBlank { null }, data?.d("price") ?: 0.0,
                data?.optJSONObject("price_change_percentage_24h")?.d("usd") ?: 0.0, null, null, i.optInt("market_cap_rank"))
        }
    }

    suspend fun cryptoGlobal(): CryptoGlobal {
        val d = JSONObject(Net.get("https://api.coingecko.com/api/v3/global", ttlMs = 5 * 60_000)).getJSONObject("data")
        val pct = d.getJSONObject("market_cap_percentage")
        return CryptoGlobal(d.getJSONObject("total_market_cap").optDouble("usd"), d.getJSONObject("total_volume").optDouble("usd"),
            pct.optDouble("btc"), pct.optDouble("eth"), d.optDouble("market_cap_change_percentage_24h_usd"), d.optInt("active_cryptocurrencies"))
    }

    suspend fun cryptoFearGreed(): FearGreed {
        val arr = JSONObject(Net.get("https://api.alternative.me/fng/?limit=30", ttlMs = 30 * 60_000)).getJSONArray("data").objs()
        return FearGreed(arr.first().optInt("value"), arr.first().s("value_classification"), arr.map { it.optInt("value") }.reversed())
    }

    suspend fun stockFearGreed(): FearGreed {
        val o = JSONObject(Net.get("https://production.dataviz.cnn.io/index/fearandgreed/graphdata", ttlMs = 30 * 60_000,
            headers = mapOf("Referer" to "https://www.cnn.com/", "Origin" to "https://www.cnn.com")))
        val fg = o.getJSONObject("fear_and_greed")
        val hist = o.optJSONObject("fear_and_greed_historical")?.optJSONArray("data")?.objs()?.takeLast(30)?.map { it.optDouble("y").toInt() } ?: emptyList()
        return FearGreed(fg.optDouble("score").toInt(), fg.s("rating").replaceFirstChar { it.uppercase() }, hist)
    }

    suspend fun ethGasGwei(): Double {
        val r = JSONObject(Net.post("https://ethereum-rpc.publicnode.com", """{"jsonrpc":"2.0","method":"eth_gasPrice","params":[],"id":1}"""))
        return r.getString("result").removePrefix("0x").toLong(16) / 1e9
    }

    suspend fun exchanges(): List<Exchange> =
        JSONArray(Net.get("https://api.coingecko.com/api/v3/exchanges?per_page=50", ttlMs = 60 * 60_000)).objs().map {
            Exchange(it.s("name"), it.s("country"), it.optInt("trust_score"), it.d("trade_volume_24h_btc"), it.s("url"), it.s("image").ifBlank { null })
        }

    /** USD value of one unit of a currency (fiat) or coin. */
    suspend fun fiatRates(): Map<String, Double> {
        val o = JSONObject(Net.get("https://api.frankfurter.dev/v1/latest?base=USD", ttlMs = 60 * 60_000)).getJSONObject("rates")
        return o.keys().asSequence().associateWith { o.optDouble(it) } + ("USD" to 1.0)
    }

    // ---------------- Search ----------------

    suspend fun search(q: String): List<SearchResult> = coroutineScope {
        if (q.isBlank()) return@coroutineScope emptyList()
        val stocks = async {
            runCatching {
                JSONObject(Net.get("$finnhubBase/search?q=${enc(q)}&exchange=US&token=${Keys.finnhub}", ttlMs = 3600_000)).getJSONArray("result").objs()
                    .filter { !it.s("symbol").contains(".") || it.s("type").contains("Index") }
                    .map { SearchResult(it.s("symbol"), it.s("description"), it.s("type").ifBlank { "Stock" }) }
            }.getOrNull()?.takeIf { it.isNotEmpty() } ?: runCatching {
                JSONObject(Net.get("https://query2.finance.yahoo.com/v1/finance/search?q=${enc(q)}&quotesCount=15&newsCount=0", ttlMs = 3600_000))
                    .getJSONArray("quotes").objs().map { SearchResult(it.s("symbol"), it.s("longname").ifBlank { it.s("shortname") }, it.s("quoteType")) }
            }.getOrNull().orEmpty()
        }
        val coins = async {
            runCatching {
                JSONObject(Net.get("https://api.coingecko.com/api/v3/search?query=${enc(q)}", ttlMs = 3600_000)).getJSONArray("coins").objs().take(8)
                    .map { SearchResult(it.s("symbol").uppercase() + "-USD", it.s("name"), "Crypto") }
            }.getOrNull().orEmpty()
        }
        val local = (Catalog.names.entries.filter { it.key.contains(q, true) || it.value.contains(q, true) }
            .map { SearchResult(it.key, it.value, typeOf(it.key).name.lowercase().replaceFirstChar { c -> c.uppercase() }) })
        (local + stocks.await().take(20) + coins.await()).distinctBy { it.symbol }
    }

    // ---------------- Company data ----------------

    suspend fun profile(symbol: String): Profile? {
        val o = JSONObject(Net.get("$finnhubBase/stock/profile2?symbol=${enc(symbol)}&token=${Keys.finnhub}", ttlMs = 7 * 24 * 3600_000L))
        if (o.length() == 0) return null
        return Profile(o.s("name"), o.s("exchange"), o.s("finnhubIndustry"), o.s("country"), o.s("ipo"),
            o.d("marketCapitalization")?.times(1e6), o.d("shareOutstanding")?.times(1e6), o.s("weburl"), o.s("logo").ifBlank { null }, o.s("phone"))
            .also { nameCache[symbol] = it.name }
    }

    suspend fun metrics(symbol: String): Map<String, Double> {
        val m = JSONObject(Net.get("$finnhubBase/stock/metric?symbol=${enc(symbol)}&metric=all&token=${Keys.finnhub}", ttlMs = 6 * 3600_000L)).optJSONObject("metric") ?: return emptyMap()
        return m.keys().asSequence().mapNotNull { k -> m.d(k)?.let { k to it } }.toMap()
    }

    suspend fun recommendations(symbol: String): List<Recommendation> =
        JSONArray(Net.get("$finnhubBase/stock/recommendation?symbol=${enc(symbol)}&token=${Keys.finnhub}", ttlMs = 12 * 3600_000L)).objs().map {
            Recommendation(it.s("period"), it.optInt("strongBuy"), it.optInt("buy"), it.optInt("hold"), it.optInt("sell"), it.optInt("strongSell"))
        }

    data class PriceTarget(val high: Double?, val low: Double?, val consensus: Double?, val median: Double?)

    suspend fun priceTarget(symbol: String): PriceTarget? {
        if (Keys.has("FMP")) runCatching {
            val o = JSONArray(Net.get("https://financialmodelingprep.com/stable/price-target-consensus?symbol=${enc(symbol)}&apikey=${Keys.fmp}", ttlMs = 12 * 3600_000L)).optJSONObject(0)
            if (o != null) return PriceTarget(o.d("targetHigh"), o.d("targetLow"), o.d("targetConsensus"), o.d("targetMedian"))
        }
        return runCatching {
            val o = JSONObject(Net.get("$finnhubBase/stock/price-target?symbol=${enc(symbol)}&token=${Keys.finnhub}", ttlMs = 12 * 3600_000L, allowStale = false))
            PriceTarget(o.d("targetHigh"), o.d("targetLow"), o.d("targetMean"), o.d("targetMedian"))
        }.getOrNull()
    }

    suspend fun insiderTrades(symbol: String): List<InsiderTrade> =
        JSONObject(Net.get("$finnhubBase/stock/insider-transactions?symbol=${enc(symbol)}&token=${Keys.finnhub}", ttlMs = 12 * 3600_000L))
            .optJSONArray("data")?.objs()?.take(40)?.map {
                InsiderTrade(it.s("name"), it.optDouble("change"), it.optDouble("share"), it.d("transactionPrice"), it.s("transactionDate"), it.s("transactionCode"))
            } ?: emptyList()

    /** Monthly insider sentiment (MSPR, -100..100). */
    suspend fun insiderSentiment(symbol: String): List<Pair<String, Double>> =
        JSONObject(Net.get("$finnhubBase/stock/insider-sentiment?symbol=${enc(symbol)}&from=${today.minusMonths(12)}&to=$today&token=${Keys.finnhub}", ttlMs = 24 * 3600_000L))
            .optJSONArray("data")?.objs()?.map { "%d-%02d".format(it.optInt("year"), it.optInt("month")) to it.optDouble("mspr") } ?: emptyList()

    suspend fun peers(symbol: String): List<String> =
        JSONArray(Net.get("$finnhubBase/stock/peers?symbol=${enc(symbol)}&token=${Keys.finnhub}", ttlMs = 7 * 24 * 3600_000L))
            .let { a -> (0 until a.length()).map { a.optString(it) } }.filter { it != symbol }.take(12)

    suspend fun filings(symbol: String): List<Filing> =
        JSONArray(Net.get("$finnhubBase/stock/filings?symbol=${enc(symbol)}&token=${Keys.finnhub}", ttlMs = 12 * 3600_000L)).objs().take(60).map {
            Filing(it.s("form"), it.s("filedDate").take(10), it.s("reportUrl").ifBlank { it.s("filingUrl") })
        }

    suspend fun dividends(symbol: String): List<DividendEvent> {
        runCatching {
            val r = JSONObject(Net.get("$yahoo/v8/finance/chart/${enc(symbol)}?range=5y&interval=1mo&events=div", ttlMs = 24 * 3600_000L))
                .getJSONObject("chart").getJSONArray("result").getJSONObject(0)
            val divs = r.optJSONObject("events")?.optJSONObject("dividends")
            if (divs != null) return divs.keys().asSequence().map { divs.getJSONObject(it) }.map {
                DividendEvent(symbol, LocalDate.ofEpochDay(it.optLong("date") / 86400).toString(), it.d("amount"))
            }.sortedByDescending { it.date }.toList()
        }
        if (Keys.has("FMP")) return JSONArray(Net.get("https://financialmodelingprep.com/stable/dividends?symbol=${enc(symbol)}&apikey=${Keys.fmp}", ttlMs = 24 * 3600_000L)).objs().map {
            DividendEvent(symbol, it.s("date"), it.d("dividend") ?: it.d("adjDividend"), it.s("paymentDate"))
        }
        return emptyList()
    }

    suspend fun etfHoldings(symbol: String): List<EtfHolding> {
        if (!Keys.has("FMP")) return emptyList()
        return JSONArray(Net.get("https://financialmodelingprep.com/stable/etf/holdings?symbol=${enc(symbol)}&apikey=${Keys.fmp}", ttlMs = 24 * 3600_000L)).objs()
            .map { EtfHolding(it.s("asset"), it.s("name"), it.d("weightPercentage") ?: 0.0) }.sortedByDescending { it.weight }.take(25)
    }

    // ---------------- Calendars & status ----------------

    suspend fun earnings(from: LocalDate = today, to: LocalDate = today.plusDays(7)): List<EarningsEvent> =
        JSONObject(Net.get("$finnhubBase/calendar/earnings?from=$from&to=$to&token=${Keys.finnhub}", ttlMs = 3 * 3600_000L))
            .optJSONArray("earningsCalendar")?.objs()?.map {
                EarningsEvent(it.s("symbol"), it.s("date"), it.s("hour"), it.d("epsEstimate"), it.d("epsActual"), it.d("revenueEstimate"))
            }?.sortedWith(compareBy({ it.date }, { -(it.revEst ?: 0.0) })) ?: emptyList()

    suspend fun ipos(from: LocalDate = today.minusDays(7), to: LocalDate = today.plusDays(30)): List<IpoEvent> =
        JSONObject(Net.get("$finnhubBase/calendar/ipo?from=$from&to=$to&token=${Keys.finnhub}", ttlMs = 6 * 3600_000L))
            .optJSONArray("ipoCalendar")?.objs()?.map {
                IpoEvent(it.s("symbol"), it.s("name"), it.s("date"), it.s("exchange"), it.s("price"), it.d("numberOfShares"), it.s("status"))
            }?.sortedBy { it.date } ?: emptyList()

    suspend fun economicCalendar(from: LocalDate = today, to: LocalDate = today.plusDays(7)): List<EconEvent> {
        if (Keys.has("FMP")) return JSONArray(Net.get("https://financialmodelingprep.com/stable/economic-calendar?from=$from&to=$to&apikey=${Keys.fmp}", ttlMs = 3 * 3600_000L)).objs()
            .filter { it.s("country") in setOf("US", "EU", "GB", "JP", "CN", "CA", "DE") }
            .map { EconEvent(it.s("date"), it.s("country"), it.s("event"), it.s("actual"), it.s("estimate"), it.s("previous"), it.s("impact")) }
            .sortedBy { it.time }
        val o = JSONObject(Net.get("$finnhubBase/calendar/economic?from=$from&to=$to&token=${Keys.finnhub}", ttlMs = 3 * 3600_000L, allowStale = false))
        return o.optJSONArray("economicCalendar")?.objs()?.map {
            EconEvent(it.s("time"), it.s("country"), it.s("event"), it.s("actual"), it.s("estimate"), it.s("prev"), it.s("impact"))
        } ?: emptyList()
    }

    suspend fun dividendCalendar(from: LocalDate = today, to: LocalDate = today.plusDays(14)): List<DividendEvent> {
        if (!Keys.has("FMP")) return emptyList()
        return JSONArray(Net.get("https://financialmodelingprep.com/stable/dividends-calendar?from=$from&to=$to&apikey=${Keys.fmp}", ttlMs = 6 * 3600_000L)).objs()
            .map { DividendEvent(it.s("symbol"), it.s("date"), it.d("dividend") ?: it.d("adjDividend"), it.s("paymentDate")) }
    }

    suspend fun holidays(): List<Holiday> =
        JSONObject(Net.get("$finnhubBase/stock/market-holiday?exchange=US&token=${Keys.finnhub}", ttlMs = 7 * 24 * 3600_000L))
            .optJSONArray("data")?.objs()?.map { Holiday(it.s("eventName"), it.s("atDate"), it.s("tradingHour")) }
            ?.filter { it.date >= today.toString() }?.sortedBy { it.date } ?: emptyList()

    suspend fun marketStatus(): MarketStatus {
        val o = JSONObject(Net.get("$finnhubBase/stock/market-status?exchange=US&token=${Keys.finnhub}", ttlMs = 60_000))
        return MarketStatus(o.optBoolean("isOpen"), o.s("session"), o.s("holiday").ifBlank { null })
    }

    // ---------------- Economy (FRED) ----------------

    val fredSeries = listOf(
        Triple("FEDFUNDS", "Fed funds rate", "%"), Triple("CPIAUCSL", "CPI inflation (YoY)", "%"), Triple("UNRATE", "Unemployment", "%"),
        Triple("A191RL1Q225SBEA", "Real GDP growth (QoQ ann.)", "%"), Triple("PAYEMS", "Nonfarm payrolls (m/m, K)", "K"),
        Triple("T10Y2Y", "10Y–2Y spread", "%"), Triple("MORTGAGE30US", "30-yr mortgage", "%"), Triple("UMCSENT", "Consumer sentiment", ""),
        Triple("DCOILWTICO", "WTI crude (FRED)", "$"), Triple("M2SL", "M2 money supply", "B$"),
    )
    val curveSeries = listOf("DGS1MO" to "1M", "DGS3MO" to "3M", "DGS6MO" to "6M", "DGS1" to "1Y", "DGS2" to "2Y", "DGS5" to "5Y", "DGS10" to "10Y", "DGS20" to "20Y", "DGS30" to "30Y")

    suspend fun fred(id: String, limit: Int = 60): List<Pair<String, Double>> {
        val o = JSONObject(Net.get("https://api.stlouisfed.org/fred/series/observations?series_id=$id&api_key=${Keys.fred}&file_type=json&sort_order=desc&limit=$limit", ttlMs = 6 * 3600_000L))
        return o.getJSONArray("observations").objs().mapNotNull { ob -> ob.s("value").toDoubleOrNull()?.let { ob.s("date") to it } }.reversed()
    }

    suspend fun econSeries(id: String, title: String, unit: String): EconSeries {
        val raw = fred(id, if (id == "CPIAUCSL" || id == "PAYEMS") 40 else 36)
        val hist = when (id) {
            "CPIAUCSL" -> raw.drop(12).mapIndexed { i, (d, v) -> d to (v / raw[i].second - 1) * 100 }
            "PAYEMS" -> raw.drop(1).mapIndexed { i, (d, v) -> d to (v - raw[i].second) }
            else -> raw
        }
        return EconSeries(id, title, unit, hist.lastOrNull()?.second, hist.lastOrNull()?.first.orEmpty(), hist)
    }

    suspend fun yieldCurve(): List<Pair<String, Double>> = coroutineScope {
        if (Keys.has("FRED")) curveSeries.map { (id, label) -> async { runCatching { fred(id, 10).lastOrNull()?.second }.getOrNull()?.let { label to it } } }
            .awaitAll().filterNotNull()
        else quotes(Catalog.yields).let { q -> Catalog.yields.mapNotNull { s -> q[s]?.let { Catalog.shortName(s) to it.price } } }
    }

    // ---------------- News ----------------

    val rssFeeds = mapOf(
        "general" to listOf(
            "CNBC" to "https://search.cnbc.com/rs/search/combinedcms/view.xml?partnerId=wrss01&id=100003114",
            "MarketWatch" to "https://feeds.content.dowjones.io/public/rss/mw_topstories",
            "Yahoo Finance" to "https://finance.yahoo.com/news/rssindex",
        ),
        "markets" to listOf(
            "CNBC Markets" to "https://search.cnbc.com/rs/search/combinedcms/view.xml?partnerId=wrss01&id=20910258",
            "MarketWatch" to "https://feeds.content.dowjones.io/public/rss/mw_marketpulse",
        ),
        "crypto" to listOf(
            "CoinDesk" to "https://www.coindesk.com/arc/outboundfeeds/rss/",
            "Cointelegraph" to "https://cointelegraph.com/rss",
        ),
        "business" to listOf(
            "CNBC Business" to "https://search.cnbc.com/rs/search/combinedcms/view.xml?partnerId=wrss01&id=10001147",
            "CNBC Economy" to "https://search.cnbc.com/rs/search/combinedcms/view.xml?partnerId=wrss01&id=20910258",
        ),
    )

    /** Categories: general, markets, business, crypto, merger, forex. */
    /** Runs one news source with a time limit, recording why it failed. */
    private suspend fun source(name: String, errors: MutableList<String>, block: suspend () -> List<NewsItem>): List<NewsItem> =
        try {
            kotlinx.coroutines.withTimeoutOrNull(8_000) { block() } ?: emptyList<NewsItem>().also { synchronized(errors) { errors += "$name: timed out" } }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) { synchronized(errors) { errors += "$name: ${e.message ?: e.javaClass.simpleName}" }; emptyList() }

    /** Categories: general, markets, business, crypto, merger, forex. Each source gets 8 seconds; slow ones are skipped. */
    suspend fun news(category: String): List<NewsItem> = coroutineScope {
        val errors = mutableListOf<String>()
        val jobs = mutableListOf<kotlinx.coroutines.Deferred<List<NewsItem>>>()
        val fh = when (category) { "crypto" -> "crypto"; "merger" -> "merger"; "forex" -> "forex"; else -> "general" }
        if (Keys.has("FINNHUB")) jobs += async {
            source("Finnhub", errors) {
                JSONArray(Net.get("$finnhubBase/news?category=$fh&token=${Keys.finnhub}", ttlMs = 3 * 60_000)).objs().map {
                    NewsItem(it.s("headline"), it.s("summary"), it.s("source"), it.s("url"), it.s("image").ifBlank { null }, it.optLong("datetime"), it.s("related"), category = category)
                }
            }
        }
        (rssFeeds[category] ?: rssFeeds["general"]!!).forEach { (name, url) -> jobs += async { source(name, errors) { rss(url, name, category) } } }
        if (Keys.has("NEWSAPI") && category in setOf("general", "business", "markets")) jobs += async {
            source("NewsAPI", errors) {
                JSONObject(Net.get("https://newsapi.org/v2/top-headlines?category=business&country=us&pageSize=40&apiKey=${Keys.newsapi}", ttlMs = 10 * 60_000))
                    .getJSONArray("articles").objs().map {
                        NewsItem(it.s("title"), it.s("description"), it.optJSONObject("source")?.s("name").orEmpty(), it.s("url"),
                            it.s("urlToImage").ifBlank { null }, parseIso(it.s("publishedAt")), category = category)
                    }
            }
        }
        if (Keys.has("MARKETAUX")) jobs += async {
            source("Marketaux", errors) { marketaux(if (category == "crypto") "&entity_types=cryptocurrency" else "&countries=us", category) }
        }
        val all = jobs.awaitAll().flatten().filter { it.title.isNotBlank() && it.url.isNotBlank() }
            .distinctBy { it.title.lowercase().filter { c -> c.isLetterOrDigit() }.take(60) }
            .sortedByDescending { it.time }.take(150)
        if (all.isEmpty() && errors.isNotEmpty()) throw java.io.IOException("Couldn't load news. " + errors.joinToString("; ").take(300))
        all
    }

    suspend fun companyNews(symbol: String): List<NewsItem> = coroutineScope {
        val a = async {
            if (isCrypto(symbol)) emptyList() else runCatching {
                JSONArray(Net.get("$finnhubBase/company-news?symbol=${enc(symbol)}&from=${today.minusDays(10)}&to=$today&token=${Keys.finnhub}", ttlMs = 10 * 60_000)).objs().map {
                    NewsItem(it.s("headline"), it.s("summary"), it.s("source"), it.s("url"), it.s("image").ifBlank { null }, it.optLong("datetime"), symbol)
                }
            }.getOrDefault(emptyList())
        }
        val b = async { runCatching { rss("https://feeds.finance.yahoo.com/rss/2.0/headline?s=${enc(symbol)}&region=US&lang=en-US", "Yahoo Finance", "company") }.getOrDefault(emptyList()) }
        val c = async { if (Keys.has("MARKETAUX")) runCatching { marketaux("&symbols=${enc(if (isCrypto(symbol)) cryptoBase(symbol) else symbol)}", "company") }.getOrDefault(emptyList()) else emptyList() }
        suspend fun <T> kotlinx.coroutines.Deferred<List<T>>.safe(): List<T> = kotlinx.coroutines.withTimeoutOrNull(10_000) { await() } ?: emptyList()
        (c.safe() + a.safe() + b.safe()).filter { it.title.isNotBlank() }.distinctBy { it.title.lowercase().take(60) }.sortedByDescending { it.time }.take(60)
    }

    private suspend fun marketaux(filter: String, category: String): List<NewsItem> =
        JSONObject(Net.get("https://api.marketaux.com/v1/news/all?language=en&filter_entities=true$filter&api_token=${Keys.marketaux}", ttlMs = 15 * 60_000))
            .getJSONArray("data").objs().map { a ->
                val ents = a.optJSONArray("entities")?.objs().orEmpty()
                val sent = ents.mapNotNull { it.d("sentiment_score") }.takeIf { it.isNotEmpty() }?.average()
                NewsItem(a.s("title"), a.s("description"), a.s("source"), a.s("url"), a.s("image_url").ifBlank { null },
                    parseIso(a.s("published_at")), ents.joinToString(",") { it.s("symbol") }, sent, category)
            }

    /** Average news sentiment (-1..1) for a symbol, from Marketaux. */
    suspend fun newsSentiment(symbol: String): Double? =
        if (!Keys.has("MARKETAUX")) null else companyNews(symbol).mapNotNull { it.sentiment }.takeIf { it.isNotEmpty() }?.average()

    private fun parseIso(s: String): Long = runCatching { java.time.OffsetDateTime.parse(s).toEpochSecond() }
        .recoverCatching { java.time.Instant.parse(s).epochSecond }
        .recoverCatching { LocalDateTime.parse(s.take(19)).atZone(ZoneId.of("UTC")).toEpochSecond() }
        .getOrDefault(0)

    private val rssDate = listOf("EEE, dd MMM yyyy HH:mm:ss Z", "EEE, dd MMM yyyy HH:mm:ss zzz", "EEE, d MMM yyyy HH:mm:ss Z")

    private fun parseRssDate(s: String): Long {
        for (f in rssDate) runCatching { return SimpleDateFormat(f, Locale.US).parse(s.trim())!!.time / 1000 }
        return parseIso(s)
    }

    suspend fun rss(url: String, sourceName: String, category: String): List<NewsItem> {
        val body = Net.get(url, ttlMs = 5 * 60_000)
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(body.reader())
        val out = mutableListOf<NewsItem>()
        var inItem = false
        var title = ""; var link = ""; var desc = ""; var date = ""; var img: String? = null
        var tag = ""
        while (p.eventType != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> {
                    tag = p.name
                    if (tag == "item" || tag == "entry") { inItem = true; title = ""; link = ""; desc = ""; date = ""; img = null }
                    if (inItem && (tag == "media:content" || tag == "media:thumbnail" || tag == "enclosure")) {
                        p.getAttributeValue(null, "url")?.let { if (img == null) img = it }
                    }
                    if (inItem && tag == "link") p.getAttributeValue(null, "href")?.let { link = it }
                }
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> if (inItem) {
                    val t = p.text ?: ""
                    when (tag) {
                        "title" -> title += t
                        "link" -> if (t.isNotBlank()) link = t.trim()
                        "description", "summary" -> desc += t
                        "pubDate", "published", "updated", "dc:date" -> if (date.isBlank()) date = t
                    }
                }
                XmlPullParser.END_TAG -> {
                    if ((p.name == "item" || p.name == "entry") && inItem) {
                        inItem = false
                        out += NewsItem(title.trim(), desc.replace(Regex("<[^>]+>"), "").trim().take(400), sourceName, link, img, parseRssDate(date), category = category)
                    }
                    tag = ""
                }
            }
            p.next()
        }
        return out
    }
}
