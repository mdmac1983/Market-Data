package app.orionmd.marketdata.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap

/**
 * Central live-quote hub. Screens register the symbols they show; the hub polls them on the
 * refresh interval and streams tick-by-tick prices over WebSockets (Finnhub for stocks, Coinbase for crypto).
 */
object QuoteHub {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + kotlinx.coroutines.CoroutineExceptionHandler { _, e -> android.util.Log.e("QuoteHub", "background error", e) })
    private val _quotes = MutableStateFlow<Map<String, Quote>>(emptyMap())
    val quotes: StateFlow<Map<String, Quote>> = _quotes
    private val _lastUpdate = MutableStateFlow(0L)
    val lastUpdate: StateFlow<Long> = _lastUpdate

    private val interest = ConcurrentHashMap<String, Int>()
    private var pollJob: Job? = null
    var onQuotes: ((Map<String, Quote>) -> Unit)? = null

    fun watch(symbols: Collection<String>) {
        var added = false
        symbols.forEach { s -> interest.merge(s, 1, Int::plus); if (s !in _quotes.value) added = true }
        Stream.update(interest.keys)
        if (added) refreshNow(symbols.filter { it !in _quotes.value })
    }

    fun watchedSymbols(): Set<String> = interest.keys.toSet()

    fun unwatch(symbols: Collection<String>) {
        symbols.forEach { s -> interest.computeIfPresent(s) { _, n -> if (n <= 1) null else n - 1 } }
        Stream.update(interest.keys)
    }

    fun start() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive) {
                val syms = interest.keys.toList()
                if (syms.isNotEmpty()) fetch(syms)
                delay(Prefs.current.refreshSec.coerceIn(10, 600) * 1000L)
            }
        }
        Stream.loadCoinbaseProducts()
        Stream.update(interest.keys)
    }

    fun stop() { pollJob?.cancel(); pollJob = null; Stream.stop() }

    fun refreshNow(symbols: Collection<String> = interest.keys.toList()) {
        scope.launch { fetch(symbols) }
    }

    private suspend fun fetch(symbols: Collection<String>) {
        val q = Market.quotes(symbols)
        if (q.isNotEmpty()) {
            put(q)
            _lastUpdate.value = System.currentTimeMillis()
        }
    }

    fun put(q: Map<String, Quote>) {
        _quotes.value = _quotes.value + q.mapValues { (s, new) ->
            val old = _quotes.value[s]
            // keep a sparkline if the new source didn't provide one
            if (new.spark.isEmpty() && old != null && old.spark.isNotEmpty()) new.copy(spark = old.spark) else new
        }
        runCatching { onQuotes?.invoke(q) }
    }

    internal fun tick(symbol: String, price: Double) {
        val cur = _quotes.value[symbol] ?: return
        if (cur.price == price) return
        _quotes.value = _quotes.value + (symbol to cur.withLive(price))
        _lastUpdate.value = System.currentTimeMillis()
    }
}

object Stream {
    private var finnhub: WebSocket? = null
    private var coinbase: WebSocket? = null
    private val fhSubs = mutableSetOf<String>()
    private var cbSubs = setOf<String>()
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected
    private var retryJob: Job? = null

    /** Products Coinbase actually lists; subscribing to an unknown one makes the whole subscription fail. */
    @Volatile var coinbaseProducts: Set<String> = setOf(
        "BTC-USD", "ETH-USD", "SOL-USD", "XRP-USD", "DOGE-USD", "ADA-USD", "AVAX-USD", "LINK-USD", "DOT-USD", "LTC-USD",
        "BCH-USD", "SHIB-USD", "UNI-USD", "XLM-USD", "ATOM-USD", "NEAR-USD", "APT-USD", "ARB-USD", "OP-USD", "SUI-USD",
    )

    fun loadCoinbaseProducts() {
        QuoteHub.scope.launch {
            runCatching {
                val arr = JSONArray(Net.get("https://api.exchange.coinbase.com/products", ttlMs = 24 * 3600_000L))
                val set = (0 until arr.length()).map { arr.getJSONObject(it) }
                    .filter { it.optString("quote_currency") == "USD" && !it.optBoolean("trading_disabled") && it.optString("status") != "delisted" }
                    .map { it.getString("id") }.toSet()
                if (set.size > 20) { coinbaseProducts = set; update(QuoteHub.watchedSymbols()) }
            }
        }
    }

