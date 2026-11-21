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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun MarketPulseScreen() {
    LazyColumn(contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        item { SectionHeading("The market, without the noise", "Market pulse.", "A clean overview of indices, breadth, sectors and your tracked universe.") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricTile("NIFTY 50", "—", "Feed not connected", modifier = Modifier.weight(1f))
                MetricTile("SENSEX", "—", "Feed not connected", accent = Cyan, modifier = Modifier.weight(1f))
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
                Box(Modifier.fillMaxWidth().height(92.dp).clip(RoundedCornerShape(14.dp)).background(PanelRaised), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    Text("MARKET DATA NOT CONNECTED", color = Muted, fontSize = 9.sp, letterSpacing = 1.3.sp, fontWeight = FontWeight.Bold)
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
