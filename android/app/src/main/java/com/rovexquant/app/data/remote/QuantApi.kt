package com.rovexquant.app.data.remote

import com.rovexquant.app.data.model.AiChatMessage
import com.rovexquant.app.data.model.AiCompletion
import com.rovexquant.app.data.model.AiDailyBar
import com.rovexquant.app.data.model.AiProvider
import com.rovexquant.app.data.model.AiStrategyBrief
import com.rovexquant.app.data.model.AiStrategyEvidence
import com.rovexquant.app.data.model.EodMarketSnapshot
import com.rovexquant.app.data.model.EodStockMove
import com.rovexquant.app.data.model.IntelligenceFeed
import com.rovexquant.app.data.model.IntelligenceSource
import com.rovexquant.app.data.model.MarketEvent
import com.rovexquant.app.data.model.SectorImpact
import com.rovexquant.app.data.model.MarketIndexInstrument
import com.rovexquant.app.data.model.MarketEquityInstrument
import com.rovexquant.app.data.model.MarketIngestionStatus
import com.rovexquant.app.data.model.MacroResearchSummary
import com.rovexquant.app.data.model.MarketIntegrityDifference
import com.rovexquant.app.data.model.MarketIntegrityStatus
import com.rovexquant.app.data.model.MarketReconciliationReport
import com.rovexquant.app.data.model.HistoricalBackfillSummary
import com.rovexquant.app.data.model.DerivedBackfillSummary
import com.rovexquant.app.data.model.MacroSeriesObservation
import com.rovexquant.app.data.model.AmfiNavObservation
import com.rovexquant.app.data.model.EventStudyResult
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


    fun fetchIntelligenceEvents(
        session: QuantSession,
        query: String,
        timespan: String,
        limit: Int = 30
    ): IntelligenceFeed {
        require(query.isNotBlank() && query.length <= 160) { "Enter a news search query of at most 160 characters." }
        require(timespan in setOf("1d", "3d", "7d")) { "Choose a supported news time window." }
        require(limit in 1..50) { "News result limit is outside the supported range." }
        val encodedQuery = java.net.URLEncoder.encode(query.trim(), Charsets.UTF_8.name())
        val request = Request.Builder()
            .url("${session.baseUrl}/api/v1/app/intelligence/events?query=$encodedQuery&timespan=$timespan&limit=$limit")
            .header("Authorization", "Bearer ${session.accessToken}")
            .get()
            .build()
        masterHttp.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException(apiError(text, response.code))
            require(text.toByteArray(Charsets.UTF_8).size <= 1024 * 1024) {
                "Intelligence response exceeds the 1 MB mobile safety limit."
            }
            val json = JSONObject(text)
            val eventRows = json.optJSONArray("events") ?: JSONArray()
            val events = buildList {
                for (index in 0 until eventRows.length()) {
                    val item = eventRows.optJSONObject(index) ?: continue
                    val sourceRows = item.optJSONArray("sources") ?: JSONArray()
                    val impactRows = item.optJSONArray("sector_impacts") ?: JSONArray()
                    val tagRows = item.optJSONArray("event_tags") ?: JSONArray()
                    val sources = buildList {
                        for (sourceIndex in 0 until sourceRows.length()) {
                            val source = sourceRows.optJSONObject(sourceIndex) ?: continue
                            add(IntelligenceSource(
                                domain = source.optString("domain"),
                                url = source.optString("url"),
                                seenAt = source.optString("seen_at"),
                                language = source.optString("language"),
                                sourceCountry = source.optString("source_country")
                            ))
                        }
                    }
                    val impacts = buildList {
                        for (impactIndex in 0 until impactRows.length()) {
                            val impact = impactRows.optJSONObject(impactIndex) ?: continue
                            add(SectorImpact(
                                sector = impact.optString("sector"),
                                direction = impact.optString("direction"),
                                mechanism = impact.optString("mechanism")
                            ))
                        }
                    }
                    val tags = buildList {
                        for (tagIndex in 0 until tagRows.length()) {
                            tagRows.optString(tagIndex).takeIf { it.isNotBlank() }?.let(::add)
                        }
                    }
                    add(MarketEvent(
                        eventId = item.optString("event_id"),
                        headline = item.optString("headline"),
                        eventCategory = item.optString("event_category"),
                        eventTags = tags,
                        sourceArticleCount = item.optInt("source_article_count"),
                        independentSourceCount = item.optInt("independent_source_count"),
                        sources = sources,
                        classificationMethod = item.optString("classification_method"),
                        classificationConfidence = item.optDouble("classification_confidence", 0.0),
                        confidenceSemantics = item.optString("confidence_semantics"),
                        sectorImpacts = impacts,
                        stockMappingStatus = item.optString("stock_mapping_status"),
                        stockMappingNote = item.optString("stock_mapping_note"),
                        impactStatus = item.optString("impact_status")
                    ))
                }
            }
            val limitationsJson = json.optJSONArray("limitations") ?: JSONArray()
            val limitations = buildList {
                for (index in 0 until limitationsJson.length()) {
                    limitationsJson.optString(index).takeIf { it.isNotBlank() }?.let(::add)
                }
            }
            return IntelligenceFeed(
                source = json.optString("source"),
                retrievedAt = json.optString("retrieved_at"),
                query = json.optString("query"),
                timespan = json.optString("timespan"),
                articleCount = json.optInt("article_count"),
                eventCount = json.optInt("event_count"),
                events = events,
                limitations = limitations
            )
        }
    }

    fun fetchEventStudy(
        session: QuantSession,
        symbol: String,
        eventDate: String,
        benchmarkSymbol: String?
    ): EventStudyResult {
        require(symbol.matches(Regex("[A-Za-z0-9&._-]{1,40}"))) { "Enter a valid NSE symbol." }
        require(eventDate.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) { "Event date must use YYYY-MM-DD." }
        require(benchmarkSymbol == null || benchmarkSymbol.isBlank() ||
            benchmarkSymbol.matches(Regex("[A-Za-z0-9&._-]{1,40}"))) { "Enter a valid benchmark symbol." }
        val benchmarkQuery = benchmarkSymbol?.takeIf { it.isNotBlank() }?.let {
            "&benchmark_symbol=${java.net.URLEncoder.encode(it.trim(), Charsets.UTF_8.name())}"
        }.orEmpty()
        val encodedSymbol = java.net.URLEncoder.encode(symbol.trim().uppercase(), Charsets.UTF_8.name())
        val request = Request.Builder()
            .url("${session.baseUrl}/api/v1/research/event-study/nse/$encodedSymbol?event_date=$eventDate$benchmarkQuery")
            .header("Authorization", "Bearer ${session.accessToken}")
            .get()
            .build()
        masterHttp.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException(apiError(body, response.code))
            require(body.toByteArray(Charsets.UTF_8).size <= 512 * 1024) {
                "Event-study response exceeds the 512 KB safety limit."
            }
            val json = JSONObject(body)
            val limitationsJson = json.optJSONArray("limitations") ?: JSONArray()
            val limitations = buildList {
                for (index in 0 until limitationsJson.length()) {
                    limitationsJson.optString(index).takeIf { it.isNotBlank() }?.let(::add)
                }
            }
            fun optionalDouble(key: String): Double? =
                json.optDouble(key, Double.NaN).takeIf { it.isFinite() }
            return EventStudyResult(
                exchange = json.optString("exchange"),
                symbol = json.optString("symbol"),
                eventDate = json.optString("event_date"),
                anchorDate = json.optString("anchor_date"),
                firstPostEventDate = json.optString("first_post_event_date"),
                lastPostEventDate = json.optString("last_post_event_date"),
                preEventReturn = optionalDouble("pre_event_period_return"),
                cumulativeReturn = optionalDouble("cumulative_return"),
                marketAdjustedReturn = optionalDouble("market_adjusted_cumulative_return"),
                cumulativeAbnormalReturn = optionalDouble("cumulative_abnormal_return"),
                benchmarkSymbol = json.optString("benchmark_symbol").takeIf { it.isNotBlank() && it != "null" },
                benchmarkMatches = json.optInt("benchmark_observations_matched"),
                postBarsUsed = json.optInt("post_event_bars_used"),
                method = json.optString("method"),
                priceBasis = json.optString("price_basis", "RAW_OR_MIXED_CLOSE"),
                limitations = limitations
            )
        }
    }


    fun fetchMarketIntegrityStatus(session: QuantSession, exchange: String = "NSE"): MarketIntegrityStatus {
        require(exchange.uppercase() in setOf("NSE", "BSE")) { "Exchange must be NSE or BSE." }
        val request = Request.Builder()
            .url("${session.baseUrl}/api/v1/app/market/data-integrity?exchange=${exchange.uppercase()}")
            .header("Authorization", "Bearer ${session.accessToken}")
            .get()
            .build()
        masterHttp.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException(apiError(body, response.code))
            require(body.toByteArray(Charsets.UTF_8).size <= 512 * 1024) {
                "Market integrity response exceeds the 512 KB safety limit."
            }
            val json = JSONObject(body)
            val reportJson = json.optJSONObject("latest_report")
            val differencesJson = reportJson?.optJSONArray("differences") ?: JSONArray()
            val differences = buildList {
                for (index in 0 until differencesJson.length()) {
                    val item = differencesJson.optJSONObject(index) ?: continue
                    add(MarketIntegrityDifference(
                        kind = item.optString("kind"),
                        symbol = item.optString("symbol"),
                        series = item.optString("series"),
                        field = item.optString("field").takeIf { it.isNotBlank() && it != "null" },
                        officialValue = item.optString("official_value").takeIf { it.isNotBlank() && it != "null" },
                        storedValue = item.optString("stored_value").takeIf { it.isNotBlank() && it != "null" }
                    ))
                }
            }
            val report = reportJson?.let {
                MarketReconciliationReport(
                    tradingDate = it.optString("trading_date"),
                    status = it.optString("status"),
                    officialRows = it.optInt("official_rows"),
                    referenceRows = it.optInt("reference_rows"),
                    matchedRows = it.optInt("matched_rows"),
                    mismatchRows = it.optInt("mismatch_rows"),
                    missingReferenceRows = it.optInt("missing_reference_rows"),
                    missingOfficialRows = it.optInt("missing_official_rows"),
                    sourceUrl = it.optString("source_url"),
                    differences = differences
                )
            }
            val backfillJson = json.optJSONArray("backfills") ?: JSONArray()
            val backfills = buildList {
                for (index in 0 until backfillJson.length()) {
                    val item = backfillJson.optJSONObject(index) ?: continue
                    add(HistoricalBackfillSummary(
                        year = item.optInt("year"),
                        status = item.optString("status"),
                        rowsSeen = item.optLong("rows_seen"),
                        rowsUpserted = item.optLong("rows_upserted"),
                        completedAt = item.optString("completed_at").takeIf { it.isNotBlank() && it != "null" }
                    ))
                }
            }

            val derivedJson = json.optJSONArray("derived_backfills") ?: JSONArray()
            val derivedBackfills = buildList {
                for (index in 0 until derivedJson.length()) {
                    val item = derivedJson.optJSONObject(index) ?: continue
                    add(DerivedBackfillSummary(
                        year = item.optInt("year"),
                        datasetKind = item.optString("dataset_kind"),
                        status = item.optString("status"),
                        rowsSeen = item.optLong("rows_seen"),
                        rowsUpserted = item.optLong("rows_upserted"),
                        completedAt = item.optString("completed_at").takeIf { it.isNotBlank() && it != "null" }
                    ))
                }
            }
            return MarketIntegrityStatus(
                exchange = json.optString("exchange", exchange.uppercase()),
                status = json.optString("status", "UNKNOWN"),
                latestReport = report,
                backfills = backfills,
                derivedBackfills = derivedBackfills
            )
        }
    }

    fun fetchMacroResearch(session: QuantSession): MacroResearchSummary {
        val errors = mutableListOf<String>()
        fun getJson(path: String): JSONObject? {
            val request = Request.Builder()
                .url("${session.baseUrl}$path")
                .header("Authorization", "Bearer ${session.accessToken}")
                .get()
                .build()
            return try {
                masterHttp.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        errors += "$path: ${apiError(body, response.code)}"
                        null
                    } else {
                        require(body.toByteArray(Charsets.UTF_8).size <= 2 * 1024 * 1024) {
                            "Macro research response exceeds the 2 MB safety limit."
                        }
                        JSONObject(body)
                    }
                }
            } catch (failure: Exception) {
                errors += "$path: ${failure.message ?: "provider unavailable"}"
                null
            }
        }

        val worldBank = getJson("/api/v1/app/research/macro/world-bank")
        val worldBankRows = worldBank?.optJSONArray("series") ?: JSONArray()
        val series = buildList {
            for (index in 0 until worldBankRows.length()) {
                val item = worldBankRows.optJSONObject(index) ?: continue
                val latest = item.optJSONArray("observations")
                val latestRow = latest?.optJSONObject((latest.length() - 1).coerceAtLeast(0))
                val rawValue = latestRow?.optDouble("value", Double.NaN) ?: Double.NaN
                add(MacroSeriesObservation(
                    key = item.optString("key"),
                    label = item.optString("label"),
                    frequency = item.optString("frequency"),
                    unit = item.optString("unit"),
                    latestPeriod = latestRow?.optString("period")?.takeIf { !it.isNullOrBlank() && it != "null" },
                    latestValue = rawValue.takeIf { it.isFinite() },
                    source = item.optString("source")
                ))
            }
        }
        val amfi = getJson("/api/v1/app/research/macro/amfi-nav?limit=10")
        val navRows = amfi?.optJSONArray("observations") ?: JSONArray()
        val navs = buildList {
            for (index in 0 until navRows.length()) {
                val item = navRows.optJSONObject(index) ?: continue
                val nav = item.optDouble("nav", Double.NaN)
                if (!nav.isFinite()) continue
                add(AmfiNavObservation(
                    schemeCode = item.optInt("scheme_code"),
                    schemeName = item.optString("scheme_name"),
                    nav = nav,
                    navDate = item.optString("nav_date")
                ))
            }
        }
        val fred = getJson("/api/v1/app/research/macro/fred")
        val fredRows = fred?.optJSONArray("series")
        return MacroResearchSummary(
            retrievedAt = worldBank?.optString("retrieved_at")?.takeIf { it != "null" }.orEmpty(),
            worldBankSeries = series,
            amfiNavCount = amfi?.optInt("result_count") ?: 0,
            amfiExamples = navs,
            fredConfigured = fred?.optBoolean("configured", false) ?: false,
            fredSeriesCount = fredRows?.length() ?: 0,
            providerErrors = errors
        )
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


    fun saveAiProviderCredential(session: QuantSession, providerId: String, apiKey: String) {
        require(providerId in setOf("deepseek", "groq", "openrouter", "gemini", "openai")) {
            "Unsupported AI provider."
        }
        require(apiKey.length in 20..4096) { "Provider API key length is invalid." }
        val body = JSONObject().put("api_key", apiKey)
        val request = Request.Builder()
            .url("${session.baseUrl}/api/v1/app/ai/providers/$providerId/credential")
            .header("Authorization", "Bearer ${session.accessToken}")
            .put(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        masterHttp.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException(apiError(text, response.code))
        }
    }

    fun saveUpstoxAnalyticsToken(session: QuantSession, token: String) {
        require(token.length in 20..4096) { "Upstox Analytics Token length is invalid." }
        val body = JSONObject().put("token", token)
        val request = Request.Builder()
            .url("${session.baseUrl}/api/v1/app/integrations/upstox/analytics-token")
            .header("Authorization", "Bearer ${session.accessToken}")
            .put(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        masterHttp.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException(apiError(text, response.code))
        }
    }

    fun fetchUpstoxConfigured(session: QuantSession): Boolean {
        val request = Request.Builder()
            .url("${session.baseUrl}/api/v1/app/integrations/upstox/status")
            .header("Authorization", "Bearer ${session.accessToken}")
            .get()
            .build()
        masterHttp.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException(apiError(text, response.code))
            return JSONObject(text).optBoolean("configured", false)
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
                ai = json.optJSONObject("ai")?.let(::parseAiCompletion),
                aiError = json.optString("ai_error").takeIf { it.isNotBlank() && it != "null" },
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


    fun requestMarketIngestion(
        session: QuantSession,
        exchange: String = "nse",
        force: Boolean = false
    ): String {
        require(exchange in setOf("nse", "bse")) { "Unsupported exchange." }
        val request = Request.Builder()
            .url("${session.baseUrl}/api/v1/app/market-data/ingestion/collect/$exchange?force=$force")
            .header("Authorization", "Bearer ${session.accessToken}")
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()
        masterHttp.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException(apiError(text, response.code))
            return JSONObject(text).optString("status", "UNKNOWN")
        }
    }

    fun fetchMarketIngestionStatus(
        session: QuantSession,
        exchange: String = "nse"
    ): MarketIngestionStatus {
        require(exchange in setOf("nse", "bse")) { "Unsupported exchange." }
        val request = Request.Builder()
            .url("${session.baseUrl}/api/v1/app/market-data/ingestion/status?exchange=$exchange")
            .header("Authorization", "Bearer ${session.accessToken}")
            .get()
            .build()
        masterHttp.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException(apiError(text, response.code))
            val root = JSONObject(text)
            fun optionalString(key: String): String? =
                root.optString(key).takeIf { it.isNotBlank() && it != "null" }
            return MarketIngestionStatus(
                exchange = root.optString("exchange", exchange.uppercase()),
                status = root.optString("status", "UNKNOWN"),
                provider = optionalString("provider"),
                tradingDate = optionalString("trading_date"),
                rowsReceived = root.optInt("rows_received"),
                rowsSkipped = root.optInt("rows_skipped"),
                rowsUpserted = root.optInt("rows_upserted"),
                startedAt = optionalString("started_at"),
                completedAt = optionalString("completed_at"),
                errorCode = optionalString("error_code"),
                errorMessage = optionalString("error_message"),
                lastSuccessfulTradingDate = optionalString("last_successful_trading_date"),
                lastSuccessfulProvider = optionalString("last_successful_provider"),
                lastSuccessfulRows = root.optInt("last_successful_rows"),
                lastSuccessfulAt = optionalString("last_successful_at")
            )
        }
    }
    fun fetchLatestEodMarketSnapshot(
        session: QuantSession,
        exchange: String = "nse",
        limit: Int = 20
    ): EodMarketSnapshot {
        require(exchange in setOf("nse", "bse")) { "Unsupported exchange." }
        require(limit in 1..50) { "Snapshot limit must be between 1 and 50." }
        val request = Request.Builder()
            .url("${session.baseUrl}/api/v1/app/market-data/ingestion/snapshot/$exchange?limit=$limit")
            .header("Authorization", "Bearer ${session.accessToken}")
            .get()
            .build()
        masterHttp.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException(apiError(text, response.code))
            val root = JSONObject(text)
            val summary = root.getJSONObject("summary")
            return EodMarketSnapshot(
                provider = root.optString("provider", "unknown"),
                dataMode = root.optString("data_mode", "END_OF_DAY"),
                exchange = root.optString("exchange", exchange.uppercase()),
                marketScope = root.optString("market_scope", "NSE_EQ"),
                tradingDate = root.optString("trading_date"),
                totalRows = summary.optInt("total_rows"),
                equitySeriesRows = summary.optInt("equity_series_rows"),
                pricedSymbols = summary.optInt("priced_symbols"),
                advances = summary.optInt("advances"),
                declines = summary.optInt("declines"),
                unchanged = summary.optInt("unchanged"),
                topGainers = parseEodStockMoves(summary.optJSONArray("top_gainers")),
                topLosers = parseEodStockMoves(summary.optJSONArray("top_losers")),
                mostActiveByVolume = parseEodStockMoves(summary.optJSONArray("most_active_by_volume"))
            )
        }
    }

    private fun parseEodStockMoves(array: org.json.JSONArray?): List<EodStockMove> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(
                    EodStockMove(
                        symbol = item.optString("symbol"),
                        name = item.optString("name"),
                        series = if (item.isNull("series")) null else item.optString("series"),
                        close = item.optFiniteDouble("close"),
                        previousClose = item.optFiniteDouble("previous_close"),
                        change = item.optFiniteDouble("change"),
                        changePercent = item.optFiniteDouble("change_pct"),
                        volume = item.optLong("volume"),
                        turnover = item.optFiniteDouble("turnover")
                    )
                )
            }
        }
    }

    private fun JSONObject.optFiniteDouble(name: String): Double? {
        if (isNull(name)) return null
        return optDouble(name, Double.NaN).takeIf { it.isFinite() }
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

    fun fetchEquityInstruments(session: QuantSession): List<MarketEquityInstrument> {
        val request = Request.Builder()
            .url("${session.baseUrl}/api/v1/app/market/equity-instruments")
            .header("Authorization", "Bearer ${session.accessToken}")
            .get()
            .build()
        masterHttp.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException(apiError(text, response.code))
            require(text.toByteArray(Charsets.UTF_8).size <= 2 * 1024 * 1024) {
                "NSE equity instrument master exceeds the 2 MB mobile safety limit."
            }
            val records = JSONObject(text).optJSONArray("instruments")
                ?: throw IllegalStateException("Backend returned no NSE equity master.")
            return buildList {
                for (index in 0 until records.length()) {
                    val item = records.optJSONObject(index) ?: continue
                    val key = item.optString("instrument_key")
                    val symbol = item.optString("trading_symbol")
                    val name = item.optString("name")
                    val isin = item.optString("isin")
                    if (key.startsWith("NSE_EQ|INE") && symbol.isNotBlank() && name.isNotBlank()
                        && isin.isNotBlank() && key == "NSE_EQ|$isin") {
                        add(MarketEquityInstrument(key, name, symbol, isin))
                    }
                }
            }.sortedBy { it.tradingSymbol }
        }
    }
}
