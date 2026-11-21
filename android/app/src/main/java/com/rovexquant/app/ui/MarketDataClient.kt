package com.rovexquant.app.ui

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import android.os.Handler
import android.os.Looper

data class QuantSession(val baseUrl: String, val accessToken: String)

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
            val json = JSONObject(text)
            val token = json.optString("access_token")
            require(token.isNotBlank()) { "Backend returned no session token." }
            return QuantSession(normalized, token)
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
        .build()
    private val handler = Handler(Looper.getMainLooper())
    private var socket: WebSocket? = null
    private var stopped = false
    private var attempts = 0
    private val retry = Runnable { connect() }
    private val keys = listOf("NSE_INDEX|Nifty 50", "NSE_INDEX|Nifty Bank")

    fun start() {
        stopped = false
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

    private fun emitStatus(value: String) { handler.post { if (!stopped) onStatus(value) } }

    private fun emitTick(value: MarketTick) { handler.post { if (!stopped) onTick(value) } }

    private fun connect() {
        if (stopped) return
        emitStatus(if (attempts == 0) "Connecting" else "Reconnecting $attempts")
        val wsUrl = session.baseUrl.replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://") + "/api/v1/app/market/stream"
        val request = Request.Builder().url(wsUrl)
            .header("Authorization", "Bearer ${session.accessToken}").build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                attempts = 0
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
                        emitStatus("Live · Upstox V3")
                    }
                }.onFailure { emitStatus("Invalid market message") }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                emitStatus("Stream unavailable")
                scheduleRetry()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
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
