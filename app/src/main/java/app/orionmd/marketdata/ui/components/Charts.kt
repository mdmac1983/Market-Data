package app.orionmd.marketdata.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.orionmd.marketdata.data.Candle
import app.orionmd.marketdata.data.Indicators
import app.orionmd.marketdata.data.Trendline
import app.orionmd.marketdata.ui.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class Indicator(val label: String, val color: Color) {
    SMA20("SMA 20", Color(0xFFF5C44F)), SMA50("SMA 50", Color(0xFFA78BFA)), SMA200("SMA 200", Color(0xFFF472B6)),
    EMA20("EMA 20", Color(0xFF34D399)), BB("Bollinger", Color(0xFF60A5FA)), VOLUME("Volume", Color(0xFF64748B)),
    RSI("RSI", Color(0xFFFB923C)), MACD("MACD", Color(0xFF22D3EE)),
}

private fun fmtAxisTime(t: Long, intraday: Boolean): String =
    Instant.ofEpochSecond(t).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern(if (intraday) "MMM d h:mm a" else "MMM d, yyyy"))

@Composable
fun PriceChart(
    candles: List<Candle>,
    candlestick: Boolean,
    indicators: Set<Indicator>,
    trendlines: List<Trendline>,
    drawMode: Boolean,
    onTrendline: (Trendline) -> Unit,
    intraday: Boolean,
    prevClose: Double? = null,
    modifier: Modifier = Modifier,
) {
    if (candles.size < 2) { Box(modifier.height(220.dp), contentAlignment = Alignment.Center) { Text("No chart data") }; return }
    val closes = remember(candles) { candles.map { it.c } }
    val sma20 = remember(closes) { Indicators.sma(closes, 20) }
    val sma50 = remember(closes) { Indicators.sma(closes, 50) }
    val sma200 = remember(closes) { Indicators.sma(closes, 200) }
    val ema20 = remember(closes) { Indicators.ema(closes, 20) }
    val bb = remember(closes) { Indicators.bollinger(closes) }
    val rsi = remember(closes) { Indicators.rsi(closes) }
    val macd = remember(closes) { Indicators.macd(closes) }

    var cross by remember(candles) { mutableStateOf<Int?>(null) }
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragEnd by remember { mutableStateOf<Offset?>(null) }

    val up = changeColor(closes.last() - (prevClose ?: closes.first()))
    val grid = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant

    // price range includes visible overlays
    val lo = remember(candles, indicators) {
        var m = candles.minOf { if (candlestick) it.l else it.c }
        if (Indicator.BB in indicators) bb.lower.filterNotNull().minOrNull()?.let { m = minOf(m, it) }
        m
    }
    val hi = remember(candles, indicators) {
        var m = candles.maxOf { if (candlestick) it.h else it.c }
        if (Indicator.BB in indicators) bb.upper.filterNotNull().maxOrNull()?.let { m = maxOf(m, it) }
        m
    }
    val pad = (hi - lo).takeIf { it > 0 }?.times(0.06) ?: (hi * 0.01)
    val minP = lo - pad; val maxP = hi + pad

    Column(modifier) {
        // header readout
        val idx = cross
        Row(Modifier.fillMaxWidth().height(20.dp), verticalAlignment = Alignment.CenterVertically) {
            if (idx != null && idx in candles.indices) {
                val c = candles[idx]
                Text(fmtAxisTime(c.t, intraday) + "  ", style = MaterialTheme.typography.labelSmall, color = textColor)
                Text(if (candlestick) "O ${fmtPrice(c.o)} H ${fmtPrice(c.h)} L ${fmtPrice(c.l)} C ${fmtPrice(c.c)}" else fmtPrice(c.c),
                    style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            } else if (drawMode) {
                Text("Drag on the chart to draw a trendline", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
        Canvas(
            Modifier.fillMaxWidth().height(240.dp)
                .pointerInput(candles, drawMode) {
                    if (drawMode) {
                        detectDragGestures(
                            onDragStart = { dragStart = it; dragEnd = it },
                            onDrag = { ch, _ -> dragEnd = ch.position },
                            onDragEnd = {
                                val s = dragStart; val e = dragEnd
                                if (s != null && e != null && (s - e).getDistance() > 20) {
                                    fun toPoint(o: Offset): Pair<Long, Double> {
                                        val i = ((o.x / size.width) * (candles.size - 1)).toInt().coerceIn(0, candles.size - 1)
                                        val p = maxP - (o.y / size.height) * (maxP - minP)
                                        return candles[i].t to p
                                    }
                                    val (t1, p1) = toPoint(s); val (t2, p2) = toPoint(e)
                                    onTrendline(Trendline(t1, p1, t2, p2))
                                }
                                dragStart = null; dragEnd = null
                            },
                        )
                    } else {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            fun setIdx(x: Float) { cross = ((x / size.width) * (candles.size - 1)).toInt().coerceIn(0, candles.size - 1) }
                            setIdx(down.position.x)
                            do {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull() ?: break
                                setIdx(ch.position.x)
                                if (ch.positionChange().x != 0f) ch.consume()
                            } while (ev.changes.any { it.pressed })
                            cross = null
                        }
                    }
                },
        ) {
            val n = candles.size
            val w = size.width; val h = size.height
            fun x(i: Int) = if (n == 1) w / 2 else w * i / (n - 1)
            fun y(p: Double) = (h * (1 - (p - minP) / (maxP - minP))).toFloat()

            // grid + labels
            for (g in 0..4) {
                val yy = h * g / 4
                drawLine(grid, Offset(0f, yy), Offset(w, yy), 1f)
            }
            prevClose?.takeIf { intraday && it in minP..maxP }?.let {
                drawLine(textColor.copy(alpha = 0.6f), Offset(0f, y(it)), Offset(w, y(it)), 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
            }

            if (Indicator.VOLUME in indicators) {
                val maxV = candles.maxOf { it.v }.takeIf { it > 0 } ?: 1.0
                val bw = (w / n * 0.7f).coerceAtLeast(1f)
                candles.forEachIndexed { i, c ->
                    val vh = (h * 0.22f * (c.v / maxV)).toFloat()
                    drawRect((if (c.c >= c.o) Up else Down).copy(alpha = 0.25f), Offset(x(i) - bw / 2, h - vh), Size(bw, vh))
                }
            }

            if (Indicator.BB in indicators) {
                drawSeries(bb.upper, ::x, ::y, Indicator.BB.color.copy(alpha = 0.7f))
                drawSeries(bb.lower, ::x, ::y, Indicator.BB.color.copy(alpha = 0.7f))
                drawSeries(bb.mid, ::x, ::y, Indicator.BB.color.copy(alpha = 0.4f))
            }

            if (candlestick) {
                val bw = (w / n * 0.65f).coerceIn(1f, 18f)
                candles.forEachIndexed { i, c ->
                    val col = if (c.c >= c.o) Up else Down
                    drawLine(col, Offset(x(i), y(c.h)), Offset(x(i), y(c.l)), 1.2f)
                    val top = y(maxOf(c.o, c.c)); val bot = y(minOf(c.o, c.c))
                    drawRect(col, Offset(x(i) - bw / 2, top), Size(bw, (bot - top).coerceAtLeast(1f)))
                }
            } else {
                val path = Path(); val fill = Path()
                closes.forEachIndexed { i, c -> if (i == 0) { path.moveTo(x(i), y(c)); fill.moveTo(x(i), h); fill.lineTo(x(i), y(c)) } else { path.lineTo(x(i), y(c)); fill.lineTo(x(i), y(c)) } }
                fill.lineTo(x(n - 1), h); fill.close()
                drawPath(fill, androidx.compose.ui.graphics.Brush.verticalGradient(listOf(up.copy(alpha = 0.30f), up.copy(alpha = 0.0f))))
                drawPath(path, up, style = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round))
            }

            if (Indicator.SMA20 in indicators) drawSeries(sma20, ::x, ::y, Indicator.SMA20.color)
            if (Indicator.SMA50 in indicators) drawSeries(sma50, ::x, ::y, Indicator.SMA50.color)
            if (Indicator.SMA200 in indicators) drawSeries(sma200, ::x, ::y, Indicator.SMA200.color)
            if (Indicator.EMA20 in indicators) drawSeries(ema20, ::x, ::y, Indicator.EMA20.color)

            // trendlines (mapped by timestamp to nearest candle)
            fun idxOf(t: Long): Int? {
                if (t < candles.first().t - 86400 * 7 || t > candles.last().t + 86400 * 7) return null
                var bestI = 0; var best = Long.MAX_VALUE
                candles.forEachIndexed { i, c -> val d = kotlin.math.abs(c.t - t); if (d < best) { best = d; bestI = i } }
                return bestI
            }
            trendlines.forEach { tl ->
                val i1 = idxOf(tl.t1); val i2 = idxOf(tl.t2)
                if (i1 != null && i2 != null) drawLine(Gold, Offset(x(i1), y(tl.p1)), Offset(x(i2), y(tl.p2)), 2.5f, cap = StrokeCap.Round)
            }
            val s = dragStart; val e = dragEnd
            if (s != null && e != null) drawLine(Gold, s, e, 2.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f)))

            // crosshair
            cross?.let { i ->
                drawLine(textColor, Offset(x(i), 0f), Offset(x(i), h), 1f)
                drawCircle(up, 5.dp.toPx(), Offset(x(i), y(closes[i])))
            }
        }
        // price scale labels
        Row(Modifier.fillMaxWidth()) {
            Text("L ${fmtPrice(lo)}", style = MaterialTheme.typography.labelSmall, color = textColor, modifier = Modifier.weight(1f))
            Text("H ${fmtPrice(hi)}", style = MaterialTheme.typography.labelSmall, color = textColor)
        }
        if (Indicator.RSI in indicators) {
            Text("RSI (14): ${fmtNum(rsi.lastOrNull { it != null })}", style = MaterialTheme.typography.labelSmall, color = Indicator.RSI.color)
            Canvas(Modifier.fillMaxWidth().height(70.dp)) {
                val n = candles.size
                fun x(i: Int) = size.width * i / (n - 1)
                fun y(v: Double) = (size.height * (1 - v / 100)).toFloat()
                listOf(30.0, 70.0).forEach { drawLine(grid, Offset(0f, y(it)), Offset(size.width, y(it)), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))) }
                drawSeries(rsi, ::x, ::y, Indicator.RSI.color)
                cross?.let { drawLine(textColor, Offset(x(it), 0f), Offset(x(it), size.height), 1f) }
            }
        }
        if (Indicator.MACD in indicators) {
            Text("MACD (12,26,9): ${fmtNum(macd.macd.lastOrNull { it != null })}  signal ${fmtNum(macd.signal.lastOrNull { it != null })}",
                style = MaterialTheme.typography.labelSmall, color = Indicator.MACD.color)
            Canvas(Modifier.fillMaxWidth().height(80.dp)) {
                val n = candles.size
                val all = (macd.macd + macd.signal + macd.hist).filterNotNull()
                if (all.isEmpty()) return@Canvas
                val mx = all.maxOf { kotlin.math.abs(it) }.takeIf { it > 0 } ?: 1.0
                fun x(i: Int) = size.width * i / (n - 1)
                fun y(v: Double) = (size.height / 2 * (1 - v / mx)).toFloat()
                drawLine(grid, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1f)
                val bw = (size.width / n * 0.6f).coerceAtLeast(1f)
                macd.hist.forEachIndexed { i, v -> if (v != null) drawRect((if (v >= 0) Up else Down).copy(alpha = 0.6f), Offset(x(i) - bw / 2, minOf(y(v), y(0.0))), Size(bw, kotlin.math.abs(y(v) - y(0.0)))) }
                drawSeries(macd.macd, ::x, ::y, Indicator.MACD.color)
                drawSeries(macd.signal, ::x, ::y, Gold)
                cross?.let { drawLine(textColor, Offset(x(it), 0f), Offset(x(it), size.height), 1f) }
            }
        }
    }
}

