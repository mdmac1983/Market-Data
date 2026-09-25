package app.orionmd.marketdata.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.orionmd.marketdata.data.Glossary
import app.orionmd.marketdata.data.OverSignal
import app.orionmd.marketdata.data.SignalState
import app.orionmd.marketdata.data.Term
import app.orionmd.marketdata.ui.*

/** Opens the help pop-up for one or more glossary terms. Provided by the app root. */
val LocalHelp = staticCompositionLocalOf<(List<String>) -> Unit> { {} }

/** Small ⓘ that explains [label] (or [terms]) when tapped. Shows nothing if there's no glossary entry. */
@Composable
fun InfoIcon(label: String, size: Dp = 16.dp, terms: List<String> = listOf(label)) {
    if (terms.none { Glossary.find(it) != null }) return
    val help = LocalHelp.current
    Icon(Icons.Outlined.Info, "What is $label?",
        Modifier.padding(start = 4.dp).size(size + 8.dp).clip(CircleShape).clickable { help(terms) }.padding(4.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f))
}

@Composable
fun HelpDialog(labels: List<String>, onDismiss: () -> Unit) {
    val nav = LocalNav.current
    val entries: List<Term> = labels.mapNotNull { Glossary.find(it) }.distinct()
    if (entries.isEmpty()) { onDismiss(); return }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Info, null) },
        title = { Text(if (entries.size == 1) entries[0].title else "What these mean") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                entries.forEach { e ->
                    if (entries.size > 1) Text(e.title, fontWeight = FontWeight.Bold)
                    Text(e.text, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Got it") } },
        dismissButton = { TextButton(onClick = { onDismiss(); nav.go("glossary") }) { Text("All terms") } },
    )
}


// ---------------- Overbought / oversold UI ----------------

fun signalColor(s: SignalState): Color = when (s) {
    SignalState.STRONG_OVERBOUGHT -> Color(0xFFEF4444)
    SignalState.OVERBOUGHT -> Color(0xFFF97316)
    SignalState.NEUTRAL -> Color(0xFF94A3B8)
    SignalState.OVERSOLD -> Color(0xFF22C55E)
    SignalState.STRONG_OVERSOLD -> Color(0xFF10B981)
}

/** Compact pill, shown only for overbought / oversold readings (unless [showNeutral]). */
@Composable
fun SignalBadge(sig: OverSignal?, showNeutral: Boolean = false, modifier: Modifier = Modifier) {
    if (sig == null || (sig.state == SignalState.NEUTRAL && !showNeutral)) return
    val c = signalColor(sig.state)
    Text(
        (if (sig.state.isOverbought) "▲ " else if (sig.state.isOversold) "▼ " else "") + sig.state.label.uppercase(),
        modifier.clip(RoundedCornerShape(5.dp)).background(c.copy(alpha = 0.18f)).padding(horizontal = 5.dp, vertical = 1.dp),
        color = c, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1,
    )
}

/** 0–100 bar with the 30/70 zones shaded and a marker at [value]. */
@Composable
fun RsiBar(value: Double, modifier: Modifier = Modifier) {
    Box(modifier.height(14.dp).clip(RoundedCornerShape(7.dp))) {
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(30f).fillMaxHeight().background(Color(0xFF22C55E).copy(alpha = 0.35f)))
            Box(Modifier.weight(40f).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceVariant))
            Box(Modifier.weight(30f).fillMaxHeight().background(Color(0xFFEF4444).copy(alpha = 0.35f)))
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val x = maxWidth * (value.coerceIn(0.0, 100.0) / 100.0).toFloat()
            Box(Modifier.offset(x = (x - 3.dp).coerceAtLeast(0.dp)).width(6.dp).fillMaxHeight().background(MaterialTheme.colorScheme.onBackground))
        }
    }
}

@Composable
fun SignalDetail(sig: OverSignal) {
    val c = signalColor(sig.state)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(sig.state.label, color = c, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        Text("${sig.timeframe} · RSI ${"%.0f".format(sig.rsi)}", style = MaterialTheme.typography.labelLarge)
    }
    RsiBar(sig.rsi, Modifier.fillMaxWidth().padding(vertical = 6.dp))
    Row(Modifier.fillMaxWidth()) {
        Text("Oversold ≤30", style = MaterialTheme.typography.labelSmall, color = Up, modifier = Modifier.weight(1f))
        Text("Overbought ≥70", style = MaterialTheme.typography.labelSmall, color = Down)
    }
    Spacer(Modifier.height(6.dp))
    KeyValueGrid(listOfNotNull(
        "RSI (14)" to "%.1f".format(sig.rsi),
        sig.stochK?.let { "Stochastic" to "%.0f".format(it) },
        sig.pctB?.let { "%B" to "%.2f".format(it) },
        sig.vsSma50?.let { "vs 50-day average" to fmtPct(it) },
    ))
    Text(when {
        sig.state.isOverbought -> "Price has risen quickly; it may be stretched and due for a pause or pullback. Strong uptrends can stay overbought."
        sig.state.isOversold -> "Price has fallen quickly; it may be stretched and due for a bounce. Downtrends can stay oversold."
        else -> "No stretched reading right now."
    } + "\n" + sig.reason(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
}
