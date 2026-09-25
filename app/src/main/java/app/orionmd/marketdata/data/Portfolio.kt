package app.orionmd.marketdata.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class Holding(
    val symbol: String,
    val qty: Double,
    val avgCost: Double,
    val costBasis: Double,
    val realized: Double,
    val dividends: Double,
) {
    fun value(q: Quote?) = q?.let { it.price * qty }
    fun dayChange(q: Quote?) = q?.let { it.change * qty }
    fun unrealized(q: Quote?) = q?.let { it.price * qty - costBasis }
}

data class PortfolioSummary(
    val holdings: List<Holding>,
    val value: Double,
    val cost: Double,
    val dayChange: Double,
    val unrealized: Double,
    val realized: Double,
    val dividends: Double,
    val missingQuotes: Int,
) {
    val totalReturn get() = unrealized + realized + dividends
    val totalReturnPct get() = if (cost > 0) unrealized / cost * 100 else 0.0
    val dayPct get() = if (value - dayChange != 0.0) dayChange / (value - dayChange) * 100 else 0.0
}

object PortfolioCalc {
    /** Average-cost method. */
    fun holdings(txns: List<Txn>): List<Holding> =
        txns.groupBy { it.symbol }.map { (sym, list) ->
            var qty = 0.0; var cost = 0.0; var realized = 0.0; var divs = 0.0
            list.sortedBy { it.date }.forEach { t ->
                when (t.kind) {
                    TxnKind.BUY -> { qty += t.qty; cost += t.qty * t.price + t.fees }
                    TxnKind.SELL -> {
                        val avg = if (qty > 0) cost / qty else 0.0
                        val q = minOf(t.qty, qty)
                        realized += q * (t.price - avg) - t.fees
                        cost -= avg * q; qty -= q
                    }
                    TxnKind.DIVIDEND -> divs += if (t.qty > 0) t.qty * t.price else t.price
                }
            }
            Holding(sym, qty, if (qty > 0) cost / qty else 0.0, cost, realized, divs)
        }.sortedBy { it.symbol }

    fun summary(txns: List<Txn>, quotes: Map<String, Quote>): PortfolioSummary {
        val hs = holdings(txns)
        val open = hs.filter { it.qty > 1e-9 }
        var value = 0.0; var day = 0.0; var unreal = 0.0; var missing = 0; var cost = 0.0
        open.forEach { h ->
            val q = quotes[h.symbol]
            if (q == null) { missing++; value += h.costBasis; cost += h.costBasis } else {
                value += h.value(q)!!; day += h.dayChange(q)!!; unreal += h.unrealized(q)!!; cost += h.costBasis
            }
        }
        return PortfolioSummary(hs, value, cost, day, unreal, hs.sumOf { it.realized }, hs.sumOf { it.dividends }, missing)
    }

    private val dateFmts = listOf(
        "yyyy-MM-dd", "MM/dd/yyyy", "M/d/yyyy", "MM/dd/yy", "M/d/yy", "yyyy/MM/dd", "dd-MM-yyyy", "dd.MM.yyyy",
        "yyyyMMdd", "MMM d, yyyy", "MMM dd, yyyy", "d MMM yyyy", "dd MMM yyyy", "d-MMM-yyyy", "dd-MMM-yy",
    )

    /** Parses many common date styles, including "09/15/2026 as of 09/12/2026" (Schwab) and ISO timestamps. */
    fun parseDate(s: String): Long? {
        val raw = s.trim().trim('"').substringBefore(" as of").trim()
        if (raw.isBlank()) return null
        val candidates = listOf(raw, raw.take(10), raw.substringBefore("T"), raw.substringBefore(" "))
        for (c in candidates.distinct()) for (f in dateFmts) runCatching {
            return LocalDate.parse(c.trim(), DateTimeFormatter.ofPattern(f, java.util.Locale.US)).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }
        return null
    }

    fun fmtDate(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate().toString()

    fun splitCsv(line: String, delim: Char = ','): List<String> {
        val out = mutableListOf<String>(); val sb = StringBuilder(); var q = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' && q && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                ch == '"' -> q = !q
                ch == delim && !q -> { out += sb.toString().trim(); sb.clear() }
                else -> sb.append(ch)
            }
            i++
        }
        out += sb.toString().trim()
        return out
    }
}

// ============================ CSV / text import ============================

enum class ImportField(val label: String) {
    SYMBOL("Symbol"), ACTION("Action / type"), QTY("Quantity"), PRICE("Price"), FEES("Fees"), COMMISSION("Commission"), DATE("Date"), AMOUNT("Amount / total")
}

