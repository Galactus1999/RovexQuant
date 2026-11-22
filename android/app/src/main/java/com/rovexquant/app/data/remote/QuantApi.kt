package com.rovexquant.app.data.remote

import com.rovexquant.app.data.model.AiChatMessage
import com.rovexquant.app.data.model.AiCompletion
import com.rovexquant.app.data.model.AiDailyBar
import com.rovexquant.app.data.model.AiProvider
import com.rovexquant.app.data.model.AiStrategyBrief
import com.rovexquant.app.data.model.AiStrategyEvidence
import com.rovexquant.app.data.model.MarketIndexInstrument
import com.rovexquant.app.data.model.NseMcpCatalog
import com.rovexquant.app.data.model.NseMcpResult
import com.rovexquant.app.data.model.NseMcpTool
import com.rovexquant.app.data.model.QuantSession
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

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


    fun fetchAiProviders(session: QuantSession): List<AiProvider> {
        val request = Request.Builder()
            .url("${session.baseUrl}/api/v1/app/ai/providers")
            .header("Authorization", "Bearer ${session.accessToken}")
            .get()
            .build()
        masterHttp.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException(apiError(text, response.code))
            val records = JSONObject(text).optJSONArray("providers")
                ?: throw IllegalStateException("AI provider catalogue is unavailable.")
            return buildList {
                for (index in 0 until records.length()) {
                    val item = records.optJSONObject(index) ?: continue
                    val ids = item.optJSONArray("credential_ids") ?: JSONArray()
                    add(AiProvider(
                        providerId = item.optString("provider_id"),
                        configured = item.optBoolean("configured", false),
                        credentialIds = buildList {
                            for (idIndex in 0 until ids.length()) {
                                ids.optString(idIndex).takeIf { it.isNotBlank() }?.let(::add)
                            }
                        },
                        defaultModel = item.optString("default_model").takeIf { it.isNotBlank() && it != "null" }
                    ))
                }
            }
        }
    }

    fun sendAiMessage(
        session: QuantSession,
        providerId: String,
        model: String,
        messages: List<AiChatMessage>,
        credentialId: String?
    ): AiCompletion {
        require(messages.isNotEmpty() && messages.size <= 12) { "Keep AI conversations to the latest 12 messages." }
        val boundedMessages = mutableListOf<AiChatMessage>()
        var combinedCharacters = 0
        messages.asReversed().forEach { message ->
            if (boundedMessages.size < 10 && combinedCharacters + message.content.length <= 22_000) {
                boundedMessages.add(message)
                combinedCharacters += message.content.length
            }
        }
        require(boundedMessages.isNotEmpty()) { "Conversation is too large to send safely." }
        val messageArray = JSONArray().apply {
            boundedMessages.asReversed().forEach { message ->
                put(JSONObject().put("role", message.role).put("content", message.content))
            }
        }
        val body = JSONObject()
            .put("provider_id", providerId)
            .put("model", model)
            .put("messages", messageArray)
            .put("max_tokens", 1200)
            .put("temperature", 0.2)
        if (!credentialId.isNullOrBlank()) body.put("credential_id", credentialId)
        val request = Request.Builder()
            .url("${session.baseUrl}/api/v1/app/ai/chat")
            .header("Authorization", "Bearer ${session.accessToken}")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        masterHttp.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException(apiError(text, response.code))
            return parseAiCompletion(JSONObject(text))
        }
    }

    fun requestAiStrategyBrief(
        session: QuantSession,
        providerId: String,
        model: String,
        symbol: String,
        exchange: String,
        fromDate: String,
        toDate: String,
        strategyContext: String,
        credentialId: String?
    ): AiStrategyBrief {
        val body = JSONObject()
            .put("provider_id", providerId)
            .put("model", model)
            .put("symbol", symbol.trim().uppercase())
            .put("exchange", exchange.uppercase())
            .put("from_date", fromDate)
            .put("to_date", toDate)
            .put("strategy_context", strategyContext)
        if (!credentialId.isNullOrBlank()) body.put("credential_id", credentialId)
        val request = Request.Builder()
            .url("${session.baseUrl}/api/v1/app/ai/strategy-brief")
            .header("Authorization", "Bearer ${session.accessToken}")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        masterHttp.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException(apiError(text, response.code))
            require(text.toByteArray(Charsets.UTF_8).size <= 2 * 1024 * 1024) {
                "Recovered EOD dataset exceeds the 2 MB mobile response limit."
            }
            val json = JSONObject(text)
            val evidenceJson = json.optJSONObject("evidence") ?: JSONObject()
            val barsJson = json.optJSONArray("bars") ?: JSONArray()
            val limitationsJson = json.optJSONArray("limitations") ?: JSONArray()
            val bars = buildList {
                for (index in 0 until barsJson.length()) {
                    val item = barsJson.optJSONObject(index) ?: continue
                    add(AiDailyBar(
                        date = item.optString("date"),
                        open = item.optDouble("open"),
                        high = item.optDouble("high"),
                        low = item.optDouble("low"),
                        close = item.optDouble("close"),
                        volume = item.optLong("volume")
                    ))
                }
            }
            return AiStrategyBrief(
                provider = json.optString("provider"),
                dataMode = json.optString("data_mode"),
                exchange = json.optString("exchange"),
                symbol = json.optString("symbol"),
                firstTradingDate = json.optString("first_trading_date"),
                latestTradingDate = json.optString("latest_trading_date"),
                receivedAt = json.optString("received_at"),
                barCount = json.optInt("bar_count"),
                evidence = AiStrategyEvidence(
                    latestClose = evidenceJson.optDouble("latest_close"),
                    sma20 = evidenceJson.optionalDouble("sma20"),
                    sma50 = evidenceJson.optionalDouble("sma50"),
                    rsi14 = evidenceJson.optionalDouble("rsi14"),
                    atr14 = evidenceJson.optionalDouble("atr14"),
                    adx14 = evidenceJson.optionalDouble("adx14"),
                    donchianState = evidenceJson.optString("latest_close_vs_donchian"),
                    relativeVolume20 = evidenceJson.optionalDouble("relative_volume_vs_prior_20_sessions"),
                    return21 = evidenceJson.optionalDouble("return_21_sessions_fraction"),
                    return63 = evidenceJson.optionalDouble("return_63_sessions_fraction"),
                    trendContext = evidenceJson.optString("trend_context")
                ),
                bars = bars,
                ai = parseAiCompletion(json.optJSONObject("ai") ?: JSONObject()),
                dataRights = json.optString("data_rights"),
                limitations = buildList {
                    for (index in 0 until limitationsJson.length()) {
                        limitationsJson.optString(index).takeIf { it.isNotBlank() }?.let(::add)
                    }
                }
            )
        }
    }

    private fun parseAiCompletion(json: JSONObject) = AiCompletion(
        providerId = json.optString("provider_id"),
        credentialId = json.optString("credential_id").takeIf { it.isNotBlank() && it != "null" },
        model = json.optString("model"),
        content = json.optString("content"),
        fallbackUsed = json.optBoolean("fallback_used", false)
    )

    private fun JSONObject.optionalDouble(key: String): Double? {
        if (!has(key) || isNull(key)) return null
        val value = optDouble(key, Double.NaN)
        return value.takeIf { it.isFinite() }
    }

    private fun apiError(body: String, code: Int): String {
        val detail = runCatching { JSONObject(body).opt("detail") }.getOrNull()
        return when (detail) {
            is String -> detail.take(400)
            is JSONObject -> detail.optString("message").takeIf { it.isNotBlank() }
                ?: "Quant API request failed (HTTP $code)."
            else -> "Quant API request failed (HTTP $code)."
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
