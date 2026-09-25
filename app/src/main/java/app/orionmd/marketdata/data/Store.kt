package app.orionmd.marketdata.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class Watchlist(val id: String, val name: String, val symbols: List<String>)

enum class TxnKind { BUY, SELL, DIVIDEND }
data class Txn(val id: String, val symbol: String, val kind: TxnKind, val qty: Double, val price: Double, val fees: Double, val date: Long, val note: String = "")

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
)

/** Cards that can be placed on the dashboard. */
enum class DashboardCard(val title: String) {
    STATUS("Market status"), INDICES("Major indices"), FUTURES("Stock futures"), WATCHLIST("Watchlist"),
    MOVERS("Top movers"), SECTORS("Sector heatmap"), CRYPTO("Crypto"), FEAR_GREED("Fear & Greed"),
    PORTFOLIO("Portfolio"), FOREX("Forex"), COMMODITIES("Commodities"), YIELDS("Treasury yields"),
    GLOBAL("Global markets"), NEWS("Headlines"), EARNINGS("Upcoming earnings"), RECENT("Recently viewed");

    companion object {
        val defaults = listOf(STATUS, INDICES, WATCHLIST, MOVERS, CRYPTO, FEAR_GREED, SECTORS, NEWS, FUTURES, COMMODITIES, YIELDS, FOREX).map { it.name }
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
                .put("fees", it.fees).put("date", it.date).put("note", it.note)
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
                    it.optDouble("qty"), it.optDouble("price"), it.optDouble("fees", 0.0), it.optLong("date"), it.optString("note"))
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
        )
    }
}