/** One row being reviewed before import. Everything is text so the user can edit it freely. */
data class DraftTxn(
    val id: Long,
    val symbol: String,
    val kind: TxnKind,
    val qty: String,
    val price: String,
    val fees: String,
    val date: String,
    val note: String = "",
    val include: Boolean = true,
    val source: String = "",
) {
    /** Problems that block importing this row; empty when it's ready. */
    fun problems(): List<String> = buildList {
        if (!Importer.looksLikeSymbol(symbol)) add("Symbol missing or invalid")
        val p = Importer.num(price)
        if (kind == TxnKind.DIVIDEND) { if (p == null || p <= 0) add("Enter the dividend amount") }
        else {
            val q = Importer.num(qty)
            if (q == null || q <= 0) add("Quantity must be more than 0")
            if (p == null || p <= 0) add("Price missing")
        }
        if (date.isNotBlank() && PortfolioCalc.parseDate(date) == null) add("Date not recognized (use YYYY-MM-DD)")
    }

    fun toTxn(portfolio: String): Txn? {
        if (problems().isNotEmpty()) return null
        val q = if (kind == TxnKind.DIVIDEND) 0.0 else Importer.num(qty)!!
        return Txn(Store.newId(), symbol.trim().uppercase(), kind, kotlin.math.abs(q), kotlin.math.abs(Importer.num(price)!!),
            kotlin.math.abs(Importer.num(fees) ?: 0.0), PortfolioCalc.parseDate(date) ?: System.currentTimeMillis(),
            note.ifBlank { "Imported" }, portfolio)
    }
}

data class ParsedImport(
    val headers: List<String>,
    val rows: List<List<String>>,
    val hasHeader: Boolean,
    val delimiter: String,
    val mapping: Map<ImportField, Int>,
    val skippedLines: Int,
    /** Broker whose export format was recognized, e.g. "E*TRADE transactions". */
    val broker: String? = null,
)

object Importer {
    private val tickerRx = Regex("^[A-Z][A-Z0-9]{0,6}([.\\-/][A-Z0-9]{1,4})?(-USD)?$")
    private val dateRx = Regex("\\d{1,4}[-/.]\\d{1,2}[-/.]\\d{1,4}|[A-Za-z]{3} \\d{1,2},? \\d{4}|\\d{8}")
    private val actionWords = listOf("buy", "bought", "sell", "sold", "div", "reinvest", "purchase", "sale")
    private val notSymbols = setOf("BUY", "SELL", "SOLD", "BOUGHT", "DIV", "DIVIDEND", "USD", "SHARES", "SHS", "AT", "OF", "YOU", "CASH", "TOTAL", "N/A", "NA")

    fun looksLikeSymbol(s: String) = s.trim().uppercase().let { it.isNotEmpty() && it !in notSymbols && tickerRx.matches(it) }

    /** Parses "$1,234.50", "(12.5)", "1.234,56" and "-3". */
    fun num(s: String?): Double? {
        var t = s?.trim()?.replace("$", "")?.replace("€", "")?.replace("£", "")?.replace(" ", "")?.replace("\u00A0", "") ?: return null
        if (t.isEmpty() || t == "-" || t == "--") return null
        var neg = false
        if (t.startsWith("(") && t.endsWith(")")) { neg = true; t = t.drop(1).dropLast(1) }
        if (t.contains(',') && t.contains('.')) t = if (t.lastIndexOf(',') > t.lastIndexOf('.')) t.replace(".", "").replace(',', '.') else t.replace(",", "")
        else if (t.contains(',')) t = if (Regex(",\\d{3}(,|$)").containsMatchIn(t)) t.replace(",", "") else t.replace(',', '.')
        val v = t.toDoubleOrNull() ?: return null
        return if (neg) -v else v
    }

    private fun detectDelimiter(lines: List<String>): Char? {
        val sample = lines.take(25)
        if (sample.isEmpty()) return null
        return listOf(',', ';', '\t', '|').map { d -> d to sample.map { l -> PortfolioCalc.splitCsv(l, d).size - 1 } }
            .filter { (_, counts) -> counts.count { it > 0 } >= (sample.size + 1) / 2 }
            .maxByOrNull { (_, counts) -> counts.sorted()[counts.size / 2] }?.first
    }

    private val headerWords = listOf("quantity", "shares", "qty", "price", "date", "action", "type", "amount", "fee", "commission", "description", "cost", "value", "trans code")

