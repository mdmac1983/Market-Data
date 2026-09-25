package app.orionmd.marketdata.ui.screens

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import app.orionmd.marketdata.data.Store
import app.orionmd.marketdata.pdf.Opt
import app.orionmd.marketdata.pdf.ReportKind
import app.orionmd.marketdata.pdf.ReportOptions
import app.orionmd.marketdata.pdf.Reports
import app.orionmd.marketdata.ui.components.*
import app.orionmd.marketdata.ui.fmtTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun PdfOptionsScreen(initialKind: ReportKind, arg: String?) {
    val ctx = LocalContext.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var kind by remember { mutableStateOf(initialKind) }
    var opts by remember(kind) { mutableStateOf(Reports.loadOptions(kind, arg.takeIf { kind == initialKind })) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var frac by remember { mutableFloatStateOf(0f) }
    var error by remember { mutableStateOf<String?>(null) }
    var kindMenu by remember { mutableStateOf(false) }

    fun set(k: String, v: String) { opts = opts.copy(values = opts.values + (k to v)) }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp, 12.dp, 12.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                SectionCard {
                    Box {
                        OutlinedButton(onClick = { kindMenu = true }, Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.PictureAsPdf, null); Spacer(Modifier.width(8.dp)); Text(kind.title, Modifier.weight(1f)); Icon(Icons.Default.ArrowDropDown, null)
                        }
                        DropdownMenu(kindMenu, { kindMenu = false }) {
                            ReportKind.entries.forEach { k -> DropdownMenuItem(text = { Column { Text(k.title); Text(k.desc, style = MaterialTheme.typography.labelSmall) } }, onClick = { kind = k; kindMenu = false }) }
                        }
                    }
                    Text(kind.desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                    OutlinedTextField(opts.title, { opts = opts.copy(title = it) }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text("Report title") }, singleLine = true)
                }
            }
            item { SectionCard("Every report") { Reports.common.forEach { OptControl(it, opts, ::set) } } }
            val specific = Reports.specs(kind)
            if (specific.isNotEmpty()) item { SectionCard("Sections") { specific.forEach { OptControl(it, opts, ::set) } } }
            item {
                Text("Your choices are remembered for the next ${kind.title.lowercase()} (except the title). The finished PDF opens in the built-in viewer where you can Share or Save it.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            error?.let { item { SectionCard { Text("Couldn't create the PDF:", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error); Text(it, color = MaterialTheme.colorScheme.error) } } }
        }
        Button(
            onClick = {
                if (busy) return@Button
                busy = true; error = null
                Reports.saveOptions(kind, opts)
                scope.launch {
                    try {
                        val out = File(Reports.tempDir(ctx), Reports.fileName(opts.title))
                        withContext(Dispatchers.IO) { Reports.generate(ctx, kind, opts, out) { s, f -> status = s; frac = f } }
                        nav.go("viewer?path=${Uri.encode(out.absolutePath)}&temp=true")
                    } catch (e: Exception) {
                        error = e.message ?: e.javaClass.simpleName
                    } finally { busy = false }
                }
            },
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp).height(52.dp),
        ) { Icon(Icons.Default.PictureAsPdf, null); Spacer(Modifier.width(8.dp)); Text("Create PDF") }

        if (busy) Surface(Modifier.align(Alignment.Center).padding(24.dp), shape = RoundedCornerShape(20.dp), color = Color(0xFF4C1D95), tonalElevation = 8.dp) {
            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Color.White)
                Text(status.ifBlank { "Getting data…" }, color = Color.White, modifier = Modifier.padding(top = 12.dp))
                LinearProgressIndicator({ frac }, Modifier.width(180.dp).padding(top = 8.dp), color = Color.White)
            }
        }
    }
}

