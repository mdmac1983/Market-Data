package app.orionmd.marketdata.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class Watchlist(val id: String, val name: String, val symbols: List<String>)

enum class TxnKind(val label: String) {
    BUY("Buy"), SELL("Sell"), DIVIDEND("Dividend"), DEPOSIT("Deposit"), WITHDRAWAL("Withdraw");

    /** Cash-only transactions (no symbol); the amount is stored in [Txn.price]. */
    val isCash get() = this == DEPOSIT || this == WITHDRAWAL
    /** Kinds entered as a single dollar amount rather than quantity × price. */
    val amountOnly get() = this == DIVIDEND || isCash
}

const val CASH_SYMBOL = "CASH"
data class Txn(val id: String, val symbol: String, val kind: TxnKind, val qty: Double, val price: Double, val fees: Double, val date: Long, val note: String = "", val portfolio: String = "main")

/** A named portfolio (like a watchlist). Transactions point to it by [id]. */
data class PortfolioDef(
    val id: String,
    val name: String,
    /** Commission charged on crypto trades, in percent of trade value (E*TRADE crypto via Zero Hash: 0.5). */
    val cryptoFeePct: Double = 0.0,
) {
    /** Fee for a crypto trade of [qty] × [price] under this portfolio's commission rate. */
    fun cryptoFee(qty: Double, price: Double) = kotlin.math.round(qty * price * cryptoFeePct) / 100.0
}

const val ETRADE_CRYPTO_FEE_PCT = 0.5

const val ALL_PORTFOLIOS = "all"

enum class AlertKind(val label: String) { ABOVE("Price above"), BELOW("Price below"), PCT_UP("Day change ≥ +%"), PCT_DOWN("Day change ≤ −%") }
data class Alert(val id: String, val symbol: String, val kind: AlertKind, val value: Double, val enabled: Boolean = true, val lastFired: Long = 0, val repeat: Boolean = false)

data class Trendline(val t1: Long, val p1: Double, val t2: Long, val p2: Double)

data class PaperPos(val qty: Double, val avg: Double)
data class PaperTrade(val symbol: String, val side: String, val qty: Double, val price: Double, val time: Long)
data class Paper(val cash: Double = 100_000.0, val start: Double = 100_000.0, val positions: Map<String, PaperPos> = emptyMap(), val history: List<PaperTrade> = emptyList())

data class AppData(
    val watchlists: List<Watchlist> = listOf(Watchlist("main", "My Watchlist", Catalog.defaultWatchlist)),
    val dashboard: List<String> = DashboardCard.defaults,
    val txns: List<Txn> = emptyList(),
    val alerts: List<Alert> = emptyList(),
    val notes: Map<String, String> = emptyMap(),
    val recent: List<String> = emptyList(),
    val trendlines: Map<String, List<Trendline>> = emptyMap(),
    val paper: Paper = Paper(),
    val pdfOptions: Map<String, String> = emptyMap(),
    val portfolios: List<PortfolioDef> = listOf(PortfolioDef("main", "My Portfolio")),
    /** Which portfolio the dashboard card shows: a portfolio id or [ALL_PORTFOLIOS]. */
    val dashPortfolio: String = ALL_PORTFOLIOS,
    val migrations: Int = 0,
    /** Dashboard cards shown at half width when the dashboard has 2+ columns. */
    val dashHalf: List<String> = listOf("FEAR_GREED", "FUTURES", "YIELDS", "FOREX", "COMMODITIES", "STATUS"),
    /** Dashboard cards collapsed to their title bar. */
    val dashCollapsed: List<String> = emptyList(),
) {
    fun txnsOf(portfolioId: String): List<Txn> = if (portfolioId == ALL_PORTFOLIOS) txns else txns.filter { it.portfolio == portfolioId }
    fun portfolioName(id: String): String = if (id == ALL_PORTFOLIOS) "All portfolios" else portfolios.firstOrNull { it.id == id }?.name ?: "Portfolio"
}

/** Cards that can be placed on the dashboard. */
enum class DashboardCard(val title: String) {
    STATUS("Market status"), INDICES("Major indices"), FUTURES("Stock futures"), WATCHLIST("Watchlist"),
    MOVERS("Top movers"), SECTORS("Sector heatmap"), CRYPTO("Crypto"), FEAR_GREED("Fear & Greed"),
    PORTFOLIO("Portfolio"), FOREX("Forex"), COMMODITIES("Commodities"), YIELDS("Treasury yields"),
    GLOBAL("Global markets"), NEWS("Headlines"), EARNINGS("Upcoming earnings"), RECENT("Recently viewed");

