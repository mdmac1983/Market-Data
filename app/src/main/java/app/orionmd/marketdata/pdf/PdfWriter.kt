package app.orionmd.marketdata.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import app.orionmd.marketdata.R
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** A table cell. */
data class Cell(val text: String, val color: Int = PdfWriter.INK, val bold: Boolean = false, val right: Boolean = false)

/**
 * Simple flowing PDF layout: headings, paragraphs, stat tiles, tables (header repeats on new pages),
 * line and bar charts. Every page gets the OrionMD watermark, a header and a footer.
 */
class PdfWriter(
    ctx: Context,
    private val title: String,
    landscape: Boolean,
    a4: Boolean,
    private val watermarkAlpha: Int,
) {
    companion object {
        val INK = Color.rgb(15, 20, 48)
        val MUTED = Color.rgb(100, 110, 140)
        val UP = Color.rgb(22, 163, 74)
        val DOWN = Color.rgb(220, 38, 38)
        val ACCENT = Color.rgb(11, 111, 164)
        val GOLD = Color.rgb(202, 138, 4)
        val LINE = Color.rgb(222, 226, 238)
        val ZEBRA = Color.rgb(246, 247, 252)
        val NAVY = Color.rgb(8, 14, 44)
        fun chg(v: Double?) = if ((v ?: 0.0) >= 0) UP else DOWN
    }

    private val doc = PdfDocument()
    val pageW: Int
    val pageH: Int
    private val margin = 36f
    val contentW get() = pageW - 2 * margin
    private val bottom get() = pageH - margin - 22f

    private var page: PdfDocument.Page? = null
    private lateinit var canvas: Canvas
    private var pageNo = 0
    var y = 0f
        private set

    private val watermark: Bitmap? = runCatching {
        BitmapFactory.decodeResource(ctx.resources, if (landscape) R.drawable.watermark_square_ink else R.drawable.watermark_ink)
    }.getOrNull()
    private val logo: Bitmap? = runCatching { BitmapFactory.decodeResource(ctx.resources, R.drawable.logo_square) }.getOrNull()
    private val generated = LocalDateTime.now().format(DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a"))

    private fun paint(size: Float, color: Int = INK, bold: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size; this.color = color; typeface = if (bold) Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) else Typeface.SANS_SERIF
    }

    init {
        val w = if (a4) 595 else 612; val h = if (a4) 842 else 792
        pageW = if (landscape) h else w; pageH = if (landscape) w else h
        newPage()
    }

    fun newPage() {
        page?.let { finishPage(it) }
        pageNo++
        val p = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNo).create())
        page = p; canvas = p.canvas
        canvas.drawColor(Color.WHITE)
        // watermark, centered
        watermark?.let { wm ->
            val maxW = pageW * 0.78f; val maxH = pageH * 0.78f
            val s = minOf(maxW / wm.width, maxH / wm.height)
            val dw = wm.width * s; val dh = wm.height * s
            val dst = RectF((pageW - dw) / 2, (pageH - dh) / 2, (pageW + dw) / 2, (pageH + dh) / 2)
            canvas.drawBitmap(wm, null, dst, Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = watermarkAlpha })
        }
        // header band
        val bandH = if (pageNo == 1) 64f else 30f
        canvas.drawRect(0f, 0f, pageW.toFloat(), bandH, Paint().apply { color = NAVY })
        logo?.let { canvas.drawBitmap(it, null, RectF(margin, 6f, margin + bandH - 12f, bandH - 6f), Paint(Paint.FILTER_BITMAP_FLAG)) }
        val tx = margin + bandH - 4f
        if (pageNo == 1) {
            canvas.drawText(title, tx, 30f, paint(18f, Color.WHITE, true))
            canvas.drawText("Market_Data · OrionMD · Generated $generated", tx, 50f, paint(9f, Color.rgb(170, 190, 230)))
        } else {
            canvas.drawText(title, tx, 20f, paint(11f, Color.WHITE, true))
        }
        y = bandH + 18f
    }

    private fun finishPage(p: PdfDocument.Page) {
        val c = p.canvas
        c.drawLine(margin, pageH - margin - 8f, pageW - margin, pageH - margin - 8f, Paint().apply { color = LINE })
        c.drawText("$title · Market_Data", margin, pageH - margin + 6f, paint(8f, MUTED))
        val pn = "Page $pageNo"
        val pp = paint(8f, MUTED)
        c.drawText(pn, pageW - margin - pp.measureText(pn), pageH - margin + 6f, pp)
        c.drawText("Data is for information only and may be delayed.", pageW / 2f - 90f, pageH - margin + 6f, paint(7f, MUTED))
        doc.finishPage(p)
    }

    fun ensure(h: Float) { if (y + h > bottom) newPage() }

    fun space(h: Float = 8f) { y += h; if (y > bottom) newPage() }

    fun h1(text: String) {
        ensure(40f); y += 6f
        canvas.drawText(text, margin, y + 14f, paint(15f, ACCENT, true))
        y += 20f
        canvas.drawRect(margin, y, margin + 40f, y + 2.5f, Paint().apply { color = GOLD })
        y += 10f
    }

    fun h2(text: String) {
        ensure(28f)
        canvas.drawText(text, margin, y + 11f, paint(11.5f, INK, true))
        y += 18f
    }

    fun para(text: String, size: Float = 9.5f, color: Int = INK, bold: Boolean = false) {
        val p = paint(size, color, bold)
        wrap(text, p, contentW).forEach { line ->
            ensure(size + 4f)
            canvas.drawText(line, margin, y + size, p)
            y += size + 3.5f
        }
        y += 3f
    }

    private fun wrap(text: String, p: Paint, width: Float): List<String> {
        val out = mutableListOf<String>()
        text.split("\n").forEach { para ->
            var line = ""
            para.split(" ").forEach { w ->
                val cand = if (line.isEmpty()) w else "$line $w"
                if (p.measureText(cand) <= width) line = cand else { if (line.isNotEmpty()) out += line; line = w }
            }
            out += line
        }
        return out
    }

    private fun ellipsize(text: String, p: Paint, width: Float): String {
        if (p.measureText(text) <= width) return text
        var t = text
        while (t.isNotEmpty() && p.measureText("$t…") > width) t = t.dropLast(1)
        return "$t…"
    }

    /** Row of summary tiles (label, value, sub, subColor). Wraps to more rows as needed. */
    fun tiles(items: List<Tile>, perRow: Int = 4) {
        if (items.isEmpty()) return
        val gap = 8f
        val w = (contentW - gap * (perRow - 1)) / perRow
        val h = 50f
        items.chunked(perRow).forEach { row ->
            ensure(h + gap)
            row.forEachIndexed { i, t ->
                val x = margin + i * (w + gap)
                val r = RectF(x, y, x + w, y + h)
                canvas.drawRoundRect(r, 8f, 8f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(215, 240, 244, 252) })
                canvas.drawRoundRect(r, 8f, 8f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = LINE; style = Paint.Style.STROKE; strokeWidth = 0.8f })
                canvas.drawText(ellipsize(t.label, paint(7.5f, MUTED), w - 12), x + 7, y + 13, paint(7.5f, MUTED))
                canvas.drawText(ellipsize(t.value, paint(12.5f, INK, true), w - 12), x + 7, y + 30, paint(12.5f, INK, true))
                t.sub?.let { canvas.drawText(ellipsize(it, paint(8f, t.subColor, true), w - 12), x + 7, y + 43, paint(8f, t.subColor, true)) }
            }
            y += h + gap
        }
        y += 4f
    }

    data class Tile(val label: String, val value: String, val sub: String? = null, val subColor: Int = MUTED)

    /** Table with proportional column [weights]. */
    fun table(headers: List<String>, weights: List<Float>, rows: List<List<Cell>>, fontSize: Float = 8.5f, rightCols: Set<Int> = emptySet()) {
        val total = weights.sum()
        val widths = weights.map { it / total * contentW }
        val rowH = fontSize + 8f
        fun header() {
            ensure(rowH * 2)
            canvas.drawRect(margin, y, margin + contentW, y + rowH, Paint().apply { color = Color.argb(235, 226, 232, 246) })
            var x = margin
            headers.forEachIndexed { i, h ->
                val p = paint(fontSize, INK, true)
                val t = ellipsize(h, p, widths[i] - 8)
                val tx = if (i in rightCols) x + widths[i] - 4 - p.measureText(t) else x + 4
                canvas.drawText(t, tx, y + rowH - 5f, p)
                x += widths[i]
            }
            y += rowH
        }
        header()
        rows.forEachIndexed { r, row ->
            if (y + rowH > bottom) { newPage(); header() }
            if (r % 2 == 1) canvas.drawRect(margin, y, margin + contentW, y + rowH, Paint().apply { color = Color.argb(200, 246, 247, 252) })
            var x = margin
            row.forEachIndexed { i, c ->
                if (i >= widths.size) return@forEachIndexed
                val p = paint(fontSize, c.color, c.bold)
                val t = ellipsize(c.text, p, widths[i] - 8)
                val tx = if (c.right || i in rightCols) x + widths[i] - 4 - p.measureText(t) else x + 4
                canvas.drawText(t, tx, y + rowH - 5f, p)
                x += widths[i]
            }
            y += rowH
        }
        canvas.drawLine(margin, y, margin + contentW, y, Paint().apply { color = LINE })
        y += 10f
    }

    /** Line chart with optional baseline. */
    fun lineChart(values: List<Double>, height: Float = 140f, color: Int? = null, label: String? = null, baseline: Double? = null, xLabels: Pair<String, String>? = null) {
        if (values.size < 2) return
        ensure(height + 30f)
        label?.let { canvas.drawText(it, margin, y + 9f, paint(8.5f, MUTED, true)); y += 13f }
        val lo = minOf(values.min(), baseline ?: values.min()); val hi = maxOf(values.max(), baseline ?: values.max())
        val r = (hi - lo).takeIf { it > 0 } ?: 1.0
        val left = margin + 44f; val w = contentW - 44f
        val top = y; val h = height
        val grid = Paint().apply { this.color = LINE; strokeWidth = 0.7f }
        val lp = paint(7f, MUTED)
        for (g in 0..4) {
            val gy = top + h * g / 4
            canvas.drawLine(left, gy, left + w, gy, grid)
            canvas.drawText(app.orionmd.marketdata.ui.fmtPrice(hi - r * g / 4), margin, gy + 3f, lp)
        }
        val c = color ?: chg(values.last() - (baseline ?: values.first()))
        fun px(i: Int) = left + w * i / (values.size - 1)
        fun py(v: Double) = (top + h * (1 - (v - lo) / r)).toFloat()
        val area = Path(); val line = Path()
        values.forEachIndexed { i, v -> if (i == 0) { line.moveTo(px(i), py(v)); area.moveTo(px(i), top + h); area.lineTo(px(i), py(v)) } else { line.lineTo(px(i), py(v)); area.lineTo(px(i), py(v)) } }
        area.lineTo(px(values.size - 1), top + h); area.close()
        canvas.drawPath(area, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.argb(40, Color.red(c), Color.green(c), Color.blue(c)) })
        canvas.drawPath(line, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = c; style = Paint.Style.STROKE; strokeWidth = 1.6f })
        baseline?.let { canvas.drawLine(left, py(it), left + w, py(it), Paint().apply { this.color = MUTED; strokeWidth = 0.6f; pathEffect = android.graphics.DashPathEffect(floatArrayOf(4f, 4f), 0f) }) }
        y += h + 4f
        xLabels?.let { (a, b) ->
            canvas.drawText(a, left, y + 8f, lp); canvas.drawText(b, left + w - lp.measureText(b), y + 8f, lp); y += 12f
        }
        y += 8f
    }

    /** Several series normalized as % change. */
    fun multiLineChart(series: List<Pair<String, List<Double>>>, colors: List<Int>, height: Float = 170f) {
        val norm = series.filter { it.second.size > 1 }.map { (n, v) -> n to v.map { (it / v.first() - 1) * 100 } }
        if (norm.isEmpty()) return
        ensure(height + 34f)
        val all = norm.flatMap { it.second }
        val lo = minOf(all.min(), 0.0); val hi = maxOf(all.max(), 0.0); val r = (hi - lo).takeIf { it > 0 } ?: 1.0
        val left = margin + 40f; val w = contentW - 40f; val top = y
        val lp = paint(7f, MUTED)
        for (g in 0..4) {
            val gy = top + height * g / 4
            canvas.drawLine(left, gy, left + w, gy, Paint().apply { color = LINE; strokeWidth = 0.7f })
            canvas.drawText("%.1f%%".format(hi - r * g / 4), margin, gy + 3f, lp)
        }
        norm.forEachIndexed { k, (_, v) ->
            val p = Path()
            v.forEachIndexed { i, x -> val px = left + w * i / (v.size - 1); val py = (top + height * (1 - (x - lo) / r)).toFloat(); if (i == 0) p.moveTo(px, py) else p.lineTo(px, py) }
            canvas.drawPath(p, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors[k % colors.size]; style = Paint.Style.STROKE; strokeWidth = 1.6f })
        }
        y += height + 8f
        var x = left
        norm.forEachIndexed { k, (n, v) ->
            val t = "$n ${"%+.2f%%".format(v.last())}"
            canvas.drawRect(x, y, x + 8f, y + 8f, Paint().apply { color = colors[k % colors.size] })
            canvas.drawText(t, x + 11f, y + 7.5f, paint(8f, INK, true))
            x += paint(8f, INK, true).measureText(t) + 26f
        }
        y += 18f
    }

    /** Horizontal bars for signed values (e.g. % change) or shares (allocation). */
    fun bars(items: List<Pair<String, Double>>, signed: Boolean = true, suffix: String = "%", colorFor: ((Int, Double) -> Int)? = null) {
        if (items.isEmpty()) return
        val max = items.maxOf { kotlin.math.abs(it.second) }.takeIf { it > 0 } ?: 1.0
        val labelW = 120f; val valW = 56f
        val barArea = contentW - labelW - valW
        val zero = if (signed) margin + labelW + barArea / 2 else margin + labelW
        items.forEachIndexed { i, (label, v) ->
            ensure(16f)
            val lp = paint(8.5f, INK)
            canvas.drawText(ellipsize(label, lp, labelW - 6), margin, y + 10f, lp)
            val len = (kotlin.math.abs(v) / max * (if (signed) barArea / 2 else barArea)).toFloat()
            val c = colorFor?.invoke(i, v) ?: chg(v)
            val r = if (v >= 0 || !signed) RectF(zero, y + 2f, zero + len, y + 12f) else RectF(zero - len, y + 2f, zero, y + 12f)
            canvas.drawRoundRect(r, 2f, 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c })
            val t = (if (signed && v >= 0) "+" else "") + "%.2f".format(v) + suffix
            canvas.drawText(t, margin + contentW - paint(8.5f, c, true).measureText(t), y + 10f, paint(8.5f, c, true))
            y += 15f
        }
        if (signed) canvas.drawLine(zero, y - items.size * 15f, zero, y, Paint().apply { color = MUTED; strokeWidth = 0.6f })
        y += 8f
    }

    /** Grid of colored heat cells (label + %). */
    fun heatGrid(items: List<Pair<String, Double>>, perRow: Int = 6) {
        val gap = 4f; val w = (contentW - gap * (perRow - 1)) / perRow; val h = 34f
        items.chunked(perRow).forEach { row ->
            ensure(h + gap)
            row.forEachIndexed { i, (label, v) ->
                val x = margin + i * (w + gap)
                val s = (kotlin.math.abs(v) / 3.0).coerceIn(0.2, 1.0)
                val base = chg(v)
                val col = Color.argb((70 + 170 * s).toInt(), Color.red(base), Color.green(base), Color.blue(base))
                canvas.drawRoundRect(RectF(x, y, x + w, y + h), 5f, 5f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col })
                val lp = paint(8f, Color.WHITE, true)
                val lt = ellipsize(label, lp, w - 6)
                canvas.drawText(lt, x + (w - lp.measureText(lt)) / 2, y + 14f, lp)
                val vt = "%+.2f%%".format(v); val vp = paint(8f, Color.WHITE)
                canvas.drawText(vt, x + (w - vp.measureText(vt)) / 2, y + 27f, vp)
            }
            y += h + gap
        }
        y += 6f
    }

    fun note(text: String) = para(text, 7.5f, MUTED)

    fun finish(out: File) {
        page?.let { finishPage(it) }; page = null
        out.parentFile?.mkdirs()
        out.outputStream().use { doc.writeTo(it) }
        doc.close()
    }

    fun abort() { runCatching { doc.close() } }
}