@Composable
private fun OptControl(o: Opt, opts: ReportOptions, set: (String, String) -> Unit) {
    when (o) {
        is Opt.Toggle -> Row(Modifier.fillMaxWidth().clickable { set(o.key, (!opts.on(o.key)).toString()) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(o.label, Modifier.weight(1f)); Switch(opts.on(o.key), { set(o.key, it.toString()) })
        }
        is Opt.Choice -> Column(Modifier.padding(vertical = 4.dp)) {
            Text(o.label, style = MaterialTheme.typography.labelLarge)
            ChipRow(o.choices, opts.str(o.key).ifBlank { o.default }, { set(o.key, it) })
        }
        is Opt.Text -> OutlinedTextField(opts.str(o.key), { set(o.key, it) }, Modifier.fillMaxWidth().padding(vertical = 4.dp), label = { Text(o.label) },
            placeholder = { Text(o.hint) }, singleLine = true)
    }
}

// ============================ Viewer ============================

@Composable
fun PdfViewerScreen(path: String, temp: Boolean) {
    val ctx = LocalContext.current
    val nav = LocalNav.current
    val file = remember(path) { File(path) }
    val widthPx = with(LocalDensity.current) { LocalConfiguration.current.screenWidthDp.dp.toPx() }.toInt()
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    // Temporary PDFs are deleted when the viewer closes; use Save to keep a copy.
    DisposableEffect(path) { onDispose { if (temp) file.delete() } }

    // Pages are rendered lazily as they scroll into view (PdfRenderer is single-threaded, so access is serialized).
    val doc by produceState<PdfDoc?>(null, path) {
        value = withContext(Dispatchers.IO) { runCatching { PdfDoc(file) }.getOrNull() }
    }
    // Capture the document now: reading `doc` inside onDispose would see the *new* value and close the
    // freshly opened PDF, which left every page blank.
    val openDoc = doc
    DisposableEffect(openDoc) { onDispose { openDoc?.close() } }

    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching { ctx.contentResolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } } }
            .onSuccess { Toast.makeText(ctx, "PDF saved", Toast.LENGTH_SHORT).show() }
            .onFailure { Toast.makeText(ctx, "Save failed: ${it.message}", Toast.LENGTH_LONG).show() }
    }

    fun share() {
        runCatching {
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
            ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_SUBJECT, file.nameWithoutExtension.replace("_", " ")).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share PDF"))
        }.onFailure { Toast.makeText(ctx, "Share failed: ${it.message}", Toast.LENGTH_LONG).show() }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { share() }, Modifier.weight(1f)) { Icon(Icons.Default.Share, null); Text(" Share") }
            OutlinedButton(onClick = { saver.launch(file.name) }, Modifier.weight(1f)) { Icon(Icons.Default.Download, null); Text(" Save") }
            IconButton(onClick = { nav.back() }) { Icon(Icons.Default.Close, "Close") }
        }
        val d = doc
        Text("${file.name} · ${d?.count ?: "…"} pages · pinch to zoom", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 16.dp))
        if (d == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { if (file.exists()) CircularProgressIndicator() else Text("This PDF is no longer available.") }
        else Box(
            Modifier.fillMaxSize().clip(RoundedCornerShape(0.dp)).background(Color(0xFF2A2F45))
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 4f)
                        if (scale > 1f) { offsetX += pan.x; offsetY += pan.y } else { offsetX = 0f; offsetY = 0f }
                    }
                },
        ) {
            LazyColumn(
                Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = offsetX; translationY = offsetY },
                contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(d.count) { i ->
                    val bmp by produceState<Bitmap?>(null, d, i) { value = d.render(i, (widthPx * 1.4f).toInt().coerceAtMost(1600)) }
                    val ratio = d.ratios[i]
                    val b = bmp
                    if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxWidth().aspectRatio(ratio))
                    else Box(Modifier.fillMaxWidth().aspectRatio(ratio).background(Color.White))
                }
            }
        }
    }
}

// ============================ Reports hub ============================

@Composable
fun ReportsScreen() {
    val ctx = LocalContext.current
    val nav = LocalNav.current
    var saved by remember { mutableStateOf(File(ctx.filesDir, "reports").listFiles()?.sortedByDescending { it.lastModified() }.orEmpty()) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("Create a report", style = MaterialTheme.typography.titleMedium) }
        items(ReportKind.entries) { k ->
            SectionCard(modifier = Modifier.clickable { nav.go("pdf/${k.name}?arg=") }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.PictureAsPdf, null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.padding(start = 12.dp).weight(1f)) { Text(k.title, fontWeight = FontWeight.Bold); Text(k.desc, style = MaterialTheme.typography.bodySmall) }
                    Icon(Icons.Default.ChevronRight, null)
                }
            }
        }
        item { Text("Scheduled reports", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp)) }
        if (saved.isEmpty()) item { Text("Turn on scheduled reports in Settings → Notifications.", style = MaterialTheme.typography.bodySmall) }
        items(saved, key = { it.name }) { f ->
            Row(Modifier.fillMaxWidth().clickable { nav.go("viewer?path=${Uri.encode(f.absolutePath)}&temp=false") }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Description, null)
                Column(Modifier.weight(1f).padding(start = 10.dp)) { Text(f.name); Text(fmtTime(f.lastModified()), style = MaterialTheme.typography.labelSmall) }
                IconButton(onClick = { f.delete(); saved = saved - f }) { Icon(Icons.Default.Delete, "Delete") }
            }
        }
    }
}

/** Keeps a PdfRenderer open and renders pages on demand with a small cache. */
private class PdfDoc(file: File) {
    private val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(fd)
    private val lock = kotlinx.coroutines.sync.Mutex()
    private val cache = object : android.util.LruCache<Int, Bitmap>(6) {}
    val count = renderer.pageCount
    val ratios: List<Float> = (0 until count).map { i -> renderer.openPage(i).use { it.width.toFloat() / it.height } }

    suspend fun render(i: Int, width: Int): Bitmap? = withContext(Dispatchers.IO) {
        cache.get(i)?.let { return@withContext it }
        lock.lock()
        try {
            runCatching {
                renderer.openPage(i).use { pg ->
                    val h = (width.toFloat() * pg.height / pg.width).toInt()
                    Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888).also { bmp ->
                        bmp.eraseColor(android.graphics.Color.WHITE)
                        pg.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        cache.put(i, bmp)
                    }
                }
            }.getOrNull()
        } finally { lock.unlock() }
    }

    fun close() { runCatching { renderer.close(); fd.close() } }
}
