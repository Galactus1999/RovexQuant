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

    LaunchedEffect(session, repository) {
        try {
            tokenConfigured = repository.fetchUpstoxConfigured(session)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            tokenConfigured = null
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
                    Text("Connections and research controls live here, not inside the market dashboard.",
                        color = Muted, fontSize = 11.sp, lineHeight = 17.sp)
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
                Text("The session is stored locally using the app's existing encrypted session store.",
                    color = Muted, fontSize = 10.sp, lineHeight = 15.sp)
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
                Text("Read-only market data access. A stored token is not proof of a working feed; live status requires valid upstream ticks.",
                    color = Muted, fontSize = 10.sp, lineHeight = 15.sp)
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
                Text("Manage model providers, encrypted API credentials, model IDs, connection tests and failover in the AI workspace.",
                    color = Muted, fontSize = 10.sp, lineHeight = 15.sp)
                Button(onClick = onOpenAiSettings, modifier = Modifier.fillMaxWidth()) {
                    Text("Open AI provider settings")
                }
            }
        }
        item {
            TerminalCard {
                Eyebrow("Data and research")
                Text("EOD, delayed exchange data, macro observations and live ticks are separate data classes. Freshness is based on the source observation, not merely the time a request completed.",
                    color = Muted, fontSize = 10.sp, lineHeight = 15.sp)
                TextButton(onClick = onOpenMarketPulse, modifier = Modifier.fillMaxWidth()) {
                    Text("Market data and ingestion status")
                }
            }
        }
        item {
            EmptyPanel(
                "Private research safeguards",
                "Provider credentials remain server-side. Do not paste secrets into research queries. Respect provider rate limits, account entitlements and data-use terms. A green connection status does not imply data completeness."
            )
        }
    }
}
