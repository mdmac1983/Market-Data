package app.orionmd.marketdata.data

/** Built-in symbol lists and display names. */
object Catalog {
    val usIndices = listOf("^DJI", "^GSPC", "^IXIC", "^NYA", "^RUT", "^VIX")
    val indexFutures = setOf("YM=F", "ES=F", "NQ=F", "RTY=F")
    val futures = listOf("YM=F", "ES=F", "NQ=F", "RTY=F")
    val globalIndices = listOf("^FTSE", "^GDAXI", "^FCHI", "^STOXX50E", "^N225", "^HSI", "000001.SS", "^BSESN", "^GSPTSE", "^AXJO")
    val commodities = listOf("GC=F", "SI=F", "CL=F", "BZ=F", "NG=F", "HG=F", "PL=F", "ZC=F")
    val forex = listOf("EURUSD=X", "GBPUSD=X", "USDJPY=X", "USDCAD=X", "AUDUSD=X", "USDCHF=X", "USDCNY=X", "USDMXN=X")
    val yieldSymbols = setOf("^IRX", "^FVX", "^TNX", "^TYX")
    val yields = listOf("^IRX", "^FVX", "^TNX", "^TYX")
    val defaultCrypto = listOf("BTC-USD", "ETH-USD", "SOL-USD", "XRP-USD", "DOGE-USD", "ADA-USD")

    val sectorEtfs = linkedMapOf(
        "XLK" to "Technology", "XLF" to "Financials", "XLV" to "Health Care", "XLY" to "Cons. Discretionary",
        "XLC" to "Communication", "XLI" to "Industrials", "XLP" to "Cons. Staples", "XLE" to "Energy",
        "XLU" to "Utilities", "XLRE" to "Real Estate", "XLB" to "Materials",
    )

    val etfs = setOf("SPY", "QQQ", "DIA", "IWM", "VTI", "VOO", "GLD", "SLV", "USO", "TLT", "IEF", "VIXY", "ARKK", "SMH", "HYG", "EEM", "EFA", "BND", "AGG", "SCHD") + sectorEtfs.keys

    /** ETF proxies used when an index quote can't be fetched (Finnhub free tier has no indices). */
    val proxies = mapOf(
        "^GSPC" to "SPY", "^DJI" to "DIA", "^IXIC" to "QQQ", "^NYA" to "VTI", "^RUT" to "IWM", "^VIX" to "VIXY",
        "ES=F" to "SPY", "YM=F" to "DIA", "NQ=F" to "QQQ", "RTY=F" to "IWM",
        "GC=F" to "GLD", "SI=F" to "SLV", "CL=F" to "USO", "BZ=F" to "BNO", "NG=F" to "UNG", "HG=F" to "CPER",
        "^TNX" to "IEF", "^TYX" to "TLT",
    )
    /** Twelve Data symbols for forex/metals fallbacks. */
    val twelveData = mapOf(
        "GC=F" to "XAU/USD", "SI=F" to "XAG/USD", "PL=F" to "XPT/USD",
        "EURUSD=X" to "EUR/USD", "GBPUSD=X" to "GBP/USD", "USDJPY=X" to "USD/JPY", "USDCAD=X" to "USD/CAD",
        "AUDUSD=X" to "AUD/USD", "USDCHF=X" to "USD/CHF", "USDCNY=X" to "USD/CNY", "USDMXN=X" to "USD/MXN",
    )

