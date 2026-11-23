package com.rovexquant.app.ui

import com.rovexquant.app.data.model.MarketTick
import com.rovexquant.app.data.model.EodMarketSnapshot
import com.rovexquant.app.data.model.EodStockMove
import com.rovexquant.app.data.model.MarketEquityInstrument
import com.rovexquant.app.data.model.QuantSession
import com.rovexquant.app.data.model.MarketIngestionStatus
import com.rovexquant.app.data.repository.QuantDataRepository

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class SectorIndex(val sector: String, val benchmark: String)

private val coreSectorIndices = listOf(
    SectorIndex("Banking", "NIFTY BANK"),
    SectorIndex("Financial Services", "NIFTY FINANCIAL SERVICES"),
    SectorIndex("Information Technology", "NIFTY IT"),
    SectorIndex("Automobiles", "NIFTY AUTO"),
    SectorIndex("Pharma", "NIFTY PHARMA"),
    SectorIndex("Healthcare", "NIFTY HEALTHCARE"),
    SectorIndex("FMCG", "NIFTY FMCG"),
    SectorIndex("Metals", "NIFTY METAL"),
    SectorIndex("Oil & Gas", "NIFTY OIL & GAS"),
    SectorIndex("Realty", "NIFTY REALTY"),
    SectorIndex("Power", "NIFTY POWER"),
    SectorIndex("Media", "NIFTY MEDIA"),
    SectorIndex("PSU Banks", "NIFTY PSU BANK"),
    SectorIndex("Private Banks", "NIFTY PRIVATE BANK"),
    SectorIndex("Consumer Durables", "NIFTY CONSUMER DURABLES"),
    SectorIndex("Capital Goods", "NIFTY CAPITAL GOODS"),
    SectorIndex("Chemicals", "NIFTY CHEMICALS"),
    SectorIndex("Cement", "NIFTY CEMENT"),
    SectorIndex("Telecom", "NIFTY TELECOMMUNICATIONS")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarketPulseScreen(
    ticks: Map<String, MarketTick> = emptyMap(),
    indexKeys: Map<String, String> = emptyMap(),
    equityInstruments: List<MarketEquityInstrument> = emptyList(),
    streamStatus: String = "Not connected",
    session: QuantSession? = null,
    repository: QuantDataRepository? = null,
    onReconnect: () -> Unit = {},
    eodSnapshot: EodMarketSnapshot? = null,
    eodSnapshotStatus: String = "EOD snapshot not loaded",
    onRefreshEodSnapshot: () -> Unit = {},
    marketIngestionStatus: MarketIngestionStatus? = null,
    marketIngestionStatusLabel: String = "Persistent database status not checked",
    onRefreshMarketIngestionStatus: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val activeSession = session
    val activeRepository = repository
    var equitySearch by remember { mutableStateOf("") }
    var upstoxToken by remember { mutableStateOf("") }
    var tokenSaving by remember { mutableStateOf(false) }
    var tokenError by remember { mutableStateOf<String?>(null) }
    var tokenConfigured by remember { mutableStateOf<Boolean?>(null) }
    var showTokenForm by remember { mutableStateOf(true) }
    var isRefreshing by remember { mutableStateOf(false) }
    var freshnessClock by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(ticks) {
        while (true) {
            freshnessClock = System.currentTimeMillis()
            delay(5_000)
        }
    }
    fun isFresh(tick: MarketTick): Boolean = runCatching {
        Instant.parse(tick.receivedAt).toEpochMilli() >= freshnessClock - 30_000
    }.getOrDefault(false)

    LaunchedEffect(eodSnapshotStatus) {
        val status = eodSnapshotStatus.lowercase()
        isRefreshing = status.startsWith("requesting") ||
            status.startsWith("collecting") || status.contains("queued") ||
            status.contains("still running")
    }

    LaunchedEffect(activeSession, activeRepository) {
        if (activeSession != null && activeRepository != null) {
            tokenConfigured = runCatching {
                activeRepository.fetchUpstoxConfigured(activeSession)
            }.getOrNull()
            if (tokenConfigured == true) showTokenForm = false
        }
    }
    val visibleEquities = remember(equityInstruments, equitySearch) {
        val query = equitySearch.trim().uppercase()
        if (query.isBlank()) equityInstruments else equityInstruments.filter {
            it.tradingSymbol.uppercase().contains(query) || it.name.uppercase().contains(query)
        }
    }
    val nifty = indexKeys["NIFTY 50"]?.let { ticks[it] }
    val bank = indexKeys["NIFTY BANK"]?.let { ticks[it] }
    val latestTick = listOfNotNull(nifty, bank).maxByOrNull { it.receivedAt }
    val rankedSectors = remember(ticks, indexKeys, freshnessClock) {
        coreSectorIndices.sortedByDescending { sector ->
            indexKeys[sector.benchmark]?.let { ticks[it] }?.let { quote ->
                quote.changePercent.takeIf { quote.quality == "VALID" && isFresh(quote) }
            } ?: Double.NEGATIVE_INFINITY
        }
    }
    val liveSectorCount = rankedSectors.count { sector ->
        indexKeys[sector.benchmark]?.let { ticks[it] }?.let { it.quality == "VALID" && isFresh(it) } == true
    }
    val exchangeClock = latestTick?.exchangeTimestamp?.substringAfter("T")?.take(8) ?: "unavailable"
    val receiveClock = latestTick?.receivedAt?.substringAfter("T")?.take(8) ?: "unavailable"

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = {
            if (activeSession != null) {
                isRefreshing = true
                onRefreshEodSnapshot()
                onReconnect()
            } else {
                isRefreshing = false
            }
        },
        modifier = Modifier.fillMaxSize()
    ) {
        LazyColumn(modifier = Modifier.fillMaxSize().testTag("market.pulse.list"), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        item { SectionHeading("The market, without the noise", "Market pulse.", "A clean overview of indices, breadth, sectors and your tracked universe.") }
        item {
            TerminalCard {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Eyebrow("Latest NSE market data")
                    StatusPill("END OF DAY", Gold)
                }
                Text(
                    "NSE equity snapshot",
                    modifier = Modifier.testTag("market.eod.snapshot"),
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    eodSnapshot?.let {
                        "PostgreSQL · ${it.provider} · ${it.tradingDate} · ${it.pricedSymbols} priced symbols"
                    } ?: "Automatically retrieving the latest available NSE trading session from TejHQ.",
                    color = Muted, fontSize = 10.sp, lineHeight = 15.sp
                )
                if (eodSnapshot == null) {
                    Text(
                        "The app retrieves the latest available trading session after login. This is EOD data, not a live quote feed.",
                        color = Muted, fontSize = 10.sp, lineHeight = 15.sp
                    )
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        EodBreadthStat("Advances", eodSnapshot.advances, Mint, Modifier.weight(1f))
                        EodBreadthStat("Declines", eodSnapshot.declines, Coral, Modifier.weight(1f))
                        EodBreadthStat("Unchanged", eodSnapshot.unchanged, Muted, Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(5.dp))
                    Text(
                        "${eodSnapshot.totalRows} source rows · ${eodSnapshot.equitySeriesRows} EQ-series breadth rows · EOD only",
                        color = Muted, fontSize = 9.sp
                    )
                    if (eodSnapshot.topGainers.isNotEmpty()) {
                        Text("TOP GAINERS", color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        eodSnapshot.topGainers.take(5).forEach { EodStockMoveRow(it, positive = true) }
                    }
                    if (eodSnapshot.topLosers.isNotEmpty()) {
                        Text("TOP LOSERS", color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        eodSnapshot.topLosers.take(5).forEach { EodStockMoveRow(it, positive = false) }
                    }
                    if (eodSnapshot.mostActiveByVolume.isNotEmpty()) {
                        Text("MOST ACTIVE BY VOLUME", color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        eodSnapshot.mostActiveByVolume.take(5).forEach { EodStockMoveRow(it, positive = null) }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(
                        eodSnapshotStatus,
                        color = if (eodSnapshot == null) Gold else Muted,
                        fontSize = 9.sp,
                        modifier = Modifier.weight(1f)
                    )
                    androidx.compose.material3.TextButton(
                        onClick = onRefreshEodSnapshot,
                        modifier = Modifier.testTag("market.eod.refresh")
                    ) { Text("Refresh") }
                }
            }
        }
        item {
            TerminalCard {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Eyebrow("Persistent PostgreSQL ingestion")
                    StatusPill(
                        marketIngestionStatus?.status ?: "UNKNOWN",
                        when (marketIngestionStatus?.status) {
                            "SUCCEEDED" -> Mint
                            "FAILED", "EMPTY" -> Coral
                            else -> Gold
                        }
                    )
                }
                Text(
                    "Database collection health",
                    modifier = Modifier.testTag("market.ingestion.status"),
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    marketIngestionStatusLabel,
                    color = Muted,
                    fontSize = 10.sp,
                    lineHeight = 15.sp
                )
                val persistedDate = marketIngestionStatus?.lastSuccessfulTradingDate
                if (persistedDate != null) {
                    Text(
                        "Persisted EOD data · $persistedDate · " +
                            "${marketIngestionStatus?.lastSuccessfulRows ?: 0} rows · " +
                            "${marketIngestionStatus?.lastSuccessfulProvider ?: "source unknown"}",
                        color = Mint,
                        fontSize = 10.sp,
                        lineHeight = 15.sp
                    )
                } else {
                    Text(
                        "No successful EOD batch is recorded in PostgreSQL yet. A live quote connection does not prove historical data has been persisted.",
                        color = Gold,
                        fontSize = 10.sp,
                        lineHeight = 15.sp
                    )
                }
                if (marketIngestionStatus?.status == "FAILED") {
                    Text(
                        "Latest run error · ${marketIngestionStatus?.errorCode ?: "unknown"}: " +
                            (marketIngestionStatus?.errorMessage ?: "No safe error detail supplied."),
                        color = Coral,
                        fontSize = 9.sp,
                        lineHeight = 14.sp
                    )
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Text(
                        marketIngestionStatus?.let {
                            "Latest run · ${it.rowsUpserted} rows processed · ${it.rowsSkipped} skipped"
                        } ?: "Waiting for backend status",
                        color = Muted,
                        fontSize = 9.sp,
                        modifier = Modifier.weight(1f)
                    )
                    androidx.compose.material3.TextButton(
                        onClick = onRefreshMarketIngestionStatus,
                        modifier = Modifier.testTag("market.ingestion.refresh")
                    ) { Text("Check") }
                }
            }
        }
        if (activeSession != null && activeRepository != null) {
            item {
                TerminalCard {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Eyebrow("Market data connection")
                        StatusPill(
                            when (tokenConfigured) {
                                true -> "Token stored"
                                false -> "Setup required"
                                null -> "Checking"
                            },
                            if (tokenConfigured == true) Mint else Gold
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Upstox Analytics Token · read-only market data",
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "Generate the free, one-year Analytics Token in your Upstox Developer Apps account. Paste it here; Quant sends it over HTTPS and stores it encrypted on the backend. Never share it in chat.",
                        color = Muted, fontSize = 10.sp, lineHeight = 15.sp
                    )
                    if (tokenConfigured == true && !showTokenForm) {
                        Text(
                            "Stored does not mean live. The feed is verified separately by upstream authorization and received ticks.",
                            color = Gold, fontSize = 10.sp, lineHeight = 15.sp
                        )
                        androidx.compose.material3.TextButton(
                            onClick = { showTokenForm = true },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Replace Analytics Token") }
                    } else {
                        Spacer(Modifier.height(7.dp))
                        OutlinedTextField(
                            value = upstoxToken,
                            onValueChange = { upstoxToken = it.take(4096); tokenError = null },
                            label = { Text("Upstox Analytics Token") },
                            placeholder = { Text("Paste your read-only token") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("market.upstox.token")
                        )
                        Button(
                            onClick = {
                                val token = upstoxToken.trim()
                                if (token.length !in 20..4096 || tokenSaving) return@Button
                                tokenSaving = true
                                tokenError = null
                                scope.launch {
                                    try {
                                        activeRepository.saveUpstoxAnalyticsToken(activeSession, token)
                                        upstoxToken = ""
                                        tokenConfigured = true
                                        showTokenForm = false
                                        onReconnect()
                                    } catch (failure: Exception) {
                                        tokenError = failure.message ?: "Unable to store Upstox token."
                                    } finally {
                                        tokenSaving = false
                                    }
                                }
                            },
                            enabled = !tokenSaving && upstoxToken.trim().length in 20..4096,
                            modifier = Modifier.fillMaxWidth().testTag("market.upstox.save")
                        ) {
                            Text(if (tokenSaving) "Encrypting on backend…" else "Save token and connect feed")
                        }
                        tokenError?.let { Text(it, color = Coral, fontSize = 10.sp, lineHeight = 15.sp) }
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricTile(
                    "NIFTY 50",
                    nifty?.lastPrice?.let { String.format(Locale.US, "%.2f", it) } ?: "—",
                    nifty?.let {
                        if (it.quality == "VALID" && isFresh(it)) "LIVE" else "STALE · ${it.quality}"
                    } ?: "Awaiting verified tick",
                    modifier = Modifier.weight(1f)
                )
                MetricTile(
                    "NIFTY BANK",
                    bank?.lastPrice?.let { String.format(Locale.US, "%.2f", it) } ?: "—",
                    bank?.let {
                        if (it.quality == "VALID" && isFresh(it)) "LIVE" else "STALE · ${it.quality}"
                    } ?: "Awaiting verified tick",
                    accent = Cyan,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricTile(
                    "Adv / Decl",
                    eodSnapshot?.let { "${it.advances} / ${it.declines}" } ?: "— / —",
                    eodSnapshot?.let { "${it.pricedSymbols} priced EQ symbols" } ?: "Awaiting EOD snapshot",
                    accent = Gold,
                    modifier = Modifier.weight(1f)
                )
                MetricTile(
                    "Signals",
                    "—",
                    "Signal generation awaits verified live-feed and strategy checks",
                    accent = Coral,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        item {
            TerminalCard {
                Eyebrow("Market structure")
                Spacer(Modifier.height(8.dp))
                Text("Index chart will appear here", color = MaterialTheme.colorScheme.onBackground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(5.dp))
                Text("No placeholder prices or synthetic candles. Connect an authorised feed or load a permitted historical dataset to render a chart.", color = Muted, fontSize = 11.sp, lineHeight = 17.sp)
                Spacer(Modifier.height(16.dp))
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(PanelRaised)
                        .testTag("market.feed.status").padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text("SOURCE · UPSTOX V3 · $streamStatus", color = Muted, fontSize = 9.sp, letterSpacing = .5.sp, fontWeight = FontWeight.Bold)
                    Text("EXCHANGE · $exchangeClock UTC    RECEIVED · $receiveClock UTC", color = Muted, fontSize = 8.sp)
                    Text(
                        "TICK QUALITY · ${latestTick?.let {
                            if (it.quality == "VALID" && isFresh(it)) "VALID" else "STALE / ${it.quality}"
                        } ?: "NO VERIFIED TICK"}",
                        color = latestTick?.let { if (it.quality == "VALID" && isFresh(it)) Mint else Gold } ?: Muted,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
        item {
            Column {
                Text("Sector performance", color = MaterialTheme.colorScheme.onBackground, fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("market.sector.heading"))
                Spacer(Modifier.height(4.dp))
                Text(
                    "Live NSE sector-index performance · $liveSectorCount / ${coreSectorIndices.size} fresh benchmarks · strongest first",
                    color = Muted, fontSize = 11.sp, lineHeight = 16.sp
                )
            }
        }
        items(rankedSectors, key = { it.benchmark }) { sector ->
            val instrumentKey = indexKeys[sector.benchmark]
            val quote = instrumentKey?.let { ticks[it] }
            TerminalCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(sector.sector, color = MaterialTheme.colorScheme.onBackground, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(3.dp))
                        Text(sector.benchmark, color = Muted, fontSize = 9.sp, letterSpacing = .5.sp, modifier = Modifier.testTag("sector.index.${sector.benchmark}"))
                    }
                    StatusPill(
                        when {
                            quote == null && instrumentKey == null -> "Not listed"
                            quote == null -> "No tick"
                            quote.quality != "VALID" -> quote.quality
                            !isFresh(quote) -> "Stale"
                            else -> "Live"
                        },
                        if (quote?.quality == "VALID" && isFresh(quote)) Mint else Gold
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    SectorMetric("LTP", quote?.lastPrice?.let { String.format(Locale.US, "%.2f", it) } ?: "—")
                    SectorMetric("CHANGE", quote?.changePercent?.let { String.format(Locale.US, "%+.2f%%", it) } ?: "—")
                    SectorMetric("QUALITY", quote?.quality ?: "—")
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "NSE listed equities · ${equityInstruments.size} instruments",
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                OutlinedTextField(
                    value = equitySearch,
                    onValueChange = { equitySearch = it },
                    modifier = Modifier.fillMaxWidth().testTag("market.equity.search"),
                    singleLine = true,
                    label = { Text("Search symbol or company") }
                )
                Text(
                    "The official NSE cash-equity master is subscribed in LTPC mode. Quotes appear only after verified exchange ticks arrive.",
                    color = Muted,
                    fontSize = 10.sp,
                    lineHeight = 15.sp
                )
            }
        }
        if (equityInstruments.isEmpty()) {
            item { EmptyPanel("NSE equity universe unavailable", "Check the official instrument master and backend connectivity. Index streaming can still operate independently.") }
        }
        items(visibleEquities, key = { it.instrumentKey }) { instrument ->
            val quote = ticks[instrument.instrumentKey]
            TerminalCard {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(instrument.tradingSymbol, color = MaterialTheme.colorScheme.onBackground, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(instrument.name, color = Muted, fontSize = 9.sp, maxLines = 1)
                        Text(quote?.quality ?: "AWAITING TICK", color = Muted, fontSize = 8.sp)
                    }
                    Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                        Text(
                            quote?.lastPrice?.let { String.format(Locale.US, "%.2f", it) } ?: "—",
                            color = MaterialTheme.colorScheme.onBackground,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            quote?.changePercent?.let { String.format(Locale.US, "%+.2f%%", it) } ?: "Awaiting data",
                            color = quote?.changePercent?.let { if (it >= 0) Mint else Coral } ?: Muted,
                            fontSize = 9.sp
                        )
                    }
                }
            }
        }
        item { EmptyPanel("Data integrity is enforced", "Stale, incomplete, delayed-without-permission or disconnected data will be visibly flagged and blocked from generating signals.") }
        }
    }
}

@Composable
private fun SectorMetric(label: String, value: String) {
    Column {
        Text(label, color = Muted, fontSize = 8.sp, letterSpacing = .7.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(3.dp))
        Text(value, color = MaterialTheme.colorScheme.onBackground, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}


@Composable
private fun EodBreadthStat(label: String, value: Int, color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(Panel, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(value.toString(), color = color, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text(label, color = Muted, fontSize = 9.sp)
    }
}

@Composable
private fun EodStockMoveRow(item: EodStockMove, positive: Boolean?) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(item.symbol, color = MaterialTheme.colorScheme.onBackground, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Text(item.name, color = Muted, fontSize = 9.sp, maxLines = 1)
        }
        Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
            Text(
                item.close?.let { "₹" + String.format(Locale.US, "%.2f", it) } ?: "—",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 10.sp
            )
            val changeText = item.changePercent?.let {
                (if (it > 0) "+" else "") + String.format(Locale.US, "%.2f%%", it)
            } ?: "Vol. ${item.volume}"
            Text(
                changeText,
                color = when (positive) {
                    true -> Mint
                    false -> Coral
                    null -> Muted
                },
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
