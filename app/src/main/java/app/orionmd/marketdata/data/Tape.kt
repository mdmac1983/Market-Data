package app.orionmd.marketdata.data

import org.json.JSONObject

/** Ticker tape: which stocks scroll across the NYSE and NASDAQ rows. */
object Tape {
    val defaultNyse = listOf("JPM", "XOM", "BRK-B", "V", "WMT", "UNH", "JNJ", "PG", "HD", "BAC", "KO", "CVX", "MA", "DIS", "T", "VZ", "PFE", "CAT", "GE", "IBM")
    val defaultNasdaq = listOf("AAPL", "MSFT", "NVDA", "AMZN", "GOOGL", "META", "TSLA", "AVGO", "COST", "NFLX", "AMD", "PEP", "ADBE", "CSCO", "INTC", "QCOM", "PYPL", "SBUX", "MU", "PLTR")
    const val NYSE_INDEX = "^NYA"
    const val NASDAQ_INDEX = "^IXIC"

    private val nyseCodes = setOf("NYQ", "NYS", "NYSE", "ASE", "PCX", "NYSEArca")
    private val nasdaqCodes = setOf("NMS", "NGM", "NCM", "NAS", "NASDAQ", "NASDAQGS", "NASDAQGM", "NASDAQCM")

    @Volatile private var cache: Pair<Long, Pair<List<String>, List<String>>>? = null

    /** (NYSE symbols, NASDAQ symbols) for the current settings. */
    suspend fun symbols(s: Settings = Prefs.current): Pair<List<String>, List<String>> {
        if (s.tapeCustom) return s.tapeNyse to s.tapeNasdaq
        cache?.let { (t, v) -> if (System.currentTimeMillis() - t < 5 * 60_000) return v.first.take(s.tapeCount) to v.second.take(s.tapeCount) }
        val v = runCatching { mostActive() }.getOrNull()?.takeIf { it.first.size >= 5 && it.second.size >= 5 }
            ?: (defaultNyse to defaultNasdaq)
        cache = System.currentTimeMillis() to v
        return v.first.take(s.tapeCount) to v.second.take(s.tapeCount)
    }

    /** Most active stocks today, split by listing exchange (Yahoo screener). */
    private suspend fun mostActive(): Pair<List<String>, List<String>> {
        val o = JSONObject(Net.get("https://query1.finance.yahoo.com/v1/finance/screener/predefined/saved?scrIds=most_actives&count=100", ttlMs = 5 * 60_000))
        val quotes = o.getJSONObject("finance").getJSONArray("result").getJSONObject(0).getJSONArray("quotes")
        val nyse = mutableListOf<String>(); val nasdaq = mutableListOf<String>()
        for (i in 0 until quotes.length()) {
            val q = quotes.getJSONObject(i)
            val sym = q.optString("symbol").takeIf { it.isNotBlank() && !it.contains("^") } ?: continue
            when (q.optString("exchange")) {
                in nyseCodes -> nyse += sym
                in nasdaqCodes -> nasdaq += sym
            }
            q.optString("shortName").takeIf { it.isNotBlank() }?.let { names[sym] = it }
        }
        return nyse to nasdaq
    }

    val names = java.util.concurrent.ConcurrentHashMap<String, String>()
}
