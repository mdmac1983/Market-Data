package app.orionmd.marketdata.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

enum class SignalState(val label: String, val short: String) {
    STRONG_OVERBOUGHT("Strongly overbought", "OVERBOUGHT+"),
    OVERBOUGHT("Overbought", "OVERBOUGHT"),
    NEUTRAL("Neutral", "NEUTRAL"),
    OVERSOLD("Oversold", "OVERSOLD"),
    STRONG_OVERSOLD("Strongly oversold", "OVERSOLD+");

    val isOverbought get() = this == OVERBOUGHT || this == STRONG_OVERBOUGHT
    val isOversold get() = this == OVERSOLD || this == STRONG_OVERSOLD
}

/**
 * Overbought / oversold reading built from three standard momentum measures:
 * RSI(14), Stochastic %K(14) and Bollinger %B(20, 2).
 */
data class OverSignal(
    val symbol: String,
    val rsi: Double,
    val stochK: Double?,
    val pctB: Double?,
    val vsSma50: Double?,
    val state: SignalState,
    /** −3 (every measure oversold) … +3 (every measure overbought). */
    val votes: Int,
    val timeframe: String,
    val time: Long = System.currentTimeMillis(),
) {
    /** One-line explanation of why the signal says what it says. */
    fun reason(): String = buildList {
        add("RSI ${"%.0f".format(rsi)}" + when { rsi >= 70 -> " (≥70)"; rsi <= 30 -> " (≤30)"; else -> "" })
        stochK?.let { add("Stochastic ${"%.0f".format(it)}" + when { it >= 80 -> " (≥80)"; it <= 20 -> " (≤20)"; else -> "" }) }
        pctB?.let { add("%B ${"%.2f".format(it)}" + when { it > 1 -> " (above upper band)"; it < 0 -> " (below lower band)"; else -> "" }) }
    }.joinToString(" · ")
}

object Signals {
    private val cache = ConcurrentHashMap<String, OverSignal>()
    private val _flow = kotlinx.coroutines.flow.MutableStateFlow<Map<String, OverSignal>>(emptyMap())
    /** Latest signal per symbol, observable by the UI (badges update as signals load). */
    val flow: kotlinx.coroutines.flow.StateFlow<Map<String, OverSignal>> = _flow
    private const val TTL = 30 * 60_000L

    fun cached(symbol: String): OverSignal? = cache[symbol]?.takeIf { System.currentTimeMillis() - it.time < 6 * 3600_000L }

    /** Signal from OHLC candles (daily candles for the standard reading). */
    fun fromCandles(symbol: String, candles: List<Candle>, timeframe: String): OverSignal? {
        if (candles.size < 20) return null
        val closes = candles.map { it.c }
        val rsi = Indicators.rsi(closes).lastOrNull() ?: return null
        val n = 14
        val window = candles.takeLast(n)
        val hi = window.maxOf { it.h }; val lo = window.minOf { it.l }
        val stoch = if (hi > lo) (closes.last() - lo) / (hi - lo) * 100 else null
        return build(symbol, closes, rsi, stoch, timeframe)
    }

    /** Signal from closing prices only (e.g. a coin's 7-day hourly sparkline). */
    fun fromCloses(symbol: String, closes: List<Double>, timeframe: String): OverSignal? {
        if (closes.size < 20) return null
        val rsi = Indicators.rsi(closes).lastOrNull() ?: return null
        val window = closes.takeLast(14)
        val stoch = if (window.max() > window.min()) (closes.last() - window.min()) / (window.max() - window.min()) * 100 else null
        return build(symbol, closes, rsi, stoch, timeframe)
    }

    private fun build(symbol: String, closes: List<Double>, rsi: Double, stoch: Double?, timeframe: String): OverSignal {
        val bb = Indicators.bollinger(closes)
        val up = bb.upper.lastOrNull(); val lowB = bb.lower.lastOrNull()
        val pctB = if (up != null && lowB != null && up > lowB) (closes.last() - lowB) / (up - lowB) else null
        val sma50 = Indicators.sma(closes, 50).lastOrNull()
        var votes = 0
        if (rsi >= 70) votes++ else if (rsi <= 30) votes--
        stoch?.let { if (it >= 80) votes++ else if (it <= 20) votes-- }
        pctB?.let { if (it > 1.0) votes++ else if (it < 0.0) votes-- }
        val state = when {
            rsi >= 80 || (rsi >= 70 && votes >= 3) -> SignalState.STRONG_OVERBOUGHT
            rsi >= 70 || (rsi >= 65 && votes >= 2) -> SignalState.OVERBOUGHT
            rsi <= 20 || (rsi <= 30 && votes <= -3) -> SignalState.STRONG_OVERSOLD
            rsi <= 30 || (rsi <= 35 && votes <= -2) -> SignalState.OVERSOLD
            else -> SignalState.NEUTRAL
        }
        val s = OverSignal(symbol, rsi, stoch, pctB, sma50?.let { (closes.last() / it - 1) * 100 }, state, votes, timeframe)
        cache[symbol] = s
        _flow.value = _flow.value + (symbol to s)
        return s
    }

    /** Daily signal for one symbol (3 months of daily candles). */
    suspend fun forSymbol(symbol: String): OverSignal? {
        cache[symbol]?.takeIf { System.currentTimeMillis() - it.time < TTL && it.timeframe == "Daily" }?.let { return it }
        val candles = Market.chart(symbol, ChartRange.M3)
        return fromCandles(symbol, candles, "Daily")
    }

    suspend fun forSymbols(symbols: Collection<String>, onProgress: (Int, Int) -> Unit = { _, _ -> }): Map<String, OverSignal> = coroutineScope {
        val sem = Semaphore(5)
        var done = 0
        symbols.distinct().map { s ->
            async { sem.withPermit { runCatching { forSymbol(s) }.getOrNull().also { synchronized(this@Signals) { done++ }; onProgress(done, symbols.size) }?.let { s to it } } }
        }.awaitAll().filterNotNull().toMap()
    }

    /** Signals for the stock universe, reusing the 3-month daily data the screener already downloads. */
    suspend fun forUniverse(onProgress: (Int, Int) -> Unit = { _, _ -> }): List<Pair<UniverseRow, OverSignal>> =
        Market.universe(onProgress).mapNotNull { r -> r.closes.takeIf { it.size >= 20 }?.let { c -> fromCloses(r.symbol, c, "Daily")?.let { r to it } } }

    /** Coins from CoinGecko's 7-day hourly sparkline, resampled to 4-hour closes. */
    fun forCoin(c: Coin): OverSignal? {
        if (c.sparkline.size < 60) return null
        val fourHour = c.sparkline.chunked(4).map { it.last() }
        return fromCloses(c.ticker, fourHour, "4-hour")
    }
}
