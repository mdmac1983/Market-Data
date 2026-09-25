package app.orionmd.marketdata.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import app.orionmd.marketdata.data.*
import app.orionmd.marketdata.ui.*
import app.orionmd.marketdata.ui.components.*
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

object Session { var unlocked by mutableStateOf(false) }

// ============================ Lock ============================

@Composable
fun LockGate(content: @Composable () -> Unit) {
    val s by Prefs.settings.collectAsState()
    if (!s.lockPortfolio || Session.unlocked) { content(); return }
    val ctx = LocalContext.current
    val activity = ctx as? FragmentActivity
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val canBio = remember {
        BiometricManager.from(ctx).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS
    }
    fun bio() {
        if (activity == null) return
        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(ctx), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { Session.unlocked = true }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { error = errString.toString() }
        })
        prompt.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle("Unlock portfolio").setSubtitle("Market_Data")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL).build())
    }
    LaunchedEffect(Unit) { if (s.useBiometric && canBio) bio() }
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Default.Lock, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Text("Portfolio locked", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(12.dp))
        if (s.pinHash.isNotBlank()) {
            OutlinedTextField(pin, { if (it.length <= 8) pin = it.filter { c -> c.isDigit() } }, label = { Text("PIN") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
            Button(onClick = { if (Prefs.hashPin(pin) == s.pinHash) Session.unlocked = true else { error = "Wrong PIN"; pin = "" } }, Modifier.padding(8.dp)) { Text("Unlock") }
        }
        if (canBio && s.useBiometric) OutlinedButton(onClick = { bio() }) { Icon(Icons.Default.Fingerprint, null); Spacer(Modifier.width(8.dp)); Text("Use fingerprint / screen lock") }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(8.dp)) }
    }
}

// ============================ Watchlists ============================

