# Market_Data (OrionMD)

An Android market-data app for US stocks (NYSE, NASDAQ, Dow Jones Industrial Average, S&P 500) and crypto. It has live prices, a dashboard you can arrange yourself, watchlists, a portfolio, alerts, and PDF reports with a built-in viewer.

Package: `app.orionmd.marketdata` · APK: `Market_Data.apk` · Android 8.0+ (API 26)

## Features

**Tabs:** Dashboard · Markets · Crypto · Watchlists · News. Search, PDF and the More menu sit in the top bar on every screen.

- **Dashboard.** Drag cards to reorder them, and add or remove them. Available cards:
  - Market status with a countdown to open/close
  - Major indices and stock futures
  - Watchlist
  - Top movers
  - Sector heatmap
  - Crypto
  - Fear & Greed (stocks and crypto)
  - Portfolio
  - Forex, commodities and Treasury yields
  - Global markets
  - Headlines
  - Upcoming earnings
  - Recently viewed
- **Markets.** Indices (Dow, S&P 500, NASDAQ, NYSE Composite, Russell 2000, VIX), movers for NYSE and NASDAQ, sectors, gainers/losers/most active, futures, rates and forex, commodities.
- **Crypto.** Top 100 coins with live prices, a heatmap, trending coins, total market cap, BTC/ETH dominance, the crypto Fear & Greed index, Ethereum gas price, exchanges, and a crypto/fiat converter.
- **Watchlists.** Create as many lists as you like. Mix stocks and crypto, reorder by dragging, sort, and rename or delete lists.
- **News.** Top, markets, business, crypto, mergers and forex headlines, plus news for the symbols in your watchlist. Sentiment tags appear when a Marketaux key is set.
- **Symbol page:**
  - Live price, plus pre-market and after-hours prices
  - Line or candlestick charts for 1D, 1W, 1M, 3M, 1Y and 5Y
  - Indicators: SMA 20/50/200, EMA 20, Bollinger bands, volume, RSI and MACD
  - Crosshair readout and trendline drawing
  - Key stats and company profile
  - Analyst ratings and price targets
  - Insider sentiment and insider trades
  - Dividends, peers, ETF holdings, SEC filings and news
  - Per-symbol notes
- **Tools:**
  - Compare 2–4 symbols on one chart
  - Stock screener: presets, or a custom filter by sector, change, price, unusual volume and 52-week highs/lows
  - Calendars: earnings, IPOs, economic events, dividends and market holidays
  - Economy dashboard: Fed rate, CPI, jobs, GDP and the yield curve
  - Global indices
- **Personal:**
  - Portfolio tracker: average cost, day and total profit/loss, realized gains, dividend income, allocation chart and broker CSV import
  - Paper trading
  - Price alerts, delivered as notifications
  - Notes
  - Recently viewed symbols
  - PIN or fingerprint lock for the portfolio
- **PDF reports** (13 types):
  - Market summary, Watchlist, Symbol, Portfolio, Crypto, Sectors, Top movers, News digest, Calendars, Economy, Global, Comparison, Paper trading
  - Every PDF button opens an options screen first, and your choices are remembered for each report type. The title is not remembered.
  - Every page carries the OrionMD logo as a watermark; its strength can be Light, Normal or Strong.
  - The finished PDF opens in the built-in viewer, which has pinch-to-zoom, **Share** and **Save**.
  - The temporary PDF is deleted when the viewer closes or the app next opens. Use **Save** to keep a copy.
- **Also included:**
  - Home-screen widget showing your watchlist
  - Morning brief and closing-bell notifications
  - Daily or weekly scheduled market-summary PDF
  - Backup and restore as JSON, plus CSV export and import
  - Dark and light themes, adjustable text size and watermark strength, compact layout
  - Tablet and landscape layouts
  - Offline mode that shows the last saved data

## Data sources

| Source | Key | Used for |
|---|---|---|
| Finnhub | `FINNHUB_API_KEY` | Live stock stream (WebSocket), quotes, news, profiles, metrics, analysts, insiders, calendars, filings, market status and holidays |
| Twelve Data | `TWELVEDATA_API_KEY` | Backup chart history, forex, metals |
| CoinStats | `COINSTATS_API_KEY` | Backup crypto prices |
| Yahoo Finance (unofficial, no key) | — | Indices, futures, commodities, yields, charts, movers, screeners, pre/post-market |
| CoinGecko (no key) | — | Crypto markets, trending, global stats, coin details, exchanges |
| Coinbase (no key) | — | Live crypto stream, 24h stats, candles |
| alternative.me / CNN (no key) | — | Fear & Greed |
| Frankfurter / ECB (no key) | — | Currency conversion |
| RSS: CNBC, MarketWatch, Yahoo, CoinDesk, Cointelegraph | — | Headlines |

These free keys are optional. They switch on extra features and can be entered in the app under **Settings → API keys** or added as build secrets:

| Service | Secret | Adds |
|---|---|---|
| FRED | `FRED_API_KEY` | Economy dashboard, full yield curve |
| Financial Modeling Prep | `FMP_API_KEY` | Economic and dividend calendars, price targets, ETF holdings, market-cap screener |
| Alpha Vantage | `ALPHAVANTAGE_API_KEY` | Backup quotes and movers |
| NewsAPI | `NEWSAPI_API_KEY` | More business headlines |
| Marketaux | `MARKETAUX_API_KEY` | News with sentiment scores |
| Polygon.io | `POLYGON_API_KEY` | Backup chart history |

## Building

API keys are **never committed**. The build reads them from environment variables (GitHub Actions secrets) or from an untracked `keys.properties` file at the repo root:

```
FINNHUB_API_KEY=...
TWELVEDATA_API_KEY=...
COINSTATS_API_KEY=...
```

Local build:

```
./gradlew :app:assembleRelease
# → app/build/outputs/apk/release/Market_Data.apk
```

### GitHub Actions

`.github/workflows/build-apk.yml` builds a signed APK on every push and uploads it as the **Market_Data-apk** artifact. Pushing a `v*` tag also attaches the APK to a GitHub Release.

Add these repository secrets under **Settings → Secrets and variables → Actions**:

- API keys: `FINNHUB_API_KEY`, `TWELVEDATA_API_KEY`, `COINSTATS_API_KEY`, and any optional keys above.
- Signing:
  - `KEYSTORE_BASE64`: the release keystore, base64-encoded (`base64 -w0 market_data_release.jks`)
  - `KEYSTORE_PASSWORD`, `KEY_ALIAS` (`marketdata`), `KEY_PASSWORD`

Every build must be signed with the same keystore; otherwise Android refuses to install an update over the existing app. Without the signing secrets, CI signs with a throwaway debug key.

> Keys built into an APK can be extracted by anyone who has the APK, so only share builds with people you trust.
