package app.orionmd.marketdata.data

enum class AssetType { STOCK, ETF, INDEX, CRYPTO, FOREX, COMMODITY, BOND, FUTURE }

/** Symbol conventions: stocks "AAPL", indices "^GSPC", futures "ES=F", forex "EURUSD=X", crypto "BTC-USD". */
fun typeOf(symbol: String): AssetType = when {
    symbol.startsWith("^") -> if (symbol in Catalog.yieldSymbols) AssetType.BOND else AssetType.INDEX
    symbol.endsWith("=X") -> AssetType.FOREX
    symbol.endsWith("=F") -> if (symbol in Catalog.indexFutures) AssetType.FUTURE else AssetType.COMMODITY
    symbol.endsWith("-USD") -> AssetType.CRYPTO
    symbol in Catalog.etfs -> AssetType.ETF
    else -> AssetType.STOCK
}

fun isCrypto(symbol: String) = symbol.endsWith("-USD")
fun cryptoBase(symbol: String) = symbol.removeSuffix("-USD")

data class Quote(
    val symbol: String,
    val name: String,
    val price: Double,
    val change: Double,
    val changePct: Double,
    val open: Double? = null,
    val high: Double? = null,
    val low: Double? = null,
    val prevClose: Double? = null,
    val volume: Double? = null,
    val marketCap: Double? = null,
    val high52: Double? = null,
    val low52: Double? = null,
    val avgVolume: Double? = null,
    val extPrice: Double? = null,
    val extChangePct: Double? = null,
    val extLabel: String? = null,
    val time: Long = System.currentTimeMillis() / 1000,
    val source: String = "",
    val proxyNote: String? = null,
    val spark: List<Double> = emptyList(),
) {
    val type get() = typeOf(symbol)
    fun withLive(p: Double): Quote {
        val base = prevClose ?: (price - change)
        val ch = p - base
        return copy(price = p, change = ch, changePct = if (base != 0.0) ch / base * 100 else 0.0,
            high = high?.let { maxOf(it, p) }, low = low?.let { minOf(it, p) }, time = System.currentTimeMillis() / 1000)
    }
}

data class Candle(val t: Long, val o: Double, val h: Double, val l: Double, val c: Double, val v: Double)

data class NewsItem(
    val title: String,
    val summary: String,
    val source: String,
    val url: String,
    val image: String?,
    val time: Long,
    val related: String = "",
    val sentiment: Double? = null,
    val category: String = "",
)

data class Coin(
    val id: String,
    val symbol: String,
    val name: String,
    val image: String?,
    val price: Double,
    val changePct24h: Double,
    val marketCap: Double?,
    val volume: Double?,
    val rank: Int?,
    val sparkline: List<Double> = emptyList(),
    val high24: Double? = null,
    val low24: Double? = null,
    val ath: Double? = null,
    val circulating: Double? = null,
) {
    val ticker get() = symbol.uppercase() + "-USD"
}

data class SearchResult(val symbol: String, val name: String, val kind: String)

data class Profile(
    val name: String, val exchange: String, val industry: String, val country: String,
    val ipo: String, val marketCap: Double?, val shares: Double?, val website: String, val logo: String?, val phone: String = "",
)

data class Recommendation(val period: String, val strongBuy: Int, val buy: Int, val hold: Int, val sell: Int, val strongSell: Int)
data class InsiderTrade(val name: String, val change: Double, val shares: Double, val price: Double?, val date: String, val code: String)
data class Filing(val form: String, val filed: String, val url: String)
data class EarningsEvent(val symbol: String, val date: String, val hour: String, val epsEst: Double?, val epsActual: Double?, val revEst: Double?)
data class IpoEvent(val symbol: String, val name: String, val date: String, val exchange: String, val price: String, val shares: Double?, val status: String)
data class EconEvent(val time: String, val country: String, val event: String, val actual: String, val estimate: String, val previous: String, val impact: String)
data class DividendEvent(val symbol: String, val date: String, val amount: Double?, val payDate: String = "")
data class Holiday(val name: String, val date: String, val hours: String)
data class MarketStatus(val isOpen: Boolean, val session: String, val holiday: String?)
data class FearGreed(val value: Int, val label: String, val history: List<Int>)
data class EconSeries(val id: String, val title: String, val unit: String, val latest: Double?, val date: String, val history: List<Pair<String, Double>>)
data class EtfHolding(val symbol: String, val name: String, val weight: Double)
data class Exchange(val name: String, val country: String, val trust: Int?, val volumeBtc: Double?, val url: String, val image: String?)
data class CryptoGlobal(val totalCap: Double, val totalVol: Double, val btcDominance: Double, val ethDominance: Double, val capChangePct: Double, val activeCoins: Int)

/** Screener row built from the stock universe. */
data class UniverseRow(val symbol: String, val name: String, val sector: String, val quote: Quote?, val avgVol: Double?, val volRatio: Double?)
