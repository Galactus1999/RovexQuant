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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
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
    val activeSession = session
    var equitySearch by remember { mutableStateOf("") }
    var equityFilter by remember { mutableStateOf("ALL") }
    var isRefreshing by remember { mutableStateOf(false) }
    var showEodMovers by remember { mutableStateOf(false) }
    var showAllSectors by remember { mutableStateOf(false) }
    var showAllEquities by remember { mutableStateOf(false) }
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

    val visibleEquities = remember(equityInstruments, equitySearch, equityFilter, ticks) {
        val query = equitySearch.trim().uppercase()
        val searched = if (query.isBlank()) equityInstruments else equityInstruments.filter {
            it.tradingSymbol.uppercase().contains(query) || it.name.uppercase().contains(query)
        }
        val filtered = when (equityFilter) {
            "GAINERS" -> searched.filter { ticks[it.instrumentKey]?.changePercent?.let { change -> change > 0.0 } == true }
            "LOSERS" -> searched.filter { ticks[it.instrumentKey]?.changePercent?.let { change -> change < 0.0 } == true }
            else -> searched
        }
        when (equityFilter) {
            "GAINERS" -> filtered.sortedByDescending { ticks[it.instrumentKey]?.changePercent ?: Double.NEGATIVE_INFINITY }
            "LOSERS" -> filtered.sortedBy { ticks[it.instrumentKey]?.changePercent ?: Double.POSITIVE_INFINITY }
            else -> filtered.sortedByDescending { ticks[it.instrumentKey]?.changePercent ?: Double.NEGATIVE_INFINITY }
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
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("Market Pulse", color = MaterialTheme.colorScheme.onBackground, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                StatusPill(if (streamStatus.startsWith("Live")) "LIVE" else "EOD / DELAYED", if (streamStatus.startsWith("Live")) Mint else Gold)
            }
        }
        item {
            TerminalCard {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Eyebrow("NSE · EOD")
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
                    eodSnapshot?.let { "${it.tradingDate} · ${it.pricedSymbols} stocks" } ?: "—",
                    color = Muted, fontSize = 10.sp, lineHeight = 15.sp
                )
                if (eodSnapshot != null) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        EodBreadthStat("Advances", eodSnapshot.advances, Mint, Modifier.weight(1f))
                        EodBreadthStat("Declines", eodSnapshot.declines, Coral, Modifier.weight(1f))
                        EodBreadthStat("Unchanged", eodSnapshot.unchanged, Muted, Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(5.dp))
                    val moverCount = eodSnapshot.topGainers.size + eodSnapshot.topLosers.size + eodSnapshot.mostActiveByVolume.size
                    if (moverCount > 0) {
                        androidx.compose.material3.TextButton(onClick = { showEodMovers = !showEodMovers }) {
                            Text(if (showEodMovers) "Hide market movers" else "Show market movers")
                        }
                        if (showEodMovers) {
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
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(
                        eodSnapshot?.tradingDate ?: eodSnapshotStatus.take(32),
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
                    eodSnapshot?.let { "${it.unchanged} unchanged" } ?: "—",
                    accent = Gold,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        item {
            Column {
                Text("Sector performance", color = MaterialTheme.colorScheme.onBackground, fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("market.sector.heading"))
                Spacer(Modifier.height(4.dp))
                Text("$liveSectorCount / ${coreSectorIndices.size} live", color = Muted, fontSize = 10.sp)
            }
        }
        items(rankedSectors.take(if (showAllSectors) rankedSectors.size else 4), key = { it.benchmark }) { sector ->
            val instrumentKey = indexKeys[sector.benchmark]
            val quote = instrumentKey?.let { ticks[it] }
            val move = quote?.changePercent?.takeIf { quote.quality == "VALID" && isFresh(quote) }
            val sectorTint = when {
                move == null -> Panel
                move >= 0.0 -> Mint.copy(alpha = .10f)
                else -> Coral.copy(alpha = .12f)
            }
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                    .background(sectorTint).padding(14.dp)
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(sector.sector, color = MaterialTheme.colorScheme.onBackground, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(3.dp))
                        Text(sector.benchmark, color = Muted, fontSize = 9.sp, letterSpacing = .5.sp, modifier = Modifier.testTag("sector.index.${sector.benchmark}"))
                    }
                    StatusPill(
                        quote?.takeIf { it.quality == "VALID" && isFresh(it) }?.changePercent
                            ?.let { String.format(Locale.US, "%+.2f%%", it) }
                            ?: if (instrumentKey == null) "N/A" else "—",
                        quote?.changePercent?.let { if (it >= 0) Mint else Coral } ?: Gold
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    SectorMetric("INDEX", sector.benchmark)
                    SectorMetric("LTP", quote?.lastPrice?.let { String.format(Locale.US, "%.2f", it) } ?: "—")
                }
            }
        }
        item {
            androidx.compose.material3.TextButton(onClick = { showAllSectors = !showAllSectors }, modifier = Modifier.fillMaxWidth()) {
                Text(if (showAllSectors) "Show fewer sectors" else "View all sector benchmarks")
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
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("ALL", "GAINERS", "LOSERS").forEach { filter ->
                        androidx.compose.material3.FilterChip(
                            selected = equityFilter == filter,
                            onClick = { equityFilter = filter },
                            label = { Text(filter) },
                            modifier = Modifier.testTag("market.equity.filter.$filter")
                        )
                    }
                }
            }
        }
        if (equityInstruments.isEmpty()) {
            item { EmptyPanel("NSE equities unavailable", "—") }
        }
        items(visibleEquities.take(if (showAllEquities || equitySearch.isNotBlank()) 100 else 12), key = { it.instrumentKey }) { instrument ->
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
        if (visibleEquities.size > 12 && equitySearch.isBlank()) {
            item {
                androidx.compose.material3.TextButton(onClick = { showAllEquities = !showAllEquities }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (showAllEquities) "Show fewer instruments" else "Load more instruments")
                }
            }
        }
        
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
