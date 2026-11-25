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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rovexquant.app.data.model.MarketEquityInstrument
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
fun AiWorkspaceScreen(
    session: QuantSession,
    repository: QuantDataRepository,
    equityInstruments: List<MarketEquityInstrument> = emptyList(),
    indexKeys: Map<String, String> = emptyMap()
) {
    val scope = rememberCoroutineScope()
    var providersLoading by remember { mutableStateOf(true) }
    var providers by remember { mutableStateOf(emptyList<com.rovexquant.app.data.model.AiProvider>()) }
    var providerError by remember { mutableStateOf<String?>(null) }
    var credentialInput by remember { mutableStateOf("") }
    var credentialSaving by remember { mutableStateOf(false) }
    var credentialError by remember { mutableStateOf<String?>(null) }
    var credentialDeleting by remember { mutableStateOf(false) }
    var providerTesting by remember { mutableStateOf(false) }
    var providerTestStatus by remember { mutableStateOf<String?>(null) }
    var selectedFallbacks by remember { mutableStateOf(emptySet<String>()) }
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
            if (selectedProvider.isBlank() && providers.isNotEmpty()) {
                val firstProvider = configured.firstOrNull() ?: providers.first()
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
                    messages = updated,
                    fallbackProviders = selectedFallbacks.toList()
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
        if (briefLoading) return
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
                    strategyContext = strategyContext.trim(),
                    fallbackProviders = selectedFallbacks.toList()
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
        modifier = Modifier.fillMaxSize().testTag("ai.workspace.list"),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SectionHeading(
                "AI Studio · model and tool integrations",
                "Quant Intelligence.",
                "Connect model providers, test credentials, configure failover and run approved research tools. Verified market evidence remains authoritative."
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
                    providerError != null && providers.isEmpty() -> Text(
                        providerError ?: "AI provider discovery failed.",
                        color = Coral, fontSize = 11.sp, lineHeight = 17.sp
                    )
                    providers.isEmpty() -> Text(
                        "No supported AI providers were returned by the backend.",
                        color = Gold, fontSize = 11.sp, lineHeight = 17.sp
                    )
                    else -> {
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(7.dp)
                        ) {
                            providers.forEach { provider ->
                                FilterChip(
                                    selected = selectedProvider == provider.providerId,
                                    onClick = {
                                        selectedProvider = provider.providerId
                                        model = provider.defaultModel.orEmpty()
                                        credentialInput = ""
                                        credentialError = null
                                    },
                                    label = {
                                        Text(
                                            provider.providerId.replaceFirstChar { it.uppercase() } +
                                                if (provider.configured) " ✓" else " · setup"
                                        )
                                    }
                                )
                            }
                        }
                        val selected = providers.firstOrNull { it.providerId == selectedProvider }
                        Spacer(Modifier.height(9.dp))
                        StatusPill(
                            if (selected?.configured == true) "Provider configured" else "Credential required",
                            if (selected?.configured == true) Mint else Gold
                        )
                        Spacer(Modifier.height(7.dp))
                        OutlinedTextField(
                            value = credentialInput,
                            onValueChange = { credentialInput = it.take(4096); credentialError = null },
                            label = { Text("Provider API key") },
                            placeholder = { Text("Paste the key from the provider console") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("ai.provider.key")
                        )
                        Button(
                            onClick = {
                                val providerId = selectedProvider
                                val apiKey = credentialInput.trim()
                                if (providerId.isBlank() || apiKey.length !in 20..4096 || credentialSaving) return@Button
                                credentialSaving = true
                                credentialError = null
                                scope.launch {
                                    try {
                                        repository.saveAiProviderCredential(session, providerId, apiKey)
                                        credentialInput = ""
                                        providers = repository.fetchAiProviders(session)
                                        val updated = providers.firstOrNull { it.providerId == providerId }
                                        model = updated?.defaultModel.orEmpty().ifBlank { model }
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (failure: Exception) {
                                        credentialError = failure.message ?: "Unable to store provider credential."
                                    } finally {
                                        credentialSaving = false
                                    }
                                }
                            },
                            enabled = !credentialSaving && credentialInput.trim().length in 20..4096,
                            modifier = Modifier.fillMaxWidth().testTag("ai.provider.save")
                        ) {
                            Text(if (credentialSaving) "Encrypting on backend…" else "Save encrypted provider key")
                        }
                        credentialError?.let {
                            Text(it, color = Coral, fontSize = 10.sp, lineHeight = 15.sp)
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
                    "API keys are encrypted in the server-side vault. They are not stored in this APK or sent in prompts. Provider connectivity is verified only by the explicit test below.",
                    color = Muted, fontSize = 10.sp, lineHeight = 15.sp
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            if (providerTesting || selectedProvider.isBlank() || model.isBlank()) return@Button
                            providerTesting = true
                            providerTestStatus = null
                            scope.launch {
                                try {
                                    val result = repository.sendAiMessage(
                                        session = session, providerId = selectedProvider, model = model.trim(),
                                        messages = listOf(AiChatMessage("user", "Reply with exactly ROVEX_AI_CONNECTION_OK."))
                                    )
                                    providerTestStatus = "Connected · ${result.providerId} / ${result.model}"
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (failure: Exception) {
                                    providerTestStatus = "Connection failed: ${failure.message ?: "provider request failed"}"
                                } finally { providerTesting = false }
                            }
                        },
                        enabled = canUseAi && !providerTesting,
                        modifier = Modifier.weight(1f).testTag("ai.provider.test")
                    ) {
                        if (providerTesting) CircularProgressIndicator(modifier = Modifier.size(16.dp)) else Text("Test connection")
                    }
                    if (providers.firstOrNull { it.providerId == selectedProvider }?.credentialIds?.contains("app") == true) {
                        TextButton(
                            enabled = !credentialDeleting && !credentialSaving,
                            onClick = {
                                credentialDeleting = true
                                credentialError = null
                                scope.launch {
                                    try {
                                        repository.deleteAiProviderCredential(session, selectedProvider)
                                        providers = repository.fetchAiProviders(session)
                                        providerTestStatus = "App-stored key removed. Environment-managed keys, if any, remain configured."
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (failure: Exception) {
                                        credentialError = failure.message ?: "Unable to remove provider key."
                                    } finally { credentialDeleting = false }
                                }
                            }
                        ) { Text(if (credentialDeleting) "Removing…" else "Remove app key") }
                    }
                }
                providerTestStatus?.let {
                    Text(it, color = if (it.startsWith("Connected")) Mint else Gold, fontSize = 10.sp, lineHeight = 15.sp)
                }
                val fallbackOptions = configuredProviders.filter { it.providerId != selectedProvider && it.fallbackModelConfigured }
                Spacer(Modifier.height(8.dp))
                Eyebrow("Automatic failover · transient errors only")
                if (fallbackOptions.isEmpty()) {
                    Text("Failover becomes available when another configured provider has a server-side fallback model.", color = Muted, fontSize = 9.sp, lineHeight = 14.sp)
                } else {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        fallbackOptions.forEach { candidate ->
                            FilterChip(
                                selected = candidate.providerId in selectedFallbacks,
                                onClick = {
                                    selectedFallbacks = if (candidate.providerId in selectedFallbacks) selectedFallbacks - candidate.providerId
                                    else (selectedFallbacks + candidate.providerId).take(3).toSet()
                                },
                                label = { Text(candidate.providerId.replaceFirstChar { it.uppercase() }) }
                            )
                        }
                    }
                    Text("Fallback providers need a server-side fallback model and credential. Authentication and entitlement errors never silently switch providers.", color = Muted, fontSize = 9.sp, lineHeight = 14.sp)
                }
                credentialError?.let { Text(it, color = Coral, fontSize = 10.sp, lineHeight = 15.sp) }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHeading("Connected research tools", "NSE MCP.", "Discover permitted read-only exchange research tools and explicitly attach a selected result to an AI request.")
                NseMcpResearchPanel(
                    session = session,
                    repository = repository,
                    onAttachToAi = { context ->
                        chatInput = (context + "\n\n" + chatInput).take(10000)
                        chatError = null
                    },
                    equityInstruments = equityInstruments,
                    indexKeys = indexKeys
                )
            }
        }
        item {
            TerminalCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Eyebrow("AI conversation")
                    StatusPill(if (chatLoading) "Thinking" else if (canUseAi) "Configured" else "Setup required", if (chatLoading) Gold else if (canUseAi) Mint else Gold)
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
                    "If your CSV import is unavailable, Quant can retrieve corporate-action-adjusted daily OHLC from its TejHQ EOD adapter and calculate deterministic price evidence. If an AI provider is configured, Quant also asks it to review the strategy. Raw volume is excluded from relative-volume claims.",
                    color = MaterialTheme.colorScheme.onBackground, fontSize = 12.sp, lineHeight = 18.sp
                )
                Spacer(Modifier.height(10.dp))
                InstrumentSymbolPicker(
                    value = symbol,
                    onValueChange = { symbol = it.uppercase().take(40) },
                    label = "NSE/BSE trading symbol",
                    testTag = "ai.recovery.symbol",
                    equities = if (exchange == "NSE") equityInstruments else emptyList(),
                    indexKeys = if (exchange == "NSE") indexKeys else emptyMap(),
                    enabled = exchange == "NSE"
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
                    enabled = symbol.isNotBlank() && fromDate.length == 10 && toDate.length == 10 && !briefLoading,
                    modifier = Modifier.fillMaxWidth().testTag("ai.recovery.run")
                ) {
                    Text(
                        if (briefLoading) "Retrieving and analysing…"
                        else if (canUseAi) "Recover EOD data + analyse"
                        else "Recover adjusted EOD data"
                    )
                }
                if (briefError != null) Text(briefError!!, color = Coral, fontSize = 11.sp, lineHeight = 16.sp)
                Text(
                    "Fallback is adjusted EOD only, not intraday/live. If the source fails, recovery stops. If AI fails, the recovered bars and deterministic metrics are still returned with a warning. Quant never fabricates candles. Private personal research only.",
                    color = Muted, fontSize = 10.sp, lineHeight = 16.sp
                )
            }
        }
        if (brief != null) {
            val report = brief!!
            item {
                TerminalCard {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Eyebrow("Structurally validated · ${report.provider} · ${report.dataMode}")
                        StatusPill("EOD", Gold)
                    }
                    Spacer(Modifier.height(7.dp))
                    Text("${report.exchange}:${report.symbol}", color = MaterialTheme.colorScheme.onBackground, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Text("${report.barCount} daily bars · ${report.firstTradingDate} to ${report.latestTradingDate}", color = Muted, fontSize = 10.sp)
                    Spacer(Modifier.height(10.dp))
                    MetricTile("Latest adjusted EOD close", String.format(Locale.US, "₹%,.2f", report.evidence.latestClose), report.latestTradingDate, accent = Cyan)
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
                    val aiResult = report.ai
                    if (aiResult != null) {
                        Eyebrow("AI strategy review · ${aiResult.providerId} / ${aiResult.model}")
                        Spacer(Modifier.height(7.dp))
                        Text(aiResult.content, color = MaterialTheme.colorScheme.onBackground, fontSize = 12.sp, lineHeight = 19.sp)
                        if (aiResult.fallbackUsed) {
                            Spacer(Modifier.height(7.dp))
                            StatusPill("AI provider fallback used", Gold)
                        }
                    } else {
                        Eyebrow("Data recovered · AI review unavailable")
                        Spacer(Modifier.height(7.dp))
                        Text(
                            report.aiError ?: "The adjusted EOD data is available. Configure a backend AI provider and model to generate commentary.",
                            color = Gold, fontSize = 11.sp, lineHeight = 17.sp
                        )
                    }
                }
            }
            item {
                TerminalCard {
                    Eyebrow("Adjusted EOD bars · latest 8")
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