    @Synchronized
    fun update(symbols: Collection<String>) {
        if (!Prefs.current.streaming) { stop(); return }
        val stocks = symbols.filter { typeOf(it) == AssetType.STOCK || typeOf(it) == AssetType.ETF }.toSet()
        val cryptos = symbols.filter { isCrypto(it) && it in coinbaseProducts }.toSet()
        if (stocks.isNotEmpty() && Keys.has("FINNHUB")) {
            val ws = finnhub ?: openFinnhub().also { finnhub = it }
            (stocks - fhSubs).forEach { ws.send("""{"type":"subscribe","symbol":"$it"}""") }
            (fhSubs - stocks).forEach { ws.send("""{"type":"unsubscribe","symbol":"$it"}""") }
            fhSubs.clear(); fhSubs.addAll(stocks)
        }
        if (cryptos != cbSubs) {
            coinbase?.close(1000, null); coinbase = null
            cbSubs = cryptos
            if (cryptos.isNotEmpty()) coinbase = openCoinbase(cryptos)
        }
    }

    @Synchronized
    fun stop() {
        finnhub?.close(1000, null); finnhub = null; fhSubs.clear()
        coinbase?.close(1000, null); coinbase = null; cbSubs = emptySet()
        _connected.value = false
    }

    private fun openFinnhub(): WebSocket {
        val req = Request.Builder().url("wss://ws.finnhub.io?token=${Keys.finnhub}").build()
        return Net.client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _connected.value = true
                synchronized(this@Stream) { fhSubs.forEach { webSocket.send("""{"type":"subscribe","symbol":"$it"}""") } }
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val o = JSONObject(text)
                    if (o.optString("type") != "trade") return
                    val data = o.getJSONArray("data")
                    val last = HashMap<String, Double>()
                    for (i in 0 until data.length()) data.getJSONObject(i).let { last[it.getString("s")] = it.getDouble("p") }
                    last.forEach { (s, p) -> QuoteHub.tick(s, p) }
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                synchronized(this@Stream) { if (finnhub === webSocket) finnhub = null }
                _connected.value = false
                scheduleRetry()
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { _connected.value = false }
        })
    }

    private fun openCoinbase(products: Set<String>): WebSocket {
        val req = Request.Builder().url("wss://ws-feed.exchange.coinbase.com").build()
        return Net.client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(JSONObject().put("type", "subscribe").put("product_ids", JSONArray(products.toList()))
                    .put("channels", JSONArray(listOf("ticker"))).toString())
                _connected.value = true
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val o = JSONObject(text)
                    if (o.optString("type") == "ticker") QuoteHub.tick(o.getString("product_id"), o.getString("price").toDouble())
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                synchronized(this@Stream) { if (coinbase === webSocket) { coinbase = null; cbSubs = emptySet() } }
                scheduleRetry()
            }
        })
    }

    private fun scheduleRetry() {
        if (retryJob?.isActive == true) return
        retryJob = QuoteHub.scope.launch {
            delay(15_000)
            QuoteHub.refreshNow()
            QuoteHub.watch(emptyList())
        }
    }
}

/** US equity market clock (Eastern Time). */
object MarketClock {
    val ET: ZoneId = ZoneId.of("America/New_York")
    private val preOpen = LocalTime.of(4, 0)
    private val open = LocalTime.of(9, 30)
    private val close = LocalTime.of(16, 0)
    private val postClose = LocalTime.of(20, 0)

    data class State(val session: String, val isOpen: Boolean, val nextLabel: String, val countdown: Duration)