    val names = mapOf(
        "^DJI" to "Dow Jones Industrial Avg", "^GSPC" to "S&P 500", "^IXIC" to "NASDAQ Composite", "^NYA" to "NYSE Composite",
        "^RUT" to "Russell 2000", "^VIX" to "VIX Volatility", "YM=F" to "Dow Futures", "ES=F" to "S&P 500 Futures",
        "NQ=F" to "NASDAQ 100 Futures", "RTY=F" to "Russell 2000 Futures", "^FTSE" to "FTSE 100 (UK)", "^GDAXI" to "DAX (Germany)",
        "^FCHI" to "CAC 40 (France)", "^STOXX50E" to "Euro Stoxx 50", "^N225" to "Nikkei 225 (Japan)", "^HSI" to "Hang Seng (HK)",
        "000001.SS" to "Shanghai Composite", "^BSESN" to "BSE Sensex (India)", "^GSPTSE" to "TSX (Canada)", "^AXJO" to "ASX 200 (Australia)",
        "GC=F" to "Gold", "SI=F" to "Silver", "CL=F" to "Crude Oil (WTI)", "BZ=F" to "Brent Crude", "NG=F" to "Natural Gas",
        "HG=F" to "Copper", "PL=F" to "Platinum", "ZC=F" to "Corn", "EURUSD=X" to "EUR/USD", "GBPUSD=X" to "GBP/USD",
        "USDJPY=X" to "USD/JPY", "USDCAD=X" to "USD/CAD", "AUDUSD=X" to "AUD/USD", "USDCHF=X" to "USD/CHF", "USDCNY=X" to "USD/CNY",
        "USDMXN=X" to "USD/MXN", "^IRX" to "13-Week T-Bill", "^FVX" to "5-Year Treasury", "^TNX" to "10-Year Treasury",
        "^TYX" to "30-Year Treasury", "BTC-USD" to "Bitcoin", "ETH-USD" to "Ethereum", "SOL-USD" to "Solana", "XRP-USD" to "XRP",
        "DOGE-USD" to "Dogecoin", "ADA-USD" to "Cardano", "SPY" to "SPDR S&P 500 ETF", "QQQ" to "Invesco QQQ", "DIA" to "SPDR Dow Jones ETF",
        "IWM" to "iShares Russell 2000", "VTI" to "Vanguard Total Market", "GLD" to "SPDR Gold", "TLT" to "20+ Yr Treasury ETF",
    ) + sectorEtfs.mapValues { "${it.value} Sector ETF" }

    fun shortName(s: String): String = when (s) {
        "^DJI" -> "Dow"; "^GSPC" -> "S&P 500"; "^IXIC" -> "NASDAQ"; "^NYA" -> "NYSE"; "^RUT" -> "Russell"; "^VIX" -> "VIX"
        "YM=F" -> "Dow Fut"; "ES=F" -> "S&P Fut"; "NQ=F" -> "NDX Fut"; "RTY=F" -> "RUT Fut"
        "^IRX" -> "3M"; "^FVX" -> "5Y"; "^TNX" -> "10Y"; "^TYX" -> "30Y"
        else -> names[s]?.takeIf { it.length <= 12 } ?: display(s)
    }

    fun display(s: String): String = when {
        s.endsWith("=X") -> s.removeSuffix("=X").let { if (it.length == 6) it.take(3) + "/" + it.drop(3) else it }
        s.endsWith("-USD") -> s.removeSuffix("-USD")
        else -> s
    }

