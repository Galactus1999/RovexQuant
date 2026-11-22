package com.rovexquant.app.ui

import android.os.Handler
import android.os.Looper
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class QuantSession(val baseUrl: String, val accessToken: String)
data class MarketIndexInstrument(val instrumentKey: String, val name: String, val tradingSymbol: String)
data class NseMcpTool(val name: String, val description: String, val inputSchema: JSONObject, val readOnly: Boolean)
data class NseMcpCatalog(val server: String, val tools: List<NseMcpTool>, val failure: String? = null)
data class NseMcpResult(val provider: String, val dataMode: String, val expectedDelay: String?, val sourceTimestamp: String?, val resultJson: String)

data class MarketTick(
    val instrumentKey: String,
    val lastPrice: Double,
    val previousClose: Double?,
    val changePercent: Double?,
    val exchangeTimestamp: String?,
    val receivedAt: String,
    val quality: String
)

object QuantApi {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    private val masterHttp = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    fun login(baseUrl: String, username: String, password: String): QuantSession {
        val normalized = baseUrl.trim().trimEnd('/')
        require(normalized.startsWith("https://")) { "Use the HTTPS URL of your Quant backend." }
        val body = JSONObject().put("username", username).put("password", password)
        val request = Request.Builder()
            .url("$normalized/api/v1/auth/session")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = runCatching { JSONObject(text).optString("detail") }.getOrNull()
                throw IllegalStateException(detail?.takeIf { it.isNotBlank() } ?: "Login failed (HTTP ${response.code}).")
            }
            val token = JSONObject(text).optString("access_token")
            require(token.isNotBlank()) { "Backend returned no session token." }
            return QuantSession(normalized, token)
        }
    }

    fun validateSession(session: QuantSession): Boolean {
        val request = Request.Builder()
            .url("${session.baseUrl}/api/v1/app/auth/session")
            .header("Authorization", "Bearer ${session.accessToken}")
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            if (response.code == 401 || response.code == 403) return false
            if (!response.isSuccessful) {
                throw IllegalStateException("Session validation unavailable (HTTP ${response.code}).")
            }
            return JSONObject(response.body?.string().orEmpty()).optBoolean("authenticated", false)
        }
    }

    fun fetchNseMcpTools(session: QuantSession, server: String = "both"): List<NseMcpCatalog> {
        require(server in setOf("bhavcopy", "cmmkt", "both")) { "Invalid NSE MCP server." }
        val request = Request.Builder()
            .url("${session.baseUrl}/api/v1/app/research/nse-mcp/tools?server=$server")
            .header("Authorization", "Bearer ${session.accessToken}")
            .get()
            .build()
        masterHttp.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException(apiError(text, response.code))
            require(text.toByteArray(Charsets.UTF_8).size <= 2 * 1024 * 1024) {
                "NSE tool catalogue exceeds the 2 MB mobile safety limit."
            }
            val responseJson = JSONObject(text)
            val catalog = responseJson.optJSONObject("catalog")
                ?: throw IllegalStateException("NSE MCP returned no tool catalogue.")
            val failures = responseJson.optJSONObject("failures")
            val requestedServers = if (server == "both") listOf("bhavcopy", "cmmkt") else listOf(server)
            val discovered = buildList {
                for (serverName in requestedServers) {
                    val records = catalog.optJSONArray(serverName)
                    val tools = buildList {
                        for (index in 0 until (records?.length() ?: 0)) {
                            val item = records?.optJSONObject(index) ?: continue
                            val name = item.optString("name")
                            val schema = item.optJSONObject("input_schema") ?: JSONObject()
                            if (name.isNotBlank()) add(NseMcpTool(
                                name = name,
                                description = item.optString("description"),
                                inputSchema = schema,
                                readOnly = item.optBoolean("read_only", false)
                            ))
                        }
                    }
                    val failure = failures?.optString(serverName)?.takeIf { it.isNotBlank() && it != "null" }
                    add(NseMcpCatalog(serverName, tools, failure))
                }
            }
            return discovered
        }
    }

    fun callNseMcpTool(
        session: QuantSession,
        server: String,
        tool: String,
        argumentsJson: String
    ): NseMcpResult {
        require(server in setOf("bhavcopy", "cmmkt")) { "Invalid NSE MCP server." }
        require(argumentsJson.toByteArray(Charsets.UTF_8).size <= 32 * 1024) { "Arguments exceed 32 KB." }
        val arguments = try { JSONObject(argumentsJson) } catch (_: Exception) {
            throw IllegalArgumentException("Arguments must be a valid JSON object.")
        }
        val body = JSONObject()
            .put("server", server)
            .put("tool", tool)
            .put("arguments", arguments)
        val request = Request.Builder()
            .url("${session.baseUrl}/api/v1/app/research/nse-mcp/call")
            .header("Authorization", "Bearer ${session.accessToken}")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        masterHttp.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException(apiError(text, response.code))
            require(text.toByteArray(Charsets.UTF_8).size <= 2 * 1024 * 1024) {
                "NSE response exceeds the 2 MB mobile research limit. Narrow the query and try again."
            }
            val json = JSONObject(text)
            val result = json.optJSONObject("result") ?: JSONObject()
            return NseMcpResult(
                provider = json.optString("provider"),
                dataMode = json.optString("data_mode"),
                expectedDelay = json.optString("expected_delay").takeIf { it.isNotBlank() && it != "null" },
                sourceTimestamp = json.optString("source_timestamp").takeIf { it.isNotBlank() && it != "null" },
                resultJson = result.toString(2)
            )
        }
    }

    private fun apiError(body: String, code: Int): String {
        val detail = runCatching { JSONObject(body).opt("detail") }.getOrNull()
        return when (detail) {
            is String -> detail.take(400)
            is JSONObject -> detail.optString("message").takeIf { it.isNotBlank() }
                ?: "NSE research request failed (HTTP $code)."
            else -> "NSE research request failed (HTTP $code)."
        }
    }

    fun fetchIndexInstruments(session: QuantSession): List<MarketIndexInstrument> {
        val request = Request.Builder()
            .url("${session.baseUrl}/api/v1/app/market/index-instruments")
            .header("Authorization", "Bearer ${session.accessToken}")
            .get()
            .build()
        masterHttp.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("Unable to load NSE index master (HTTP ${response.code}).")
            }
            val records = JSONObject(text).optJSONArray("instruments")
                ?: throw IllegalStateException("Backend returned no NSE index master.")
            return buildList {
                for (index in 0 until records.length()) {
                    val item = records.optJSONObject(index) ?: continue
                    val key = item.optString("instrument_key")
                    val name = item.optString("name")
                    if (key.startsWith("NSE_INDEX|") && name.isNotBlank()) {
                        add(MarketIndexInstrument(key, name, item.optString("trading_symbol")))
                    }
                }
            }
        }
    }
}