    /** A header row names the symbol column plus at least one other known column (or is a lone "Symbol" column). */
    private fun isHeaderRow(row: List<String>): Boolean {
        if (row.none { isHeaderCell(it) }) return false
        if (row.size == 1) return row[0].trim().trim('"').length <= 16
        val known = row.count { c -> val h = c.lowercase(); headerWords.any { h.contains(it) } && h.length <= 40 }
        return known >= 1 && row.none { Importer.num(it) != null && it.any(Char::isDigit) }
    }

    private fun isHeaderCell(c: String): Boolean {
        val h = c.lowercase().trim().trim('"')
        return h in setOf("symbol", "ticker", "ticker symbol", "security", "instrument", "stock", "asset", "code") ||
            h.contains("symbol") || h.contains("ticker")
    }

    fun parse(text: String): ParsedImport {
        val lines = text.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n').lines()
            .map { it.removePrefix("\uFEFF") }.filter { it.isNotBlank() }
        val delim = detectDelimiter(lines)
        val split: (String) -> List<String> = if (delim != null) { l -> PortfolioCalc.splitCsv(l, delim) }
            else { l -> l.trim().split(Regex("\\s+")).map { it.trim(',', ';') }.filter { it.isNotEmpty() && it != "@" && it != "x" } }
        val table = lines.map(split)
        val delimName = when (delim) { ',' -> "comma"; ';' -> "semicolon"; '\t' -> "tab"; '|' -> "pipe"; else -> "spaces" }
        // Some exports (e.g. Vanguard) contain several tables, each with its own header row.
        // Prefer the table that has an action/type column (transactions) over a holdings table.
        val headerRows = table.indices.filter { i -> isHeaderRow(table[i]) }
        if (headerRows.isNotEmpty()) {
            val headerIdx = headerRows.firstOrNull { ImportField.ACTION in guessFromHeaders(table[it]) } ?: headerRows.first()
            val headers = table[headerIdx].map { it.trim().trim('"') }
            val end = headerRows.firstOrNull { it > headerIdx } ?: table.size
            // brokers add footers like "Total" or disclaimers; keep rows with at least 2 cells
            val rows = table.subList(headerIdx + 1, end).filter { r -> r.any { it.isNotBlank() } && (r.size >= 2 || headers.size == 1) }
            return ParsedImport(headers, rows, true, delimName, guessFromHeaders(headers), headerIdx, detectBroker(headers, lines.take(3)))
        }
        val width = table.maxOfOrNull { it.size } ?: 0
        return ParsedImport((1..width).map { "Column $it" }, table, false, delimName, guessFromContent(table, width), 0)
    }

    /** Names the broker when the header row matches a known export layout. */
    private fun detectBroker(h: List<String>, firstLines: List<String>): String? {
        val low = h.map { it.lowercase().trim() }.toSet()
        fun has(vararg k: String) = k.all { key -> low.any { it == key } }
        return when {
            has("activity/trade date", "activity type") || (has("transactiondate", "transactiontype", "securitytype")) -> "E*TRADE transactions"
            has("price paid \$") || (has("last price \$") && low.any { it.startsWith("day's gain") }) -> "E*TRADE portfolio (positions)"
            firstLines.any { it.startsWith("For Account", true) } -> "E*TRADE"
            has("run date", "action") -> "Fidelity"
            has("fees & comm", "action") -> "Schwab"
            has("activity date", "trans code") -> "Robinhood"
            has("trade date", "transaction type", "investment name") -> "Vanguard"
            else -> null
        }
    }

