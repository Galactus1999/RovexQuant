package com.rovexquant.app.data.remote

import android.os.Handler
import android.os.Looper
import com.rovexquant.app.data.model.MarketTick
import com.rovexquant.app.data.model.QuantSession
import com.rovexquant.app.data.repository.MarketStream
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class MarketStreamController(
    private val session: QuantSession,
    private val onStatus: (String) -> Unit,
    private val onTicks: (List<MarketTick>) -> Unit
) : MarketStream {
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

    override fun start(instrumentKeys: List<String>) {
        stopped = false
        keys = instrumentKeys.distinct()
        if (keys.isEmpty()) {
            emitStatus("No verified NSE instruments found")
            return
        }
        if (keys.size > 5000) {
            emitStatus("NSE universe exceeds Upstox LTPC subscription limit (5,000)")
            return
        }
        connect()
    }

    override fun stop() {
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

    private fun emitTicks(values: List<MarketTick>) {
        if (values.isEmpty()) return
        handler.post { if (!stopped) onTicks(values) }
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
                    .put("mode", "ltpc")
                webSocket.send(subscription.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val json = JSONObject(text)
                    if (json.optString("type") == "error") {
                        emitStatus(json.optString("message", "Stream error"))
                        return
                    }
                    if (json.optString("type") == "upstream_text") {
                        emitStatus("Upstox sent an unexpected text frame; expected binary Protobuf data")
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