    /** Stock universe for the offline-capable screener, movers fallback, 52-week and unusual-volume scans. */
    val universe: List<Triple<String, String, String>> = listOf(
        "AAPL|Apple|Technology", "MSFT|Microsoft|Technology", "NVDA|NVIDIA|Technology", "AVGO|Broadcom|Technology",
        "ORCL|Oracle|Technology", "CRM|Salesforce|Technology", "AMD|Advanced Micro Devices|Technology", "ADBE|Adobe|Technology",
        "CSCO|Cisco|Technology", "ACN|Accenture|Technology", "IBM|IBM|Technology", "INTC|Intel|Technology", "QCOM|Qualcomm|Technology",
        "TXN|Texas Instruments|Technology", "NOW|ServiceNow|Technology", "INTU|Intuit|Technology", "AMAT|Applied Materials|Technology",
        "MU|Micron|Technology", "PLTR|Palantir|Technology", "PANW|Palo Alto Networks|Technology", "SNOW|Snowflake|Technology",
        "SMCI|Super Micro Computer|Technology", "DELL|Dell|Technology", "ANET|Arista Networks|Technology", "CRWD|CrowdStrike|Technology",
        "GOOGL|Alphabet|Communication", "META|Meta Platforms|Communication", "NFLX|Netflix|Communication", "DIS|Disney|Communication",
        "CMCSA|Comcast|Communication", "T|AT&T|Communication", "VZ|Verizon|Communication", "TMUS|T-Mobile|Communication",
        "AMZN|Amazon|Cons. Discretionary", "TSLA|Tesla|Cons. Discretionary", "HD|Home Depot|Cons. Discretionary", "MCD|McDonald's|Cons. Discretionary",
        "NKE|Nike|Cons. Discretionary", "SBUX|Starbucks|Cons. Discretionary", "LOW|Lowe's|Cons. Discretionary", "BKNG|Booking|Cons. Discretionary",
        "GM|General Motors|Cons. Discretionary", "F|Ford|Cons. Discretionary", "ABNB|Airbnb|Cons. Discretionary", "UBER|Uber|Industrials",
        "WMT|Walmart|Cons. Staples", "COST|Costco|Cons. Staples", "PG|Procter & Gamble|Cons. Staples", "KO|Coca-Cola|Cons. Staples",
        "PEP|PepsiCo|Cons. Staples", "PM|Philip Morris|Cons. Staples", "MO|Altria|Cons. Staples", "TGT|Target|Cons. Staples",
        "JPM|JPMorgan Chase|Financials", "BAC|Bank of America|Financials", "WFC|Wells Fargo|Financials", "GS|Goldman Sachs|Financials",
        "MS|Morgan Stanley|Financials", "C|Citigroup|Financials", "BRK-B|Berkshire Hathaway|Financials", "V|Visa|Financials",
        "MA|Mastercard|Financials", "AXP|American Express|Financials", "PYPL|PayPal|Financials", "SCHW|Charles Schwab|Financials",
        "BLK|BlackRock|Financials", "COIN|Coinbase|Financials", "HOOD|Robinhood|Financials", "SOFI|SoFi|Financials",
        "UNH|UnitedHealth|Health Care", "JNJ|Johnson & Johnson|Health Care", "LLY|Eli Lilly|Health Care", "ABBV|AbbVie|Health Care",
        "MRK|Merck|Health Care", "PFE|Pfizer|Health Care", "TMO|Thermo Fisher|Health Care", "ABT|Abbott|Health Care",
        "AMGN|Amgen|Health Care", "ISRG|Intuitive Surgical|Health Care", "CVS|CVS Health|Health Care", "MRNA|Moderna|Health Care",
        "NVO|Novo Nordisk|Health Care", "XOM|Exxon Mobil|Energy", "CVX|Chevron|Energy", "COP|ConocoPhillips|Energy",
        "SLB|Schlumberger|Energy", "OXY|Occidental|Energy", "EOG|EOG Resources|Energy", "CAT|Caterpillar|Industrials",
        "BA|Boeing|Industrials", "GE|GE Aerospace|Industrials", "HON|Honeywell|Industrials", "UPS|UPS|Industrials",
        "RTX|RTX|Industrials", "LMT|Lockheed Martin|Industrials", "DE|Deere|Industrials", "UNP|Union Pacific|Industrials",
        "DAL|Delta Air Lines|Industrials", "NEE|NextEra Energy|Utilities", "DUK|Duke Energy|Utilities", "SO|Southern Co|Utilities",
        "PLD|Prologis|Real Estate", "AMT|American Tower|Real Estate", "O|Realty Income|Real Estate", "LIN|Linde|Materials",
        "FCX|Freeport-McMoRan|Materials", "NEM|Newmont|Materials", "RIVN|Rivian|Cons. Discretionary", "LCID|Lucid|Cons. Discretionary",
        "SHOP|Shopify|Technology", "BABA|Alibaba|Cons. Discretionary", "TSM|Taiwan Semiconductor|Technology", "ASML|ASML|Technology",
        "ARM|Arm Holdings|Technology", "MSTR|MicroStrategy|Technology", "RDDT|Reddit|Communication", "SPOT|Spotify|Communication",
    ).map { val p = it.split("|"); Triple(p[0], p[1], p[2]) }

    val universeNames = universe.associate { it.first to it.second }
    val sectors = universe.map { it.third }.distinct().sorted()

    fun nameOf(symbol: String): String? = names[symbol] ?: universeNames[symbol]

    val defaultWatchlist = listOf("AAPL", "MSFT", "NVDA", "AMZN", "GOOGL", "TSLA", "SPY", "BTC-USD", "ETH-USD")
}