private fun DrawScope.drawSeries(v: List<Double?>, x: (Int) -> Float, y: (Double) -> Float, color: Color) {
    val path = Path(); var started = false
    v.forEachIndexed { i, p ->
        if (p == null) { started = false; return@forEachIndexed }
        if (!started) { path.moveTo(x(i), y(p)); started = true } else path.lineTo(x(i), y(p))
    }
    drawPath(path, color, style = Stroke(1.6.dp.toPx()))
}

val SeriesColors = listOf(Color(0xFF3FC8F5), Color(0xFFF5C44F), Color(0xFFF472B6), Color(0xFF34D399), Color(0xFFA78BFA))

/** Overlays several series as % change from their first value. */
@Composable
fun CompareChart(series: List<Pair<String, List<Candle>>>, modifier: Modifier = Modifier) {
    val grid = MaterialTheme.colorScheme.outlineVariant
    val norm = remember(series) { series.map { (s, c) -> s to c.map { it.t to (it.c / c.first().c - 1) * 100 } } }
    val all = norm.flatMap { it.second.map { p -> p.second } }
    if (all.isEmpty()) return
    val lo = minOf(all.min(), 0.0); val hi = maxOf(all.max(), 0.0)
    val t0 = norm.minOf { it.second.firstOrNull()?.first ?: Long.MAX_VALUE }
    val t1 = norm.maxOf { it.second.lastOrNull()?.first ?: 0L }
    Canvas(modifier.fillMaxWidth().height(260.dp)) {
        fun x(t: Long) = if (t1 == t0) 0f else (size.width * (t - t0).toDouble() / (t1 - t0)).toFloat()
        fun y(v: Double) = (size.height * (1 - (v - lo) / (hi - lo).coerceAtLeast(0.001))).toFloat()
        drawLine(grid, Offset(0f, y(0.0)), Offset(size.width, y(0.0)), 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
        norm.forEachIndexed { k, (_, pts) ->
            val path = Path()
            pts.forEachIndexed { i, (t, v) -> if (i == 0) path.moveTo(x(t), y(v)) else path.lineTo(x(t), y(v)) }
            drawPath(path, SeriesColors[k % SeriesColors.size], style = Stroke(2.dp.toPx()))
        }
    }
}

@Composable
fun DonutChart(slices: List<Pair<String, Double>>, modifier: Modifier = Modifier) {
    val total = slices.sumOf { it.second }.takeIf { it > 0 } ?: return
    Canvas(modifier) {
        var start = -90f
        val stroke = size.minDimension * 0.18f
        val d = size.minDimension - stroke
        slices.forEachIndexed { i, (_, v) ->
            val sweep = (v / total * 360).toFloat()
            drawArc(pieColor(i), start, sweep - 1f, false, Offset((size.width - d) / 2, (size.height - d) / 2), Size(d, d), style = Stroke(stroke))
            start += sweep
        }
    }
}

fun pieColor(i: Int): Color = listOf(
    Color(0xFF3FC8F5), Color(0xFFF5C44F), Color(0xFFA78BFA), Color(0xFF34D399), Color(0xFFF472B6), Color(0xFFFB923C),
    Color(0xFF60A5FA), Color(0xFF84CC16), Color(0xFFE879F9), Color(0xFF94A3B8),
)[i % 10]

/** Simple line chart for series like economic data or the yield curve. */
@Composable
fun SimpleLineChart(points: List<Double>, modifier: Modifier = Modifier, color: Color = Cyan, showDots: Boolean = false) {
    if (points.size < 2) return
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier) {
        val lo = points.min(); val hi = points.max(); val r = (hi - lo).takeIf { it > 0 } ?: 1.0
        fun x(i: Int) = size.width * i / (points.size - 1)
        fun y(v: Double) = (size.height * 0.9f * (1 - (v - lo) / r) + size.height * 0.05f).toFloat()
        for (g in 0..3) drawLine(grid.copy(alpha = 0.5f), Offset(0f, size.height * g / 3), Offset(size.width, size.height * g / 3), 1f)
        val p = Path()
        points.forEachIndexed { i, v -> if (i == 0) p.moveTo(x(i), y(v)) else p.lineTo(x(i), y(v)) }
        drawPath(p, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
        if (showDots) points.forEachIndexed { i, v -> drawCircle(color, 4.dp.toPx(), Offset(x(i), y(v))) }
    }
}
