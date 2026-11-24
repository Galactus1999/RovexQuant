package com.rovexquant.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.util.Locale

data class SectorHeatmapTile(
    val label: String,
    val shortLabel: String,
    val benchmark: String,
    val changePercent: Double?,
    val lastPrice: Double?,
    val isFresh: Boolean
)

@Composable
fun SectorHeatmapDialog(
    sectors: List<SectorHeatmapTile>,
    liveCount: Int,
    onDismiss: () -> Unit,
    onSelectSector: (SectorHeatmapTile) -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                .testTag("market.sector.heatmap.dialog"),
            shape = RoundedCornerShape(22.dp),
            color = PanelRaised
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalAlignment = Alignment.Start
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            "SECTOR HEATMAP",
                            color = MaterialTheme.colorScheme.onBackground,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "$liveCount / ${sectors.size} sectors with fresh quotes",
                            color = Muted,
                            fontSize = 10.sp
                        )
                    }
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Each sector is one flat tile. Green = positive, red = negative. Tap a tile to open its index chart.",
                    color = Muted,
                    fontSize = 10.sp
                )
                Spacer(Modifier.height(12.dp))
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 104.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 520.dp)
                        .testTag("market.sector.heatmap.grid"),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(sectors, key = { it.benchmark }) { sector ->
                        val fresh = sector.isFresh && sector.changePercent?.isFinite() == true
                        val positive = fresh && sector.changePercent!! >= 0.0
                        val tileColor = when {
                            !fresh -> Color(0xFF657386)
                            positive -> Color(0xFF16834F)
                            else -> Color(0xFFC33445)
                        }
                        Card(
                            onClick = { onSelectSector(sector) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 88.dp)
                                .testTag("sector.heatmap.${sector.benchmark}"),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = tileColor),
                            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 9.dp, vertical = 12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    sector.shortLabel,
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 2
                                )
                                Spacer(Modifier.height(5.dp))
                                Text(
                                    if (fresh) String.format(Locale.US, "%+.2f%%", sector.changePercent)
                                    else "—",
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    if (fresh) sector.lastPrice?.let { String.format(Locale.US, "%.2f", it) } ?: ""
                                    else "STALE",
                                    color = Color.White.copy(alpha = 0.86f),
                                    fontSize = 9.sp
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    HeatmapLegend(Color(0xFF16834F), "Positive")
                    Spacer(Modifier.width(12.dp))
                    HeatmapLegend(Color(0xFFC33445), "Negative")
                    Spacer(Modifier.width(12.dp))
                    HeatmapLegend(Color(0xFF657386), "Stale / unavailable")
                }
            }
        }
    }
}

@Composable
private fun HeatmapLegend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        androidx.compose.foundation.Canvas(Modifier.size(8.dp)) { drawCircle(color) }
        Text(label, color = Muted, fontSize = 9.sp)
    }
}