    private fun guessFromHeaders(h: List<String>): Map<ImportField, Int> {
        val low = h.map { it.lowercase().trim() }
        fun first(vararg preds: (String) -> Boolean): Int? {
            for (p in preds) low.indexOfFirst(p).takeIf { it >= 0 }?.let { return it }
            return null
        }
        val m = mutableMapOf<ImportField, Int>()
        first({ it == "symbol" || it == "ticker" }, { it.contains("symbol") }, { it.contains("ticker") },
            { it in setOf("security", "instrument", "stock", "asset", "code") })?.let { m[ImportField.SYMBOL] = it }
        val actionWord = Regex("(^|[^a-z])action")  // not "transaction"
        first({ it == "action" }, { actionWord.containsMatchIn(it) }, { it == "side" || it.contains("buy/sell") }, { it.contains("activity") && !it.contains("date") },
            { it.replace(" ", "").contains("transactiontype") || it.contains("trans type") || it.contains("trade type") || it.contains("trans code") || it.contains("transaction code") },
            { it == "type" || it == "transaction" })
            ?.let { m[ImportField.ACTION] = it }
        first({ it.contains("quantity") }, { it.contains("shares") }, { it.contains("qty") }, { it.contains("units") })?.let { m[ImportField.QTY] = it }
        first({ it == "price" }, { it.startsWith("price") },
            // positions files: the price you paid, not today's price
            { it.contains("price paid") || it.contains("cost basis per share") || it.contains("avg cost") || it.contains("average cost") || it.contains("cost per") || it.contains("unit cost") },
            { it.contains("price") && !it.contains("total") && !it.contains("last") }, { it.contains("price") && !it.contains("total") })?.let { m[ImportField.PRICE] = it }
        first({ it.contains("fee") })?.let { m[ImportField.FEES] = it }
        // Some brokers split commission and fees into two columns; both are added together.
        first({ (it.contains("commission") || it == "comm") && low.indexOf(it) != m[ImportField.FEES] })?.let { m[ImportField.COMMISSION] = it }
        first({ it == "trade date" }, { it == "date" }, { it.contains("trade date") || it.contains("run date") || it.contains("transaction date") || it.contains("activity date") },
            { it.contains("date") && !it.contains("settle") })?.let { m[ImportField.DATE] = it }
        first({ it.contains("amount") }, { it.contains("total") }, { it == "value" || it.contains("net") })?.let { m[ImportField.AMOUNT] = it }
        return m
    }

    /** No header row: work out the columns from what the cells look like. */
    private fun guessFromContent(rows: List<List<String>>, width: Int): Map<ImportField, Int> {
        val m = mutableMapOf<ImportField, Int>()
        fun share(col: Int, pred: (String) -> Boolean) = rows.count { r -> r.getOrNull(col)?.let(pred) == true }.toDouble() / rows.size.coerceAtLeast(1)
        val cols = (0 until width)
        val isSym: (String) -> Boolean = { looksLikeSymbol(it) }
        val isDate: (String) -> Boolean = { dateRx.containsMatchIn(it) && PortfolioCalc.parseDate(it) != null }
        val isAct: (String) -> Boolean = { c -> actionWords.any { w -> c.lowercase().contains(w) } }
        cols.maxByOrNull { share(it, isSym) }?.takeIf { share(it, isSym) >= 0.5 }?.let { m[ImportField.SYMBOL] = it }
        cols.filter { it !in m.values }.maxByOrNull { share(it, isDate) }?.takeIf { share(it, isDate) >= 0.5 }?.let { m[ImportField.DATE] = it }
        cols.filter { it !in m.values }.maxByOrNull { share(it, isAct) }?.takeIf { share(it, isAct) >= 0.5 }?.let { m[ImportField.ACTION] = it }
        val numeric = cols.filter { it !in m.values && share(it) { c -> num(c) != null } >= 0.5 }
        numeric.getOrNull(0)?.let { m[ImportField.QTY] = it }
        numeric.getOrNull(1)?.let { m[ImportField.PRICE] = it }
        numeric.getOrNull(2)?.let { m[ImportField.FEES] = it }
        return m
    }

    private var nextId = 1L

    fun blankDraft() = DraftTxn(nextId++, "", TxnKind.BUY, "", "", "", LocalDate.now().toString(), source = "Added by hand")

