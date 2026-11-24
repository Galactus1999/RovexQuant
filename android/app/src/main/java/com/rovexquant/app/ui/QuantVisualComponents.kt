package com.rovexquant.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max

private val VisualPositive = Color(0xFF00D98B)
private val VisualNegative = Color(0xFFFF5D70)
private val VisualGrid = Color(0xFF334155)

/** One global freshness indicator; individual metric cards should not repeat STALE labels. */
@Composable
fun DataFreshnessIndicator(label: String, fresh: Boolean, modifier: Modifier = Modifier) {
    val tint = if (fresh) VisualPositive else Color(0xFFFFC857)
    Row(
        modifier.clip(CircleShape).background(tint.copy(alpha = 0.10f))
            .padding(horizontal = 9.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(tint))
        Text(label, color = tint, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun IndexQuoteCard(
    name: String,
    price: Double?,
    changePercent: Double?,
    trace: List<Double>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val positive = (changePercent ?: 0.0) >= 0.0
    val tint = if (positive) VisualPositive else VisualNegative
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(Color(0xFF151D2E))
            .border(1.dp, Color(0xFF28364A), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick).padding(12.dp)
    ) {
        Text(name, color = Color(0xFF9BAEC4), fontSize = 9.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(5.dp))
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(price?.let { String.format(java.util.Locale.US, "%,.2f", it) } ?: "—",
                color = MaterialTheme.colorScheme.onBackground, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text(changePercent?.let { String.format(java.util.Locale.US, "%+.2f%%", it) } ?: "—",
                color = tint, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(7.dp))
        MiniSparkline(trace, tint, Modifier.fillMaxWidth().height(34.dp))
    }
}

@Composable
fun MiniSparkline(values: List<Double>, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.semantics {
        contentDescription = if (values.size >= 2) "Recent observed price trace" else "Price history is accumulating"
    }) {
        for (index in 1..3) {
            val y = size.height * index / 4f
            drawLine(VisualGrid.copy(alpha = .55f), Offset(0f, y), Offset(size.width, y), 0.7f)
        }
        if (values.size < 2) return@Canvas
        val low = values.minOrNull() ?: return@Canvas
        val high = values.maxOrNull() ?: return@Canvas
        val spread = (high - low).takeIf { it > 0.0 } ?: max(kotlin.math.abs(high) * .0001, .01)
        val path = Path()
        values.forEachIndexed { index, value ->
            val x = index.toFloat() / (values.size - 1) * size.width
            val y = size.height - ((value - low) / spread).toFloat() * (size.height - 4f) - 2f
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, tint, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Composable
fun MarketBreadthBar(advances: Int, declines: Int, unchanged: Int, modifier: Modifier = Modifier) {
    val total = (advances + declines + unchanged).coerceAtLeast(1)
    Column(modifier) {
        Row(Modifier.fillMaxWidth().height(11.dp).clip(CircleShape)) {
            if (advances > 0) Box(Modifier.weight(advances.toFloat()).fillMaxHeight().background(VisualPositive))
            if (declines > 0) Box(Modifier.weight(declines.toFloat()).fillMaxHeight().background(VisualNegative))
            if (unchanged > 0) Box(Modifier.weight(unchanged.toFloat()).fillMaxHeight().background(Color(0xFF64748B)))
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("▲ $advances", color = VisualPositive, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            Text("▼ $declines", color = VisualNegative, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            Text("• $unchanged", color = Color(0xFF9BAEC4), fontSize = 10.sp)
        }
        Text("$total stocks · market breadth", color = Color(0xFF8394A9), fontSize = 8.sp)
    }
}

@Composable
fun SectorHeatmapTile(
    label: String,
    changePercent: Double?,
    fresh: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val value = changePercent
    val background = when {
        !fresh || value == null -> Color(0xFF263449)
        value >= 0 -> VisualPositive.copy(alpha = (0.16f + (value.coerceAtMost(3.0) / 3.0 * .44f)).coerceIn(.16f, .60f))
        else -> VisualNegative.copy(alpha = (0.16f + (kotlin.math.abs(value).coerceAtMost(3.0) / 3.0 * .44f)).coerceIn(.16f, .60f))
    }
    Column(
        modifier.clip(RoundedCornerShape(10.dp)).background(background)
            .border(1.dp, Color.White.copy(alpha = .06f), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick).padding(9.dp)
    ) {
        Text(label, color = Color(0xFFE4EDF7), fontSize = 9.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Spacer(Modifier.height(4.dp))
        Text(value?.takeIf { fresh }?.let { String.format(java.util.Locale.US, "%+.2f%%", it) } ?: "—",
            color = if (value == null || !fresh) Color(0xFF9BAEC4) else if (value >= 0) VisualPositive else VisualNegative,
            fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun GracefulErrorState(
    message: String = "Data delayed. Tap to retry.",
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF263449).copy(alpha = .72f)).padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(message, color = Color(0xFFFFD27A), fontSize = 10.sp, modifier = Modifier.weight(1f))
        if (onRetry != null) TextButton(onClick = onRetry, modifier = Modifier.testTag("graceful.error.retry")) {
            Text("Retry", fontSize = 10.sp)
        }
    }
}

/** Honest chart skeleton. It does not invent candles when no validated series exists. */
@Composable
fun EmptyCandlestickChart(
    message: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
        .background(Color(0xFF0D1727)).border(1.dp, Color(0xFF29384D), RoundedCornerShape(16.dp))
        .padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.fillMaxWidth().height(150.dp)) {
            for (index in 1..4) {
                val y = size.height * index / 5f
                drawLine(VisualGrid.copy(alpha = .6f), Offset(0f, y), Offset(size.width, y), .8f)
            }
            for (index in 1..7) {
                val x = size.width * index / 8f
                drawLine(VisualGrid.copy(alpha = .45f), Offset(x, 0f), Offset(x, size.height), .7f)
            }
            val candleX = listOf(.10f, .19f, .28f, .37f, .46f, .55f, .64f, .73f, .82f, .91f)
            candleX.forEachIndexed { index, fraction ->
                val x = fraction * size.width
                val top = size.height * (.25f + (index % 4) * .035f)
                val bottom = size.height * (.65f - (index % 3) * .025f)
                drawLine(Color(0xFF64748B).copy(alpha = .22f), Offset(x, top - 12f), Offset(x, bottom + 12f), 2f)
                drawRect(Color(0xFF64748B).copy(alpha = .18f),
                    Offset(x - 4f, if (index % 2 == 0) top else top + 8f),
                    androidx.compose.ui.geometry.Size(8f, 20f))
            }
        }
        Text(message, color = Color(0xFFB7C4D4), fontSize = 11.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(5.dp))
        TextButton(onClick = onAction) { Text(actionLabel) }
    }
}
