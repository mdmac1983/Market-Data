package app.orionmd.marketdata.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.orionmd.marketdata.data.Catalog
import app.orionmd.marketdata.data.Market
import app.orionmd.marketdata.data.NewsItem
import app.orionmd.marketdata.data.Quote
import app.orionmd.marketdata.data.QuoteHub
import app.orionmd.marketdata.data.SearchResult
import app.orionmd.marketdata.ui.*
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

// ---------- navigation ----------

interface Nav {
    fun go(route: String)
    fun symbol(s: String) = go("symbol/${Uri.encode(s)}")
    fun back()
}

val LocalNav = staticCompositionLocalOf<Nav> { error("no nav") }

// ---------- data helpers ----------

/** Live quotes for [symbols]; registers interest with the hub while on screen. */
@Composable
fun rememberLive(symbols: List<String>): Map<String, Quote> {
    val key = symbols.joinToString(",")
    DisposableEffect(key) {
        QuoteHub.watch(symbols)
        onDispose { QuoteHub.unwatch(symbols) }
    }
    val all by QuoteHub.quotes.collectAsState()
    return remember(all, key) { symbols.mapNotNull { s -> all[s]?.let { s to it } }.toMap() }
}

class LoadState<T>(val data: T?, val error: String?, val loading: Boolean, val reload: () -> Unit)

/** Loads data with [loader]; reloads when keys change and every [refreshMs] if > 0. */
@Composable
fun <T> rememberLoad(vararg keys: Any?, refreshMs: Long = 0, loader: suspend () -> T): LoadState<T> {
    var data by remember(*keys) { mutableStateOf<T?>(null) }
    var error by remember(*keys) { mutableStateOf<String?>(null) }
    var loading by remember(*keys) { mutableStateOf(true) }
    var tick by remember(*keys) { mutableIntStateOf(0) }
    LaunchedEffect(*keys, tick) {
        while (true) {
            loading = true
            try {
                data = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { loader() }; error = null
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = friendlyError(e)
            }
            loading = false
            if (refreshMs <= 0) break
            delay(refreshMs)
        }
    }
    return LoadState(data, error, loading) { tick++ }
}

fun friendlyError(e: Throwable): String = when {
    e is app.orionmd.marketdata.data.HttpException && e.code == 429 -> "Rate limited by the data provider. Try again in a minute."
    e is app.orionmd.marketdata.data.HttpException && (e.code == 401 || e.code == 403) -> "Not available with the current API key/plan."
    e is java.net.UnknownHostException -> "No internet connection."
    e is java.net.SocketTimeoutException -> "The data provider took too long to respond."
    else -> e.message ?: e.javaClass.simpleName
}

@Composable
fun <T> LoadContent(state: LoadState<T>, empty: String = "Nothing to show", isEmpty: (T) -> Boolean = { (it as? Collection<*>)?.isEmpty() == true }, content: @Composable (T) -> Unit) {
    val d = state.data
    when {
        d != null && !isEmpty(d) -> content(d)
        state.loading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.5.dp) }
        state.error != null -> ErrorBox(state.error, state.reload)
        else -> Text(empty, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun ErrorBox(msg: String, retry: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(msg, Modifier.weight(1f), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        if (retry != null) IconButton(onClick = retry) { Icon(Icons.Default.Refresh, "Retry") }
    }
}

@Composable
fun KeyNeeded(service: String, what: String) {
    val nav = LocalNav.current
    Column(Modifier.fillMaxWidth().padding(14.dp)) {
        Text("$what needs a free $service API key.", style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = { nav.go("settings") }, contentPadding = PaddingValues(0.dp)) { Text("Add key in Settings →") }
    }
}

// ---------- layout ----------

@Composable
fun SectionCard(
    title: String? = null,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
    onTitleClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val sp = LocalSpacing.current
    val dark = LocalDark.current
    val size = LocalCardSize.current
    val collapse = LocalCardCollapse.current.takeIf { title != null }
    Card(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(when (size) { 2 -> 10.dp; 1 -> 14.dp; else -> 18.dp }),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = if (dark) 0.78f else 0.70f)),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
    ) {
        Column(Modifier.padding(sp.card)) {
            if (title != null) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(bottom = if (collapse?.collapsed == true) 0.dp else if (size == 2) 2.dp else 6.dp)) {
                    Text(title, style = when (size) { 2 -> MaterialTheme.typography.labelLarge; 1 -> MaterialTheme.typography.titleSmall; else -> MaterialTheme.typography.titleMedium },
                        fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).then(if (onTitleClick != null) Modifier.clickable(onClick = onTitleClick) else Modifier))
                    if (collapse?.collapsed != true) action?.invoke()
                    if (collapse != null) Icon(
                        if (collapse.collapsed) Icons.Default.ExpandMore else Icons.Default.ExpandLess, if (collapse.collapsed) "Expand" else "Collapse",
                        Modifier.size(if (size == 2) 20.dp else 24.dp).clip(CircleShape).clickable(onClick = collapse.toggle),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (collapse?.collapsed != true) CompositionLocalProvider(LocalCardCollapse provides null) { content() }
        }
    }
}