class MarketStreamController(
    private val session: QuantSession,
    private val onStatus: (String) -> Unit,
    private val onTick: (MarketTick) -> Unit
) {
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    private val handler = Handler(Looper.getMainLooper())
    private var socket: WebSocket? = null
    private var stopped = false
    private var attempts = 0
    private var keys: List<String> = emptyList()
    private val retry = Runnable { connect() }

    fun start(instrumentKeys: List<String>) {
        keys = instrumentKeys.distinct().take(100)
        stopped = false
        if (keys.isEmpty()) {
            emitStatus("No verified NSE index keys found")
            return
        }
        connect()
    }

    fun stop() {
        stopped = true
        handler.removeCallbacks(retry)
        socket?.close(1000, "App leaving market screen")
        socket = null
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
    }

    private fun emitStatus(value: String) {
        handler.post { if (!stopped) onStatus(value) }
    }

    private fun emitTick(value: MarketTick) {
        handler.post { if (!stopped) onTick(value) }
    }

    private fun connect() {
        if (stopped || keys.isEmpty()) return
        emitStatus(if (attempts == 0) "Connecting" else "Reconnecting $attempts")
        val wsUrl = session.baseUrl.replaceFirst("https://", "wss://") + "/api/v1/app/market/stream"
        val request = Request.Builder().url(wsUrl)
            .header("Authorization", "Bearer ${session.accessToken}")
            .build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                emitStatus("Connected · awaiting ticks")
                val subscription = JSONObject()
                    .put("instrument_keys", JSONArray(keys))
                    .put("mode", "full")
                webSocket.send(subscription.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val json = JSONObject(text)
                    if (json.optString("type") == "error") {
                        emitStatus(json.optString("message", "Stream error"))
                        return
                    }
                    val quotes = json.optJSONArray("quotes") ?: return
                    for (index in 0 until quotes.length()) {
                        val quote = quotes.optJSONObject(index) ?: continue
                        val key = quote.optString("instrument_key")
                        val ltp = quote.optDouble("last_price", Double.NaN)
                        if (key.isBlank() || !ltp.isFinite() || ltp <= 0.0) continue
                        emitTick(MarketTick(
                            instrumentKey = key,
                            lastPrice = ltp,
                            previousClose = quote.optDouble("previous_close").takeIf { it.isFinite() && it > 0 },
                            changePercent = quote.optDouble("change_percent").takeIf { it.isFinite() },
                            exchangeTimestamp = quote.optString("exchange_timestamp").takeIf { it.isNotBlank() && it != "null" },
                            receivedAt = quote.optString("received_at"),
                            quality = quote.optString("quality", "UNKNOWN")
                        ))
                    }
                    if (json.optString("type") == "initial_feed" || json.optString("type") == "live_feed") {
                        attempts = 0
                        emitStatus("Live · Upstox V3")
                    }
                }.onFailure { emitStatus("Invalid market message") }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (response?.code == 401 || response?.code == 403) {
                    emitStatus("Sign in again · session rejected")
                    return
                }
                emitStatus("Stream unavailable")
                scheduleRetry()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (code == 4401) {
                    emitStatus("Session expired · sign in again")
                    return
                }
                if (code == 4400) return
                scheduleRetry()
            }
        })
    }

    private fun scheduleRetry() {
        if (stopped || attempts >= 8) return
        attempts += 1
        val delay = (1000L shl (attempts - 1).coerceAtMost(5)).coerceAtMost(30000L)
        handler.removeCallbacks(retry)
        handler.postDelayed(retry, delay)
    }
}
