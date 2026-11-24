package com.rovexquant.app.data.remote

import android.os.Handler
import android.os.Looper
import com.rovexquant.app.data.model.MarketTick
import com.rovexquant.app.data.model.QuantSession
import com.rovexquant.app.data.repository.MarketStream
import kotlinx.coroutines.BufferOverflow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

private const val FRAME_QUEUE_CAPACITY = 64
private const val CHART_FLOW_CAPACITY = 32
private const val DASHBOARD_COALESCE_MS = 250L

internal fun marketStreamStatusForQuotes(quotes: List<MarketTick>): String {
    if (quotes.isEmpty()) return "Connected · waiting for verified quotes"
    return if (quotes.any { it.quality == "VALID" }) {
        "Live · Upstox V3"
    } else {
        "Connected · stale market snapshot"
    }
}

/** Latest-value merge: tick snapshots are replaceable; they are not durable OHLCV events. */
internal fun mergeLatestMarketTicks(
    destination: MutableMap<String, MarketTick>,
    incoming: List<MarketTick>
) {
    incoming.forEach { tick -> destination[tick.instrumentKey] = tick }
}

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
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(supervisor + Dispatchers.Default)
    private val droppedFrames = AtomicLong(0)
    private val malformedFrames = AtomicLong(0)
    private val incomingFrames = Channel<String>(
        capacity = FRAME_QUEUE_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
        onUndeliveredElement = { droppedFrames.incrementAndGet() }
    )
    private val mutableChartTicks = MutableSharedFlow<List<MarketTick>>(
        replay = 0,
        extraBufferCapacity = CHART_FLOW_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val chartTicks = mutableChartTicks.asSharedFlow()

    private val mutableDashboardTicks = MutableSharedFlow<List<MarketTick>>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private var dashboardCollector: Job? = null
    private var ingestWorker: Job? = null
    @Volatile private var socket: WebSocket? = null
    @Volatile private var stopped = false
    @Volatile private var attempts = 0
    @Volatile private var keys: List<String> = emptyList()
    private val retry = Runnable { connect() }

    override val droppedFrameCount: Long
        get() = droppedFrames.get()

    init {
        dashboardCollector = scope.launch(Dispatchers.Main.immediate) {
            mutableDashboardTicks.collect { ticks ->
                if (!stopped && ticks.isNotEmpty()) onTicks(ticks)
            }
        }
        ingestWorker = scope.launch(Dispatchers.Default) {
            consumeFrames()
        }
    }

    override fun start(instrumentKeys: List<String>) {
        if (stopped) {
            emitStatus("Market stream has been stopped; create a new stream to reconnect")
            return
        }
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
        if (stopped) return
        stopped = true
        handler.removeCallbacks(retry)
        incomingFrames.close()
        socket?.close(1000, "App leaving market screen")
        socket = null
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        scope.cancel()
    }

    private fun emitStatus(value: String) {
        handler.post { if (!stopped) onStatus(value) }
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
                if (!webSocket.send(subscription.toString())) {
                    emitStatus("Subscription send rejected · reconnecting")
                    webSocket.cancel()
                    scheduleRetry()
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (stopped) return
                // OkHttp callback threads only enqueue. JSON parsing never runs here.
                incomingFrames.trySend(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (stopped) return
                if (response?.code == 401 || response?.code == 403) {
                    emitStatus("Sign in again · session rejected")
                    return
                }
                emitStatus("Stream unavailable")
                scheduleRetry()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (stopped) return
                if (code == 4401) {
                    emitStatus("Session expired · sign in again")
                    return
                }
                if (code == 4400) return
                scheduleRetry()
            }
        })
    }

    private suspend fun consumeFrames() {
        while (currentCoroutineContext().isActive) {
            val first = incomingFrames.receiveCatching().getOrNull() ?: break
            val latestByInstrument = linkedMapOf<String, MarketTick>()
            processFrame(first, latestByInstrument)

            val deadlineNanos = System.nanoTime() + DASHBOARD_COALESCE_MS * 1_000_000L
            while (currentCoroutineContext().isActive) {
                val remainingNanos = deadlineNanos - System.nanoTime()
                if (remainingNanos <= 0L) break
                val next = withTimeoutOrNull((remainingNanos / 1_000_000L).coerceAtLeast(1L)) {
                    incomingFrames.receiveCatching().getOrNull()
                } ?: break
                processFrame(next, latestByInstrument)
            }

            if (latestByInstrument.isNotEmpty()) {
                mutableDashboardTicks.tryEmit(latestByInstrument.values.toList())
            }
        }
    }

    private fun processFrame(
        frame: String,
        latestByInstrument: MutableMap<String, MarketTick>
    ) {
        try {
            val json = JSONObject(frame)
            when (json.optString("type")) {
                "error" -> {
                    emitStatus(json.optString("message", "Stream error"))
                    return
                }
                "upstream_text" -> {
                    emitStatus("Upstox sent an unexpected text frame; expected binary Protobuf data")
                    return
                }
            }
            val quotes = json.optJSONArray("quotes") ?: return
            val parsedTicks = buildList {
                for (index in 0 until quotes.length()) {
                    val quote = quotes.optJSONObject(index) ?: continue
                    val key = quote.optString("instrument_key")
                    val ltp = quote.optDouble("last_price", Double.NaN)
                    if (key.isBlank() || !ltp.isFinite() || ltp <= 0.0) continue
                    add(MarketTick(
                        instrumentKey = key,
                        lastPrice = ltp,
                        previousClose = quote.optDouble("previous_close").takeIf { it.isFinite() && it > 0 },
                        changePercent = quote.optDouble("change_percent").takeIf { it.isFinite() },
                        exchangeTimestamp = quote.optString("exchange_timestamp").takeIf { it.isNotBlank() && it != "null" },
                        receivedAt = quote.optString("received_at"),
                        quality = quote.optString("quality", "UNKNOWN")
                    ))
                }
            }
            if (parsedTicks.isNotEmpty()) {
                // Chart consumers can opt into this bounded higher-frequency stream.
                mutableChartTicks.tryEmit(parsedTicks)
                mergeLatestMarketTicks(latestByInstrument, parsedTicks)
            }
            if (json.optString("type") == "initial_feed" || json.optString("type") == "live_feed") {
                attempts = 0
                emitStatus(marketStreamStatusForQuotes(parsedTicks))
            }
        } catch (_: Exception) {
            malformedFrames.incrementAndGet()
            emitStatus("Invalid market message")
        }
    }

    private fun scheduleRetry() {
        if (stopped || attempts >= 8) return
        attempts += 1
        val delay = (1000L shl (attempts - 1).coerceAtMost(5)).coerceAtMost(30000L)
        handler.removeCallbacks(retry)
        handler.postDelayed(retry, delay)
    }
}
