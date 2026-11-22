package com.rovexquant.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rovexquant.app.data.model.MarketEvent
import com.rovexquant.app.data.model.QuantSession
import com.rovexquant.app.data.repository.QuantDataRepository
import com.rovexquant.app.feature.intelligence.IntelligenceCentreViewModel

@Composable
fun IntelligenceCentreScreen(session: QuantSession, repository: QuantDataRepository) {
    val factory = remember(session, repository) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(IntelligenceCentreViewModel::class.java))
                return IntelligenceCentreViewModel(session, repository) as T
            }
        }
    }
    val model: IntelligenceCentreViewModel = viewModel(
        key = "intelligence-${session.baseUrl}",
        factory = factory
    )
    val state by model.uiState.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("India OR oil OR war OR technology OR central bank") }
    var category by rememberSaveable { mutableStateOf("ALL") }
    val uriHandler = LocalUriHandler.current

    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("intelligence.screen"),
        contentPadding = PaddingValues(start = 17.dp, end = 17.dp, top = 8.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            SectionHeading(
                "Research intelligence · evidence first",
                "AI Intelligence Centre",
                "Global developments translated into transparent Indian-sector exposure hypotheses. Chat is separate and optional."
            )
        }
        item {
            TerminalCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Eyebrow("Global event discovery")
                    StatusPill(state.feed?.source ?: "GDELT", Cyan)
                }
                Spacer(Modifier.height(7.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it.take(160) },
                    label = { Text("Search global developments") },
                    placeholder = { Text("India, oil, conflict, AI, central banks…") },
                    modifier = Modifier.fillMaxWidth().testTag("intelligence.query"),
                    minLines = 1,
                    maxLines = 2
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    listOf("1d", "3d", "7d").forEach { span ->
                        FilterChip(
                            selected = state.timespan == span,
                            onClick = { model.refresh(query, span) },
                            label = { Text(span) }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { model.refresh(query, state.timespan) },
                    enabled = !state.loading && query.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().testTag("intelligence.refresh")
                ) {
                    Text(if (state.loading) "Retrieving developments…" else "Refresh intelligence")
                }
                Text(
                    "On-demand retrieval · global news discovery, not a live market feed.",
                    color = Muted, fontSize = 10.sp, lineHeight = 15.sp
                )
            }
        }
        if (state.loading && state.feed == null) {
            item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Mint) } }
        }
        if (state.error != null) {
            item { EmptyPanel("Intelligence retrieval unavailable", state.error ?: "Try again later.", Coral) }
        }
        state.feed?.let { feed ->
            item {
                TerminalCard {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Eyebrow("Evidence inventory")
                            Text("${feed.eventCount} distinct events", color = MaterialTheme.colorScheme.onBackground, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            Text("${feed.articleCount} source articles · ${feed.timespan} window", color = Muted, fontSize = 10.sp)
                        }
                        StatusPill("On demand", Gold)
                    }
                    Text("Retrieved ${feed.retrievedAt}", color = Muted, fontSize = 9.sp)
                }
            }
            item {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("ALL", "WAR_CONFLICT", "MONETARY_POLICY", "COMMODITY_SUPPLY", "TECHNOLOGY_INNOVATION", "GEOPOLITICS_TRADE", "REGULATION_POLICY").forEach { item ->
                        FilterChip(
                            selected = category == item,
                            onClick = { category = item },
                            label = { Text(item.replace('_', ' ').take(14), fontSize = 9.sp) }
                        )
                    }
                }
            }
            val visibleEvents = feed.events.filter { category == "ALL" || it.eventCategory == category }
            if (visibleEvents.isEmpty()) {
                item { EmptyPanel("No matching events", "No event in this retrieval window matched the selected category. This is not evidence that the topic has no market impact.") }
            } else {
                items(visibleEvents, key = { it.eventId }) { event ->
                    IntelligenceEventCard(event = event, onOpenSource = { url ->
                        runCatching { uriHandler.openUri(url) }
                    })
                }
            }
            item {
                TerminalCard {
                    Eyebrow("Coverage and interpretation limits")
                    feed.limitations.forEach { limitation ->
                        Text("• $limitation", color = Muted, fontSize = 10.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun IntelligenceEventCard(event: MarketEvent, onOpenSource: (String) -> Unit) {
    TerminalCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Eyebrow(event.eventCategory.replace('_', ' '))
            StatusPill("${event.independentSourceCount} sources", if (event.independentSourceCount > 1) Mint else Gold)
        }
        Spacer(Modifier.height(6.dp))
        Text(event.headline, color = MaterialTheme.colorScheme.onBackground, fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(7.dp))
        Text(
            "Rule-match score ${(event.classificationConfidence * 100).toInt()}% · heuristic, not a probability",
            color = Muted, fontSize = 9.sp
        )
        if (event.sectorImpacts.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Eyebrow("Potential sector transmission")
            event.sectorImpacts.forEach { impact ->
                Column(Modifier.fillMaxWidth().padding(top = 7.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(impact.sector.replace('_', ' '), color = MaterialTheme.colorScheme.onBackground, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text(impact.direction.replace('_', ' '), color = when {
                            impact.direction.contains("NEGATIVE") -> Coral
                            impact.direction.contains("SUPPORTIVE") -> Mint
                            else -> Gold
                        }, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(impact.mechanism, color = Muted, fontSize = 10.sp, lineHeight = 15.sp)
                }
            }
        } else {
            Text("No deterministic sector mapping exists for this headline.", color = Muted, fontSize = 10.sp)
        }
        Spacer(Modifier.height(9.dp))
        Text(
            event.stockMappingNote,
            color = Gold, fontSize = 10.sp, lineHeight = 15.sp
        )
        event.sources.forEach { source ->
            if (source.url.startsWith("https://") || source.url.startsWith("http://")) {
                Text(
                    "↗ ${source.domain.ifBlank { "Open original source" }} · seen ${source.seenAt}",
                    color = Cyan, fontSize = 10.sp,
                    modifier = Modifier.padding(top = 7.dp).clickable { onOpenSource(source.url) }
                )
            }
        }
    }
}