@Composable
fun ChipRow(options: List<String>, selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { o -> FilterChip(selected = o == selected, onClick = { onSelect(o) }, label = { Text(o) }) }
    }
}

@Composable
fun ChangePill(pct: Double?, modifier: Modifier = Modifier) {
    val c by animateColorAsState(changeColor(pct), tween(300), label = "pill")
    val small = LocalCardSize.current == 2
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(c.copy(alpha = 0.16f)).padding(horizontal = if (small) 5.dp else 8.dp, vertical = if (small) 1.dp else 3.dp)) {
        Text(fmtPct(pct), color = c, fontWeight = FontWeight.SemiBold, style = if (small) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun Sparkline(values: List<Double>, modifier: Modifier = Modifier, color: Color? = null, baseline: Double? = null) {
    if (values.size < 2) { Spacer(modifier); return }
    val c = color ?: changeColor(values.last() - (baseline ?: values.first()))
    Canvas(modifier) {
        val min = minOf(values.min(), baseline ?: values.min()); val max = maxOf(values.max(), baseline ?: values.max())
        val range = (max - min).takeIf { it > 0 } ?: 1.0
        fun y(v: Double) = (size.height * (1 - (v - min) / range)).toFloat()
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = size.width * i / (values.size - 1)
            if (i == 0) path.moveTo(x, y(v)) else path.lineTo(x, y(v))
        }
        baseline?.let { drawLine(c.copy(alpha = 0.3f), Offset(0f, y(it)), Offset(size.width, y(it)), 1f) }
        drawPath(path, c, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Composable
fun QuoteRow(symbol: String, q: Quote?, onClick: () -> Unit, modifier: Modifier = Modifier, showSpark: Boolean = true, trailing: (@Composable () -> Unit)? = null) {
    val sp = LocalSpacing.current
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = sp.row, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val size = LocalCardSize.current
        Column(Modifier.weight(1f)) {
            Text(Catalog.display(symbol), fontWeight = FontWeight.Bold, style = if (size == 2) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge, maxLines = 1)
            if (size < 2) Text(q?.name ?: Catalog.nameOf(symbol) ?: "", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (showSpark && q != null && q.spark.size > 2) Sparkline(q.spark, Modifier.width(if (size == 2) 40.dp else 56.dp).height(if (size == 2) 18.dp else 26.dp).padding(horizontal = 4.dp), baseline = q.prevClose)
        if (size == 2) {
            FlashText(q?.price, fmtPrice(q?.price), MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(6.dp))
            if (q != null) ChangePill(q.changePct, Modifier.widthIn(min = 64.dp)) else Text("—")
        } else Column(horizontalAlignment = Alignment.End, modifier = Modifier.widthIn(min = 88.dp)) {
            FlashText(q?.price, fmtPrice(q?.price))
            if (q != null) ChangePill(q.changePct) else Text("—")
        }
        trailing?.invoke()
    }
}

/** Price text that flashes green/red when the value changes. */
@Composable
fun FlashText(value: Double?, text: String, style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyLarge, weight: FontWeight = FontWeight.SemiBold) {
    var last by remember { mutableStateOf(value) }
    var flash by remember { mutableStateOf(Color.Transparent) }
    LaunchedEffect(value) {
        val l = last
        if (value != null && l != null && value != l) {
            flash = if (value > l) Up.copy(alpha = 0.35f) else Down.copy(alpha = 0.35f)
            delay(600); flash = Color.Transparent
        }
        last = value
    }
    val bg by animateColorAsState(flash, tween(400), label = "flash")
    Text(text, style = style, fontWeight = weight, modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(bg).padding(horizontal = 2.dp))
}

@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier, sub: String? = null, subColor: Color? = null, onClick: (() -> Unit)? = null) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(if (LocalCardSize.current == 2) 6.dp else 10.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
        if (sub != null) Text(sub, style = MaterialTheme.typography.labelMedium, color = subColor ?: MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun QuoteTile(symbol: String, q: Quote?, modifier: Modifier = Modifier) {
    val nav = LocalNav.current
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .clickable { nav.symbol(symbol) }.padding(when (LocalCardSize.current) { 2 -> 5.dp; 1 -> 8.dp; else -> 10.dp }),
    ) {
        Text(Catalog.shortName(symbol), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        FlashText(q?.price, fmtPrice(q?.price), if (LocalCardSize.current == 2) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleMedium, FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(fmtPct(q?.changePct), color = changeColor(q?.changePct), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            if (q?.proxyNote != null) Text(" ·${q.proxyNote}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        if (q != null && q.spark.size > 2 && LocalCardSize.current < 2) Sparkline(q.spark, Modifier.fillMaxWidth().height(if (LocalCardSize.current == 1) 16.dp else 22.dp).padding(top = 4.dp), baseline = q.prevClose)
    }
}

/** Grid of quote tiles, [columns] per row. */
@Composable
fun QuoteTileGrid(symbols: List<String>, quotes: Map<String, Quote>, columns: Int = 3) {
    val size = LocalCardSize.current
    val gap = if (size == 2) 5.dp else 8.dp
    // Fit as many tiles per row as the width allows (more when cards are smaller).
    BoxWithConstraints {
        val minTile = when (size) { 2 -> 74.dp; 1 -> 88.dp; else -> 104.dp }
        val fit = ((maxWidth + gap) / (minTile + gap)).toInt().coerceIn(2, 6)
        val cols = (if (size == 0) minOf(columns, fit).coerceAtLeast(minOf(columns, 2)) else fit).coerceAtMost(maxOf(symbols.size, 1))
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            symbols.chunked(cols).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    row.forEach { s -> QuoteTile(s, quotes[s], Modifier.weight(1f)) }
                    repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
fun HeatTile(label: String, pct: Double?, modifier: Modifier = Modifier, sub: String? = null, onClick: () -> Unit = {}) {
    val p = pct ?: 0.0
    val strength = (kotlin.math.abs(p) / 3.0).coerceIn(0.15, 1.0).toFloat()
    val base = if (p >= 0) Up else Down
    Column(
        modifier.clip(RoundedCornerShape(10.dp)).background(base.copy(alpha = 0.18f + 0.62f * strength)).clickable(onClick = onClick).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        Text(fmtPct(pct), color = Color.White, style = MaterialTheme.typography.labelMedium)
        if (sub != null) Text(sub, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

@Composable
fun FearGreedGauge(value: Int, label: String, modifier: Modifier = Modifier) {
    val color = when {
        value < 25 -> Down; value < 45 -> Color(0xFFF97316); value < 55 -> Gold; value < 75 -> Color(0xFF84CC16); else -> Up
    }
    Box(modifier, contentAlignment = Alignment.BottomCenter) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 12.dp.toPx()
            val d = minOf(size.width, size.height * 2) - stroke
            val topLeft = Offset((size.width - d) / 2, stroke / 2)
            val sz = Size(d, d)
            val segs = listOf(Down, Color(0xFFF97316), Gold, Color(0xFF84CC16), Up)
            segs.forEachIndexed { i, c -> drawArc(c.copy(alpha = 0.35f), 180f + i * 36f, 35f, false, topLeft, sz, style = Stroke(stroke)) }
            drawArc(color, 180f, 180f * value / 100f, false, topLeft, sz, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$value", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = color)
            Text(label, style = MaterialTheme.typography.labelMedium, color = color)
        }
    }
}

@Composable
fun KeyValueGrid(items: List<Pair<String, String>>, columns: Int = 2) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { (k, v) ->
                    Row(Modifier.weight(1f)) {
                        Text(k, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f), maxLines = 1)
                        Text(v, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

fun openUrl(ctx: android.content.Context, url: String) {
    if (url.isBlank()) return
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

@Composable
fun NewsRow(n: NewsItem, showImage: Boolean = true) {
    val ctx = LocalContext.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { openUrl(ctx, n.url) }.padding(vertical = 8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(n.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${n.source} · ${fmtAgo(n.time)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                n.sentiment?.let {
                    val (lbl, c) = when { it > 0.15 -> "Bullish" to Up; it < -0.15 -> "Bearish" to Down; else -> "Neutral" to Gold }
                    Text("  $lbl", style = MaterialTheme.typography.labelSmall, color = c, fontWeight = FontWeight.Bold)
                }
            }
        }
        if (showImage && !n.image.isNullOrBlank()) {
            AsyncImage(n.image, null, Modifier.padding(start = 8.dp).size(64.dp).clip(RoundedCornerShape(8.dp)), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
        }
    }
}

@Composable
fun Dot(color: Color, size: androidx.compose.ui.unit.Dp = 8.dp) = Box(Modifier.size(size).clip(CircleShape).background(color))

/** Dialog to search and pick a symbol (stocks, ETFs, indices, crypto). */
@Composable
fun SymbolPicker(title: String = "Add symbol", onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var q by remember { mutableStateOf("") }
    val results = rememberLoad(q) { delay(300); if (q.length < 1) emptyList() else Market.search(q) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Symbol or name, e.g. AAPL, bitcoin") },
                    leadingIcon = { Icon(Icons.Default.Search, null) })
                Spacer(Modifier.height(8.dp))
                val list: List<SearchResult> = if (q.isBlank()) (Catalog.defaultWatchlist + Catalog.usIndices).map { SearchResult(it, Catalog.nameOf(it) ?: it, "") } else results.data.orEmpty()
                if (results.loading && q.isNotBlank()) LinearProgressIndicator(Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(list, key = { it.symbol }) { r ->
                        Row(Modifier.fillMaxWidth().clickable { onPick(r.symbol) }.padding(vertical = 10.dp)) {
                            Text(Catalog.display(r.symbol), fontWeight = FontWeight.Bold, modifier = Modifier.width(90.dp))
                            Text(r.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(r.kind, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (q.isNotBlank() && q.length <= 10) TextButton(onClick = { onPick(q.trim().uppercase()) }) { Text("Use \"${q.trim().uppercase()}\" as typed") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
fun Pill(text: String, color: Color = MaterialTheme.colorScheme.primary) {
    Text(text, color = color, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
        modifier = Modifier.border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
}
