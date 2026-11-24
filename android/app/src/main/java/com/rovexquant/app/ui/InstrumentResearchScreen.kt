package com.rovexquant.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rovexquant.app.data.local.TejHqOhlcvStore
import com.rovexquant.app.data.local.TejHqOhlcvSyncManager
import com.rovexquant.app.data.repository.QuantDataRepository
import com.rovexquant.app.data.model.TechnicalIndicators
import com.rovexquant.app.data.model.InstrumentQuoteSnapshot
import com.rovexquant.app.data.model.IntelligenceFeed
import com.rovexquant.app.data.model.QuantSession
import com.rovexquant.app.domain.InstrumentCatalog
import com.rovexquant.app.domain.OhlcvInterval
import com.rovexquant.app.domain.TechnicalIndicatorCalculator
import com.rovexquant.app.domain.ChartSeriesMapper
import com.rovexquant.app.domain.ChartSeriesData
import com.rovexquant.app.domain.MarketOhlcvSeries
import com.rovexquant.app.domain.OhlcvBar
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun InstrumentResearchScreen(
    symbol: String,
    displayName: String,
    repository: QuantDataRepository,
    session: QuantSession? = null,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var quoteSnapshot by remember(symbol) { mutableStateOf<InstrumentQuoteSnapshot?>(null) }
    var quoteLoading by remember(symbol) { mutableStateOf(false) }
    var quoteError by remember(symbol) { mutableStateOf<String?>(null) }
    var intelligenceFeed by remember(symbol) { mutableStateOf<IntelligenceFeed?>(null) }
    var intelligenceLoading by remember(symbol) { mutableStateOf(false) }
    var intelligenceError by remember(symbol) { mutableStateOf<String?>(null) }
    var selectedIntervalKey by rememberSaveable(symbol) { mutableStateOf(OhlcvInterval.DAILY.key) }
    val selectedInterval = remember(selectedIntervalKey) {
        OhlcvInterval.entries.first { it.key == selectedIntervalKey }
    }
    val identity = remember(symbol, selectedInterval) {
        runCatching { InstrumentCatalog.resolve(symbol, "NSE", selectedInterval) }.getOrNull()
    }
    val identityError = remember(symbol, selectedInterval) {
        runCatching { InstrumentCatalog.resolve(symbol, "NSE", selectedInterval) }.exceptionOrNull()?.message
    }
    var series by remember(symbol, selectedIntervalKey) { mutableStateOf<MarketOhlcvSeries?>(null) }
    var chartData by remember(symbol, selectedIntervalKey) { mutableStateOf<ChartSeriesData?>(null) }
    var loading by remember(symbol, selectedIntervalKey) { mutableStateOf(true) }
    var refreshing by remember(symbol, selectedIntervalKey) { mutableStateOf(false) }
    var error by remember(symbol, selectedIntervalKey) { mutableStateOf<String?>(null) }
    var range by rememberSaveable(symbol, selectedIntervalKey) {
        mutableStateOf(if (selectedInterval.isIntraday) "5D" else "1Y")
    }
    var volume by rememberSaveable(symbol, selectedIntervalKey) { mutableStateOf(true) }
    var showSma by rememberSaveable(symbol, selectedIntervalKey) { mutableStateOf(false) }
    var showEma by rememberSaveable(symbol, selectedIntervalKey) { mutableStateOf(false) }
    var showBollinger by rememberSaveable(symbol, selectedIntervalKey) { mutableStateOf(false) }
    var showRsi by rememberSaveable(symbol, selectedIntervalKey) { mutableStateOf(false) }
    var showMacd by rememberSaveable(symbol, selectedIntervalKey) { mutableStateOf(false) }
    var showAtr by rememberSaveable(symbol, selectedIntervalKey) { mutableStateOf(false) }
    var showAdx by rememberSaveable(symbol, selectedIntervalKey) { mutableStateOf(false) }
    var indicators by remember(symbol, selectedIntervalKey) { mutableStateOf<TechnicalIndicators?>(null) }
    var indicatorsLoading by remember(symbol, selectedIntervalKey) { mutableStateOf(false) }
    var indicatorsError by remember(symbol, selectedIntervalKey) { mutableStateOf<String?>(null) }
    var indicatorsSource by remember(symbol, selectedIntervalKey) { mutableStateOf("BACKEND") }
    var indicatorsNotice by remember(symbol, selectedIntervalKey) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun refreshSeries(fullHistory: Boolean = false) {
        val selected = identity ?: return
        refreshing = true
        error = null
        try {
            val today = LocalDate.now(ZoneId.of("Asia/Kolkata"))
            val from = when {
                selected.interval.isIntraday -> today.minusDays(selected.interval.maximumHistoryDays)
                fullHistory -> today.minusDays(6200)
                else -> today.minusYears(3)
            }
            val result = withContext(Dispatchers.IO) {
                val fetched = TejHqOhlcvSyncManager.fetchAndSave(
                    context = context,
                    repository = repository,
                    instrument = selected,
                    from = from,
                    to = today,
                    incremental = selected.interval == OhlcvInterval.DAILY && !fullHistory
                )
                fetched to ChartSeriesMapper.map(fetched)
            }
            series = result.first
            chartData = result.second
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = "Historical data unavailable. Tap retry to check the authorized source."
        } finally {
            refreshing = false
            loading = false
        }
    }

    LaunchedEffect(identity, session) {
        quoteSnapshot = null
        quoteError = null
        val selected = identity
        val activeSession = session
        if (selected == null || activeSession == null) return@LaunchedEffect
        quoteLoading = true
        try {
            quoteSnapshot = repository.fetchInstrumentQuote(activeSession, selected.canonicalSymbol)
            if (quoteSnapshot == null) quoteError = "No quote snapshot returned by the configured provider."
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            quoteError = "Quote data delayed. Try again later."
        } finally {
            quoteLoading = false
        }
    }

    LaunchedEffect(identity, session) {
        intelligenceFeed = null
        intelligenceError = null
        val selected = identity
        val activeSession = session
        if (selected == null || activeSession == null) return@LaunchedEffect
        intelligenceLoading = true
        try {
            val query = "\"" + selected.canonicalSymbol + "\" OR \"" + displayName.take(70) + "\""
            intelligenceFeed = repository.fetchIntelligenceEvents(activeSession, query, "7d")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            intelligenceError = "Recent news is temporarily unavailable."
        } finally {
            intelligenceLoading = false
        }
    }

    LaunchedEffect(identity) {
        series = null
        chartData = null
        loading = true
        error = null
        val selected = identity
        if (selected == null) {
            error = identityError ?: "Unsupported instrument identity."
            loading = false
            return@LaunchedEffect
        }
        val cachedResult = withContext(Dispatchers.IO) {
            val cached = runCatching { TejHqOhlcvStore.load(context, selected) }.getOrNull()
            if (cached.isNullOrEmpty()) {
                null
            } else {
                runCatching {
                    val lastSync = TejHqOhlcvStore.lastSyncedMillis(context, selected)
                    val cachedSeries = MarketOhlcvSeries(
                        instrument = selected,
                        bars = cached,
                        retrievedAt = lastSync.takeIf { it > 0L }?.let(Instant::ofEpochMilli),
                        volumeAvailable = selected.instrumentType == com.rovexquant.app.domain.InstrumentType.EQUITY,
                        adjustmentStatus = when (selected.provider) {
                            com.rovexquant.app.domain.OhlcvProvider.TEJHQ_EOD -> "Raw end-of-day bhavcopy prices; corporate-action adjustment not applied"
                            com.rovexquant.app.domain.OhlcvProvider.NSE_INDICES_PORTAL -> "Price Return Index OHLC; dividends are not included"
                            com.rovexquant.app.domain.OhlcvProvider.UPSTOX_V3_INTRADAY -> "Raw intraday OHLCV; corporate-action adjustment not applied"
                        }
                    )
                    cachedSeries to ChartSeriesMapper.map(cachedSeries)
                }
            }
        }
        if (cachedResult != null) {
            cachedResult.onSuccess { (cachedSeries, mapped) ->
                series = cachedSeries
                chartData = mapped
                loading = false
            }.onFailure { failure ->
                error = failure.message ?: "Cached chart data failed validation."
            }
        }
        refreshSeries()
    }

    val bars = series?.bars.orEmpty()
    val visible = remember(bars, range) {
        val latestTradingDate = bars.lastOrNull()?.timestamp
            ?.atZoneSameInstant(ZoneId.of("Asia/Kolkata"))?.toLocalDate()
            ?: LocalDate.now(ZoneId.of("Asia/Kolkata"))
        val cutoff = when (range) {
            "1D" -> latestTradingDate
            "5D" -> latestTradingDate.minusDays(7)
            "1M" -> latestTradingDate.minusMonths(1)
            "3M" -> latestTradingDate.minusMonths(3)
            "6M" -> latestTradingDate.minusMonths(6)
            "1Y" -> latestTradingDate.minusYears(1)
            "3Y" -> latestTradingDate.minusYears(3)
            else -> null
        }
        if (cutoff == null) bars else bars.filter {
            !it.timestamp.atZoneSameInstant(ZoneId.of("Asia/Kolkata")).toLocalDate().isBefore(cutoff)
        }
    }
    val chartVisibleData by produceState<ChartSeriesData?>(initialValue = null, chartData, visible) {
        val source = chartData
        val firstDate = visible.firstOrNull()
            ?.timestamp?.atZoneSameInstant(ZoneId.of("Asia/Kolkata"))?.toLocalDate()?.toString()
        val lastDate = visible.lastOrNull()
            ?.timestamp?.atZoneSameInstant(ZoneId.of("Asia/Kolkata"))?.toLocalDate()?.toString()
        value = if (source == null || firstDate == null || lastDate == null) {
            null
        } else {
            withContext(Dispatchers.Default) {
                source.copy(
                    candles = source.candles.filter { it.time.take(10) >= firstDate && it.time.take(10) <= lastDate },
                    volumeBars = source.volumeBars?.filter { it.time.take(10) >= firstDate && it.time.take(10) <= lastDate }
                )
            }
        }
    }
    val latest = visible.lastOrNull()
    val visibleStartIndex = remember(bars, visible) {
        val firstDate = visible.firstOrNull()?.timestamp
            ?.atZoneSameInstant(ZoneId.of("Asia/Kolkata"))?.toLocalDate()
        if (firstDate == null) 0 else bars.indexOfFirst {
            it.timestamp.atZoneSameInstant(ZoneId.of("Asia/Kolkata")).toLocalDate() == firstDate
        }.coerceAtLeast(0)
    }

    LaunchedEffect(bars) {
        if (bars.isEmpty()) {
            indicators = null
            indicatorsError = null
            indicatorsNotice = null
            indicatorsLoading = false
            return@LaunchedEffect
        }
        val windowSize = minOf(10_000, bars.size)
        val startIndex = bars.size - windowSize
        val requestBars = bars.subList(startIndex, bars.size).toList()
        indicators = null
        indicatorsLoading = true
        indicatorsError = null
        indicatorsNotice = null
        try {
            // Compute locally first so chart controls become usable immediately,
            // even if the remote backend is sleeping, offline or rate-limited.
            val local = withContext(Dispatchers.Default) {
                TechnicalIndicatorCalculator.calculate(requestBars, startIndex)
            }
            indicators = local
            indicatorsSource = "LOCAL"
            try {
                val remote = withContext(Dispatchers.IO) {
                    repository.fetchTechnicalIndicators(requestBars, startIndex)
                }
                require(remote.startIndex == startIndex && remote.barCount == requestBars.size) {
                    "Backend indicator coverage does not match the requested OHLC window."
                }
                indicators = remote
                indicatorsSource = "BACKEND"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (backendFailure: Exception) {
                indicatorsNotice = backendFailure.message ?: "Backend indicator service unavailable."
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            indicators = null
            indicatorsError = failure.message ?: "Local indicator calculation failed."
        } finally {
            indicatorsLoading = false
        }
    }

    Column(
        Modifier.fillMaxSize().background(Ink).padding(16.dp)
            .verticalScroll(rememberScrollState())
            .testTag("instrument.research.screen")
    ) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Back to Pulse") }
            Spacer(Modifier.weight(1f))
            TextButton(
                enabled = identity != null && !refreshing,
                onClick = { scope.launch { refreshSeries() } }
            ) {
                Text(if (refreshing) "Refreshing…" else "↻ Refresh")
            }
        }
        Text(displayName.ifBlank { symbol }, color = MaterialTheme.colorScheme.onBackground,
            fontSize = 25.sp, fontWeight = FontWeight.Bold)
        if (!displayName.equals(symbol, ignoreCase = true)) {
            Text(symbol, color = Muted, fontSize = 11.sp)
        }
        Spacer(Modifier.height(8.dp))
        if (series != null) {
            Surface(
                color = androidx.compose.ui.graphics.Color(0xFF18352F),
                shape = RoundedCornerShape(20.dp)
            ) {
                Text(
                    "${series!!.sourceLabel}  ·  ${series!!.instrument.interval.key.uppercase()}",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    color = androidx.compose.ui.graphics.Color(0xFF8FE0B7),
                    fontSize = 10.sp
                )
            }
            Text(
                series!!.adjustmentStatus,
                color = Muted,
                fontSize = 10.sp,
                modifier = Modifier.padding(top = 5.dp)
            )
            series!!.providerNote?.let { Text(it, color = Muted, fontSize = 9.sp) }
            series!!.retrievedAt?.let {
                Text(it.relativeAgeLabel(),
                    color = Muted, fontSize = 9.sp)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState())
                .testTag("instrument.chart.timeframes"),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            (listOf(OhlcvInterval.DAILY) + OhlcvInterval.intraday).forEach { timeframe ->
                FilterChip(
                    enabled = !refreshing,
                    selected = selectedInterval == timeframe,
                    onClick = { selectedIntervalKey = timeframe.key },
                    label = { Text(timeframe.displayLabel) },
                    modifier = Modifier.testTag("instrument.chart.timeframe.${timeframe.key}")
                )
            }
        }
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            val availableRanges = if (selectedInterval.isIntraday) {
                if (selectedInterval.maximumHistoryDays <= 30L) listOf("1D", "5D", "1M")
                else listOf("1D", "5D", "1M", "3M")
            } else listOf("1M", "3M", "6M", "1Y", "3Y", "ALL")
            availableRanges.forEach { period ->
                FilterChip(
                    enabled = !refreshing,
                    selected = range == period,
                    onClick = {
                        range = period
                        if (period == "ALL" && selectedInterval == OhlcvInterval.DAILY) {
                            val earliest = series?.bars?.firstOrNull()?.timestamp?.toLocalDate()
                            val target = LocalDate.now(ZoneId.of("Asia/Kolkata")).minusDays(6190)
                            if (earliest == null || earliest.isAfter(target)) {
                                scope.launch { refreshSeries(fullHistory = true) }
                            }
                        }
                    },
                    label = { Text(period) }
                )
            }
        }
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState())
                .testTag("instrument.chart.indicator.controls"),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            if (series?.volumeAvailable == true) {
                FilterChip(
                    modifier = Modifier.testTag("instrument.chart.volume.toggle"),
                    selected = volume,
                    onClick = { volume = !volume },
                    label = { Text("Volume") }
                )
            }
            FilterChip(
                modifier = Modifier.testTag("instrument.chart.indicator.sma.toggle"),
                enabled = indicators != null,
                selected = showSma,
                onClick = { showSma = !showSma },
                label = { Text("SMA 20") }
            )
            FilterChip(
                modifier = Modifier.testTag("instrument.chart.indicator.ema.toggle"),
                enabled = indicators != null,
                selected = showEma,
                onClick = { showEma = !showEma },
                label = { Text("EMA 20") }
            )
            FilterChip(
                modifier = Modifier.testTag("instrument.chart.indicator.bollinger.toggle"),
                enabled = indicators != null,
                selected = showBollinger,
                onClick = { showBollinger = !showBollinger },
                label = { Text("Bollinger") }
            )
            FilterChip(
                modifier = Modifier.testTag("instrument.chart.indicator.rsi.toggle"),
                enabled = indicators != null,
                selected = showRsi,
                onClick = { showRsi = !showRsi },
                label = { Text("RSI") }
            )
            FilterChip(
                modifier = Modifier.testTag("instrument.chart.indicator.macd.toggle"),
                enabled = indicators != null,
                selected = showMacd,
                onClick = { showMacd = !showMacd },
                label = { Text("MACD") }
            )
            FilterChip(
                modifier = Modifier.testTag("instrument.chart.indicator.atr.toggle"),
                enabled = indicators != null,
                selected = showAtr,
                onClick = { showAtr = !showAtr },
                label = { Text("ATR") }
            )
            FilterChip(
                modifier = Modifier.testTag("instrument.chart.indicator.adx.toggle"),
                enabled = indicators != null,
                selected = showAdx,
                onClick = { showAdx = !showAdx },
                label = { Text("ADX") }
            )
        }
        when {
            indicatorsLoading -> Text(
                if (indicators != null) "Local indicators ready · syncing backend calculation…"
                else "Calculating indicators…",
                color = Muted, fontSize = 9.sp
            )
            indicatorsError != null -> GracefulErrorState("Indicators unavailable. Refresh the chart to retry.")
            indicators != null -> Text(
                "${if (indicatorsSource == "BACKEND") "Backend" else "Offline local fallback"} indicators · ${indicators!!.barCount} bars · " +
                    "${bars.getOrNull(indicators!!.startIndex)?.timestamp?.toLocalDate()?.toString() ?: "coverage pending"} onward" +
                    (indicatorsNotice?.let { " · backend unavailable" } ?: ""),
                color = if (indicatorsSource == "BACKEND") Muted else Gold,
                fontSize = 9.sp
            )
        }
        if (series?.volumeAvailable == false) {
            Text(
                "Traded volume is not published for this index series.",
                color = Muted,
                fontSize = 10.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        if (indicators != null && visibleStartIndex < indicators!!.startIndex) {
            Text(
                "Indicator coverage begins at ${bars.getOrNull(indicators!!.startIndex)?.timestamp?.toLocalDate() ?: "the latest 10,000 bars"}; older candles have no indicator values.",
                color = Muted,
                fontSize = 9.sp
            )
        }
        if (range == "ALL" && bars.isNotEmpty()) {
            Text(
                if (refreshing) "Loading extended history…" else "All locally available bars · earliest ${bars.first().timestamp.toLocalDate()}",
                color = Muted, fontSize = 9.sp
            )
        }
        Spacer(Modifier.height(10.dp))
        when {
            loading -> CircularProgressIndicator()
            visible.isEmpty() -> EmptyCandlestickChart(
                message = "Connect an authorized data feed to render validated candles.",
                actionLabel = if (refreshing) "Loading…" else "Retry data",
                onAction = { if (!refreshing && identity != null) scope.launch { refreshSeries() } }
            )
            else -> TerminalCard {
                Text(
                    "${visible.size} bars  ·  ${visible.first().timestamp.toLocalDate()} → ${visible.last().timestamp.toLocalDate()}",
                    color = Muted, fontSize = 10.sp
                )
                chartVisibleData?.let { validated ->
                    ValidatedCandlestickChart(
                        data = validated,
                        indicators = indicators,
                        indicatorSourceOffset = visibleStartIndex,
                        showVolume = volume,
                        showSma = showSma,
                        showEma = showEma,
                        showBollinger = showBollinger,
                        showRsi = showRsi,
                        showMacd = showMacd,
                        showAtr = showAtr,
                        showAdx = showAdx
                    )
                } ?: CircularProgressIndicator()
                latest?.let {
                    Text(
                        "Close  ${"%.2f".format(it.close)}",
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (series?.volumeAvailable == true) {
                        Text("Volume  ${it.volume}", color = Muted, fontSize = 10.sp)
                    }
                }
            }
        }
        InstrumentOverviewPanel(
            symbol = identity?.canonicalSymbol ?: symbol,
            isIndex = identity?.instrumentType == com.rovexquant.app.domain.InstrumentType.BROAD_MARKET_INDEX ||
                identity?.instrumentType == com.rovexquant.app.domain.InstrumentType.SECTOR_INDEX,
            bars = bars,
            visibleBars = visible,
            interval = selectedInterval,
            quote = quoteSnapshot,
            quoteLoading = quoteLoading,
            quoteError = quoteError,
            intelligence = intelligenceFeed,
            intelligenceLoading = intelligenceLoading,
            intelligenceError = intelligenceError,
            onOpenUrl = { url -> uriHandler.openUri(url) }
        )
        error?.let { GracefulErrorState("Historical data delayed. Tap retry to check again.", onRetry = { scope.launch { refreshSeries() } }, modifier = Modifier.padding(top = 8.dp)) }
    }
}

internal fun simpleMovingAverage(bars: List<OhlcvBar>, period: Int): List<Double?> {
    require(period > 0) { "Moving-average period must be positive." }
    val result = MutableList<Double?>(bars.size) { null }
    var rollingSum = 0.0
    for (index in bars.indices) {
        rollingSum += bars[index].close
        if (index >= period) rollingSum -= bars[index - period].close
        if (index >= period - 1) result[index] = rollingSum / period
    }
    return result
}


@Composable
private fun InstrumentOverviewPanel(
    symbol: String,
    isIndex: Boolean,
    bars: List<OhlcvBar>,
    visibleBars: List<OhlcvBar>,
    interval: OhlcvInterval,
    quote: InstrumentQuoteSnapshot?,
    quoteLoading: Boolean,
    quoteError: String?,
    intelligence: IntelligenceFeed?,
    intelligenceLoading: Boolean,
    intelligenceError: String?,
    onOpenUrl: (String) -> Unit
) {
    val latest = bars.lastOrNull()
    val previous = bars.getOrNull(bars.lastIndex - 1)
    val dailyChange = quote?.changePercent
        ?: if (latest != null && previous != null && previous.close > 0.0) {
            (latest.close / previous.close - 1.0) * 100.0
        } else null
    val periodHigh = visibleBars.maxOfOrNull { it.high }
    val periodLow = visibleBars.minOfOrNull { it.low }
    fun periodReturn(days: Long): Double? {
        val last = bars.lastOrNull() ?: return null
        val targetDate = last.timestamp.toLocalDate().minusDays(days)
        val base = bars.lastOrNull { !it.timestamp.toLocalDate().isAfter(targetDate) } ?: return null
        if (base.close <= 0.0) return null
        return (last.close / base.close - 1.0) * 100.0
    }
    fun price(value: Double?): String = value?.takeIf { it.isFinite() && it > 0.0 }
        ?.let { String.format(Locale.US, "%.2f", it) } ?: "—"
    fun percent(value: Double?): String = value?.takeIf { it.isFinite() }
        ?.let { String.format(Locale.US, "%+.2f%%", it) } ?: "—"

    Column(
        modifier = Modifier.testTag("instrument.overview.panel"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            "INSTRUMENT OVERVIEW",
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            "Market data and price-derived statistics · ${quote?.provider ?: "historical OHLC source"}",
            color = Muted,
            fontSize = 9.sp
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OverviewMetric("Last price", price(quote?.lastPrice ?: latest?.close), Modifier.weight(1f))
            OverviewMetric(
                if (interval.isIntraday) "vs prior candle" else "Daily change",
                percent(dailyChange),
                Modifier.weight(1f),
                valueColor = when {
                    dailyChange == null -> Muted
                    dailyChange >= 0.0 -> Mint
                    else -> Coral
                }
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OverviewMetric("52-week high", price(quote?.yearHigh), Modifier.weight(1f))
            OverviewMetric("52-week low", price(quote?.yearLow), Modifier.weight(1f))
        }
        if (quote == null && !quoteLoading) {
            Text(
                quoteError ?: "52-week range is not available from the configured quote provider.",
                color = Muted,
                fontSize = 9.sp
            )
        } else if (quoteLoading) {
            Text("Loading provider quote and 52-week range…", color = Muted, fontSize = 9.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OverviewMetric("Selected-range high", price(periodHigh), Modifier.weight(1f))
            OverviewMetric("Selected-range low", price(periodLow), Modifier.weight(1f))
        }
        Text("PRICE PERFORMANCE", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OverviewMetric("1 month", percent(periodReturn(30)), Modifier.weight(1f))
            OverviewMetric("3 months", percent(periodReturn(90)), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OverviewMetric("6 months", percent(periodReturn(180)), Modifier.weight(1f))
            OverviewMetric("1 year", percent(periodReturn(365)), Modifier.weight(1f))
        }
        Text(
            "VALUATION & FUNDAMENTALS",
            color = Muted,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold
        )
        TerminalCard {
            Text(
                if (isIndex) "Index valuation (P/E, P/B, dividend yield)"
                else "Company valuation (P/E, P/B, EPS, market capitalisation, dividend yield)",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "Not yet available from a connected, validated fundamentals provider. These values are intentionally not estimated from price candles.",
                color = Muted,
                fontSize = 10.sp
            )
        }
        Text("POSITION PROFIT / LOSS", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Text(
            "The figures above show market-price performance, not your personal P&L. Personal profit/loss requires holdings, quantity, average buy price and corporate-action adjustments.",
            color = Muted,
            fontSize = 9.sp
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("RECENT NEWS & DEVELOPMENTS", color = MaterialTheme.colorScheme.onBackground,
                fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text("7 days", color = Muted, fontSize = 9.sp)
        }
        when {
            intelligenceLoading -> Text("Searching recent news…", color = Muted, fontSize = 10.sp)
            intelligenceError != null -> GracefulErrorState("Recent news unavailable. Try again later.")
            intelligence?.events?.isNotEmpty() == true -> {
                intelligence.events.take(6).forEach { event ->
                    TerminalCard {
                        Text(event.headline, color = MaterialTheme.colorScheme.onBackground,
                            fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${event.eventCategory} · ${event.sourceArticleCount} articles · ${event.independentSourceCount} independent sources",
                            color = Muted, fontSize = 9.sp
                        )
                        event.sources.take(2).forEach { source ->
                            TextButton(
                                onClick = { if (source.url.startsWith("https://")) onOpenUrl(source.url) },
                                enabled = source.url.startsWith("https://")
                            ) {
                                Text(
                                    "${source.domain} · ${source.seenAt.ifBlank { "timestamp unavailable" }}",
                                    fontSize = 9.sp
                                )
                            }
                        }
                    }
                }
            }
            else -> Text("No matching news events were returned for this 7-day search.", color = Muted, fontSize = 10.sp)
        }
        Text("IMPORTANT DATES & EXCHANGE DISCLOSURES", color = MaterialTheme.colorScheme.onBackground,
            fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text(
            "Upcoming ex-dates, board meetings and result dates need a verified exchange corporate-actions feed; they are not inferred from news headlines.",
            color = Muted,
            fontSize = 9.sp
        )
        TextButton(onClick = {
            onOpenUrl("https://www.nseindia.com/companies-listing/corporate-filings-announcements")
        }) {
            Text("Open official NSE company announcements")
        }
    }
}

@Composable
private fun OverviewMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onBackground
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = PanelRaised
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(label, color = Muted, fontSize = 9.sp)
            Text(value, color = valueColor, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
    }
}