@Composable
fun WatchlistsScreen() {
    val nav = LocalNav.current
    val d by Store.data.collectAsState()
    var sel by rememberSaveableString(d.watchlists.firstOrNull()?.id ?: "")
    val list = d.watchlists.firstOrNull { it.id == sel } ?: d.watchlists.firstOrNull()
    var editing by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<String?>(null) } // "new" | "rename"
    var sort by rememberSaveableString("Custom")
    var menu by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        if (d.watchlists.isNotEmpty()) ScrollableTabRow(d.watchlists.indexOfFirst { it.id == list?.id }.coerceAtLeast(0), edgePadding = 12.dp, containerColor = androidx.compose.ui.graphics.Color.Transparent) {
            d.watchlists.forEach { w -> Tab(w.id == list?.id, { sel = w.id; editing = false }, text = { Text("${w.name} (${w.symbols.size})") }) }
            Tab(false, { dialog = "new" }, text = { Text("+ New") })
        }
        if (list == null) { Button(onClick = { dialog = "new" }, Modifier.padding(16.dp)) { Text("Create a watchlist") }; return@Column }
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!editing) ChipRow(listOf("Custom", "Symbol", "% Change", "Price"), sort, { sort = it }, Modifier.weight(1f)) else Spacer(Modifier.weight(1f))
            IconButton(onClick = { adding = true }) { Icon(Icons.Default.Add, "Add symbol") }
            IconButton(onClick = { editing = !editing }) { Icon(if (editing) Icons.Default.Check else Icons.Default.Edit, "Edit") }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; dialog = "rename" })
                    DropdownMenuItem(text = { Text("Compare all (first 4)") }, onClick = { menu = false; nav.go("compare?syms=${android.net.Uri.encode(list.symbols.take(4).joinToString(","))}") })
                    DropdownMenuItem(text = { Text("Watchlist PDF") }, onClick = { menu = false; nav.go("pdf/WATCHLIST?arg=") })
                    DropdownMenuItem(text = { Text("Move to top") }, onClick = { menu = false; Store.update { it.copy(watchlists = listOf(list) + it.watchlists.filter { w -> w.id != list.id }) } })
                    if (d.watchlists.size > 1) DropdownMenuItem(text = { Text("Delete list", color = Down) }, onClick = {
                        menu = false; Store.update { it.copy(watchlists = it.watchlists.filter { w -> w.id != list.id }) }; sel = ""
                    })
                }
            }
        }
        val q = rememberLive(list.symbols)
        if (editing) {
            var order by remember(list.id, list.symbols) { mutableStateOf(list.symbols) }
            val state = rememberLazyListState()
            val reorder = rememberReorderableLazyListState(state) { from, to ->
                val f = order.indexOf(from.key as String); val t = order.indexOf(to.key as String)
                if (f >= 0 && t >= 0) {
                    order = order.toMutableList().apply { add(t, removeAt(f)) }
                    Store.update { dd -> dd.copy(watchlists = dd.watchlists.map { if (it.id == list.id) it.copy(symbols = order) else it }) }
                }
            }
            LazyColumn(state = state, contentPadding = PaddingValues(12.dp)) {
                items(order, key = { it }) { s ->
                    ReorderableItem(reorder, key = s) { dragging ->
                        Surface(tonalElevation = if (dragging) 6.dp else 0.dp) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = {}, Modifier.draggableHandle()) { Icon(Icons.Default.DragHandle, "Drag") }
                                Text(Catalog.display(s), Modifier.weight(1f), fontWeight = FontWeight.Bold)
                                IconButton(onClick = { Store.removeFromWatchlist(list.id, s) }) { Icon(Icons.Default.Delete, "Remove", tint = Down) }
                            }
                        }
                    }
                }
            }
        } else {
            val syms = when (sort) {
                "Symbol" -> list.symbols.sorted(); "% Change" -> list.symbols.sortedByDescending { q[it]?.changePct ?: -1e9 }
                "Price" -> list.symbols.sortedByDescending { q[it]?.price ?: 0.0 }; else -> list.symbols
            }
            LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                if (syms.isEmpty()) item { Text("Tap + to add stocks, ETFs, indices or crypto.", Modifier.padding(16.dp)) }
                items(syms, key = { it }) { s ->
                    QuoteRow(s, q[s], { nav.symbol(s) })
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                }
            }
        }
    }
    if (adding && list != null) SymbolPicker("Add to ${list.name}", { adding = false }) { s -> Store.addToWatchlist(list.id, s); adding = false }
    dialog?.let { mode ->
        var name by remember { mutableStateOf(if (mode == "rename") list?.name.orEmpty() else "") }
        AlertDialog(onDismissRequest = { dialog = null }, title = { Text(if (mode == "new") "New watchlist" else "Rename watchlist") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }) },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isNotBlank()) {
                        if (mode == "new") { val id = Store.newId(); Store.update { it.copy(watchlists = it.watchlists + Watchlist(id, name.trim(), emptyList())) }; sel = id }
                        else Store.update { it.copy(watchlists = it.watchlists.map { w -> if (w.id == list?.id) w.copy(name = name.trim()) else w }) }
                    }
                    dialog = null
                }) { Text("Save") }
            }, dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } })
    }
}

// ============================ Portfolio ============================

