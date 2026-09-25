package app.orionmd.marketdata.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.orionmd.marketdata.data.Catalog
import app.orionmd.marketdata.data.Prefs
import app.orionmd.marketdata.data.Quote
import app.orionmd.marketdata.data.QuoteHub
import app.orionmd.marketdata.data.Tape
import app.orionmd.marketdata.ui.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Two scrolling rows (NYSE and NASDAQ) of live prices. */
@Composable
fun TickerTape(modifier: Modifier = Modifier) {
    val s by Prefs.settings.collectAsState()
    var lists by remember { mutableStateOf(Tape.defaultNyse.take(s.tapeCount) to Tape.defaultNasdaq.take(s.tapeCount)) }
    LaunchedEffect(s.tapeCustom, s.tapeCount, s.tapeNyse, s.tapeNasdaq) {
        while (isActive) {
            lists = Tape.symbols(s)
            delay(5 * 60_000L) // most-active lists change during the day
        }
    }
    val (nyse, nasdaq) = lists
    val all = remember(lists) { listOf(Tape.NYSE_INDEX, Tape.NASDAQ_INDEX) + nyse + nasdaq }
    // Tape-only symbols stream only if there's room under the free-tier cap; screens take priority.
    DisposableEffect(all) {
        QuoteHub.lowPriority = all.toSet()
        onDispose { QuoteHub.lowPriority = emptySet() }
    }
    val quotes = rememberLive(all)
    val speed = when (s.tapeSpeed) { 0 -> 28f; 2 -> 80f; else -> 48f } // dp per second
    val dark = LocalDark.current
    Column(
        modifier.fillMaxWidth().background(if (dark) Color(0xCC050817) else Color(0xCCDCE0E9)),
    ) {
        TapeRow("NYSE", Tape.NYSE_INDEX, nyse, quotes, speed)
        Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)))
        TapeRow("NASDAQ", Tape.NASDAQ_INDEX, nasdaq, quotes, speed * 0.9f)
    }
}

@Composable
private fun TapeRow(label: String, index: String, symbols: List<String>, quotes: Map<String, Quote>, speedDp: Float) {
    val nav = LocalNav.current
    val density = LocalDensity.current
    var paused by remember { mutableStateOf(false) }
    var contentWidth by remember { mutableIntStateOf(0) }
    var offset by remember { mutableFloatStateOf(0f) }
    val pxPerSec = with(density) { speedDp.dp.toPx() }

    LaunchedEffect(contentWidth, pxPerSec) {
        var last = 0L
        while (isActive) {
            withFrameNanos { now ->
                if (last != 0L && !paused && contentWidth > 0) {
                    offset -= pxPerSec * (now - last) / 1_000_000_000f
                    if (offset <= -contentWidth) offset += contentWidth
                }
                last = now
            }
        }
    }

    Row(Modifier.fillMaxWidth().height(26.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.fillMaxHeight().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)).padding(horizontal = 6.dp),
            contentAlignment = Alignment.Center,
        ) { Text(label, color = MaterialTheme.colorScheme.onPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
        Box(
            Modifier.weight(1f).fillMaxHeight().clipToBounds()
                // press and hold anywhere on the row to pause it (taps still reach the tickers)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        paused = true
                        waitForUpOrCancellation(PointerEventPass.Initial)
                        paused = false
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Row(Modifier.wrapContentWidth(Alignment.Start, unbounded = true).offset { IntOffset(offset.toInt(), 0) }) {
                // Two copies side by side so the loop is seamless.
                repeat(2) { copy ->
                    Row(
                        (if (copy == 0) Modifier.onSizeChanged { contentWidth = it.width } else Modifier),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TapeItem(index, quotes[index], isIndex = true) { nav.symbol(index) }
                        symbols.forEach { sym -> TapeItem(sym, quotes[sym], isIndex = false) { nav.symbol(sym) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun TapeItem(symbol: String, q: Quote?, isIndex: Boolean, onClick: () -> Unit) {
    var last by remember { mutableStateOf(q?.price) }
    var flash by remember { mutableStateOf(Color.Transparent) }
    LaunchedEffect(q?.price) {
        val l = last; val p = q?.price
        if (p != null && l != null && p != l) {
            flash = if (p > l) Up.copy(alpha = 0.35f) else Down.copy(alpha = 0.35f)
            delay(500); flash = Color.Transparent
        }
        last = p
    }
    val bg by animateColorAsState(flash, tween(350), label = "tapeflash")
    val c = changeColor(q?.changePct)
    Row(
        Modifier.padding(horizontal = 3.dp).clip(RoundedCornerShape(4.dp)).background(bg).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (isIndex) Catalog.shortName(symbol) else Catalog.display(symbol), fontSize = 12.sp, fontWeight = FontWeight.Bold,
            color = if (isIndex) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground)
        Text(" " + fmtPrice(q?.price), fontSize = 12.sp, color = MaterialTheme.colorScheme.onBackground)
        if (q != null) Text(" " + (if (q.changePct >= 0) "▲" else "▼") + "%.2f%%".format(kotlin.math.abs(q.changePct)), fontSize = 11.sp, color = c, fontWeight = FontWeight.SemiBold)
        Text("   ·", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
    }
}
