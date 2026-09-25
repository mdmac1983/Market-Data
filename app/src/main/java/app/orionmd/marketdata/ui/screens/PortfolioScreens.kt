package app.orionmd.marketdata.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.orionmd.marketdata.data.*
import app.orionmd.marketdata.ui.*
import app.orionmd.marketdata.ui.components.*
import kotlinx.coroutines.launch

// ============================ Shared bits ============================

/** Tabs for choosing a portfolio (plus optional "All" and "+ New"). */
@Composable
private fun PortfolioTabs(selected: String, includeAll: Boolean, onSelect: (String) -> Unit, onNew: (() -> Unit)?) {
    val d by Store.data.collectAsState()
    val ids = d.portfolios.map { it.id } + if (includeAll && d.portfolios.size > 1) listOf(ALL_PORTFOLIOS) else emptyList()
    ScrollableTabRow(
        ids.indexOf(selected).coerceAtLeast(0), edgePadding = 12.dp,
        containerColor = Color.Transparent, contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        ids.forEach { id ->
            val n = d.txnsOf(id).let { t -> PortfolioCalc.holdings(t).count { it.qty > 1e-9 } }
            Tab(id == selected, { onSelect(id) }, text = { Text("${d.portfolioName(id)} ($n)", maxLines = 1) })
        }
        if (onNew != null) Tab(false, onNew, text = { Text("+ New") })
    }
}

