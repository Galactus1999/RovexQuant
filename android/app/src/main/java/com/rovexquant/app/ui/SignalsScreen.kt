package com.rovexquant.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SignalsScreen() {
    var filter by remember { mutableStateOf("All") }
    LazyColumn(contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { SectionHeading("Evidence before alerts", "Signal centre.", "A transparent journal of generated, invalidated and expired research signals.") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("All", "Active", "Invalidated", "Expired").forEach { value ->
                    FilterChip(selected = filter == value, onClick = { filter = value }, label = { Text(value) })
                }
            }
        }
        item {
            TerminalCard {
                Eyebrow("Signal lifecycle")
                Spacer(Modifier.height(7.dp))
                Text("No signals generated", color = MaterialTheme.colorScheme.onBackground, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(5.dp))
                Text("This is expected while the market-data provider is disconnected. RovexQuant will not fabricate entries, stop-losses, targets or confidence scores.", color = Muted, fontSize = 11.sp, lineHeight = 18.sp)
            }
        }
        item { EmptyPanel("Every signal will carry its evidence", "Strategy and version, timeframe, data timestamp, entry zone, invalidation level, risk/reward, expiry and the reason it was generated.") }
        item { EmptyPanel("Risk gate cannot be bypassed", "Signals are suppressed for stale, incomplete or unauthorised data, invalid stops, inadequate reward/risk or breached user risk limits.", Coral) }
    }
}
