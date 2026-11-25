package com.rovexquant.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.rovexquant.app.data.local.QuantSessionStore
import com.rovexquant.app.data.model.AiChatMessage
import com.rovexquant.app.data.model.AiCompletion
import com.rovexquant.app.data.model.AiDailyBar
import com.rovexquant.app.data.model.AiProvider
import com.rovexquant.app.data.model.AiTaskRoute
import com.rovexquant.app.data.model.AiStrategyBrief
import com.rovexquant.app.data.model.EodMarketSnapshot
import com.rovexquant.app.data.model.EodStockMove
import com.rovexquant.app.data.model.AiStrategyEvidence
import com.rovexquant.app.data.model.MarketIndexInstrument
import com.rovexquant.app.data.model.MarketEquityInstrument
import com.rovexquant.app.data.model.IntelligenceFeed
import com.rovexquant.app.data.model.IntelligenceSource
import com.rovexquant.app.data.model.MarketEvent
import com.rovexquant.app.data.model.SectorImpact
import com.rovexquant.app.data.model.MarketTick
import com.rovexquant.app.data.model.MarketIngestionStatus
import com.rovexquant.app.data.model.MarketIntegrityStatus
import com.rovexquant.app.data.model.MarketDataHealth
import com.rovexquant.app.data.model.DataHealthItem
import com.rovexquant.app.data.model.MacroResearchSummary
import com.rovexquant.app.data.model.MacroSeriesObservation
import com.rovexquant.app.data.model.AmfiNavObservation
import com.rovexquant.app.data.model.EventStudyResult
import com.rovexquant.app.data.model.NseMcpCatalog
import com.rovexquant.app.data.model.NseMcpResult
import com.rovexquant.app.data.model.NseMcpTool
import com.rovexquant.app.data.model.QuantSession
import com.rovexquant.app.data.model.TechnicalIndicators
import com.rovexquant.app.data.model.TechnicalIndicatorPeriods
import com.rovexquant.app.data.repository.MarketStream
import com.rovexquant.app.data.repository.QuantDataRepository
import com.rovexquant.app.domain.OhlcvBar
import com.rovexquant.app.domain.InstrumentIdentity
import com.rovexquant.app.domain.InstrumentType
import com.rovexquant.app.domain.MarketOhlcvSeries
import com.rovexquant.app.domain.OhlcvProvider
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.Instant
import com.rovexquant.app.feature.workspace.QuantWorkspaceViewModel
import org.json.JSONObject

/**
 * Deterministic test host compiled only into the debug variant.
 *
 * Workspace tests restore a pre-authenticated fake session so dashboard tests
 * never depend on typing credentials or contacting a backend. Login tests use
 * [QuantLoginTestActivity], which deliberately starts without a session.
 */
open class QuantTestActivity : MainActivity() {
    open val fakeRepository = FakeQuantRepository(restoreAuthenticatedSession = true)

    override fun createWorkspaceViewModelFactory(): ViewModelProvider.Factory =
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(QuantWorkspaceViewModel::class.java))
                return QuantWorkspaceViewModel(application, fakeRepository) as T
            }
        }
}

/** Separate host keeps login-form coverage independent of authenticated UI tests. */
class QuantLoginTestActivity : QuantTestActivity() {
    override val fakeRepository = FakeQuantRepository(restoreAuthenticatedSession = false)
}

