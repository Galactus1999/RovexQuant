package com.rovexquant.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rovexquant.app.data.model.AiChatMessage
import com.rovexquant.app.data.model.AiStrategyBrief
import com.rovexquant.app.data.model.QuantSession
import com.rovexquant.app.data.repository.QuantDataRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

@Composable
fun AiWorkspaceScreen(session: QuantSession, repository: QuantDataRepository) {
    val scope = rememberCoroutineScope()
    var providersLoading by remember { mutableStateOf(true) }
    var providers by remember { mutableStateOf(emptyList<com.rovexquant.app.data.model.AiProvider>()) }
    var providerError by remember { mutableStateOf<String?>(null) }
    var selectedProvider by rememberSaveable { mutableStateOf("") }
    var model by rememberSaveable { mutableStateOf("") }
    var messages by remember { mutableStateOf(emptyList<AiChatMessage>()) }
    var chatInput by rememberSaveable { mutableStateOf("") }
    var chatLoading by remember { mutableStateOf(false) }
    var chatError by remember { mutableStateOf<String?>(null) }

    var symbol by rememberSaveable { mutableStateOf("RELIANCE") }
    var exchange by rememberSaveable { mutableStateOf("NSE") }
    var fromDate by rememberSaveable {
        mutableStateOf(LocalDate.now(ZoneId.of("Asia/Kolkata")).minusDays(365).toString())
    }
    var toDate by rememberSaveable {
        mutableStateOf(LocalDate.now(ZoneId.of("Asia/Kolkata")).toString())
    }
    var strategyContext by rememberSaveable {
        mutableStateOf("Review a swing or positional strategy using trend, momentum and Donchian breakout evidence. Identify missing tests and failure modes.")
    }
    var briefLoading by remember { mutableStateOf(false) }
    var briefError by remember { mutableStateOf<String?>(null) }
    var brief by remember { mutableStateOf<AiStrategyBrief?>(null) }

    LaunchedEffect(session, repository) {
        providersLoading = true
        providerError = null
        try {
            providers = repository.fetchAiProviders(session)
            val configured = providers.filter { it.configured }
            if (selectedProvider.isBlank() && configured.isNotEmpty()) {
                val firstProvider = configured.first()
                selectedProvider = firstProvider.providerId
                model = firstProvider.defaultModel.orEmpty()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            providerError = failure.message ?: "AI provider discovery failed."
        } finally {
            providersLoading = false
        }
    }

    val configuredProviders = providers.filter { it.configured }
    val canUseAi = selectedProvider.isNotBlank() && model.isNotBlank() &&
        configuredProviders.any { it.providerId == selectedProvider }

    fun sendMessage() {
        val prompt = chatInput.trim()
        if (prompt.isEmpty() || !canUseAi || chatLoading) return
        val updated = (messages + AiChatMessage("user", prompt)).takeLast(10)
        messages = updated
        chatInput = ""
        chatError = null
        chatLoading = true
        scope.launch {
            try {
                val response = repository.sendAiMessage(
                    session = session,
                    providerId = selectedProvider,
                    model = model.trim(),
                    messages = updated
                )
                messages = (updated + AiChatMessage("assistant", response.content)).takeLast(10)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                chatError = failure.message ?: "AI request failed."
            } finally {
                chatLoading = false
            }
        }
    }

    fun recoverEodData() {
        if (!canUseAi || briefLoading) return
        brief = null
        briefError = null
        briefLoading = true
        scope.launch {
            try {
                brief = repository.requestAiStrategyBrief(
                    session = session,
                    providerId = selectedProvider,
                    model = model.trim(),
                    symbol = symbol.trim(),
                    exchange = exchange,
                    fromDate = fromDate.trim(),
                    toDate = toDate.trim(),
                    strategyContext = strategyContext.trim()
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                briefError = failure.message ?: "EOD recovery failed."
            } finally {
                briefLoading = false
            }
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SectionHeading(
                "Research copilot · evidence first",
                "Quant AI.",
                "Ask research questions, explain indicators and review strategy ideas. Market facts must come from verified data, never model guesses."
            )
        }
        item {
            TerminalCard {
                Eyebrow("AI provider")
                Spacer(Modifier.height(7.dp))
                when {
                    providersLoading -> Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Mint, strokeWidth = 2.dp)
                        Text("Checking server-side providers…", color = Muted, fontSize = 11.sp)
                    }
                    configuredProviders.isEmpty() -> Text(
                        providerError ?: "No AI provider is configured on the Quant backend. Add a provider API key and model configuration to the backend environment; never put keys in the APK.",
                        color = Gold, fontSize = 11.sp, lineHeight = 17.sp
                    )
                    else -> {
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(7.dp)
                        ) {
                            configuredProviders.forEach { provider ->
                                FilterChip(
                                    selected = selectedProvider == provider.providerId,
                                    onClick = {
                                        selectedProvider = provider.providerId
                                        model = provider.defaultModel.orEmpty()
                                    },
                                    label = { Text(provider.providerId.replaceFirstChar { it.uppercase() }) }
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it.take(160) },
                    label = { Text("Provider model ID") },
                    placeholder = { Text("Enter a model enabled for this provider") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("ai.model")
                )
                Text(
                    "Provider credentials stay on the backend. This screen sends only your app-session token.",
                    color = Muted, fontSize = 10.sp, lineHeight = 15.sp
                )
            }
        }
        item {
            TerminalCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Eyebrow("AI conversation")
                    StatusPill(if (chatLoading) "Thinking" else "Ready", if (chatLoading) Gold else Mint)
                }
                Spacer(Modifier.height(8.dp))
                if (messages.isEmpty()) {
                    Text(
                        "Start with a question about a strategy, indicator, market-data quality or research methodology.",
                        color = Muted, fontSize = 11.sp, lineHeight = 17.sp
                    )
                } else {
                    messages.forEach { message ->
                        Column(
                            Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (message.role == "user") PanelRaised else Ink)
                                .padding(11.dp)
                        ) {
                            Text(
                                if (message.role == "user") "YOU" else "QUANT AI",
                                color = if (message.role == "user") Cyan else Mint,
                                fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(message.content, color = MaterialTheme.colorScheme.onBackground, fontSize = 12.sp, lineHeight = 18.sp)
                        }
                        Spacer(Modifier.height(7.dp))
                    }
                }
                OutlinedTextField(
                    value = chatInput,
                    onValueChange = { chatInput = it.take(6000) },
                    label = { Text("Ask Quant AI") },
                    placeholder = { Text("Explain this setup or help structure a research test…") },
                    modifier = Modifier.fillMaxWidth().testTag("ai.chat.input"),
                    minLines = 2,
                    maxLines = 5
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = ::sendMessage,
                    enabled = canUseAi && chatInput.isNotBlank() && !chatLoading,
                    modifier = Modifier.fillMaxWidth().testTag("ai.chat.send")
                ) {
                    Text(if (chatLoading) "Generating…" else "Send to Quant AI")
                }
                if (chatError != null) Text(chatError!!, color = Coral, fontSize = 11.sp, lineHeight = 16.sp)
                TextButton(onClick = { messages = emptyList(); chatError = null }, modifier = Modifier.fillMaxWidth()) {
                    Text("Clear conversation")
                }
            }
        }
        item {
            TerminalCard {
                Eyebrow("Final strategy stage · data rescue")
                Spacer(Modifier.height(6.dp))
                Text(
                    "If your CSV import is unavailable, Quant can retrieve verified daily OHLCV from its configured TejHQ EOD adapter, validate it, calculate deterministic evidence and ask AI to review the strategy.",
                    color = MaterialTheme.colorScheme.onBackground, fontSize = 12.sp, lineHeight = 18.sp
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = symbol,
                    onValueChange = { symbol = it.uppercase().filter { ch -> ch.isLetterOrDigit() || ch == '-' || ch == '&' }.take(30) },
                    label = { Text("NSE/BSE trading symbol") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("ai.recovery.symbol")
                )
                Spacer(Modifier.height(7.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("NSE", "BSE").forEach { item ->
                        FilterChip(selected = exchange == item, onClick = { exchange = item }, label = { Text(item) })
                    }
                }
                OutlinedTextField(
                    value = fromDate, onValueChange = { fromDate = it.take(10) },
                    label = { Text("From date · YYYY-MM-DD") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("ai.recovery.from")
                )
                OutlinedTextField(
                    value = toDate, onValueChange = { toDate = it.take(10) },
                    label = { Text("To date · YYYY-MM-DD") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("ai.recovery.to")
                )
                OutlinedTextField(
                    value = strategyContext,
                    onValueChange = { strategyContext = it.take(1500) },
                    label = { Text("Strategy to review") },
                    minLines = 2, maxLines = 4,
                    modifier = Modifier.fillMaxWidth().testTag("ai.recovery.strategy")
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = ::recoverEodData,
                    enabled = canUseAi && symbol.isNotBlank() && fromDate.length == 10 && toDate.length == 10 && !briefLoading,
                    modifier = Modifier.fillMaxWidth().testTag("ai.recovery.run")
                ) {
                    Text(if (briefLoading) "Retrieving and analysing…" else "Recover EOD data + analyse")
                }
                if (briefError != null) Text(briefError!!, color = Coral, fontSize = 11.sp, lineHeight = 16.sp)
                Text(
                    "Fallback is EOD only, not intraday/live. If the source or AI provider is unavailable, Quant reports failure; it never fabricates candles. Private personal research only.",
                    color = Muted, fontSize = 10.sp, lineHeight = 16.sp
                )
            }
        }
        if (brief != null) {
            val report = brief!!
            item {
                TerminalCard {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Eyebrow("Verified source · ${report.dataMode}")
                        StatusPill("EOD", Gold)
                    }
                    Spacer(Modifier.height(7.dp))
                    Text("${report.exchange}:${report.symbol}", color = MaterialTheme.colorScheme.onBackground, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Text("${report.barCount} daily bars · ${report.firstTradingDate} to ${report.latestTradingDate}", color = Muted, fontSize = 10.sp)
                    Spacer(Modifier.height(10.dp))
                    MetricTile("Latest EOD close", String.format(Locale.US, "₹%,.2f", report.evidence.latestClose), report.latestTradingDate, accent = Cyan)
                    Spacer(Modifier.height(8.dp))
                    AiIndicatorLine("SMA 20", report.evidence.sma20?.let { String.format(Locale.US, "₹%,.2f", it) } ?: "Insufficient history")
                    AiIndicatorLine("SMA 50", report.evidence.sma50?.let { String.format(Locale.US, "₹%,.2f", it) } ?: "Insufficient history")
                    AiIndicatorLine("RSI 14", report.evidence.rsi14?.let { String.format(Locale.US, "%.1f", it) } ?: "Unavailable")
                    AiIndicatorLine("ADX 14", report.evidence.adx14?.let { String.format(Locale.US, "%.1f", it) } ?: "Unavailable")
                    AiIndicatorLine("Donchian 20", report.evidence.donchianState)
                    AiIndicatorLine("Relative volume", report.evidence.relativeVolume20?.let { String.format(Locale.US, "%.2fx", it) } ?: "Unavailable")
                    AiIndicatorLine("Trend context", report.evidence.trendContext)
                }
            }
            item {
                TerminalCard {
                    Eyebrow("AI strategy review · ${report.ai.providerId} / ${report.ai.model}")
                    Spacer(Modifier.height(7.dp))
                    Text(report.ai.content, color = MaterialTheme.colorScheme.onBackground, fontSize = 12.sp, lineHeight = 19.sp)
                    if (report.ai.fallbackUsed) {
                        Spacer(Modifier.height(7.dp))
                        StatusPill("AI provider fallback used", Gold)
                    }
                }
            }
            item {
                TerminalCard {
                    Eyebrow("Recovered source bars · latest 8")
                    Spacer(Modifier.height(5.dp))
                    report.bars.takeLast(8).asReversed().forEach { bar ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(bar.date, color = Muted, fontSize = 9.sp)
                            Text(String.format(Locale.US, "O %.2f  H %.2f  L %.2f  C %.2f", bar.open, bar.high, bar.low, bar.close), color = MaterialTheme.colorScheme.onBackground, fontSize = 9.sp)
                        }
                    }
                    Spacer(Modifier.height(7.dp))
                    Text(report.dataRights, color = Gold, fontSize = 10.sp, lineHeight = 16.sp)
                    report.limitations.forEach { limitation ->
                        Text("• $limitation", color = Muted, fontSize = 10.sp, lineHeight = 16.sp)
                    }
                }
            }
        }
    }
}


@Composable
private fun AiIndicatorLine(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = Muted, fontSize = 11.sp)
        Text(value, color = MaterialTheme.colorScheme.onBackground, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}
