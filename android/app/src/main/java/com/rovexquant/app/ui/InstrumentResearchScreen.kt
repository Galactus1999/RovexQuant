package com.rovexquant.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rovexquant.app.data.repository.QuantDataRepository
import com.rovexquant.app.domain.OhlcvBar
import com.rovexquant.app.data.local.TejHqOhlcvStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

@Composable
fun InstrumentResearchScreen(symbol: String, displayName: String, repository: QuantDataRepository, onBack: () -> Unit) {
    val context = LocalContext.current
    var bars by remember(symbol) { mutableStateOf<List<OhlcvBar>>(emptyList()) }
    var loading by remember(symbol) { mutableStateOf(true) }
    var error by remember(symbol) { mutableStateOf<String?>(null) }
    var range by rememberSaveable(symbol) { mutableStateOf("1Y") }
    var volume by rememberSaveable(symbol) { mutableStateOf(true) }
    var sma20 by rememberSaveable(symbol) { mutableStateOf(false) }
    var sma50 by rememberSaveable(symbol) { mutableStateOf(false) }
    LaunchedEffect(symbol) {
        loading = true
        error = null
        val today = LocalDate.now(ZoneId.of("Asia/Kolkata"))
        val settings = TejHqOhlcvStore.settings(context)
        val imported = withContext(Dispatchers.IO) {
            if (settings.symbol.equals(symbol, ignoreCase = true) && settings.exchange.equals("nse", ignoreCase = true)) {
                runCatching { TejHqOhlcvStore.load(context).orEmpty() }.getOrDefault(emptyList())
            } else emptyList()
        }
        val fetched = withContext(Dispatchers.IO) { runCatching { repository.fetchTejHqOhlcv("nse", symbol, today.minusYears(3), today) } }
        bars = fetched.getOrNull()?.takeIf { it.isNotEmpty() } ?: imported
        if (bars.isEmpty()) error = fetched.exceptionOrNull()?.message ?: "No OHLCV history found. Fetch this symbol in Research Lab."
        loading = false
    }
    val visible = remember(bars, range) {
        val today = LocalDate.now(ZoneId.of("Asia/Kolkata"))
        val cutoff = when (range) { "1M" -> today.minusMonths(1); "3M" -> today.minusMonths(3); "6M" -> today.minusMonths(6); "1Y" -> today.minusYears(1); else -> null }
        if (cutoff == null) bars else bars.filter { !it.timestamp.toLocalDate().isBefore(cutoff) }
    }
    Column(Modifier.fillMaxSize().background(Ink).padding(16.dp)) {
        TextButton(onClick = onBack) { Text("← Back to Pulse") }
        Text(symbol, color = MaterialTheme.colorScheme.onBackground, fontSize = 25.sp, fontWeight = FontWeight.Bold)
        Text(displayName, color = Muted, fontSize = 11.sp)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            listOf("1M", "3M", "6M", "1Y", "ALL").forEach { p -> FilterChip(selected = range == p, onClick = { range = p }, label = { Text(p) }) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            FilterChip(selected = volume, onClick = { volume = !volume }, label = { Text("Volume") })
            FilterChip(selected = sma20, onClick = { sma20 = !sma20 }, label = { Text("SMA 20") })
            FilterChip(selected = sma50, onClick = { sma50 = !sma50 }, label = { Text("SMA 50") })
        }
        Spacer(Modifier.height(10.dp))
        when {
            loading -> CircularProgressIndicator()
            visible.isEmpty() -> TerminalCard { Text(error ?: "No bars for selected period", color = Muted) }
            else -> TerminalCard {
                Text("OHLCV · " + visible.size + " bars · " + visible.first().timestamp.toLocalDate() + " to " + visible.last().timestamp.toLocalDate(), color = Muted, fontSize = 10.sp)
                InteractivePriceChart(visible, volume, sma20, sma50)
                Text("Historical close: " + visible.last().close.toString() + "   Volume: " + visible.last().volume.toString(), color = MaterialTheme.colorScheme.onBackground, fontSize = 11.sp)
            }
        }
        error?.let { Text(it, color = Coral, fontSize = 10.sp) }
    }
}

@Composable
private fun InteractivePriceChart(bars: List<OhlcvBar>, showVolume: Boolean, showSma20: Boolean, showSma50: Boolean) {
    var selected by remember(bars) { mutableIntStateOf(bars.lastIndex) }
    val bar = bars.getOrNull(selected)
    Column {
        bar?.let { Text(it.timestamp.toLocalDate().toString() + "  O " + it.open + " H " + it.high + " L " + it.low + " C " + it.close, color = MaterialTheme.colorScheme.onBackground, fontSize = 10.sp) }
        Canvas(Modifier.fillMaxWidth().height(if (showVolume) 230.dp else 180.dp).pointerInput(bars) { detectTapGestures { p -> selected = ((p.x / size.width) * bars.size).toInt().coerceIn(bars.indices) } }) {
            if (bars.isEmpty()) return@Canvas
            val low = bars.minOf { it.low }; val high = bars.maxOf { it.high }; val spread = (high - low).takeIf { it > 0.0 } ?: 1.0
            val volumeHeight = if (showVolume) size.height * .22f else 0f; val priceHeight = size.height - volumeHeight - 8f
            val step = size.width / (bars.size - 1).coerceAtLeast(1)
            fun y(v: Double) = priceHeight - ((v - low) / spread * (priceHeight - 12f)).toFloat() - 6f
            val path = Path(); bars.forEachIndexed { i, b -> if (i == 0) path.moveTo(0f, y(b.close)) else path.lineTo(i * step, y(b.close)) }
            drawPath(path, androidx.compose.ui.graphics.Color(0xFF63A9FF), style = Stroke(2.5f))
            if (showSma20 && bars.size >= 20) { val p = Path(); for (i in 19 until bars.size) { val v = bars.subList(i - 19, i + 1).map { it.close }.average(); if (i == 19) p.moveTo(i * step, y(v)) else p.lineTo(i * step, y(v)) }; drawPath(p, androidx.compose.ui.graphics.Color(0xFF27C58B), style = Stroke(1.8f)) }
            if (showSma50 && bars.size >= 50) { val p = Path(); for (i in 49 until bars.size) { val v = bars.subList(i - 49, i + 1).map { it.close }.average(); if (i == 49) p.moveTo(i * step, y(v)) else p.lineTo(i * step, y(v)) }; drawPath(p, androidx.compose.ui.graphics.Color(0xFFFF6678), style = Stroke(1.8f)) }
            if (showVolume) { val maxVol = bars.maxOf { it.volume }.coerceAtLeast(1L).toFloat(); bars.forEachIndexed { i, b -> val top = size.height - b.volume.toFloat() / maxVol * volumeHeight; drawLine(if (b.close >= b.open) androidx.compose.ui.graphics.Color(0xFF27C58B) else androidx.compose.ui.graphics.Color(0xFFFF6678), Offset(i * step, size.height), Offset(i * step, top), strokeWidth = (size.width / bars.size).coerceAtLeast(1f) * .65f) } }
            val x = selected * step; drawLine(androidx.compose.ui.graphics.Color.Gray, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
        }
        Text("Tap chart to inspect individual OHLC bars", color = Muted, fontSize = 9.sp)
    }
}