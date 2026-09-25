package app.orionmd.marketdata.data

/** A glossary entry. [aliases] are other labels in the app that should open this entry. */
data class Term(val key: String, val title: String, val category: String, val text: String, val aliases: List<String> = emptyList())

object Glossary {
    private fun t(title: String, category: String, text: String, vararg aliases: String) = Term(norm(title), title, category, text, aliases.toList())

    fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9%&+/]"), "")

    val categories = listOf("Signals & indicators", "Markets", "Stock data", "Crypto", "Economy", "Portfolio", "App features")

    val terms: List<Term> = listOf(
        // ---------------- Signals & indicators ----------------
        t("Overbought / oversold", "Signals & indicators",
            "A reading of how stretched a price is after a run up or down. \"Overbought\" means the price rose fast and may be due for a pause or pullback; \"oversold\" means it fell fast and may be due for a bounce. It is not a buy or sell order — strong trends can stay overbought or oversold for a long time.\n\n" +
                "This app combines three measures: RSI(14) at or above 70 (overbought) or at or below 30 (oversold), Stochastic %K above 80 or below 20, and Bollinger %B above 1 or below 0. \"Strongly\" means RSI beyond 80/20 or all three agree. Stocks use daily prices; the crypto list uses 4-hour prices from the last 7 days.",
            "Overbought", "Oversold", "Signal", "Signals", "Overbought / oversold signal", "Strongly overbought", "Strongly oversold"),
        t("RSI (Relative Strength Index)", "Signals & indicators",
            "A 0–100 momentum score comparing recent gains to recent losses over 14 periods. Above 70 is usually called overbought, below 30 oversold, 50 is neutral. Shown under the chart when the RSI chip is on.", "RSI", "RSI (14)", "RSI level"),
        t("Stochastic %K", "Signals & indicators",
            "Where today's close sits within the high–low range of the last 14 periods, from 0 (at the low) to 100 (at the high). Above 80 is overbought, below 20 oversold.", "Stochastic"),
        t("Bollinger Bands", "Signals & indicators",
            "Two lines drawn 2 standard deviations above and below the 20-period average. Prices near the upper band are stretched high, near the lower band stretched low. The bands widen when prices swing more.", "Bollinger", "BB"),
        t("%B (Bollinger %B)", "Signals & indicators",
            "Where the price sits between the Bollinger Bands: 0 = on the lower band, 1 = on the upper band. Above 1 is outside the upper band (overbought), below 0 is outside the lower band (oversold).", "%B", "Percent B"),
        t("SMA (Simple Moving Average)", "Signals & indicators",
            "The plain average of the last N closing prices (20, 50 or 200). It smooths out noise; prices above a rising average suggest an uptrend. The 50- and 200-day averages are widely watched.", "SMA", "SMA 20", "SMA 50", "SMA 200", "Moving average", "vs 50-day average"),
        t("EMA (Exponential Moving Average)", "Signals & indicators",
            "Like an SMA but gives more weight to recent prices, so it reacts faster.", "EMA", "EMA 20"),
        t("MACD", "Signals & indicators",
            "Moving Average Convergence Divergence: the 12-period EMA minus the 26-period EMA, with a 9-period \"signal\" line. The MACD crossing above its signal line is read as bullish momentum, below as bearish. The bars show the gap between the two.", "MACD (12,26,9)"),
        t("Volume", "Signals & indicators",
            "How many shares (or coins) traded. Moves on high volume carry more weight than moves on low volume. The Volume chip shows it as bars under the chart.", "Vol", "24h volume"),
        t("Candlestick chart", "Signals & indicators",
            "Each candle shows one period: the body spans open to close (green if it closed higher, red if lower) and the thin wicks show the high and low.", "Candles", "Candlestick", "Line"),
        t("Trendline", "Signals & indicators",
            "A line you draw across highs or lows to see a trend. Tap the pencil above the chart, then drag across the chart. Lines are saved per symbol; the layers icon clears them.", "Draw trendline", "Trendlines"),
        t("Unusual volume", "Signals & indicators",
            "Today's volume compared with the stock's 50-day average (e.g. 3.0× means three times normal). Big volume often comes with news.", "× Avg", "Volume ×"),
        t("52-week high / low", "Signals & indicators",
            "The highest and lowest prices over the past year. Stocks near their 52-week high are in strong uptrends; near the low, in weak ones.", "52-wk high", "52-wk low", "52-week high", "52-week low", "52-wk range", "Near 52-week highs", "Near 52-week lows"),

        // ---------------- Markets ----------------
        t("Dow Jones Industrial Average", "Markets", "An index of 30 large US companies, weighted by share price. The oldest widely quoted US index.", "Dow", "^DJI", "Dow Jones"),
        t("S&P 500", "Markets", "An index of about 500 of the largest US companies, weighted by market value. The most common benchmark for the US stock market.", "^GSPC", "S&P 500 today"),
        t("NASDAQ Composite", "Markets", "An index of all stocks listed on the NASDAQ exchange — tech-heavy (Apple, Microsoft, NVIDIA and others).", "NASDAQ", "^IXIC"),
        t("NYSE Composite", "Markets", "An index of all common stocks listed on the New York Stock Exchange.", "NYSE", "^NYA"),
        t("Russell 2000", "Markets", "An index of 2,000 small US companies; a gauge of small-cap stocks.", "Russell", "^RUT"),
        t("VIX", "Markets", "The CBOE Volatility Index — the market's expectation of S&P 500 swings over the next 30 days. Often called the \"fear gauge\": below 15 is calm, above 30 is stressed.", "VIX Volatility"),
        t("Major indices", "Markets", "The headline US stock indexes: Dow, S&P 500, NASDAQ, NYSE Composite, Russell 2000 and the VIX. Tap any tile for its chart."),
        t("Stock futures", "Markets", "Contracts on where an index will be at a future date. They trade nearly around the clock, so they hint at how stocks may open. \"S&P Fut\" is the E-mini S&P 500 future.", "Futures", "US stock futures"),
        t("Pre-market / after hours", "Markets", "Trading outside regular hours (9:30 am–4:00 pm ET): pre-market from 4:00 am, after hours until 8:00 pm. Prices can move on earnings or news, with fewer trades.", "Pre-market", "After hours", "Extended hours", "Pre-market & after-hours prices"),
        t("Market status", "Markets", "Whether US exchanges are open, in pre-market, after hours, closed, or on a holiday, with a countdown to the next open or close (Eastern Time)."),
        t("Top movers", "Markets", "Today's biggest gainers and losers by percent change, and the most active stocks by trading volume.", "Movers", "Gainers", "Losers", "Most active"),
        t("Sector heatmap", "Markets", "The 11 S&P 500 sectors (via their SPDR sector ETFs, like XLK for technology), colored green or red by today's change. Darker = bigger move.", "Sectors", "Sector ETFs", "Sector performance"),
        t("Global markets", "Markets", "Major stock indexes outside the US — London (FTSE), Germany (DAX), Japan (Nikkei), Hong Kong (Hang Seng) and more.", "World indices", "World heatmap"),
        t("Forex", "Markets", "Currency exchange rates, e.g. EUR/USD is how many US dollars one euro buys.", "Currencies"),
        t("Commodities", "Markets", "Raw materials traded on futures markets: gold, silver, crude oil, natural gas, copper, corn."),
        t("Treasury yields", "Markets", "The interest rate the US government pays to borrow for a given term (3 months to 30 years), shown in percent. Rising yields make borrowing costlier and often weigh on stocks.", "Yields", "10-Year Treasury", "Rates & FX"),
        t("ETF", "Markets", "Exchange-traded fund — a basket of stocks or bonds that trades like a single stock (e.g. SPY holds the S&P 500)."),
        t("Ticker tape", "Markets", "The two scrolling rows under the top bar: NYSE and NASDAQ, led by each exchange's index. Tap a ticker to open it, press and hold to pause. Change which stocks appear in Settings → Ticker tape."),
        t("Fear & Greed", "Markets", "A 0–100 sentiment score. Stocks use CNN's index (momentum, breadth, options, safe-haven demand); crypto uses alternative.me's index. Extreme fear (<25) sometimes marks bottoms, extreme greed (>75) tops.", "Fear & Greed (crypto)", "Fear & Greed Index"),

        // ---------------- Stock data ----------------
        t("Market cap", "Stock data", "Company value: share price × shares outstanding. Mega cap > $200B, large > $10B, mid $2–10B, small $300M–2B.", "Market cap", "Cap", "Market capitalization"),
        t("P/E ratio", "Stock data", "Price divided by earnings per share over the last 12 months — how many dollars investors pay per $1 of profit. Higher often means higher growth expectations.", "P/E (TTM)", "P/E", "PE"),
        t("EPS", "Stock data", "Earnings per share: profit divided by shares outstanding. \"TTM\" = trailing twelve months.", "EPS (TTM)", "EPS est", "EPS estimate", "EPS actual"),
        t("Beta", "Stock data", "How much a stock tends to move with the market. 1 = in line, 1.5 = 50% bigger swings, 0.5 = half as much."),
        t("Dividend yield", "Stock data", "Yearly dividends per share divided by the price, in percent.", "Dividend yield"),
        t("Dividend", "Stock data", "Cash a company pays to shareholders, usually quarterly. You must own the stock before the ex-dividend date to receive it.", "Dividends", "Dividend history", "Ex-date", "Ex-dividend date"),
        t("Price/Book", "Stock data", "Price divided by book value (assets minus liabilities) per share."),
        t("Price/Sales", "Stock data", "Market cap divided by yearly revenue."),
        t("ROE", "Stock data", "Return on equity: yearly profit as a percent of shareholders' equity — how efficiently a company uses its capital."),
        t("Net margin", "Stock data", "Profit as a percent of revenue."),
        t("Debt/Equity", "Stock data", "Total debt divided by shareholders' equity; higher means more borrowed money."),
        t("Day range", "Stock data", "Today's lowest and highest trade prices.", "Day high", "Day low"),
        t("Open / previous close", "Stock data", "Open is today's first trade price; previous close is yesterday's last. Today's change is measured from the previous close.", "Open", "Prev close"),
        t("Analyst recommendations", "Stock data", "How many Wall Street analysts rate the stock strong buy, buy, hold, sell or strong sell, by month. Consensus is the weighted average.", "Analyst ratings", "Consensus"),
        t("Price target", "Stock data", "Where analysts expect the price to be in about 12 months: low, consensus (average) and high. Needs a free Financial Modeling Prep key.", "Price targets"),
        t("Insider transactions", "Stock data", "Buys and sells by company executives and directors, reported on SEC Form 4. Open-market buys are usually read as confidence.", "Insider trades"),
        t("Insider sentiment (MSPR)", "Stock data", "Monthly Share Purchase Ratio from −100 (insiders only selling) to +100 (only buying).", "Insider sentiment", "Sentiment"),
        t("News sentiment", "Stock data", "Whether recent articles about the company read bullish, neutral or bearish (from Marketaux, needs a free key).", "Bullish", "Bearish"),
        t("SEC filings", "Stock data", "Official reports: 10-K (annual), 10-Q (quarterly), 8-K (major events), Form 4 (insider trades), DEF 14A (proxy).", "Filings", "10-K", "10-Q", "8-K"),
        t("Peers", "Stock data", "Companies in the same industry, for comparison."),
        t("ETF holdings", "Stock data", "The largest positions inside an ETF and their weights. Needs a free Financial Modeling Prep key.", "Top holdings"),
        t("Earnings", "Stock data", "Quarterly results. \"Before open\" (BMO) reports come before 9:30 am ET, \"after close\" (AMC) after 4:00 pm. Prices often jump on earnings.", "Upcoming earnings", "Before open", "After close"),
        t("IPO", "Stock data", "Initial public offering — a company's first sale of shares on an exchange.", "IPOs"),
        t("Key statistics", "Stock data", "Valuation, profitability and trading figures for the company, from Finnhub."),
        t("Profile", "Stock data", "Company name, industry, exchange, country, IPO date, website and size."),

        // ---------------- Crypto ----------------
        t("Market cap (crypto)", "Crypto", "Coin price × coins in circulation. Rank orders coins by market cap.", "Total market cap", "Rank"),
        t("BTC dominance", "Crypto", "Bitcoin's share of the whole crypto market's value. Rising dominance often means money is moving to Bitcoin from smaller coins.", "BTC dominance", "ETH dominance"),
        t("Circulating supply", "Crypto", "Coins currently in the market. Max supply is the most that can ever exist (21 million for Bitcoin).", "Circulating", "Total supply", "Max supply"),
        t("All-time high (ATH)", "Crypto", "The highest price ever. \"From ATH\" is how far below it the price is now.", "All-time high", "From ATH", "ATH"),
        t("Ethereum gas", "Crypto", "The fee to make a transaction on Ethereum, in gwei (billionths of an ETH). Low under 5, high above 20.", "Gas", "Ethereum gas"),
        t("Trending coins", "Crypto", "The coins people are searching for most on CoinGecko in the last 24 hours.", "Trending"),
        t("Crypto heatmap", "Crypto", "The top coins as tiles colored by their 24-hour change; bigger tiles are the largest coins.", "Heatmap"),
        t("Crypto exchanges", "Crypto", "Where coins trade, ranked by 24-hour volume, with CoinGecko's 1–10 trust score.", "Exchanges", "Trust"),
        t("Crypto commission", "Crypto", "A fee charged as a percent of each crypto trade. E*TRADE crypto (through Zero Hash) charges 0.50% per trade. Set it per portfolio from the portfolio's ⋮ menu; it's filled in automatically for crypto buys and sells.", "E*TRADE crypto", "Zero Hash"),
        t("Converter", "Crypto", "Converts between currencies and coins using European Central Bank rates (daily) and CoinGecko prices.", "Currency & crypto converter"),

        // ---------------- Economy ----------------
        t("Yield curve", "Economy", "Treasury yields plotted from short (1 month) to long (30 years) terms. Normally it slopes up; when short rates are above long ones it's \"inverted\", which has often come before recessions.", "Treasury yield curve", "10Y–2Y spread", "Inverted"),
        t("Fed funds rate", "Economy", "The Federal Reserve's benchmark short-term interest rate. Higher rates slow borrowing and inflation."),
        t("CPI inflation", "Economy", "The Consumer Price Index measures prices of everyday goods and services; shown as the change from a year earlier.", "CPI inflation (YoY)", "CPI"),
        t("Unemployment", "Economy", "Share of the labor force without a job and looking for one."),
        t("Nonfarm payrolls", "Economy", "Jobs added or lost each month outside farming — released the first Friday of the month and closely watched.", "Nonfarm payrolls (m/m, K)"),
        t("GDP growth", "Economy", "How fast the economy grew, shown as an annualized quarterly rate.", "Real GDP growth (QoQ ann.)"),
        t("Economic calendar", "Economy", "Scheduled data releases (jobs, inflation, Fed decisions) with forecasts and actual numbers. High-impact events can move markets. Needs a free Financial Modeling Prep key.", "Economic", "Economic events", "Economic indicators"),
        t("Consumer sentiment", "Economy", "University of Michigan survey of how confident households feel about the economy."),

        // ---------------- Portfolio ----------------
        t("Portfolio", "Portfolio", "Your holdings, built from the transactions you add or import. You can keep several named portfolios, like watchlists."),
        t("Cost basis / average cost", "Portfolio", "What you paid, including fees. Average cost is the cost basis divided by shares held; sells reduce it proportionally (average-cost method).", "Cost basis", "Avg cost", "Average cost"),
        t("Unrealized gain", "Portfolio", "Profit or loss on shares you still hold: current value minus cost basis.", "Unrealized", "Total gain"),
        t("Realized gain", "Portfolio", "Profit or loss locked in by selling: sale proceeds minus the average cost of the shares sold, minus fees.", "Realized", "Realized P/L"),
        t("Cash (portfolio)", "Portfolio", "Money in the portfolio that isn't invested. Sale proceeds (minus fees), dividends and deposits add cash; buys are paid from cash first (anything more is counted as new money); withdrawals take it out. Cash is included in the total value.", "Cash"),
        t("Deposit / withdraw", "Portfolio", "Add money to or take money out of a portfolio's cash.", "Deposit", "Withdraw", "Withdrawal"),
        t("Invested", "Portfolio", "The market value of your holdings, not counting cash."),
        t("Allocation", "Portfolio", "How your portfolio's value is split between holdings (and cash)."),
        t("Day change", "Portfolio", "How much the value changed today, based on each holding's move since yesterday's close.", "Today"),
        t("Import", "Portfolio", "Bring in many transactions at once from a broker CSV, a pasted list or rows typed in. You can check columns and edit every row before saving.", "Import transactions"),
        t("Paper trading", "Portfolio", "A practice account with pretend money (default $100,000). Orders fill at the latest price. Nothing real is bought or sold.", "Practice account"),

        // ---------------- App features ----------------
        t("Watchlist", "App features", "Lists of symbols to follow. Create several, reorder by dragging, and mix stocks, ETFs, indexes and crypto.", "Watchlists", "My Watchlist"),
        t("Price alerts", "App features", "Notifications when a symbol crosses a price, moves a set percent in a day, or becomes overbought/oversold (RSI). Checked while the app is open and about every 15 minutes in the background.", "Alerts", "Price alert"),
        t("Screener", "App features", "Filters stocks by sector, day change, price, unusual volume and nearness to 52-week highs/lows, or uses Yahoo's preset screens.", "Stock screener", "Filters"),
        t("Compare", "App features", "Overlays 2–4 symbols on one chart as percent change, so different prices can be compared.", "Compare symbols"),
        t("Calendars", "App features", "Upcoming earnings, IPOs, economic releases, dividends and market holidays."),
        t("PDF reports", "App features", "The PDF button (top bar) makes a report for the screen you're on. Choose sections, paper size and watermark, then Create PDF; Share or Save it from the viewer.", "PDF", "PDF report", "Reports", "Create PDF"),
        t("Search", "App features", "Find any stock, ETF, index or coin by symbol or name (magnifying glass in the top bar)."),
        t("Live / offline", "App features", "\"Live\" means prices are streaming tick by tick. \"Offline\" means there's no connection and you're seeing the last saved data.", "Live", "Offline"),
        t("Streaming vs refresh", "App features", "Finnhub streams up to about 48 stock symbols live on the free plan (Coinbase streams crypto). Everything else refreshes on the interval set in Settings.", "Live streaming", "Refresh"),
        t("Card size", "App features", "Large, Medium or Small cards; smaller cards fit more on screen. On the dashboard you can also collapse cards (˄) and set half-width cards in Edit dashboard.", "Cards", "Columns", "Dashboard columns"),
        t("API keys", "App features", "Free accounts with data providers that unlock more data. Paste them in Settings → API keys (or More → API keys). Each can be tested with the Test button."),
    )

    private val index: Map<String, Term> by lazy {
        val m = HashMap<String, Term>()
        terms.forEach { term -> m.putIfAbsent(term.key, term); term.aliases.forEach { m.putIfAbsent(norm(it), term) } }
        m
    }

    /** The entry for an on-screen label, if there is one. */
    fun find(label: String?): Term? = label?.let { index[norm(it)] }
}
