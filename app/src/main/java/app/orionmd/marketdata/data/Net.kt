package app.orionmd.marketdata.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class HttpException(val code: Int, msg: String) : IOException(msg)

/**
 * HTTP with a memory + disk cache. When the network fails, the last saved copy is returned
 * so the app keeps working offline; [offlineSince] tells the UI it is showing saved data.
 */
object Net {
    const val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private lateinit var cacheDir: File
    private val mem = ConcurrentHashMap<String, Pair<Long, String>>()
    private val _offline = MutableStateFlow<Long?>(null)
    val offlineSince: StateFlow<Long?> = _offline

    fun init(ctx: Context) {
        cacheDir = File(ctx.cacheDir, "http").apply { mkdirs() }
    }

    private fun keyOf(url: String) = MessageDigest.getInstance("MD5").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }

    // Simple per-host rate limiting so free API tiers aren't exceeded.
    private val limits = mapOf("finnhub.io" to 55, "api.twelvedata.com" to 7, "www.alphavantage.co" to 5, "api.polygon.io" to 5)
    private val calls = ConcurrentHashMap<String, ArrayDeque<Long>>()
    private val limitLock = Mutex()

    private suspend fun throttle(host: String) {
        val max = limits[host] ?: return
        while (true) {
            val wait = limitLock.withLock {
                val q = calls.getOrPut(host) { ArrayDeque() }
                val now = System.currentTimeMillis()
                while (q.isNotEmpty() && now - q.first() > 60_000) q.removeFirst()
                if (q.size < max) { q.addLast(now); 0L } else 60_000 - (now - q.first()) + 50
            }
            if (wait <= 0) return
            delay(wait)
        }
    }

    /** Returns true if a call to [host] would have to wait for the rate limit right now. */
    fun busy(host: String): Boolean {
        val max = limits[host] ?: return false
        val q = calls[host] ?: return false
        val now = System.currentTimeMillis()
        return q.count { now - it < 60_000 } >= max
    }

    suspend fun get(
        url: String,
        ttlMs: Long = 30_000,
        headers: Map<String, String> = emptyMap(),
        allowStale: Boolean = true,
    ): String = withContext(Dispatchers.IO) {
        val key = keyOf(url)
        val now = System.currentTimeMillis()
        mem[key]?.let { (t, body) -> if (now - t < ttlMs) return@withContext body }
        val file = File(cacheDir, key)
        if (ttlMs > 5 * 60_000 && file.exists() && now - file.lastModified() < ttlMs) {
            val body = file.readText(); mem[key] = file.lastModified() to body; return@withContext body
        }
        try {
            val host = url.substringAfter("://").substringBefore("/").substringBefore("?")
            throttle(host)
            val req = Request.Builder().url(url).header("User-Agent", UA).header("Accept", "application/json, text/xml, */*")
            headers.forEach { (k, v) -> req.header(k, v) }
            client.newCall(req.build()).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw HttpException(resp.code, "HTTP ${resp.code} from $host")
                if (body.contains("You don't have access to this resource")) throw HttpException(403, "Not included in free plan")
                mem[key] = now to body
                runCatching { file.writeText(body) }
                _offline.value = null
                body
            }
        } catch (e: Exception) {
            if (allowStale && file.exists()) {
                if (e !is HttpException) _offline.value = _offline.value ?: file.lastModified()
                file.readText()
            } else {
                if (e !is HttpException && e is IOException) _offline.value = _offline.value ?: now
                throw e
            }
        }
    }

    suspend fun post(url: String, json: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).header("User-Agent", UA)
            .post(json.toRequestBody("application/json".toMediaType())).build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw HttpException(r.code, "HTTP ${r.code}")
            r.body?.string().orEmpty()
        }
    }

    fun clearMemory() = mem.clear()

    fun cacheSizeBytes(): Long = cacheDir.listFiles()?.sumOf { it.length() } ?: 0

    fun clearDisk() { cacheDir.listFiles()?.forEach { it.delete() }; mem.clear() }
}
