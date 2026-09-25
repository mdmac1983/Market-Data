package app.orionmd.marketdata.ui

import java.text.DecimalFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

private val p2 = DecimalFormat("#,##0.00")
private val p4 = DecimalFormat("#,##0.0000")
private val p6 = DecimalFormat("#,##0.000000")
private val p0 = DecimalFormat("#,##0")

fun fmtPrice(v: Double?): String = when {
    v == null || v.isNaN() -> "—"
    abs(v) >= 1 -> p2.format(v)
    abs(v) >= 0.01 -> p4.format(v)
    else -> p6.format(v)
}

fun fmtMoney(v: Double?): String = if (v == null || v.isNaN()) "—" else (if (v < 0) "-$" else "$") + p2.format(abs(v))

fun fmtSigned(v: Double?): String = if (v == null || v.isNaN()) "—" else (if (v >= 0) "+" else "") + fmtPrice(v)

fun fmtSignedMoney(v: Double?): String = if (v == null || v.isNaN()) "—" else (if (v >= 0) "+$" else "-$") + p2.format(abs(v))

fun fmtPct(v: Double?): String = if (v == null || v.isNaN()) "—" else (if (v >= 0) "+" else "") + p2.format(v) + "%"

fun fmtBig(v: Double?): String {
    if (v == null || v.isNaN()) return "—"
    val a = abs(v); val s = if (v < 0) "-" else ""
    return s + when {
        a >= 1e12 -> p2.format(a / 1e12) + "T"
        a >= 1e9 -> p2.format(a / 1e9) + "B"
        a >= 1e6 -> p2.format(a / 1e6) + "M"
        a >= 1e3 -> p2.format(a / 1e3) + "K"
        else -> p0.format(a)
    }
}

fun fmtNum(v: Double?, digits: Int = 2): String = if (v == null || v.isNaN()) "—" else "%,.${digits}f".format(v)

fun fmtAgo(epochSec: Long): String {
    if (epochSec <= 0) return ""
    val d = System.currentTimeMillis() / 1000 - epochSec
    return when {
        d < 60 -> "just now"
        d < 3600 -> "${d / 60}m ago"
        d < 86400 -> "${d / 3600}h ago"
        d < 7 * 86400 -> "${d / 86400}d ago"
        else -> Instant.ofEpochSecond(epochSec).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MMM d"))
    }
}

fun fmtTime(epochMs: Long, pattern: String = "MMM d, h:mm a"): String =
    Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern(pattern))