class FakeQuantRepository(
    private val restoreAuthenticatedSession: Boolean
) : QuantDataRepository {
    var rejectLogin = false
    var dalalOsConfigured = false
    private val testSession = QuantSession("https://quant.test", "test-session-token")

    override suspend fun loadSavedConnection() =
        QuantSessionStore.SavedConnection(testSession.baseUrl, "quant-test")

    override suspend fun loadSession(): QuantSession? =
        testSession.takeIf { restoreAuthenticatedSession }

    override suspend fun saveSession(session: QuantSession, username: String) = Unit
    override suspend fun clearSession() = Unit

    override suspend fun login(baseUrl: String, username: String, password: String): QuantSession {
        if (rejectLogin || username != "quant-test" || password != "correct-password") {
            throw IllegalStateException("Test credentials rejected.")
        }
        require(baseUrl.startsWith("https://"))
        return testSession
    }

    override suspend fun validateSession(session: QuantSession): Boolean = true

    override suspend fun fetchIndexInstruments(session: QuantSession) = listOf(
        MarketIndexInstrument("NSE_INDEX|Nifty 50", "Nifty 50", "NIFTY 50"),
        MarketIndexInstrument("NSE_INDEX|Nifty Bank", "Nifty Bank", "NIFTY BANK")
    )

    override suspend fun fetchEquityInstruments(session: QuantSession) = listOf(
        MarketEquityInstrument("NSE_EQ|INE002A01018", "RELIANCE INDUSTRIES LTD", "RELIANCE", "INE002A01018"),
        MarketEquityInstrument("NSE_EQ|INE040A01034", "HDFC BANK LTD", "HDFCBANK", "INE040A01034"),
        MarketEquityInstrument("NSE_EQ|INE009A01021", "INFOSYS LIMITED", "INFY", "INE009A01021")
    )

    override suspend fun fetchNseMcpTools(session: QuantSession, server: String): List<NseMcpCatalog> {
        val servers = if (server == "both") listOf("bhavcopy", "cmmkt") else listOf(server)
        return servers.map { serverName ->
            val tools = if (serverName == "dalalos") listOf(
                NseMcpTool("get_company_exposures", "Company exposure fixture.", JSONObject("""{"type":"object","properties":{"query":{"type":"string"}}}"""), true),
                NseMcpTool("get_filing_extract", "Filing processing fixture.", JSONObject("""{"type":"object","properties":{"query":{"type":"string"},"news_id":{"type":"string"}}}"""), false, true, false)
            ) else emptyList()
            NseMcpCatalog(serverName, tools, if (serverName == "dalalos" && !dalalOsConfigured) "DalalOS API key is not configured." else null)
        }
    }

    override suspend fun saveDalalOsApiKey(session: QuantSession, apiKey: String) {
        require(apiKey.length in 20..4096)
        dalalOsConfigured = true
    }
    override suspend fun deleteDalalOsApiKey(session: QuantSession) { dalalOsConfigured = false }
    override suspend fun fetchDalalOsConfigured(session: QuantSession): Boolean = dalalOsConfigured

    override suspend fun fetchAiProviders(session: QuantSession) =
        listOf(AiProvider("groq", true, listOf("default")))

    override suspend fun fetchAiTaskRoutes(session: QuantSession): Map<String, AiTaskRoute> =
        mapOf("research" to AiTaskRoute("groq", "server-research-model", emptyList()))

    override suspend fun saveAiProviderCredential(session: QuantSession, providerId: String, apiKey: String) = Unit
    override suspend fun deleteAiProviderCredential(session: QuantSession, providerId: String) = Unit
    override suspend fun saveUpstoxAnalyticsToken(session: QuantSession, token: String) = Unit
    override suspend fun fetchUpstoxConfigured(session: QuantSession): Boolean = false
    override suspend fun requestMarketIngestion(
        session: QuantSession,
        exchange: String,
        force: Boolean
    ): String = "SUCCEEDED"

    override suspend fun fetchMarketIngestionStatus(
        session: QuantSession,
        exchange: String
    ) = MarketIngestionStatus(
        exchange = exchange.uppercase(),
        status = "SUCCEEDED",
        provider = "tejhq_eod",
        tradingDate = "2026-11-20",
        rowsReceived = 3000,
        rowsSkipped = 500,
        rowsUpserted = 2500,
        lastSuccessfulTradingDate = "2026-11-20",
        lastSuccessfulProvider = "tejhq_eod",
        lastSuccessfulRows = 2500,
        lastSuccessfulAt = "2026-11-21T04:30:00Z"
    )
    override suspend fun fetchLatestEodMarketSnapshot(
        session: QuantSession,
        exchange: String
    ): EodMarketSnapshot = EodMarketSnapshot(
        provider = "tejhq_eod",
        dataMode = "END_OF_DAY",
        exchange = exchange.uppercase(),
        marketScope = "NSE_EQ",
        tradingDate = "2026-11-20",
        totalRows = 3000,
        equitySeriesRows = 2500,
        pricedSymbols = 2450,
        advances = 1200,
        declines = 1100,
        unchanged = 150,
        topGainers = listOf(EodStockMove("FIXTURE", "Fixture Industries", "EQ", 110.0, 100.0, 10.0, 10.0, 100000, null)),
        topLosers = listOf(EodStockMove("LOSER", "Loser Industries", "EQ", 90.0, 100.0, -10.0, -10.0, 200000, null)),
        mostActiveByVolume = listOf(EodStockMove("ACTIVE", "Active Industries", "EQ", 100.0, 99.0, 1.0, 1.01, 500000, null))
    )

    override suspend fun fetchIntelligenceEvents(
        session: QuantSession, query: String, timespan: String
    ) = IntelligenceFeed(
        source = "GDELT_DOC_API",
        retrievedAt = "2026-11-22T10:00:00Z",
        query = query,
        timespan = timespan,
        articleCount = 2,
        eventCount = 1,
        events = listOf(MarketEvent(
            eventId = "event-fixture-001",
            headline = "Oil supply disruption raises crude prices",
            eventCategory = "COMMODITY_SUPPLY",
            eventTags = listOf("COMMODITY_SUPPLY"),
            sourceArticleCount = 2,
            independentSourceCount = 2,
            sources = listOf(IntelligenceSource("fixture.example", "https://fixture.example/oil", "20261122T090000Z", "English", "India")),
            classificationMethod = "DETERMINISTIC_KEYWORD_RULES",
            classificationConfidence = 0.64,
            confidenceSemantics = "Heuristic rule-match score; not a calibrated probability.",
            sectorImpacts = listOf(SectorImpact("AVIATION", "POTENTIAL_NEGATIVE", "Fuel costs may pressure operating margins.")),
            stockMappingStatus = "NOT_RESOLVED",
            stockMappingNote = "Company and instrument mapping is not yet enabled.",
            impactStatus = "HYPOTHESIS_REQUIRES_COMPANY_LEVEL_VERIFICATION"
        )),
        limitations = listOf("Global news discovery is not primary-source verification.")
    )

    override suspend fun fetchInstrumentOhlcv(
        instrument: InstrumentIdentity,
        from: LocalDate,
        to: LocalDate
    ): MarketOhlcvSeries {
        val bars = buildList {
            var date = if (instrument.interval.isIntraday) from else to.minusDays(160)
            var index = 0
            while (!date.isAfter(to)) {
                if (date.dayOfWeek.value <= 5) {
                    val intradayStepMinutes = when (instrument.interval.apiUnit) {
                        "hours" -> (instrument.interval.apiInterval ?: 1) * 60
                        "minutes" -> instrument.interval.apiInterval ?: 5
                        else -> 0
                    }
                    val observations = if (instrument.interval.isIntraday) 30 else 1
                    repeat(observations) { candleIndex ->
                        val close = 100.0 + index * 0.25
                        val open = close - 0.2
                        val timestamp = if (instrument.interval.isIntraday) {
                            OffsetDateTime.of(
                                date.year, date.monthValue, date.dayOfMonth, 9, 15, 0, 0,
                                ZoneOffset.ofHoursMinutes(5, 30)
                            ).plusMinutes((candleIndex * intradayStepMinutes).toLong())
                        } else {
                            OffsetDateTime.of(
                                date.year, date.monthValue, date.dayOfMonth, 15, 30, 0, 0,
                                ZoneOffset.ofHoursMinutes(5, 30)
                            )
                        }
                        add(
                            OhlcvBar(
                                timestamp = timestamp,
                                open = open,
                                high = close + 0.5,
                                low = open - 0.5,
                                close = close,
                                volume = if (instrument.instrumentType == InstrumentType.EQUITY) 100_000L + index else 0L
                            )
                        )
                        index++
                    }
                }
                date = date.plusDays(1)
            }
        }
        return MarketOhlcvSeries(
            instrument = instrument,
            bars = bars,
            retrievedAt = Instant.parse("2026-11-24T10:00:00Z"),
            volumeAvailable = instrument.instrumentType == InstrumentType.EQUITY,
            adjustmentStatus = if (instrument.interval.isIntraday) {
                "Deterministic test fixture · raw intraday OHLCV"
            } else if (instrument.instrumentType == InstrumentType.EQUITY) {
                "Deterministic test fixture · raw EOD"
            } else {
                "Deterministic test fixture · price-return index OHLC"
            }
        )
    }

    override suspend fun fetchTejHqOhlcv(
        exchange: String, symbol: String, from: LocalDate, to: LocalDate
    ): List<OhlcvBar> = emptyList()

    override suspend fun fetchTechnicalIndicators(
        bars: List<OhlcvBar>,
        startIndex: Int,
        periods: TechnicalIndicatorPeriods
    ): TechnicalIndicators {
        val count = bars.size
        fun series(period: Int, value: (Int) -> Double): List<Double?> =
            List(count) { index -> if (index + 1 < period) null else value(index) }
        return TechnicalIndicators(
            startIndex = startIndex,
            barCount = count,
            sma = series(periods.sma) { bars[it].close },
            ema = series(periods.ema) { bars[it].close },
            rsi = series(periods.rsi + 1) { 50.0 },
            macdLine = series(26) { 0.5 },
            macdSignal = series(34) { 0.4 },
            macdHistogram = series(34) { 0.1 },
            bollingerUpper = series(periods.bollinger) { bars[it].close + 1.0 },
            bollingerMiddle = series(periods.bollinger) { bars[it].close },
            bollingerLower = series(periods.bollinger) { bars[it].close - 1.0 },
            atr = series(periods.atr + 1) { 1.0 },
            adx = series(periods.adx * 2) { 25.0 }
        )
    }

    override suspend fun fetchEventStudy(
        session: QuantSession, symbol: String, eventDate: String, benchmarkSymbol: String?
    ) = EventStudyResult(
        exchange = "NSE", symbol = symbol, eventDate = eventDate, anchorDate = "2026-11-20",
        firstPostEventDate = "2026-11-23", lastPostEventDate = "2026-11-23",
        preEventReturn = 0.02, cumulativeReturn = 0.015,
        marketAdjustedReturn = null, cumulativeAbnormalReturn = null,
        benchmarkSymbol = benchmarkSymbol, benchmarkMatches = 0, postBarsUsed = 1,
        method = "UNADJUSTED_CLOSE_TO_CLOSE_EVENT_WINDOW",
        priceBasis = "RAW_CLOSE",
        corporateActions = emptyList(),
        limitations = listOf("Deterministic test fixture; not live data.")
    )

    override suspend fun fetchMacroResearch(session: QuantSession) = MacroResearchSummary(
        retrievedAt = "2026-11-23T00:00:00Z",
        worldBankSeries = listOf(
            MacroSeriesObservation("gdp_growth", "GDP growth (annual %)", "ANNUAL", "PERCENT", "2024", 6.5, "WORLD_BANK_INDICATORS_V2"),
            MacroSeriesObservation("inflation", "Consumer price inflation (annual %)", "ANNUAL", "PERCENT", "2024", 4.9, "WORLD_BANK_INDICATORS_V2")
        ),
        amfiNavCount = 1,
        amfiExamples = listOf(AmfiNavObservation(119551, "Fixture Equity Fund", 123.45, "21-Nov-2026")),
        fredConfigured = false,
        fredSeriesCount = 0,
        providerErrors = emptyList()
    )

    override suspend fun fetchMarketIntegrityStatus(session: QuantSession, exchange: String) =
        MarketIntegrityStatus(
            exchange = exchange.uppercase(),
            status = "NOT_RUN",
            latestReport = null,
            backfills = emptyList(),
            derivedBackfills = emptyList()
        )

    override suspend fun fetchMarketDataHealth(session: QuantSession, exchange: String) =
        MarketDataHealth(
            exchange = exchange.uppercase(),
            overallStatus = "PARTIAL",
            generatedAt = "fixture",
            marketDataMode = "END_OF_DAY_ONLY",
            eod = DataHealthItem("EOD", "NO_DATA", null, null, "UNKNOWN", 0, "No fixture data"),
            macroProviders = emptyList(),
            intelligence = DataHealthItem("Intelligence", "NOT_RUN", null, null, "UNKNOWN", 0, null),
            derivedBackfills = emptyList(),
            reconciliation = DataHealthItem("Reconciliation", "NOT_RUN", null, null, "UNKNOWN", 0, null),
            notes = emptyList()
        )

    override suspend fun sendAiMessage(
        session: QuantSession,
        providerId: String?,
        model: String?,
        messages: List<AiChatMessage>,
        credentialId: String?,
        fallbackProviders: List<String>,
        taskType: String
    ) = AiCompletion(
        providerId ?: "groq", credentialId, model ?: "server-research-model",
        "Deterministic AI fixture response.", false, taskType
    )

    override suspend fun requestAiStrategyBrief(
        session: QuantSession,
        providerId: String?,
        model: String?,
        symbol: String,
        exchange: String,
        fromDate: String,
        toDate: String,
        strategyContext: String,
        credentialId: String?,
        fallbackProviders: List<String>,
        automaticTaskRouting: Boolean
    ) = AiStrategyBrief(
        provider = "tejhq_eod",
        dataMode = "END_OF_DAY",
        exchange = exchange,
        symbol = symbol,
        firstTradingDate = fromDate,
        latestTradingDate = toDate,
        receivedAt = "2026-11-22T10:00:00Z",
        barCount = 60,
        evidence = AiStrategyEvidence(
            latestClose = 2500.0,
            sma20 = 2450.0,
            sma50 = 2400.0,
            rsi14 = 58.0,
            atr14 = 45.0,
            adx14 = 22.0,
            donchianState = "INSIDE_PRIOR_20_DAY_RANGE",
            relativeVolume20 = 1.1,
            return21 = 0.04,
            return63 = 0.09,
            trendContext = "CLOSE_ABOVE_SMA20_AND_SMA50"
        ),
        bars = listOf(AiDailyBar(toDate, 2480.0, 2520.0, 2470.0, 2500.0, 100000)),
        ai = AiCompletion(providerId, credentialId, model, "Deterministic EOD strategy fixture response.", false),
        dataRights = "Private personal research only.",
        limitations = listOf("EOD daily bars only.")
    )

    override suspend fun callNseMcpTool(
        session: QuantSession,
        server: String,
        tool: String,
        argumentsJson: String,
        confirmNonReadOnly: Boolean
    ): NseMcpResult {
        if (tool == "get_filing_extract") require(confirmNonReadOnly) { "Explicit quota confirmation required." }
        return NseMcpResult(
            if (server == "dalalos") "dalalos_mcp" else "test",
            if (server == "dalalos") "SOURCE_ATTRIBUTED_RESEARCH" else "TEST",
            "Dataset-specific",
            "2026-11-24",
            JSONObject().put("tool", tool).put("source", "deterministic-fixture").toString(2)
        )
    }

    override fun createMarketStream(
        session: QuantSession,
        onStatus: (String) -> Unit,
        onTicks: (List<MarketTick>) -> Unit
    ): MarketStream = object : MarketStream {
        override fun start(instrumentKeys: List<String>) {
            onStatus("Connected · deterministic UI test")
        }

        override fun stop() = Unit
    }
}