    fun state(now: ZonedDateTime = ZonedDateTime.now(ET), holidays: Set<String> = emptySet()): State {
        fun tradingDay(d: ZonedDateTime) = d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY && d.toLocalDate().toString() !in holidays
        fun nextOpen(from: ZonedDateTime): ZonedDateTime {
            var d = from.toLocalDate().atTime(open).atZone(ET)
            if (!from.isBefore(d)) d = d.plusDays(1)
            while (!tradingDay(d)) d = d.plusDays(1)
            return d
        }
        val t = now.toLocalTime()
        if (!tradingDay(now)) return State(if (now.toLocalDate().toString() in holidays) "Holiday" else "Weekend", false, "Opens in", Duration.between(now, nextOpen(now)))
        return when {
            t.isBefore(preOpen) -> State("Closed", false, "Opens in", Duration.between(now, nextOpen(now)))
            t.isBefore(open) -> State("Pre-market", false, "Opens in", Duration.between(now, nextOpen(now)))
            t.isBefore(close) -> State("Market open", true, "Closes in", Duration.between(now, now.toLocalDate().atTime(close).atZone(ET)))
            t.isBefore(postClose) -> State("After hours", false, "Opens in", Duration.between(now, nextOpen(now)))
            else -> State("Closed", false, "Opens in", Duration.between(now, nextOpen(now)))
        }
    }

    fun fmt(d: Duration): String {
        val h = d.toHours(); val m = d.toMinutes() % 60; val s = d.seconds % 60
        return if (h >= 24) "${h / 24}d ${h % 24}h" else if (h > 0) "${h}h ${m}m" else "${m}m ${s}s"
    }
}

/** Technical indicators. */
object Indicators {
    fun sma(v: List<Double>, n: Int): List<Double?> = v.indices.map { i -> if (i + 1 < n) null else v.subList(i + 1 - n, i + 1).average() }

    fun ema(v: List<Double>, n: Int): List<Double?> {
        if (v.isEmpty()) return emptyList()
        val k = 2.0 / (n + 1)
        val out = ArrayList<Double?>(v.size)
        var prev: Double? = null
        v.forEachIndexed { i, x ->
            prev = when {
                i + 1 < n -> null
                i + 1 == n -> v.subList(0, n).average()
                else -> x * k + prev!! * (1 - k)
            }
            out += prev
        }
        return out
    }

    fun rsi(v: List<Double>, n: Int = 14): List<Double?> {
        if (v.size <= n) return v.map { null }
        val out = MutableList<Double?>(v.size) { null }
        var gain = 0.0; var loss = 0.0
        for (i in 1..n) { val d = v[i] - v[i - 1]; if (d > 0) gain += d else loss -= d }
        gain /= n; loss /= n
        out[n] = if (loss == 0.0) 100.0 else 100 - 100 / (1 + gain / loss)
        for (i in n + 1 until v.size) {
            val d = v[i] - v[i - 1]
            gain = (gain * (n - 1) + maxOf(d, 0.0)) / n
            loss = (loss * (n - 1) + maxOf(-d, 0.0)) / n
            out[i] = if (loss == 0.0) 100.0 else 100 - 100 / (1 + gain / loss)
        }
        return out
    }

    data class Macd(val macd: List<Double?>, val signal: List<Double?>, val hist: List<Double?>)

    fun macd(v: List<Double>, fast: Int = 12, slow: Int = 26, sig: Int = 9): Macd {
        val f = ema(v, fast); val s = ema(v, slow)
        val m = v.indices.map { i -> if (f[i] != null && s[i] != null) f[i]!! - s[i]!! else null }
        val firstIdx = m.indexOfFirst { it != null }
        val signal = MutableList<Double?>(v.size) { null }
        if (firstIdx >= 0) {
            val sub = ema(m.drop(firstIdx).map { it!! }, sig)
            sub.forEachIndexed { i, x -> signal[firstIdx + i] = x }
        }
        return Macd(m, signal, v.indices.map { i -> if (m[i] != null && signal[i] != null) m[i]!! - signal[i]!! else null })
    }

    data class Bands(val mid: List<Double?>, val upper: List<Double?>, val lower: List<Double?>)

    fun bollinger(v: List<Double>, n: Int = 20, k: Double = 2.0): Bands {
        val mid = sma(v, n)
        val sd = v.indices.map { i ->
            if (i + 1 < n) null else { val w = v.subList(i + 1 - n, i + 1); val m = w.average(); Math.sqrt(w.sumOf { (it - m) * (it - m) } / n) }
        }
        return Bands(mid, mid.indices.map { i -> mid[i]?.let { it + k * sd[i]!! } }, mid.indices.map { i -> mid[i]?.let { it - k * sd[i]!! } })
    }
}
