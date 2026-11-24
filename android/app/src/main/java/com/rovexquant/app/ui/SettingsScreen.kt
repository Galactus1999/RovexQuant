package com.rovexquant.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rovexquant.app.data.model.QuantSession
import com.rovexquant.app.data.model.MarketIngestionStatus
import com.rovexquant.app.data.model.MarketDataHealth
import com.rovexquant.app.data.repository.QuantDataRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    session: QuantSession,
    repository: QuantDataRepository,
    onBack: () -> Unit,
    onOpenAiSettings: () -> Unit,
    onOpenMarketPulse: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var tokenConfigured by remember { mutableStateOf<Boolean?>(null) }
    var tokenInput by remember { mutableStateOf("") }
    var savingToken by remember { mutableStateOf(false) }
    var tokenError by remember { mutableStateOf<String?>(null) }
    var providersLoading by remember { mutableStateOf(true) }
    var configuredAiProviders by remember { mutableStateOf(0) }
    var providerError by remember { mutableStateOf<String?>(null) }
    var ingestionStatus by remember { mutableStateOf<MarketIngestionStatus?>(null) }
    var ingestionLoading by remember { mutableStateOf(false) }
    var ingestionError by remember { mutableStateOf<String?>(null) }
    var dataHealth by remember { mutableStateOf<MarketDataHealth?>(null) }

    LaunchedEffect(session, repository) {
        try {
            tokenConfigured = repository.fetchUpstoxConfigured(session)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            tokenConfigured = null
        }
        try {
            ingestionLoading = true
            ingestionStatus = repository.fetchMarketIngestionStatus(session, "nse")
            dataHealth = repository.fetchMarketDataHealth(session, "NSE")
            ingestionError = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            ingestionError = failure.message ?: "Database status unavailable"
        } finally {
            ingestionLoading = false
        }
        try {
            configuredAiProviders = repository.fetchAiProviders(session).count { it.configured }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            providerError = failure.message ?: "AI provider status unavailable"
        } finally {
            providersLoading = false
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("settings.screen"),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Eyebrow("Workspace configuration")
                    Text("Settings", color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 27.sp, fontWeight = FontWeight.Bold)
                }
                TextButton(onClick = onBack) { Text("Close") }
            }
        }
        item {
            TerminalCard {
                Eyebrow("Backend session")
                Text("Authenticated backend", color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(session.baseUrl, color = Muted, fontSize = 10.sp)

            }
        }
        item {
            TerminalCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Eyebrow("Market data provider")
                    StatusPill(
                        when (tokenConfigured) {
                            true -> "Credential stored"
                            false -> "Not configured"
                            null -> "Status unavailable"
                        },
                        if (tokenConfigured == true) Mint else Gold
                    )
                }
                Text("Upstox Analytics Token", color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold)

                OutlinedTextField(
                    value = tokenInput,
                    onValueChange = { tokenInput = it.take(4096); tokenError = null },
                    label = { Text("Replace Analytics Token") },
                    placeholder = { Text("Paste token; never share it in chat") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("settings.upstox.token")
                )
                Button(
                    onClick = {
                        val value = tokenInput.trim()
                        if (value.length !in 20..4096 || savingToken) return@Button
                        savingToken = true
                        tokenError = null
                        scope.launch {
                            try {
                                repository.saveUpstoxAnalyticsToken(session, value)
                                tokenInput = ""
                                tokenConfigured = true
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                tokenError = failure.message ?: "Unable to store the token."
                            } finally {
                                savingToken = false
                            }
                        }
                    },
                    enabled = !savingToken && tokenInput.trim().length in 20..4096,
                    modifier = Modifier.fillMaxWidth().testTag("settings.upstox.save")
                ) {
                    if (savingToken) CircularProgressIndicator(modifier = Modifier.size(17.dp), strokeWidth = 2.dp)
                    else Text("Save encrypted token")
                }
                tokenError?.let { Text(it, color = Coral, fontSize = 10.sp) }
                TextButton(onClick = onOpenMarketPulse, modifier = Modifier.fillMaxWidth()) {
                    Text("Open Market Pulse · verify received ticks")
                }
            }
        }
        item {
            TerminalCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Eyebrow("AI provider connections")
                    StatusPill(
                        if (providersLoading) "Checking" else "$configuredAiProviders configured",
                        if (!providersLoading && configuredAiProviders > 0) Mint else Gold
                    )
                }
                providerError?.let { Text(it, color = Coral, fontSize = 10.sp) }

                Button(onClick = onOpenAiSettings, modifier = Modifier.fillMaxWidth()) {
                    Text("Open AI provider settings")
                }
            }
        }
        item {
            TerminalCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Eyebrow("Database & data health")
                    StatusPill(
                        dataHealth?.overallStatus ?: if (ingestionLoading) "Checking" else "Unavailable",
                        when (dataHealth?.overallStatus) {
                            "HEALTHY" -> Mint
                            "DEGRADED", "FAILED" -> Coral
                            else -> Gold
                        }
                    )
                }
                Text(
                    dataHealth?.let { "EOD · ${it.eod.status} · ${it.eod.records ?: 0} rows" } ?: "Status unavailable",
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    dataHealth?.let { "Reconciliation · ${it.reconciliation.status}" } ?: "",
                    color = Muted,
                    fontSize = 10.sp
                )
            }
        }
        item {
            TerminalCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Eyebrow("Database connection")
                    StatusPill(
                        ingestionStatus?.status ?: if (ingestionLoading) "Checking" else "Unavailable",
                        when (ingestionStatus?.status) {
                            "SUCCEEDED" -> Mint
                            "FAILED", "EMPTY" -> Coral
                            else -> Gold
                        }
                    )
                }
                Text(
                    ingestionStatus?.lastSuccessfulTradingDate?.let { "EOD · $it · ${ingestionStatus?.lastSuccessfulRows ?: 0} rows" }
                        ?: ingestionStatus?.status?.replace('_', ' ') ?: "Status unavailable",
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                ingestionError?.let { Text(it, color = Coral, fontSize = 10.sp) }
                ingestionStatus?.errorMessage?.takeIf { ingestionStatus?.status == "FAILED" }?.let {
                    Text(it, color = Coral, fontSize = 10.sp)
                }
                Button(
                    onClick = {
                        if (!ingestionLoading) scope.launch {
                            ingestionLoading = true
                            ingestionError = null
                            try {
                                ingestionStatus = repository.fetchMarketIngestionStatus(session, "nse")
                                dataHealth = repository.fetchMarketDataHealth(session, "NSE")
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                ingestionError = failure.message ?: "Database status unavailable"
                            } finally {
                                ingestionLoading = false
                            }
                        }
                    },
                    enabled = !ingestionLoading,
                    modifier = Modifier.fillMaxWidth().testTag("settings.database.refresh")
                ) {
                    if (ingestionLoading) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Text("Refresh status")
                }
            }
        }
    }
}
