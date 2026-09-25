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

    private val dateFmts = listOf("yyyy-MM-dd", "MM/dd/yyyy", "M/d/yyyy", "dd-MM-yyyy", "yyyy/MM/dd", "MM/dd/yy")

    fun parseDate(s: String): Long? {
        val t = s.trim().take(10).trim()
        for (f in dateFmts) runCatching { return LocalDate.parse(t, DateTimeFormatter.ofPattern(f)).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }
        return null
    }

    fun fmtDate(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate().toString()

    /**
     * Imports a broker CSV. Understands common column names:
     * symbol/ticker, action/type/side, quantity/shares/qty, price, fees/commission, date/trade date, amount.
     */
    fun importCsv(text: String): Pair<List<Txn>, List<String>> {
        val lines = text.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return emptyList<Txn>() to listOf("File is empty")
        val header = splitCsv(lines.first()).map { it.lowercase().trim() }
        fun col(vararg names: String) = header.indexOfFirst { h -> names.any { h == it || h.contains(it) } }
        val iSym = col("symbol", "ticker"); val iAct = col("action", "type", "side", "transaction")
        val iQty = col("quantity", "shares", "qty"); val iPrice = col("price"); val iFee = col("fee", "commission")
        val iDate = col("trade date", "date"); val iAmt = col("amount", "total")
        if (iSym < 0) return emptyList<Txn>() to listOf("No 'symbol' or 'ticker' column found")
        val errors = mutableListOf<String>()
        val out = lines.drop(1).mapIndexedNotNull { n, line ->
            val c = splitCsv(line)
            fun g(i: Int) = if (i in c.indices) c[i].trim() else ""
            fun num(i: Int) = g(i).replace("$", "").replace(",", "").replace("(", "-").replace(")", "").toDoubleOrNull()
            val sym = g(iSym).uppercase().ifBlank { return@mapIndexedNotNull null }
            val act = g(iAct).lowercase()
            val kind = when {
                act.contains("sell") || act.contains("sold") -> TxnKind.SELL
                act.contains("div") -> TxnKind.DIVIDEND
                act.isBlank() || act.contains("buy") || act.contains("bought") || act.contains("reinvest") -> TxnKind.BUY
                else -> { errors += "Row ${n + 2}: skipped '$act'"; return@mapIndexedNotNull null }
            }
            val qty = kotlin.math.abs(num(iQty) ?: 0.0)
            var price = num(iPrice)
            if (kind == TxnKind.DIVIDEND) {
                val amt = kotlin.math.abs(num(iAmt) ?: price ?: 0.0)
                return@mapIndexedNotNull Txn(Store.newId(), sym, kind, 0.0, amt, 0.0, parseDate(g(iDate)) ?: System.currentTimeMillis(), "Imported")
            }
            if (price == null && qty > 0) price = num(iAmt)?.let { kotlin.math.abs(it) / qty }
            if (qty <= 0 || price == null) { errors += "Row ${n + 2}: missing quantity or price"; return@mapIndexedNotNull null }
            Txn(Store.newId(), sym, kind, qty, price, kotlin.math.abs(num(iFee) ?: 0.0), parseDate(g(iDate)) ?: System.currentTimeMillis(), "Imported")
        }
        return out to errors
    }

    fun splitCsv(line: String): List<String> {
        val out = mutableListOf<String>(); val sb = StringBuilder(); var q = false
        for (ch in line) when {
            ch == '"' -> q = !q
            ch == ',' && !q -> { out += sb.toString(); sb.clear() }
            else -> sb.append(ch)
        }
        out += sb.toString()
        return out
    }
}