    companion object {
        val defaults = listOf(STATUS, INDICES, WATCHLIST, PORTFOLIO, MOVERS, CRYPTO, FEAR_GREED, SECTORS, NEWS, FUTURES, COMMODITIES, YIELDS, FOREX).map { it.name }
    }
}

object Store {
    private lateinit var file: File
    private val _data = MutableStateFlow(AppData())
    val data: StateFlow<AppData> = _data
    val current get() = _data.value

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "appdata.json")
        if (file.exists()) runCatching { _data.value = fromJson(JSONObject(file.readText())) }
        migrate()
    }

    /** One-time upgrades for data saved by older versions. */
    fun migrate() {
        val d = _data.value
        if (d.migrations < 1) update { cur ->
            // v1: the Portfolio card was not on the default dashboard; add it after the watchlist.
            val dash = if (DashboardCard.PORTFOLIO.name in cur.dashboard) cur.dashboard else {
                val i = cur.dashboard.indexOf(DashboardCard.WATCHLIST.name)
                cur.dashboard.toMutableList().apply { add(if (i >= 0) i + 1 else size.coerceAtMost(2), DashboardCard.PORTFOLIO.name) }
            }
            val ids = cur.portfolios.map { it.id }.toSet()
            cur.copy(dashboard = dash, migrations = 1,
                txns = cur.txns.map { if (it.portfolio in ids) it else it.copy(portfolio = cur.portfolios.first().id) })
        }
    }

    @Synchronized
    fun update(f: (AppData) -> AppData) {
        _data.value = f(_data.value)
        runCatching {
            val tmp = File(file.parentFile, "appdata.tmp")
            tmp.writeText(toJson(_data.value).toString())
            tmp.renameTo(file)
        }
    }

    fun newId() = UUID.randomUUID().toString().take(8)

    // ---- convenience mutators ----
    fun addToWatchlist(listId: String, symbol: String) = update { d ->
        d.copy(watchlists = d.watchlists.map { if (it.id == listId && symbol !in it.symbols) it.copy(symbols = it.symbols + symbol) else it })
    }
    fun removeFromWatchlist(listId: String, symbol: String) = update { d ->
        d.copy(watchlists = d.watchlists.map { if (it.id == listId) it.copy(symbols = it.symbols - symbol) else it })
    }
    fun viewed(symbol: String) = update { d -> d.copy(recent = (listOf(symbol) + d.recent.filter { it != symbol }).take(20)) }
    fun setNote(symbol: String, note: String) = update { d -> d.copy(notes = if (note.isBlank()) d.notes - symbol else d.notes + (symbol to note)) }

    fun allWatchedSymbols(): List<String> = current.watchlists.flatMap { it.symbols }.distinct()

    // ---- JSON ----
    fun toJson(d: AppData): JSONObject = JSONObject().apply {
        put("watchlists", JSONArray(d.watchlists.map { JSONObject().put("id", it.id).put("name", it.name).put("symbols", JSONArray(it.symbols)) }))
        put("dashboard", JSONArray(d.dashboard))
        put("txns", JSONArray(d.txns.map {
            JSONObject().put("id", it.id).put("symbol", it.symbol).put("kind", it.kind.name).put("qty", it.qty).put("price", it.price)
                .put("fees", it.fees).put("date", it.date).put("note", it.note).put("portfolio", it.portfolio)
        }))
        put("alerts", JSONArray(d.alerts.map {
            JSONObject().put("id", it.id).put("symbol", it.symbol).put("kind", it.kind.name).put("value", it.value)
                .put("enabled", it.enabled).put("lastFired", it.lastFired).put("repeat", it.repeat)
        }))
        put("notes", JSONObject(d.notes as Map<*, *>))
        put("recent", JSONArray(d.recent))
        put("trendlines", JSONObject().apply {
            d.trendlines.forEach { (k, v) -> put(k, JSONArray(v.map { JSONArray(listOf(it.t1, it.p1, it.t2, it.p2)) })) }
        })
        put("paper", JSONObject().apply {
            put("cash", d.paper.cash); put("start", d.paper.start)
            put("positions", JSONObject().apply { d.paper.positions.forEach { (k, v) -> put(k, JSONArray(listOf(v.qty, v.avg))) } })
            put("history", JSONArray(d.paper.history.map {
                JSONObject().put("s", it.symbol).put("side", it.side).put("q", it.qty).put("p", it.price).put("t", it.time)
            }))
        })
        put("pdfOptions", JSONObject(d.pdfOptions as Map<*, *>))
        put("portfolios", JSONArray(d.portfolios.map { JSONObject().put("id", it.id).put("name", it.name).put("cryptoFeePct", it.cryptoFeePct) }))
        put("dashPortfolio", d.dashPortfolio)
        put("migrations", d.migrations)
        put("dashHalf", JSONArray(d.dashHalf)); put("dashCollapsed", JSONArray(d.dashCollapsed))
    }

    private fun JSONArray?.strings(): List<String> = this?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
    private fun JSONArray?.objects(): List<JSONObject> = this?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } } ?: emptyList()
    private fun JSONObject?.stringMap(): Map<String, String> = this?.let { o -> o.keys().asSequence().associateWith { o.optString(it) } } ?: emptyMap()

    fun fromJson(o: JSONObject): AppData {
        val def = AppData()
        return AppData(
            watchlists = o.optJSONArray("watchlists").objects().map {
                Watchlist(it.optString("id"), it.optString("name"), it.optJSONArray("symbols").strings())
            }.ifEmpty { def.watchlists },
            dashboard = o.optJSONArray("dashboard")?.strings()?.filter { n -> DashboardCard.entries.any { it.name == n } } ?: def.dashboard,
            txns = o.optJSONArray("txns").objects().map {
                Txn(it.optString("id"), it.optString("symbol"), runCatching { TxnKind.valueOf(it.optString("kind")) }.getOrDefault(TxnKind.BUY),
                    it.optDouble("qty"), it.optDouble("price"), it.optDouble("fees", 0.0), it.optLong("date"), it.optString("note"),
                    it.optString("portfolio", "main").ifBlank { "main" })
            },
            alerts = o.optJSONArray("alerts").objects().map {
                Alert(it.optString("id"), it.optString("symbol"), runCatching { AlertKind.valueOf(it.optString("kind")) }.getOrDefault(AlertKind.ABOVE),
                    it.optDouble("value"), it.optBoolean("enabled", true), it.optLong("lastFired"), it.optBoolean("repeat"))
            },
            notes = o.optJSONObject("notes").stringMap(),
            recent = o.optJSONArray("recent").strings(),
            trendlines = o.optJSONObject("trendlines")?.let { t ->
                t.keys().asSequence().associateWith { k ->
                    val a = t.optJSONArray(k)
                    (0 until (a?.length() ?: 0)).mapNotNull { i ->
                        a!!.optJSONArray(i)?.let { l -> Trendline(l.optLong(0), l.optDouble(1), l.optLong(2), l.optDouble(3)) }
                    }
                }
            } ?: emptyMap(),
            paper = o.optJSONObject("paper")?.let { p ->
                Paper(
                    cash = p.optDouble("cash", 100_000.0), start = p.optDouble("start", 100_000.0),
                    positions = p.optJSONObject("positions")?.let { ps ->
                        ps.keys().asSequence().associateWith { k -> ps.optJSONArray(k).let { PaperPos(it.optDouble(0), it.optDouble(1)) } }
                    } ?: emptyMap(),
                    history = p.optJSONArray("history").objects().map {
                        PaperTrade(it.optString("s"), it.optString("side"), it.optDouble("q"), it.optDouble("p"), it.optLong("t"))
                    },
                )
            } ?: Paper(),
            pdfOptions = o.optJSONObject("pdfOptions").stringMap(),
            portfolios = o.optJSONArray("portfolios").objects().map { PortfolioDef(it.optString("id"), it.optString("name"), it.optDouble("cryptoFeePct", 0.0).takeIf { v -> !v.isNaN() } ?: 0.0) }
                .filter { it.id.isNotBlank() }.ifEmpty { def.portfolios },
            dashPortfolio = o.optString("dashPortfolio", ALL_PORTFOLIOS).ifBlank { ALL_PORTFOLIOS },
            migrations = o.optInt("migrations", 0),
            dashHalf = o.optJSONArray("dashHalf")?.strings() ?: def.dashHalf,
            dashCollapsed = o.optJSONArray("dashCollapsed").strings(),
        )
    }
}
