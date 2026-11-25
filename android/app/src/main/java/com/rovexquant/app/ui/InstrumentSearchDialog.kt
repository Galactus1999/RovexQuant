package com.rovexquant.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.rovexquant.app.data.model.MarketEquityInstrument

private data class InstrumentSearchResult(
    val symbol: String,
    val name: String,
    val type: String
)

@Composable
fun InstrumentSearchDialog(
    equities: List<MarketEquityInstrument>,
    indexKeys: Map<String, String>,
    onDismiss: () -> Unit,
    onSelect: (symbol: String, displayName: String) -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    val allItems = remember(equities, indexKeys) {
        buildList {
            indexKeys.keys.forEach { name ->
                add(InstrumentSearchResult(name, name, "INDEX"))
            }
            equities.forEach { item ->
                add(InstrumentSearchResult(item.tradingSymbol, item.name, "NSE EQUITY"))
            }
        }.distinctBy { it.symbol.uppercase() }
    }
    val results = remember(allItems, query) {
        val normalized = query.trim().uppercase().replace(Regex("[^A-Z0-9]+"), " ").trim()
        if (normalized.isBlank()) {
            allItems.sortedWith(compareBy<InstrumentSearchResult> { if (it.type == "INDEX") 0 else 1 }
                .thenBy { it.name }).take(60)
        } else {
            fun score(item: InstrumentSearchResult): Int {
                val symbol = item.symbol.uppercase().replace(Regex("[^A-Z0-9]+"), " ").trim()
                val name = item.name.uppercase().replace(Regex("[^A-Z0-9]+"), " ").trim()
                return when {
                    symbol == normalized -> 0
                    name == normalized -> 1
                    symbol.startsWith(normalized) -> 2
                    name.startsWith(normalized) -> 3
                    name.split(" ").any { it.startsWith(normalized) } -> 4
                    symbol.contains(normalized) -> 5
                    name.contains(normalized) -> 6
                    normalized.split(" ").all { token -> name.contains(token) || symbol.contains(token) } -> 7
                    else -> Int.MAX_VALUE
                }
            }
            allItems.map { it to score(it) }
                .filter { it.second != Int.MAX_VALUE }
                .sortedWith(compareBy<Pair<InstrumentSearchResult, Int>> { it.second }
                    .thenBy { it.first.name })
                .take(60)
                .map { it.first }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                .testTag("instrument.global.search.dialog"),
            color = PanelRaised,
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Find a company or index", color = MaterialTheme.colorScheme.onBackground,
                            fontSize = 19.sp, fontWeight = FontWeight.Bold)
                        Text("${equities.size} NSE companies · ${indexKeys.size} indices in the on-device name library",
                            color = Muted, fontSize = 9.sp)
                    }
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().testTag("instrument.global.search.input"),
                    singleLine = true,
                    placeholder = { Text("Company name, ticker or index") },
                    label = { Text("Search by name or symbol") }
                )
                Spacer(Modifier.height(8.dp))
                if (results.isEmpty()) {
                    Text(
                        if (equities.isEmpty()) "The NSE instrument master is not loaded. Reconnect and try again."
                        else "No matching company or index in the current instrument master.",
                        color = Muted, fontSize = 12.sp,
                        modifier = Modifier.padding(vertical = 18.dp)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp)
                            .testTag("instrument.global.search.results")
                    ) {
                        items(results, key = { "${it.type}:${it.symbol}" }) { item ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable { onSelect(item.symbol, item.name) }
                                    .testTag("instrument.global.search.result.${item.symbol}")
                                    .padding(vertical = 11.dp, horizontal = 5.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(item.name, color = MaterialTheme.colorScheme.onBackground,
                                        fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                    Text(item.symbol, color = Muted, fontSize = 10.sp)
                                }
                                Text(item.type, color = Cyan, fontSize = 8.sp)
                            }
                            HorizontalDivider(color = Muted.copy(alpha = 0.25f))
                        }
                    }
                }
            }
        }
    }
}


@Composable
fun InstrumentSymbolPicker(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    testTag: String,
    equities: List<MarketEquityInstrument>,
    indexKeys: Map<String, String>,
    placeholder: String = "Search by name or symbol",
    enabled: Boolean = true
) {
    var showSearch by remember(testTag) { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            modifier = Modifier.weight(1f).testTag(testTag),
            singleLine = true,
            label = { Text(label) },
            placeholder = { Text(placeholder) }
        )
        TextButton(
            onClick = { showSearch = true },
            enabled = enabled,
            modifier = Modifier.testTag("$testTag.find")
        ) { Text("Find") }
    }
    if (showSearch) {
        InstrumentSearchDialog(
            equities = equities,
            indexKeys = indexKeys,
            onDismiss = { showSearch = false },
            onSelect = { symbol, _ ->
                onValueChange(symbol)
                showSearch = false
            }
        )
    }
}