@Composable
fun NewPortfolioDialog(initial: String = "", title: String = "New portfolio", onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name, e.g. Retirement, Crypto, Brokerage") }) },
        confirmButton = { TextButton(onClick = { if (name.isNotBlank()) onSave(name.trim()) }, enabled = name.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

fun createPortfolio(name: String): String {
    val id = Store.newId()
    Store.update { it.copy(portfolios = it.portfolios + PortfolioDef(id, name)) }
    return id
}

// ============================ Portfolio screen ============================

@Composable
fun PortfolioScreen() {
    val nav = LocalNav.current
    val d by Store.data.collectAsState()
    var sel by rememberSaveable { mutableStateOf(d.portfolios.first().id) }
    if (sel != ALL_PORTFOLIOS && d.portfolios.none { it.id == sel }) sel = d.portfolios.first().id
    if (sel == ALL_PORTFOLIOS && d.portfolios.size < 2) sel = d.portfolios.first().id
    val all = sel == ALL_PORTFOLIOS
    val txns = d.txnsOf(sel)
    val holdings = PortfolioCalc.holdings(txns)
    val open = holdings.filter { it.qty > 1e-9 }
    val q = rememberLive(open.map { it.symbol })
    val s = PortfolioCalc.summary(txns, q)
    var txnDialog by remember { mutableStateOf<Txn?>(null) }
    var addTxn by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<String?>(null) } // new | rename | delete
    var menu by remember { mutableStateOf(false) }
    var tab by rememberSaveableString("Holdings")
    val ctx = LocalContext.current

    Column(Modifier.fillMaxSize()) {
        PortfolioTabs(sel, includeAll = true, onSelect = { sel = it }, onNew = { dialog = "new" })
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                SectionCard(d.portfolioName(sel), action = {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Portfolio options") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text(if (d.dashPortfolio == sel) "✓ Shown on dashboard" else "Show on dashboard") },
                                leadingIcon = { Icon(Icons.Default.Dashboard, null) }, onClick = {
                                    menu = false
                                    Store.update { dd ->
                                        val dash = if (DashboardCard.PORTFOLIO.name in dd.dashboard) dd.dashboard else listOf(dd.dashboard.first(), DashboardCard.PORTFOLIO.name) + dd.dashboard.drop(1)
                                        dd.copy(dashPortfolio = sel, dashboard = dash)
                                    }
                                    Toast.makeText(ctx, "${d.portfolioName(sel)} will show on the dashboard", Toast.LENGTH_SHORT).show()
                                })
                            if (!all) {
                                DropdownMenuItem(text = { Text("Crypto commission" + (d.portfolios.firstOrNull { it.id == sel }?.cryptoFeePct?.takeIf { it > 0 }?.let { " (${Importer.fmtNum(it)}%)" } ?: "")) },
                                    leadingIcon = { Icon(Icons.Default.Percent, null) }, onClick = { menu = false; dialog = "fee" })
                                DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { menu = false; dialog = "rename" })
                                if (d.portfolios.size > 1) DropdownMenuItem(text = { Text("Delete portfolio", color = Down) }, leadingIcon = { Icon(Icons.Default.Delete, null, tint = Down) },
                                    onClick = { menu = false; dialog = "delete" })
                            }
                            DropdownMenuItem(text = { Text("Portfolio PDF") }, leadingIcon = { Icon(Icons.Default.PictureAsPdf, null) }, onClick = { menu = false; nav.go("pdf/PORTFOLIO?arg=${android.net.Uri.encode(sel)}") })
                        }
                    }
                }) {
                    Text("Total value", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(fmtMoney(s.value), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                    Text("Today ${fmtSignedMoney(s.dayChange)} (${fmtPct(s.dayPct)})", color = changeColor(s.dayChange), fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatTile("Unrealized", fmtSignedMoney(s.unrealized), Modifier.weight(1f), fmtPct(s.totalReturnPct), changeColor(s.unrealized))
                        StatTile("Realized", fmtSignedMoney(s.realized), Modifier.weight(1f), null)
                        StatTile("Dividends", fmtMoney(s.dividends), Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { addTxn = true }, Modifier.weight(1f)) { Icon(Icons.Default.Add, null); Text(" Transaction") }
                        OutlinedButton(onClick = { nav.go("import?pf=${if (all) d.portfolios.first().id else sel}") }, Modifier.weight(1f)) {
                            Icon(Icons.Default.UploadFile, null); Text(" Import")
                        }
                    }
                }
            }
            if (open.isNotEmpty()) item {
                SectionCard("Allocation") {
                    val slices = open.map { Catalog.display(it.symbol) to (it.value(q[it.symbol]) ?: it.costBasis) }.sortedByDescending { it.second }
                    val top = slices.take(9) + if (slices.size > 9) listOf("Other" to slices.drop(9).sumOf { it.second }) else emptyList()
                    val tot = top.sumOf { it.second }.takeIf { it > 0 } ?: 1.0
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DonutChart(top, Modifier.size(130.dp))
                        Column(Modifier.padding(start = 16.dp)) {
                            top.forEachIndexed { i, (n, v) ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Dot(pieColor(i)); Text(" $n", Modifier.width(70.dp), style = MaterialTheme.typography.labelMedium)
                                    Text(fmtNum(v / tot * 100, 1) + "%", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                }
            }
            item { ChipRow(listOf("Holdings", "Transactions", "Dividends", "Closed"), tab, { tab = it }) }
            when (tab) {
                "Holdings" -> {
                    if (open.isEmpty()) item {
                        SectionCard {
                            Text("No holdings yet.", fontWeight = FontWeight.Bold)
                            Text("Add a transaction, or tap Import to bring in a CSV from your broker or paste a list. You can review and fix every row before it's saved.",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    items(open.sortedByDescending { it.value(q[it.symbol]) ?: it.costBasis }, key = { it.symbol }) { h ->
                        val x = q[h.symbol]
                        SectionCard(modifier = Modifier.clickable { nav.symbol(h.symbol) }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(Catalog.display(h.symbol), fontWeight = FontWeight.Bold)
                                    Text("${fmtQty(h.qty)} @ ${fmtPrice(h.avgCost)} · now ${fmtPrice(x?.price)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(fmtMoney(h.value(x)), fontWeight = FontWeight.SemiBold)
                                    Text("${fmtSignedMoney(h.unrealized(x))} (${fmtPct(h.unrealized(x)?.let { it / h.costBasis * 100 })})", color = changeColor(h.unrealized(x)), style = MaterialTheme.typography.labelMedium)
                                    Text("Today ${fmtSignedMoney(h.dayChange(x))}", color = changeColor(h.dayChange(x)), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
                "Transactions" -> {
                    if (txns.isEmpty()) item { Text("No transactions yet.", Modifier.padding(8.dp)) }
                    else item { Text("Tap a transaction to edit it.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(txns.sortedByDescending { it.date }, key = { it.id }) { t -> TxnRow(t, if (all) d.portfolioName(t.portfolio) else null) { txnDialog = t } }
                }
                "Dividends" -> {
                    item { Text("Total dividend income: ${fmtMoney(s.dividends)}", fontWeight = FontWeight.Bold, modifier = Modifier.padding(8.dp)) }
                    items(txns.filter { it.kind == TxnKind.DIVIDEND }.sortedByDescending { it.date }, key = { it.id }) { t -> TxnRow(t, null) { txnDialog = t } }
                }
                "Closed" -> items(holdings.filter { it.qty <= 1e-9 }, key = { it.symbol }) { h ->
                    Row(Modifier.fillMaxWidth().padding(8.dp)) {
                        Text(h.symbol, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text("Realized ${fmtSignedMoney(h.realized)}", color = changeColor(h.realized))
                    }
                }
            }
        }
    }
    if (addTxn) TxnDialog(null, if (all) d.portfolios.first().id else sel) { addTxn = false }
    txnDialog?.let { t -> TxnDialog(t, t.portfolio) { txnDialog = null } }
    when (dialog) {
        "new" -> NewPortfolioDialog(onDismiss = { dialog = null }) { name -> sel = createPortfolio(name); dialog = null }
        "fee" -> CryptoFeeDialog(d.portfolios.first { it.id == sel }, { dialog = null }) { pct ->
            Store.update { dd -> dd.copy(portfolios = dd.portfolios.map { if (it.id == sel) it.copy(cryptoFeePct = pct) else it }) }; dialog = null
        }
        "rename" -> NewPortfolioDialog(d.portfolioName(sel), "Rename portfolio", { dialog = null }) { name ->
            Store.update { dd -> dd.copy(portfolios = dd.portfolios.map { if (it.id == sel) it.copy(name = name) else it }) }; dialog = null
        }
        "delete" -> {
            val count = d.txnsOf(sel).size
            val other = d.portfolios.first { it.id != sel }
            AlertDialog(onDismissRequest = { dialog = null }, title = { Text("Delete ${d.portfolioName(sel)}?") },
                text = { Text(if (count == 0) "This portfolio is empty." else "It has $count transactions. Delete them too, or move them to ${other.name}?") },
                confirmButton = {
                    TextButton(onClick = {
                        val gone = sel
                        Store.update { dd -> dd.copy(portfolios = dd.portfolios.filter { it.id != gone }, txns = dd.txns.filter { it.portfolio != gone },
                            dashPortfolio = if (dd.dashPortfolio == gone) ALL_PORTFOLIOS else dd.dashPortfolio) }
                        sel = other.id; dialog = null
                    }) { Text(if (count == 0) "Delete" else "Delete all", color = Down) }
                },
                dismissButton = {
                    Row {
                        if (count > 0) TextButton(onClick = {
                            val gone = sel
                            Store.update { dd -> dd.copy(portfolios = dd.portfolios.filter { it.id != gone },
                                txns = dd.txns.map { if (it.portfolio == gone) it.copy(portfolio = other.id) else it },
                                dashPortfolio = if (dd.dashPortfolio == gone) other.id else dd.dashPortfolio) }
                            sel = other.id; dialog = null
                        }) { Text("Move to ${other.name}") }
                        TextButton(onClick = { dialog = null }) { Text("Cancel") }
                    }
                })
        }
    }
}

fun fmtQty(q: Double) = Importer.fmtNum(q)

@Composable
fun CryptoFeeDialog(p: PortfolioDef, onDismiss: () -> Unit, onSave: (Double) -> Unit) {
    var choice by remember { mutableStateOf(when (p.cryptoFeePct) { 0.0 -> "None"; ETRADE_CRYPTO_FEE_PCT -> "E*TRADE"; else -> "Custom" }) }
    var custom by remember { mutableStateOf(if (p.cryptoFeePct > 0) Importer.fmtNum(p.cryptoFeePct) else "") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Crypto commission · ${p.name}") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Added automatically to crypto buys and sells in this portfolio when no fee is entered.", style = MaterialTheme.typography.bodySmall)
            listOf("None" to "No commission", "E*TRADE" to "E*TRADE crypto (Zero Hash) — 0.50% per trade", "Custom" to "Custom percent").forEach { (k, label) ->
                Row(Modifier.fillMaxWidth().clickable { choice = k }, verticalAlignment = Alignment.CenterVertically) { RadioButton(choice == k, { choice = k }); Text(label) }
            }
            if (choice == "Custom") OutlinedTextField(custom, { custom = it }, label = { Text("Percent of trade value") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), suffix = { Text("%") })
        }
    }, confirmButton = {
        TextButton(onClick = {
            onSave(when (choice) { "None" -> 0.0; "E*TRADE" -> ETRADE_CRYPTO_FEE_PCT; else -> (Importer.num(custom) ?: 0.0).coerceIn(0.0, 10.0) })
        }) { Text("Save") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun TxnRow(t: Txn, portfolioName: String?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Pill(t.kind.name, when (t.kind) { TxnKind.BUY -> Up; TxnKind.SELL -> Down; TxnKind.DIVIDEND -> Gold })
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(Catalog.display(t.symbol), fontWeight = FontWeight.Bold)
            Text(listOfNotNull(PortfolioCalc.fmtDate(t.date), portfolioName, t.note.ifBlank { null }).joinToString(" · "), style = MaterialTheme.typography.labelSmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(if (t.kind == TxnKind.DIVIDEND) fmtMoney(if (t.qty > 0) t.qty * t.price else t.price) else "${fmtQty(t.qty)} @ ${fmtPrice(t.price)}")
        Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Add a new transaction, or edit/delete [existing]. */
@Composable
private fun TxnDialog(existing: Txn?, defaultPortfolio: String, onDismiss: () -> Unit) {
    val d by Store.data.collectAsState()
    var symbol by remember { mutableStateOf(existing?.symbol.orEmpty()) }
    var kind by remember { mutableStateOf(existing?.kind ?: TxnKind.BUY) }
    var qty by remember { mutableStateOf(existing?.qty?.takeIf { it > 0 }?.let { fmtQty(it) }.orEmpty()) }
    var price by remember { mutableStateOf(existing?.price?.let { fmtQty(it) }.orEmpty()) }
    var fees by remember { mutableStateOf(existing?.fees?.takeIf { it > 0 }?.let { fmtQty(it) }.orEmpty()) }
    var date by remember { mutableStateOf(existing?.date?.let { PortfolioCalc.fmtDate(it) } ?: java.time.LocalDate.now().toString()) }
    var note by remember { mutableStateOf(existing?.note.orEmpty()) }
    var pf by remember { mutableStateOf(defaultPortfolio.takeIf { id -> d.portfolios.any { it.id == id } } ?: d.portfolios.first().id) }
    var pick by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(symbol) { if (existing == null && symbol.isNotBlank() && price.isBlank()) Market.quote(symbol)?.let { price = fmtQty(it.price) } }
    val pfDef = d.portfolios.firstOrNull { it.id == pf }
    val autoFee = pfDef?.takeIf { it.cryptoFeePct > 0 && Importer.isCryptoSymbol(symbol) && kind != TxnKind.DIVIDEND }?.let { p0 ->
        val qn = Importer.num(qty); val pn = Importer.num(price)
        if (qn != null && pn != null) p0.cryptoFee(qn, pn) else null
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (existing == null) "Add transaction" else "Edit transaction") }, text = {
        Column(Modifier.verticalScrollSafe(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pick = true }, Modifier.fillMaxWidth()) { Text(if (symbol.isBlank()) "Choose symbol" else Catalog.display(symbol)) }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { TxnKind.entries.forEach { k -> FilterChip(kind == k, { kind = k }, { Text(k.name.lowercase().replaceFirstChar { it.uppercase() }) }) } }
            if (kind != TxnKind.DIVIDEND) OutlinedTextField(qty, { qty = it }, label = { Text("Quantity") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            OutlinedTextField(price, { price = it }, label = { Text(if (kind == TxnKind.DIVIDEND) "Total dividend amount" else "Price per share") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            if (kind != TxnKind.DIVIDEND) OutlinedTextField(fees, { fees = it }, label = { Text("Fees (optional)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                placeholder = autoFee?.let { { Text("auto ${fmtMoney(it)}") } },
                supportingText = autoFee?.let { { Text("${Importer.fmtNum(pfDef!!.cryptoFeePct)}% crypto commission = ${fmtMoney(it)} (used if left blank)") } })
            OutlinedTextField(date, { date = it }, label = { Text("Date (YYYY-MM-DD)") }, singleLine = true)
            OutlinedTextField(note, { note = it }, label = { Text("Note (optional)") }, singleLine = true)
            if (d.portfolios.size > 1) {
                Text("Portfolio", style = MaterialTheme.typography.labelLarge)
                ChipRow(d.portfolios.map { it.name }, d.portfolioName(pf), { n -> pf = d.portfolios.first { it.name == n }.id })
            }
            err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = {
        TextButton(onClick = {
            val p = Importer.num(price); val qn = if (kind == TxnKind.DIVIDEND) 0.0 else Importer.num(qty)
            val dt = PortfolioCalc.parseDate(date)
            err = when {
                symbol.isBlank() -> "Choose a symbol"
                qn == null || (kind != TxnKind.DIVIDEND && qn <= 0) -> "Enter a quantity"
                p == null || p <= 0 -> "Enter a price"
                dt == null -> "Date not recognized"
                else -> null
            }
            if (err == null) {
                val fee = Importer.num(fees)?.let { kotlin.math.abs(it) } ?: autoFee ?: 0.0
                val t = Txn(existing?.id ?: Store.newId(), symbol.uppercase(), kind, kotlin.math.abs(qn!!), kotlin.math.abs(p!!), fee, dt!!,
                    if (fees.isBlank() && autoFee != null && autoFee > 0) (note.ifBlank { "" } + " ${Importer.fmtNum(pfDef!!.cryptoFeePct)}% crypto commission").trim() else note, pf)
                Store.update { dd -> dd.copy(txns = if (existing == null) dd.txns + t else dd.txns.map { if (it.id == t.id) t else it }) }
                onDismiss()
            }
        }) { Text("Save") }
    }, dismissButton = {
        Row {
            if (existing != null) TextButton(onClick = { Store.update { it.copy(txns = it.txns.filter { x -> x.id != existing.id }) }; onDismiss() }) { Text("Delete", color = Down) }
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    })
    if (pick) SymbolPicker("Symbol", { pick = false }) { symbol = it; if (existing == null) price = ""; pick = false }
}

@Composable
private fun Modifier.verticalScrollSafe(): Modifier = this.verticalScroll(androidx.compose.foundation.rememberScrollState())

// ============================ Dashboard card ============================

@Composable
fun PortfolioMiniCard() {
    val nav = LocalNav.current
    val d by Store.data.collectAsState()
    val settings by Prefs.settings.collectAsState()
    var menu by remember { mutableStateOf(false) }
    val pid = d.dashPortfolio.takeIf { it == ALL_PORTFOLIOS || d.portfolios.any { p -> p.id == it } } ?: ALL_PORTFOLIOS
    val txns = d.txnsOf(pid)
    val open = PortfolioCalc.holdings(txns).filter { it.qty > 1e-9 }
    val q = rememberLive(open.map { it.symbol })
    val s = PortfolioCalc.summary(txns, q)
    val title = if (d.portfolios.size == 1 && pid == ALL_PORTFOLIOS) d.portfolios.first().name else d.portfolioName(pid)
    SectionCard(title, onTitleClick = { nav.go("portfolio") }, action = {
        if (d.portfolios.size > 1) Box {
            TextButton(onClick = { menu = true }) { Text("Change"); Icon(Icons.Default.ArrowDropDown, null) }
            DropdownMenu(menu, { menu = false }) {
                (listOf(ALL_PORTFOLIOS) + d.portfolios.map { it.id }).forEach { id ->
                    DropdownMenuItem(text = { Text((if (id == pid) "✓ " else "") + d.portfolioName(id)) }, onClick = {
                        menu = false; Store.update { it.copy(dashPortfolio = id) }
                    })
                }
            }
        }
    }) {
        if (txns.isEmpty()) { TextButton(onClick = { nav.go("portfolio") }) { Text("Add or import your holdings →") }; return@SectionCard }
        if (settings.lockPortfolio && !Session.unlocked) { TextButton(onClick = { nav.go("portfolio") }) { Text("🔒 Unlock to view") }; return@SectionCard }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("Value", fmtMoney(s.value), Modifier.weight(1f))
            StatTile("Today", fmtSignedMoney(s.dayChange), Modifier.weight(1f), fmtPct(s.dayPct), changeColor(s.dayChange))
            StatTile("Total gain", fmtSignedMoney(s.unrealized), Modifier.weight(1f), fmtPct(s.totalReturnPct), changeColor(s.unrealized))
        }
        Spacer(Modifier.height(6.dp))
        open.sortedByDescending { it.value(q[it.symbol]) ?: it.costBasis }.take(5).forEach { h ->
            val x = q[h.symbol]
            Row(Modifier.fillMaxWidth().clickable { nav.symbol(h.symbol) }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(Catalog.display(h.symbol), fontWeight = FontWeight.Bold, modifier = Modifier.width(80.dp))
                Text(fmtMoney(h.value(x)), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                ChangePill(x?.changePct)
            }
        }
        if (open.size > 5) TextButton(onClick = { nav.go("portfolio") }, contentPadding = PaddingValues(0.dp)) { Text("All ${open.size} holdings →") }
    }
}

// ============================ Import & review ============================

@Composable
fun ImportScreen(initialPortfolio: String) {
    val ctx = LocalContext.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val d by Store.data.collectAsState()
    var target by remember { mutableStateOf(initialPortfolio.takeIf { id -> d.portfolios.any { it.id == id } } ?: d.portfolios.first().id) }
    var parsed by remember { mutableStateOf<ParsedImport?>(null) }
    var mapping by remember { mutableStateOf<Map<ImportField, Int>>(emptyMap()) }
    val drafts = remember { mutableStateListOf<DraftTxn>() }
    var sourceName by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var pasteOpen by remember { mutableStateOf(false) }
    var newPf by remember { mutableStateOf(false) }
    var filter by rememberSaveableString("All")
    var filling by remember { mutableStateOf(false) }
    var feePct by remember { mutableStateOf("") }
    val targetDef = d.portfolios.firstOrNull { it.id == target }

    fun load(text: String, name: String) {
        message = null
        if (text.isBlank()) { message = "That file is empty."; return }
        val p = Importer.parse(text)
        parsed = p; mapping = p.mapping; sourceName = name
        drafts.clear(); drafts.addAll(Importer.drafts(p, p.mapping))
        // E*TRADE crypto trades (Zero Hash) carry a 0.50% commission that exports often leave out.
        val pct = d.portfolios.firstOrNull { it.id == target }?.cryptoFeePct?.takeIf { it > 0 }
            ?: ETRADE_CRYPTO_FEE_PCT.takeIf { p.broker?.startsWith("E*TRADE") == true }
        if (pct != null && drafts.any { Importer.isCryptoSymbol(it.symbol) }) {
            feePct = Importer.fmtNum(pct)
            val n = Importer.applyCryptoFee(drafts, pct)
            if (n > 0) Toast.makeText(ctx, "Added ${Importer.fmtNum(pct)}% crypto commission to $n rows", Toast.LENGTH_LONG).show()
        }
        message = when {
            drafts.isEmpty() -> "No rows found. Check the file, or paste the rows as text."
            ImportField.SYMBOL !in p.mapping -> "Couldn't find a symbol column. Pick it under Columns below."
            else -> null
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            val bytes = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            if (bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
                message = "This looks like an Excel .xlsx file. In Excel or Google Sheets choose File → Save as / Download → CSV, then import that."
                return@rememberLauncherForActivityResult
            }
            val text = if (bytes.size > 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) String(bytes, Charsets.UTF_16LE)
            else String(bytes, Charsets.UTF_8)
            val name = ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME); if (c.moveToFirst() && i >= 0) c.getString(i) else null
            } ?: "file"
            load(text, name)
        }.onFailure { message = "Couldn't read that file: ${it.message}" }
    }
    val templateSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching { ctx.contentResolver.openOutputStream(uri)!!.use { it.write(Importer.TEMPLATE.toByteArray()) } }
            .onSuccess { Toast.makeText(ctx, "Template saved", Toast.LENGTH_SHORT).show() }
    }

    val ready = drafts.count { it.include && it.problems().isEmpty() }
    val attention = drafts.count { it.include && it.problems().isNotEmpty() }
    val excluded = drafts.count { !it.include }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp, 12.dp, 12.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                SectionCard("1 · Add transactions") {
                    Text("Import many at once from a CSV file, paste a list, or type rows in. Nothing is saved until you tap Import.",
                        style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { picker.launch(arrayOf("text/*", "text/csv", "text/comma-separated-values", "application/csv", "application/vnd.ms-excel", "application/octet-stream", "*/*")) },
                            Modifier.weight(1f)) { Icon(Icons.Default.FileOpen, null); Text(" CSV file") }
                        OutlinedButton(onClick = { pasteOpen = true }, Modifier.weight(1f)) { Icon(Icons.Default.ContentPaste, null); Text(" Paste list") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { drafts.add(0, Importer.blankDraft()); filter = "All" }, Modifier.weight(1f)) { Icon(Icons.Default.Add, null); Text(" Add row") }
                        OutlinedButton(onClick = { templateSaver.launch("portfolio_template.csv") }, Modifier.weight(1f)) { Icon(Icons.Default.Download, null); Text(" Template") }
                    }
                    Text("Works with broker exports (E*TRADE transactions & portfolio downloads, Fidelity, Schwab, Vanguard, Robinhood…) and simple lists like:\nAAPL, 10, 185.50, 2026-01-15\nBUY MSFT 5 @ 410.25",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                }
            }
            item {
                SectionCard("2 · Import into") {
                    ChipRow(d.portfolios.map { it.name } + "+ New portfolio", d.portfolioName(target), { n ->
                        if (n == "+ New portfolio") newPf = true else target = d.portfolios.first { it.name == n }.id
                    })
                }
            }
            message?.let { m -> item { SectionCard { Text(m, color = MaterialTheme.colorScheme.error) } } }
            parsed?.let { p ->
                item {
                    SectionCard("3 · Columns") {
                        p.broker?.let { Text("Recognized: $it export", fontWeight = FontWeight.SemiBold, color = Up) }
                        Text("$sourceName · ${p.rows.size} rows · separated by ${p.delimiter}" + (if (p.hasHeader) "" else " · no header row") +
                            (if (p.skippedLines > 0) " · skipped ${p.skippedLines} title lines" else ""),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Check each field points at the right column. Changing a column re-reads the rows (your edits below are reset).",
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp))
                        ImportField.entries.forEach { f ->
                            ColumnPicker(f, p, mapping[f]) { idx ->
                                mapping = if (idx == null) mapping - f else mapping + (f to idx)
                                drafts.clear(); drafts.addAll(Importer.drafts(p, mapping))
                            }
                        }
                    }
                }
            }
            if (drafts.any { Importer.isCryptoSymbol(it.symbol) }) item {
                SectionCard("Crypto commission") {
                    Text("E*TRADE crypto trades (Zero Hash) are charged 0.50% per trade. This fills the fee on crypto rows that don't have one.",
                        style = MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(feePct, { feePct = it }, Modifier.width(110.dp), singleLine = true, label = { Text("Percent") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), suffix = { Text("%") })
                        AssistChip(onClick = { feePct = "0.5" }, label = { Text("E*TRADE 0.50%") })
                        Button(onClick = {
                            val pct = Importer.num(feePct) ?: return@Button
                            val n = Importer.applyCryptoFee(drafts, pct)
                            Toast.makeText(ctx, "Added ${Importer.fmtNum(pct)}% to $n crypto rows", Toast.LENGTH_SHORT).show()
                        }, enabled = (Importer.num(feePct) ?: 0.0) > 0) { Text("Apply") }
                    }
                    if (targetDef != null && (Importer.num(feePct) ?: 0.0) > 0 && targetDef.cryptoFeePct != Importer.num(feePct))
                        TextButton(onClick = {
                            val pct = Importer.num(feePct)!!
                            Store.update { dd -> dd.copy(portfolios = dd.portfolios.map { if (it.id == target) it.copy(cryptoFeePct = pct) else it }) }
                        }, contentPadding = PaddingValues(0.dp)) { Text("Also use ${feePct}% for future crypto trades in ${targetDef.name}") }
                }
            }
            if (drafts.isNotEmpty()) {
                item {
                    SectionCard("4 · Review") {
                        Text("$ready ready · $attention need attention · $excluded excluded", fontWeight = FontWeight.SemiBold)
                        ChipRow(listOf("All", "Needs attention", "Excluded"), filter, { filter = it })
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AssistChip(enabled = !filling, onClick = {
                                filling = true
                                scope.launch {
                                    val need = drafts.filter { it.kind != TxnKind.DIVIDEND && Importer.num(it.price) == null && Importer.looksLikeSymbol(it.symbol) }.map { it.symbol.uppercase() }.distinct()
                                    val qs = runCatching { Market.quotes(need) }.getOrDefault(emptyMap())
                                    for (i in drafts.indices) {
                                        val dr = drafts[i]
                                        if (dr.kind != TxnKind.DIVIDEND && Importer.num(dr.price) == null) qs[dr.symbol.uppercase()]?.let { drafts[i] = dr.copy(price = Importer.fmtNum(it.price)) }
                                    }
                                    filling = false
                                    Toast.makeText(ctx, "Filled ${qs.size} prices with the latest quote", Toast.LENGTH_SHORT).show()
                                }
                            }, label = { Text(if (filling) "Getting prices…" else "Fill missing prices") }, leadingIcon = { Icon(Icons.Default.AutoFixHigh, null) })
                            AssistChip(onClick = { for (i in drafts.indices) if (drafts[i].problems().isNotEmpty()) drafts[i] = drafts[i].copy(include = false) },
                                label = { Text("Exclude rows with problems") })
                            AssistChip(onClick = { for (i in drafts.indices) if (drafts[i].problems().isEmpty()) drafts[i] = drafts[i].copy(include = true) },
                                label = { Text("Include all ready rows") })
                        }
                    }
                }
                val shown = drafts.filter {
                    when (filter) { "Needs attention" -> it.include && it.problems().isNotEmpty(); "Excluded" -> !it.include; else -> true }
                }
                items(shown, key = { it.id }) { dr ->
                    DraftCard(dr, onChange = { nd -> val i = drafts.indexOfFirst { it.id == dr.id }; if (i >= 0) drafts[i] = nd },
                        onDelete = { drafts.removeAll { it.id == dr.id } })
                }
            }
        }
        Button(
            onClick = {
                val txns = drafts.filter { it.include }.mapNotNull { it.toTxn(target) }
                if (txns.isEmpty()) return@Button
                Store.update { it.copy(txns = it.txns + txns) }
                Toast.makeText(ctx, "Imported ${txns.size} transactions into ${d.portfolioName(target)}", Toast.LENGTH_LONG).show()
                nav.back()
            },
            enabled = ready > 0,
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp).height(52.dp),
        ) { Icon(Icons.Default.Check, null); Text(" Import $ready transaction" + if (ready == 1) "" else "s") }
    }

    if (pasteOpen) {
        var text by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { pasteOpen = false }, title = { Text("Paste transactions") }, text = {
            Column {
                Text("One transaction per line. A header row is optional.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().heightIn(min = 180.dp).padding(top = 8.dp),
                    placeholder = { Text("symbol,action,quantity,price,date\nAAPL,buy,10,185.50,2026-01-15\nMSFT 5 410.25\nBUY NVDA 3 @ 120", style = MaterialTheme.typography.bodySmall) },
                    textStyle = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = { TextButton(onClick = { load(text, "Pasted text"); pasteOpen = false }, enabled = text.isNotBlank()) { Text("Read rows") } },
            dismissButton = { TextButton(onClick = { pasteOpen = false }) { Text("Cancel") } })
    }
    if (newPf) NewPortfolioDialog(onDismiss = { newPf = false }) { name -> target = createPortfolio(name); newPf = false }
}

@Composable
private fun ColumnPicker(field: ImportField, p: ParsedImport, current: Int?, onPick: (Int?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val sample = { i: Int -> p.rows.firstOrNull { it.getOrNull(i)?.isNotBlank() == true }?.getOrNull(i)?.take(18).orEmpty() }
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(field.label, Modifier.width(110.dp), style = MaterialTheme.typography.bodyMedium)
        Box(Modifier.weight(1f)) {
            OutlinedButton(onClick = { open = true }, Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                Text(current?.let { "${p.headers.getOrElse(it) { "Column ${it + 1}" }}  (${sample(it)})" } ?: "— not in file —",
                    Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                Icon(Icons.Default.ArrowDropDown, null)
            }
            DropdownMenu(open, { open = false }, Modifier.heightIn(max = 400.dp)) {
                DropdownMenuItem(text = { Text("— not in file —") }, onClick = { onPick(null); open = false })
                p.headers.forEachIndexed { i, h ->
                    DropdownMenuItem(text = { Text("$h  ·  e.g. ${sample(i)}", maxLines = 1) }, onClick = { onPick(i); open = false })
                }
            }
        }
    }
}

@Composable
private fun DraftCard(dr: DraftTxn, onChange: (DraftTxn) -> Unit, onDelete: () -> Unit) {
    val problems = dr.problems()
    val border = when { !dr.include -> MaterialTheme.colorScheme.outlineVariant; problems.isNotEmpty() -> Gold; else -> Up.copy(alpha = 0.6f) }
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = if (dr.include) 0.9f else 0.5f)),
        border = BorderStroke(1.dp, border),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(dr.include, { onChange(dr.copy(include = it)) })
                Text(if (dr.include) (if (problems.isEmpty()) "Ready" else "Needs attention") else "Excluded",
                    fontWeight = FontWeight.Bold, color = if (!dr.include) MaterialTheme.colorScheme.onSurfaceVariant else if (problems.isEmpty()) Up else Gold,
                    modifier = Modifier.weight(1f))
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Remove row") }
            }
            val small = TextStyle.Default.merge(MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(dr.symbol, { onChange(dr.copy(symbol = it.uppercase().trim())) }, Modifier.weight(1f), label = { Text("Symbol") }, singleLine = true, textStyle = small)
                OutlinedTextField(dr.date, { onChange(dr.copy(date = it)) }, Modifier.weight(1.2f), label = { Text("Date") }, singleLine = true, textStyle = small)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TxnKind.entries.forEach { k ->
                    FilterChip(dr.kind == k, { onChange(dr.copy(kind = k)) }, { Text(k.name.lowercase().replaceFirstChar { it.uppercase() }) })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (dr.kind != TxnKind.DIVIDEND) OutlinedTextField(dr.qty, { onChange(dr.copy(qty = it)) }, Modifier.weight(1f), label = { Text("Qty") }, singleLine = true, textStyle = small,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(dr.price, { onChange(dr.copy(price = it)) }, Modifier.weight(1f), label = { Text(if (dr.kind == TxnKind.DIVIDEND) "Amount" else "Price") }, singleLine = true, textStyle = small,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                if (dr.kind != TxnKind.DIVIDEND) OutlinedTextField(dr.fees, { onChange(dr.copy(fees = it)) }, Modifier.weight(0.8f), label = { Text("Fees") }, singleLine = true, textStyle = small,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            }
            if (dr.include) problems.forEach { Text("• $it", color = Gold, style = MaterialTheme.typography.labelMedium) }
            if (dr.note.isNotBlank()) Text(dr.note, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (dr.source.isNotBlank()) Text(dr.source, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
