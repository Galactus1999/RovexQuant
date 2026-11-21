package com.rovexquant.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

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

@Composable
fun MarketPulseScreen(ticks: Map<String, MarketTick> = emptyMap(), indexKeys: Map<String, String> = emptyMap(), streamStatus: String = "Not connected") {
    val nifty = indexKeys["NIFTY 50"]?.let { ticks[it] }
    val bank = indexKeys["NIFTY BANK"]?.let { ticks[it] }
    LazyColumn(contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        item { SectionHeading("The market, without the noise", "Market pulse.", "A clean overview of indices, breadth, sectors and your tracked universe.") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricTile(
                    "NIFTY 50",
                    nifty?.lastPrice?.let { String.format(Locale.US, "%.2f", it) } ?: "—",
                    nifty?.quality ?: "Awaiting verified tick",
                    modifier = Modifier.weight(1f)
                )
                MetricTile(
                    "NIFTY BANK",
                    bank?.lastPrice?.let { String.format(Locale.US, "%.2f", it) } ?: "—",
                    bank?.quality ?: "Awaiting verified tick",
                    accent = Cyan,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricTile("Adv / Decl", "— / —", "Breadth unavailable", accent = Gold, modifier = Modifier.weight(1f))
                MetricTile("Signals", "0", "No verified feed", accent = Coral, modifier = Modifier.weight(1f))
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
                Box(Modifier.fillMaxWidth().height(92.dp).clip(RoundedCornerShape(14.dp)).background(PanelRaised)
                    .testTag("market.feed.status"), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    Text("UPSTOX V3 · $streamStatus", color = Muted, fontSize = 9.sp, letterSpacing = 1.0.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        item {
            Column {
                Text("Sector performance", color = MaterialTheme.colorScheme.onBackground, fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("market.sector.heading"))
                Spacer(Modifier.height(4.dp))
                Text("NSE sector-index watchlist · live values are shown only for instruments actually subscribed and received.", color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
            }
        }
        items(coreSectorIndices, key = { it.benchmark }) { sector ->
            val instrumentKey = indexKeys[sector.benchmark]
            val quote = instrumentKey?.let { ticks[it] }
            TerminalCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(sector.sector, color = MaterialTheme.colorScheme.onBackground, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(3.dp))
                        Text(sector.benchmark, color = Muted, fontSize = 9.sp, letterSpacing = .5.sp, modifier = Modifier.testTag("sector.index.${sector.benchmark}"))
                    }
                    StatusPill(if (quote?.quality == "VALID") "Live" else if (instrumentKey == null) "Not listed" else "No tick", if (quote?.quality == "VALID") Mint else Gold)
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
            Text("Starter universe · editable controls pending", color = MaterialTheme.colorScheme.onBackground, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        items(listOf("NIFTY 50" to "Index", "NIFTY BANK" to "Index", "RELIANCE" to "Energy", "HDFCBANK" to "Banking", "INFY" to "Technology")) { item ->
            TerminalCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text(item.first, color = MaterialTheme.colorScheme.onBackground, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(item.second, color = Muted, fontSize = 10.sp)
                    }
                    Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                        Text("—", color = Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text("Awaiting data", color = Muted, fontSize = 9.sp)
                    }
                }
            }
        }
        item { EmptyPanel("Data integrity is enforced", "Stale, incomplete, delayed-without-permission or disconnected data will be visibly flagged and blocked from generating signals.") }
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
