package app.orionmd.marketdata.data

import android.content.Context
import android.content.SharedPreferences
import app.orionmd.marketdata.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.security.MessageDigest

enum class ThemeMode { SYSTEM, DARK, LIGHT }
enum class ReportSchedule { OFF, DAILY, WEEKLY }

data class Settings(
    val theme: ThemeMode = ThemeMode.DARK,
    val refreshSec: Int = 30,
    val streaming: Boolean = true,
    val textScale: Float = 1f,
    val compact: Boolean = false,
    /** 0 = large, 1 = medium, 2 = small cards. */
    val cardSize: Int = 0,
    /** Dashboard columns; 0 = automatic. */
    val dashColumns: Int = 0,
    val watermarkAlpha: Float = 0.35f,
    val lightWatermarkAlpha: Float = 0.6f,
    val lockPortfolio: Boolean = false,
    val pinHash: String = "",
    val useBiometric: Boolean = true,
    val morningSummary: Boolean = false,
    val closingSummary: Boolean = false,
    val reportSchedule: ReportSchedule = ReportSchedule.OFF,
    val reportHour: Int = 17,
    val showExtendedHours: Boolean = true,
    // Ticker tape (NYSE + NASDAQ rows under the top bar)
    val tapeOn: Boolean = true,
    /** Overbought / oversold badges in watchlists and lists. */
    val showSignals: Boolean = true,
    val tapeAllScreens: Boolean = true,
    val tapeSpeed: Int = 1,          // 0 slow, 1 normal, 2 fast
    val tapeCustom: Boolean = false, // false = most active, true = my lists
    val tapeCount: Int = 15,
    val tapeNyse: List<String> = Tape.defaultNyse,
    val tapeNasdaq: List<String> = Tape.defaultNasdaq,
    val keys: Map<String, String> = emptyMap(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("theme", theme.name); put("refreshSec", refreshSec); put("streaming", streaming); put("textScale", textScale.toDouble())
        put("compact", compact); put("cardSize", cardSize); put("dashColumns", dashColumns); put("watermarkAlpha", watermarkAlpha.toDouble()); put("lightWatermarkAlpha", lightWatermarkAlpha.toDouble()); put("lockPortfolio", lockPortfolio)
        put("pinHash", pinHash); put("useBiometric", useBiometric); put("morningSummary", morningSummary)
        put("closingSummary", closingSummary); put("reportSchedule", reportSchedule.name); put("reportHour", reportHour)
        put("showExtendedHours", showExtendedHours)
        put("tapeOn", tapeOn); put("showSignals", showSignals); put("tapeAllScreens", tapeAllScreens); put("tapeSpeed", tapeSpeed); put("tapeCustom", tapeCustom)
        put("tapeCount", tapeCount); put("tapeNyse", org.json.JSONArray(tapeNyse)); put("tapeNasdaq", org.json.JSONArray(tapeNasdaq)); put("keys", JSONObject(keys as Map<*, *>))
    }

    companion object {
        fun fromJson(o: JSONObject) = Settings(
            theme = runCatching { ThemeMode.valueOf(o.optString("theme")) }.getOrDefault(ThemeMode.DARK),
            refreshSec = o.optInt("refreshSec", 30),
            streaming = o.optBoolean("streaming", true),
            textScale = o.optDouble("textScale", 1.0).toFloat(),
            compact = o.optBoolean("compact", false),
            cardSize = o.optInt("cardSize", if (o.optBoolean("compact", false)) 1 else 0).coerceIn(0, 2),
            dashColumns = o.optInt("dashColumns", 0).coerceIn(0, 4),
            watermarkAlpha = o.optDouble("watermarkAlpha", 0.35).toFloat(),
            lightWatermarkAlpha = o.optDouble("lightWatermarkAlpha", 0.6).toFloat(),
            lockPortfolio = o.optBoolean("lockPortfolio", false),
            pinHash = o.optString("pinHash", ""),
            useBiometric = o.optBoolean("useBiometric", true),
            morningSummary = o.optBoolean("morningSummary", false),
            closingSummary = o.optBoolean("closingSummary", false),
            reportSchedule = runCatching { ReportSchedule.valueOf(o.optString("reportSchedule")) }.getOrDefault(ReportSchedule.OFF),
            reportHour = o.optInt("reportHour", 17),
            showExtendedHours = o.optBoolean("showExtendedHours", true),
            tapeOn = o.optBoolean("tapeOn", true),
            showSignals = o.optBoolean("showSignals", true),
            tapeAllScreens = o.optBoolean("tapeAllScreens", true),
            tapeSpeed = o.optInt("tapeSpeed", 1),
            tapeCustom = o.optBoolean("tapeCustom", false),
            tapeCount = o.optInt("tapeCount", 15),
            tapeNyse = o.optJSONArray("tapeNyse")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: Tape.defaultNyse,
            tapeNasdaq = o.optJSONArray("tapeNasdaq")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: Tape.defaultNasdaq,
            keys = o.optJSONObject("keys")?.let { k -> k.keys().asSequence().associateWith { k.optString(it) } } ?: emptyMap(),
        )
    }
}