@Composable
fun PortfolioScreen() {
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val d by Store.data.collectAsState()
    val holdings = PortfolioCalc.holdings(d.txns)
    val open = holdings.filter { it.qty > 1e-9 }
    val q = rememberLive(open.map { it.symbol })
    val s = PortfolioCalc.summary(d.txns, q)
    var addTxn by remember { mutableStateOf(false) }
    var tab by rememberSaveableString("Holdings")
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val text = runCatching { ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.readText() }.getOrNull().orEmpty()
        val (txns, errors) = PortfolioCalc.importCsv(text)
        if (txns.isNotEmpty()) Store.update { it.copy(txns = it.txns + txns) }
        Toast.makeText(ctx, "Imported ${txns.size} transactions" + if (errors.isNotEmpty()) ", ${errors.size} rows skipped" else "", Toast.LENGTH_LONG).show()
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SectionCard {
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
                    OutlinedButton(onClick = { importer.launch(arrayOf("text/*", "text/csv", "application/vnd.ms-excel", "*/*")) }, Modifier.weight(1f)) { Icon(Icons.Default.UploadFile, null); Text(" Import CSV") }
                }
            }
        }
        if (open.isNotEmpty()) item {
            SectionCard("Allocation") {
                val slices = open.map { Catalog.display(it.symbol) to (it.value(q[it.symbol]) ?: it.costBasis) }.sortedByDescending { it.second }
                val top = slices.take(9) + if (slices.size > 9) listOf("Other" to slices.drop(9).sumOf { it.second }) else emptyList()
                val tot = top.sumOf { it.second }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DonutChart(top, Modifier.size(130.dp))
                    Column(Modifier.padding(start = 16.dp)) {
                        top.forEachIndexed { i, (n, v) ->
                            Row(verticalAlignment = Alignment.CenterVertically) { Dot(pieColor(i)); Text(" $n", Modifier.width(70.dp), style = MaterialTheme.typography.labelMedium); Text(fmtNum(v / tot * 100, 1) + "%", style = MaterialTheme.typography.labelMedium) }
                        }
                    }
                }
            }
        }
        item { ChipRow(listOf("Holdings", "Transactions", "Dividends", "Closed"), tab, { tab = it }) }
        when (tab) {
            "Holdings" -> {
                if (open.isEmpty()) item { Text("No holdings yet. Add a buy transaction or import a CSV from your broker (columns: symbol, action, quantity, price, date, fees).", Modifier.padding(8.dp)) }
                items(open.sortedByDescending { it.value(q[it.symbol]) ?: it.costBasis }, key = { it.symbol }) { h ->
                    val x = q[h.symbol]
                    SectionCard(modifier = Modifier.clickable { nav.symbol(h.symbol) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(Catalog.display(h.symbol), fontWeight = FontWeight.Bold)
                                Text("${fmtNum(h.qty, 4).trimEnd('0').trimEnd('.')} @ ${fmtPrice(h.avgCost)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            "Transactions" -> items(d.txns.sortedByDescending { it.date }, key = { it.id }) { t -> TxnRow(t) }
            "Dividends" -> {
                val divs = d.txns.filter { it.kind == TxnKind.DIVIDEND }
                item { Text("Total dividend income: ${fmtMoney(s.dividends)}", fontWeight = FontWeight.Bold, modifier = Modifier.padding(8.dp)) }
                items(divs.sortedByDescending { it.date }, key = { it.id }) { t -> TxnRow(t) }
            }
            "Closed" -> items(holdings.filter { it.qty <= 1e-9 }, key = { it.symbol }) { h ->
                Row(Modifier.fillMaxWidth().padding(8.dp)) {
                    Text(h.symbol, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text("Realized ${fmtSignedMoney(h.realized)}", color = changeColor(h.realized))
                }
            }
        }
    }
    if (addTxn) TxnDialog { addTxn = false }
}

@Composable
private fun TxnRow(t: Txn) {
    var confirm by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Pill(t.kind.name, when (t.kind) { TxnKind.BUY -> Up; TxnKind.SELL -> Down; TxnKind.DIVIDEND -> Gold })
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(t.symbol, fontWeight = FontWeight.Bold)
            Text(PortfolioCalc.fmtDate(t.date) + if (t.note.isNotBlank()) " · ${t.note}" else "", style = MaterialTheme.typography.labelSmall)
        }
        Text(if (t.kind == TxnKind.DIVIDEND) fmtMoney(if (t.qty > 0) t.qty * t.price else t.price) else "${fmtNum(t.qty, 4).trimEnd('0').trimEnd('.')} @ ${fmtPrice(t.price)}")
        IconButton(onClick = { confirm = true }) { Icon(Icons.Default.Delete, "Delete") }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Delete transaction?") },
        confirmButton = { TextButton(onClick = { Store.update { it.copy(txns = it.txns.filter { x -> x.id != t.id }) }; confirm = false }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } })
}

@Composable
private fun TxnDialog(onDismiss: () -> Unit) {
    var symbol by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(TxnKind.BUY) }
    var qty by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }
    var fees by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(java.time.LocalDate.now().toString()) }
    var pick by remember { mutableStateOf(false) }
    LaunchedEffect(symbol) { if (symbol.isNotBlank() && price.isBlank()) Market.quote(symbol)?.let { price = fmtPrice(it.price).replace(",", "") } }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Add transaction") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pick = true }, Modifier.fillMaxWidth()) { Text(if (symbol.isBlank()) "Choose symbol" else Catalog.display(symbol)) }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { TxnKind.entries.forEach { k -> FilterChip(kind == k, { kind = k }, { Text(k.name.lowercase().replaceFirstChar { it.uppercase() }) }) } }
            if (kind != TxnKind.DIVIDEND) OutlinedTextField(qty, { qty = it }, label = { Text("Quantity") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            OutlinedTextField(price, { price = it }, label = { Text(if (kind == TxnKind.DIVIDEND) "Total dividend amount" else "Price per share") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            if (kind != TxnKind.DIVIDEND) OutlinedTextField(fees, { fees = it }, label = { Text("Fees (optional)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            OutlinedTextField(date, { date = it }, label = { Text("Date (YYYY-MM-DD)") }, singleLine = true)
        }
    }, confirmButton = {
        TextButton(onClick = {
            val p = price.replace(",", "").toDoubleOrNull(); val qn = if (kind == TxnKind.DIVIDEND) 0.0 else qty.replace(",", "").toDoubleOrNull()
            if (symbol.isNotBlank() && p != null && qn != null) {
                Store.update { it.copy(txns = it.txns + Txn(Store.newId(), symbol, kind, qn, p, fees.toDoubleOrNull() ?: 0.0, PortfolioCalc.parseDate(date) ?: System.currentTimeMillis())) }
                onDismiss()
            }
        }) { Text("Save") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
    if (pick) SymbolPicker("Symbol", { pick = false }) { symbol = it; price = ""; pick = false }
}

// ============================ Paper trading ============================

@Composable
fun PaperScreen() {
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val d by Store.data.collectAsState()
    val p = d.paper
    val q = rememberLive(p.positions.keys.toList())
    val posVal = p.positions.entries.sumOf { (s, ps) -> (q[s]?.price ?: ps.avg) * ps.qty }
    val total = p.cash + posVal
    var trade by remember { mutableStateOf<String?>(null) }
    var pick by remember { mutableStateOf(false) }
    var reset by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SectionCard("Practice account") {
                Text("Account value", style = MaterialTheme.typography.labelLarge)
                Text(fmtMoney(total), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                Text("${fmtSignedMoney(total - p.start)} (${fmtPct((total / p.start - 1) * 100)}) since start", color = changeColor(total - p.start))
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile("Cash", fmtMoney(p.cash), Modifier.weight(1f)); StatTile("Invested", fmtMoney(posVal), Modifier.weight(1f)); StatTile("Trades", "${p.history.size}", Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { pick = true }, Modifier.weight(1f)) { Text("New trade") }
                    OutlinedButton(onClick = { reset = true }) { Text("Reset") }
                }
                Text("Market orders fill at the latest quote. No real money is involved.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
        }
        item { Text("Positions", style = MaterialTheme.typography.titleMedium) }
        if (p.positions.isEmpty()) item { Text("No open positions.", Modifier.padding(8.dp)) }
        items(p.positions.entries.toList(), key = { it.key }) { (s, ps) ->
            val px = q[s]?.price
            SectionCard(modifier = Modifier.clickable { trade = s }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(Catalog.display(s), fontWeight = FontWeight.Bold, modifier = Modifier.clickable { nav.symbol(s) })
                        Text("${fmtNum(ps.qty, 4).trimEnd('0').trimEnd('.')} @ ${fmtPrice(ps.avg)}", style = MaterialTheme.typography.bodySmall)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(fmtMoney(px?.let { it * ps.qty }), fontWeight = FontWeight.SemiBold)
                        val pl = px?.let { (it - ps.avg) * ps.qty }
                        Text("${fmtSignedMoney(pl)} (${fmtPct(px?.let { (it / ps.avg - 1) * 100 })})", color = changeColor(pl), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
        if (p.history.isNotEmpty()) {
            item { Text("History", style = MaterialTheme.typography.titleMedium) }
            items(p.history.sortedByDescending { it.time }.take(50)) { t ->
                Row(Modifier.fillMaxWidth().padding(4.dp)) {
                    Pill(t.side, if (t.side == "BUY") Up else Down); Spacer(Modifier.width(8.dp))
                    Text("${Catalog.display(t.symbol)} ${fmtNum(t.qty, 4).trimEnd('0').trimEnd('.')} @ ${fmtPrice(t.price)}", Modifier.weight(1f))
                    Text(fmtTime(t.time, "MMM d h:mm a"), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
    if (pick) SymbolPicker("Trade symbol", { pick = false }) { trade = it; pick = false }
    trade?.let { sym -> PaperTradeDialog(sym) { trade = null } }
    if (reset) {
        var amt by remember { mutableStateOf("100000") }
        AlertDialog(onDismissRequest = { reset = false }, title = { Text("Reset practice account") },
            text = { OutlinedTextField(amt, { amt = it }, label = { Text("Starting cash") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)) },
            confirmButton = { TextButton(onClick = { val a = amt.toDoubleOrNull() ?: 100_000.0; Store.update { it.copy(paper = Paper(a, a)) }; reset = false
                Toast.makeText(ctx, "Account reset", Toast.LENGTH_SHORT).show() }) { Text("Reset") } },
            dismissButton = { TextButton(onClick = { reset = false }) { Text("Cancel") } })
    }
}

@Composable
private fun PaperTradeDialog(symbol: String, onDismiss: () -> Unit) {
    val q = rememberLive(listOf(symbol))[symbol]
    val p by Store.data.collectAsState()
    var side by remember { mutableStateOf("BUY") }
    var qty by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    val held = p.paper.positions[symbol]?.qty ?: 0.0
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Trade ${Catalog.display(symbol)}") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Price: ${fmtPrice(q?.price)}  ·  Cash: ${fmtMoney(p.paper.cash)}  ·  Held: ${fmtNum(held, 4).trimEnd('0').trimEnd('.')}")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(side == "BUY", { side = "BUY" }, { Text("Buy") }); FilterChip(side == "SELL", { side = "SELL" }, { Text("Sell") })
            }
            OutlinedTextField(qty, { qty = it }, label = { Text("Quantity") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            val n = qty.toDoubleOrNull(); if (n != null && q != null) Text("Estimated ${fmtMoney(n * q.price)}")
            err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = {
        TextButton(onClick = {
            val n = qty.toDoubleOrNull(); val px = q?.price
            when {
                n == null || n <= 0 -> err = "Enter a quantity"
                px == null -> err = "No price yet"
                side == "BUY" && n * px > p.paper.cash -> err = "Not enough cash"
                side == "SELL" && n > held + 1e-9 -> err = "You only hold ${fmtNum(held, 4)}"
                else -> {
                    Store.update { d ->
                        val pa = d.paper; val cur = pa.positions[symbol]
                        val positions = if (side == "BUY") {
                            val nq = (cur?.qty ?: 0.0) + n
                            pa.positions + (symbol to PaperPos(nq, ((cur?.qty ?: 0.0) * (cur?.avg ?: 0.0) + n * px) / nq))
                        } else {
                            val left = (cur?.qty ?: 0.0) - n
                            if (left <= 1e-9) pa.positions - symbol else pa.positions + (symbol to cur!!.copy(qty = left))
                        }
                        d.copy(paper = pa.copy(cash = pa.cash + if (side == "BUY") -n * px else n * px, positions = positions,
                            history = pa.history + PaperTrade(symbol, side, n, px, System.currentTimeMillis())))
                    }
                    onDismiss()
                }
            }
        }) { Text("Place order") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

// ============================ Alerts ============================

@Composable
fun AlertsScreen() {
    val nav = LocalNav.current
    val d by Store.data.collectAsState()
    var add by remember { mutableStateOf(false) }
    val q = rememberLive(d.alerts.map { it.symbol }.distinct())
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            SectionCard {
                Text("Alerts are checked on every refresh while the app is open, and about every 15 minutes in the background.", style = MaterialTheme.typography.bodySmall)
                Button(onClick = { add = true }, Modifier.padding(top = 8.dp)) { Icon(Icons.Default.Add, null); Text(" New alert") }
            }
        }
        if (d.alerts.isEmpty()) item { Text("No alerts yet.", Modifier.padding(8.dp)) }
        items(d.alerts, key = { it.id }) { a ->
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clickable { nav.symbol(a.symbol) }) {
                        Text(Catalog.display(a.symbol), fontWeight = FontWeight.Bold)
                        Text("${a.kind.label} ${if (a.kind == AlertKind.ABOVE || a.kind == AlertKind.BELOW) fmtPrice(a.value) else fmtNum(a.value) + "%"}" + if (a.repeat) " · repeats" else "",
                            style = MaterialTheme.typography.bodySmall)
                        Text("Now ${fmtPrice(q[a.symbol]?.price)} (${fmtPct(q[a.symbol]?.changePct)})" + if (a.lastFired > 0) " · last fired ${fmtTime(a.lastFired)}" else "",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(a.enabled, { on -> Store.update { it.copy(alerts = it.alerts.map { x -> if (x.id == a.id) x.copy(enabled = on, lastFired = if (on) 0 else x.lastFired) else x }) } })
                    IconButton(onClick = { Store.update { it.copy(alerts = it.alerts.filter { x -> x.id != a.id }) } }) { Icon(Icons.Default.Delete, "Delete") }
                }
            }
        }
    }
    if (add) AddAlertDialog(null, null) { add = false }
}

@Composable
fun AddAlertDialog(symbol: String?, price: Double?, onDismiss: () -> Unit) {
    var sym by remember { mutableStateOf(symbol.orEmpty()) }
    var kind by remember { mutableStateOf(AlertKind.ABOVE) }
    var value by remember { mutableStateOf(price?.let { fmtPrice(it).replace(",", "") } ?: "") }
    var repeat by remember { mutableStateOf(false) }
    var pick by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("New price alert") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pick = true }, Modifier.fillMaxWidth()) { Text(if (sym.isBlank()) "Choose symbol" else Catalog.display(sym)) }
            AlertKind.entries.forEach { k -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { kind = k }) { RadioButton(kind == k, { kind = k }); Text(k.label) } }
            OutlinedTextField(value, { value = it }, label = { Text(if (kind == AlertKind.ABOVE || kind == AlertKind.BELOW) "Price" else "Percent") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(repeat, { repeat = it }); Text("Keep alert on after it fires (max every 6h)") }
        }
    }, confirmButton = {
        TextButton(onClick = {
            val v = value.replace(",", "").toDoubleOrNull()
            if (sym.isNotBlank() && v != null) { Store.update { it.copy(alerts = it.alerts + Alert(Store.newId(), sym, kind, kotlin.math.abs(v), repeat = repeat)) }; onDismiss() }
        }) { Text("Save") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
    if (pick) SymbolPicker("Alert symbol", { pick = false }) { sym = it; pick = false }
}

// ============================ Notes ============================

@Composable
fun NotesScreen() {
    val nav = LocalNav.current
    val d by Store.data.collectAsState()
    var edit by remember { mutableStateOf<String?>(null) }
    var pick by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Button(onClick = { pick = true }) { Icon(Icons.Default.Add, null); Text(" New note") } }
        if (d.notes.isEmpty()) item { Text("Notes you add on any symbol page show up here.", Modifier.padding(8.dp)) }
        items(d.notes.entries.toList(), key = { it.key }) { (s, n) ->
            SectionCard(Catalog.display(s), onTitleClick = { nav.symbol(s) }, action = { IconButton(onClick = { edit = s }) { Icon(Icons.Default.Edit, "Edit") } }) {
                Text(n, style = MaterialTheme.typography.bodyMedium, maxLines = 6, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    if (pick) SymbolPicker("Note for", { pick = false }) { edit = it; pick = false }
    edit?.let { NoteDialog(it) { edit = null } }
}
