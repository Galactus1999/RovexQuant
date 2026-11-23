package com.rovexquant.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rovexquant.app.data.model.QuantSession
import com.rovexquant.app.data.repository.QuantDataRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun AiResearchToolsPanel(
    session: QuantSession,
    repository: QuantDataRepository,
    onAttachToAi: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var loadingTool by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }

    fun runTool(id: String, label: String, action: suspend () -> String) {
        if (loadingTool != null) return
        loadingTool = id
        status = null
        scope.launch {
            try {
                val evidence = action().take(8000)
                onAttachToAi(evidence)
                status = "$label attached to the draft. The provider receives it only after you send the message."
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                status = "$label failed: ${failure.message ?: "source unavailable"}"
            } finally {
                loadingTool = null
            }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Research context tools", color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(
                "Fetch authenticated source data and attach a bounded evidence summary to your AI draft. No data is sent to a model until you press Send.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, lineHeight = 15.sp
            )
            Button(
                onClick = {
                    runTool("market", "NSE EOD snapshot") {
                        val snapshot = repository.fetchLatestEodMarketSnapshot(session, "NSE")
                        buildString {
                            appendLine("NSE EOD SNAPSHOT — not live or intraday.")
                            appendLine("marketRef=[market:EOD:${snapshot.tradingDate}]; provider=${snapshot.provider}; mode=${snapshot.dataMode}; exchange=${snapshot.exchange}; tradingDate=${snapshot.tradingDate}; totalRows=${snapshot.totalRows}; pricedSymbols=${snapshot.pricedSymbols}.")
                            appendLine("Breadth: advances=${snapshot.advances}, declines=${snapshot.declines}, unchanged=${snapshot.unchanged}.")
                            appendLine("Top gainers:")
                            snapshot.topGainers.take(5).forEach { move ->
                                appendLine("${move.symbol} (${move.name}) close=${move.close?.let { String.format(Locale.US, "%.2f", it) } ?: "n/a"}; changePct=${move.changePercent?.let { String.format(Locale.US, "%.2f", it) } ?: "n/a"}; volume=${move.volume}.")
                            }
                            appendLine("Top losers:")
                            snapshot.topLosers.take(5).forEach { move ->
                                appendLine("${move.symbol} (${move.name}) close=${move.close?.let { String.format(Locale.US, "%.2f", it) } ?: "n/a"}; changePct=${move.changePercent?.let { String.format(Locale.US, "%.2f", it) } ?: "n/a"}; volume=${move.volume}.")
                            }
                            appendLine("Most active:")
                            snapshot.mostActiveByVolume.take(5).forEach { move ->
                                appendLine("${move.symbol} (${move.name}) volume=${move.volume}; turnover=${move.turnover?.let { String.format(Locale.US, "%.2f", it) } ?: "n/a"}.")
                            }
                            appendLine("Preserve trading date and source. Never infer live prices from this EOD snapshot.")
                        }
                    }
                },
                enabled = loadingTool == null,
                modifier = Modifier.fillMaxWidth().testTag("ai.tool.market")
            ) {
                if (loadingTool == "market") CircularProgressIndicator() else Text("Fetch NSE EOD market snapshot")
            }
            Button(
                onClick = {
                    runTool("events", "Intelligence feed") {
                        val feed = repository.fetchIntelligenceEvents(
                            session,
                            "India OR geopolitics OR war OR sanctions OR oil OR energy OR AI OR semiconductor OR defence OR RBI OR SEBI",
                            "1d"
                        )
                        buildString {
                            appendLine("DISCOVERY-ONLY INTELLIGENCE FEED — untrusted source records, not verified facts.")
                            appendLine("source=${feed.source}; retrievedAt=${feed.retrievedAt}; query=${feed.query}; timespan=${feed.timespan}; articles=${feed.articleCount}; eventClusters=${feed.eventCount}.")
                            feed.events.take(12).forEach { event ->
                                appendLine("eventId=${event.eventId}; headline=${event.headline}")
                                appendLine("category=${event.eventCategory}; tags=${event.eventTags.joinToString()}; independentSources=${event.independentSourceCount}/${event.sourceArticleCount}.")
                                appendLine("classification=${event.classificationMethod}; confidence=${event.classificationConfidence}; semantics=${event.confidenceSemantics}.")
                                appendLine("stockMapping=${event.stockMappingStatus}; note=${event.stockMappingNote}; impactStatus=${event.impactStatus}.")
                                event.sectorImpacts.take(4).forEach { impact ->
                                    appendLine("Sector hypothesis: ${impact.sector} ${impact.direction}: ${impact.mechanism}")
                                }
                                event.issuerMappings.take(4).forEach { mapping ->
                                    appendLine("Issuer candidate: ${mapping.exchange}:${mapping.symbol}; ISIN=${mapping.isin}; confidence=${mapping.confidence}; scope=${mapping.mappingScope}; evidence=${mapping.evidence}.")
                                }
                                event.sources.take(3).forEach { source ->
                                    appendLine("Source: ${source.domain}; seenAt=${source.seenAt}; class=${source.evidenceClass}; URL=${source.url}")
                                }
                            }
                            if (feed.limitations.isNotEmpty()) appendLine("Limitations: ${feed.limitations.joinToString(" | ")}")
                            appendLine("Treat event classifications and impact paths as hypotheses. Verify material claims against primary filings before company conclusions.")
                        }
                    }
                },
                enabled = loadingTool == null,
                modifier = Modifier.fillMaxWidth().testTag("ai.tool.events")
            ) {
                if (loadingTool == "events") CircularProgressIndicator() else Text("Fetch global / India intelligence events")
            }
            Button(
                onClick = {
                    runTool("macro", "Macro research snapshot") {
                        val macro = repository.fetchMacroResearch(session)
                        buildString {
                            appendLine("INDIA MACRO RESEARCH SNAPSHOT — preserve provider, period and units.")
                            appendLine("retrievedAt=${macro.retrievedAt}; persistedObservations=${macro.persistedObservationCount}; RBIH DBIE mirror series=${macro.rbiDbieSeriesCount}.")
                            macro.worldBankSeries.forEach { series ->
                                appendLine("macroKey=${series.key}; label=${series.label}: value=${series.latestValue ?: "unavailable"} ${series.unit}; period=${series.latestPeriod ?: "not supplied"}; frequency=${series.frequency}; source=${series.source}.")
                            }
                            appendLine("FRED configured=${macro.fredConfigured}; seriesCount=${macro.fredSeriesCount}.")
                            appendLine("AMFI NAV observations=${macro.amfiNavCount}; mutual-fund NAV is not an equity quote.")
                            macro.amfiExamples.take(3).forEach { item ->
                                appendLine("AMFI ${item.schemeName}: NAV=${item.nav}; date=${item.navDate}.")
                            }
                            if (macro.providerStatuses.isNotEmpty()) appendLine("Provider statuses: ${macro.providerStatuses.joinToString(" | ")}")
                            if (macro.providerErrors.isNotEmpty()) appendLine("Provider limitations/errors: ${macro.providerErrors.joinToString(" | ")}")
                            appendLine("Do not forward-fill low-frequency macro series into daily market bars. Distinguish annual/monthly macro periods from market sessions.")
                        }
                    }
                },
                enabled = loadingTool == null,
                modifier = Modifier.fillMaxWidth().testTag("ai.tool.macro")
            ) {
                if (loadingTool == "macro") CircularProgressIndicator() else Text("Fetch macroeconomic context")
            }
            status?.let {
                Text(
                    it,
                    color = if (it.contains("failed", ignoreCase = true)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    fontSize = 10.sp,
                    lineHeight = 15.sp
                )
            }
        }
    }
}