    /** Turns parsed rows into editable drafts using [mapping]. */
    fun drafts(p: ParsedImport, mapping: Map<ImportField, Int>): List<DraftTxn> = p.rows.mapIndexed { i, r ->
        fun cell(f: ImportField) = mapping[f]?.let { r.getOrNull(it) }?.trim()?.trim('"').orEmpty()
        val line = (if (p.hasHeader) p.skippedLines + 2 else 1) + i
        val sym = normalizeCrypto(cell(ImportField.SYMBOL).uppercase().removePrefix("$").trim().split(" ").firstOrNull().orEmpty().removeSuffix("*"), r)
        val act = cell(ImportField.ACTION).lowercase()
        val qn = num(cell(ImportField.QTY))
        var price = num(cell(ImportField.PRICE))
        val amount = num(cell(ImportField.AMOUNT))
        var include = true
        var note = ""
        val kind = when {
            act.contains("sell") || act.contains("sold") || act.contains("sale") -> TxnKind.SELL
            act.contains("reinvest") -> TxnKind.BUY
            act.contains("div") || act.contains("distribution") -> TxnKind.DIVIDEND
            act.contains("buy") || act.contains("bought") || act.contains("purchase") -> TxnKind.BUY
            act.isBlank() -> if ((qn ?: 0.0) < 0) TxnKind.SELL else TxnKind.BUY
            else -> { include = false; note = "Unrecognized action \"${cell(ImportField.ACTION)}\""; TxnKind.BUY }
        }
        val qty = qn?.let { kotlin.math.abs(it) }
        if (kind == TxnKind.DIVIDEND) price = amount?.let { kotlin.math.abs(it) } ?: price?.let { p0 -> qty?.takeIf { it > 0 }?.let { p0 * it } ?: p0 }
        else if (price == null && amount != null && qty != null && qty > 0) price = kotlin.math.abs(amount) / qty
        if (sym.isBlank()) { include = false; if (note.isBlank()) note = "No symbol (cash or fee row?)" }
        else if (sym in setOf("CASH", "TOTAL", "TOTALS", "ACCOUNT", "SUBTOTAL") || sym.startsWith("TOTAL")) { include = false; note = "Cash/total row" }
        val d = cell(ImportField.DATE)
        DraftTxn(nextId++, sym, kind, qty?.let { fmtNum(it) }.orEmpty(), price?.let { fmtNum(it) }.orEmpty(),
            (num(cell(ImportField.FEES)) to num(cell(ImportField.COMMISSION))).let { (f, c) ->
                if (f == null && c == null) "" else fmtNum(kotlin.math.abs(f ?: 0.0) + kotlin.math.abs(c ?: 0.0))
            },
            PortfolioCalc.parseDate(d)?.let { PortfolioCalc.fmtDate(it) } ?: d, note, include,
            "Line $line: " + r.joinToString(" | ").take(140))
    }

    /** Common coins, so "BTC", "BTCUSD" or "BTC/USD" from a broker export become the app's "BTC-USD". */
    val cryptoBases = setOf(
        "BTC", "ETH", "SOL", "XRP", "DOGE", "ADA", "AVAX", "LINK", "DOT", "LTC", "BCH", "SHIB", "UNI", "XLM", "ATOM", "NEAR", "APT",
        "ARB", "OP", "SUI", "MATIC", "POL", "AAVE", "ALGO", "ETC", "FIL", "HBAR", "ICP", "PEPE", "TRX", "XTZ", "USDC", "USDT", "BNB", "TON",
    )

    fun normalizeCrypto(sym: String, row: List<String> = emptyList()): String {
        val s = sym.trim().uppercase()
        if (s.endsWith("-USD")) return s
        val base = when {
            s.contains("/") -> s.substringBefore("/").takeIf { s.substringAfter("/") in setOf("USD", "USDT", "USDC") }
            s.length > 3 && s.endsWith("USD") && s.removeSuffix("USD") in cryptoBases -> s.removeSuffix("USD")
            else -> null
        }
        if (base != null) return "$base-USD"
        val rowText = row.joinToString(" ").lowercase()
        val cryptoRow = rowText.contains("crypto") || rowText.contains("zero hash") || rowText.contains("zerohash") ||
            rowText.contains("bitcoin") || rowText.contains("ethereum")
        // "BTC" alone could be a stock ticker, so only convert it when the row says it's crypto.
        return if (s in cryptoBases && cryptoRow) "$s-USD" else s
    }

    fun isCryptoSymbol(s: String) = s.trim().uppercase().endsWith("-USD")

    /** Fills the fee on crypto rows that have none, using [pct] percent of quantity × price. Returns how many rows changed. */
    fun applyCryptoFee(drafts: MutableList<DraftTxn>, pct: Double): Int {
        var n = 0
        for (i in drafts.indices) {
            val d = drafts[i]
            if (!isCryptoSymbol(d.symbol) || d.kind == TxnKind.DIVIDEND) continue
            if ((num(d.fees) ?: 0.0) > 0) continue
            val q = num(d.qty) ?: continue; val p = num(d.price) ?: continue
            drafts[i] = d.copy(fees = fmtNum(kotlin.math.round(q * p * pct) / 100.0), note = (d.note + " ${fmtNum(pct)}% crypto commission added").trim())
            n++
        }
        return n
    }

    fun fmtNum(v: Double): String = java.math.BigDecimal(v).setScale(6, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

    const val TEMPLATE = "date,symbol,action,quantity,price,fees\n2026-01-15,AAPL,BUY,10,185.50,0\n2026-02-03,MSFT,BUY,5,410.25,0\n2026-03-20,AAPL,SELL,2,210.00,0\n2026-05-15,AAPL,DIVIDEND,,2.00,0\n2026-06-01,BTC-USD,BUY,0.05,68000,1.50\n"
}