object Prefs {
    private lateinit var sp: SharedPreferences
    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings
    val current get() = _settings.value

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("market_data", Context.MODE_PRIVATE)
        sp.getString("settings", null)?.let { runCatching { _settings.value = Settings.fromJson(JSONObject(it)) } }
    }

    fun update(f: (Settings) -> Settings) {
        _settings.value = f(_settings.value)
        sp.edit().putString("settings", _settings.value.toJson().toString()).apply()
    }

    fun hashPin(pin: String): String =
        MessageDigest.getInstance("SHA-256").digest(("orionmd:" + pin).toByteArray()).joinToString("") { "%02x".format(it) }
}

/** API keys: a key entered in Settings wins over the one built into the APK. */
object Keys {
    data class KeyInfo(val id: String, val label: String, val signup: String, val adds: String, val builtIn: String)

    val all = listOf(
        KeyInfo("FINNHUB", "Finnhub", "https://finnhub.io/register", "Live stock stream, quotes, news, profiles, calendars", BuildConfig.FINNHUB_API_KEY),
        KeyInfo("TWELVEDATA", "Twelve Data", "https://twelvedata.com/register", "Chart history, forex, metals", BuildConfig.TWELVEDATA_API_KEY),
        KeyInfo("COINSTATS", "CoinStats", "https://openapi.coinstats.app", "Backup crypto prices", BuildConfig.COINSTATS_API_KEY),
        KeyInfo("FRED", "FRED (St. Louis Fed)", "https://fred.stlouisfed.org/docs/api/api_key.html", "Economy dashboard, full yield curve", BuildConfig.FRED_API_KEY),
        KeyInfo("FMP", "Financial Modeling Prep", "https://site.financialmodelingprep.com/register", "Screener, economic & dividend calendars, ETF holdings, price targets", BuildConfig.FMP_API_KEY),
        KeyInfo("ALPHAVANTAGE", "Alpha Vantage", "https://www.alphavantage.co/support/#api-key", "Backup quotes and movers", BuildConfig.ALPHAVANTAGE_API_KEY),
        KeyInfo("NEWSAPI", "NewsAPI", "https://newsapi.org/register", "More business headlines", BuildConfig.NEWSAPI_API_KEY),
        KeyInfo("MARKETAUX", "Marketaux", "https://www.marketaux.com/register", "News with sentiment scores", BuildConfig.MARKETAUX_API_KEY),
        KeyInfo("POLYGON", "Polygon.io", "https://polygon.io/dashboard/signup", "Backup chart history", BuildConfig.POLYGON_API_KEY),
    )

    fun get(id: String): String =
        Prefs.current.keys[id]?.takeIf { it.isNotBlank() } ?: all.firstOrNull { it.id == id }?.builtIn.orEmpty()

    fun has(id: String) = get(id).isNotBlank()

    val finnhub get() = get("FINNHUB")
    val twelve get() = get("TWELVEDATA")
    val coinstats get() = get("COINSTATS")
    val fred get() = get("FRED")
    val fmp get() = get("FMP")
    val alpha get() = get("ALPHAVANTAGE")
    val newsapi get() = get("NEWSAPI")
    val marketaux get() = get("MARKETAUX")
    val polygon get() = get("POLYGON")
}
