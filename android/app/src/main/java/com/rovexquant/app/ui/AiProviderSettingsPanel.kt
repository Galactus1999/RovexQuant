package com.rovexquant.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rovexquant.app.data.model.AiChatMessage
import com.rovexquant.app.data.model.AiProvider
import com.rovexquant.app.data.model.AiTaskRoute
import com.rovexquant.app.data.model.QuantSession
import com.rovexquant.app.data.repository.QuantDataRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun AiProviderSettingsPanel(
    session: QuantSession,
    repository: QuantDataRepository
) {
    val scope = rememberCoroutineScope()
    var providers by remember { mutableStateOf(emptyList<AiProvider>()) }
    var routes by remember { mutableStateOf<Map<String, AiTaskRoute>>(emptyMap()) }
    var selectedProvider by rememberSaveable { mutableStateOf("") }
    var model by rememberSaveable { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        scope.launch {
            loading = true
            error = null
            try {
                providers = repository.fetchAiProviders(session)
                if (selectedProvider.isBlank() || providers.none { it.providerId == selectedProvider }) {
                    val preferred = providers.firstOrNull { it.configured } ?: providers.firstOrNull()
                    selectedProvider = preferred?.providerId.orEmpty()
                    model = preferred?.defaultModel.orEmpty()
                } else {
                    model = providers.firstOrNull { it.providerId == selectedProvider }?.defaultModel.orEmpty().ifBlank { model }
                }
                routes = repository.fetchAiTaskRoutes(session)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "Provider settings could not be loaded."
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(session, repository) { refresh() }

    val selected = providers.firstOrNull { it.providerId == selectedProvider }
    TerminalCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Eyebrow("AI provider configuration")
                Text("Connections & routing", color = MaterialTheme.colorScheme.onBackground, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text("Credentials are stored in the backend vault, never in this APK.", color = Muted, fontSize = 10.sp)
            }
            TextButton(onClick = { refresh() }, enabled = !loading) { Text("Refresh") }
        }
        if (loading && providers.isEmpty()) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        } else if (providers.isEmpty()) {
            Text("No supported providers were returned by the backend.", color = Gold, fontSize = 11.sp)
        } else {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                providers.forEach { provider ->
                    FilterChip(
                        selected = selectedProvider == provider.providerId,
                        onClick = {
                            selectedProvider = provider.providerId
                            model = provider.defaultModel.orEmpty()
                            apiKey = ""
                            status = null
                            error = null
                        },
                        label = { Text(provider.providerId.replaceFirstChar { it.uppercase() }) }
                    )
                }
            }
            Spacer(Modifier.height(9.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(selectedProvider.replaceFirstChar { it.uppercase() }, color = MaterialTheme.colorScheme.onBackground, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                StatusPill(
                    if (selected?.configured == true) "Configured" else "Credential required",
                    if (selected?.configured == true) Mint else Gold
                )
            }
            OutlinedTextField(
                value = model,
                onValueChange = { model = it.take(160); status = null },
                label = { Text("Provider model ID") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("settings.ai.model")
            )
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it.take(4096); error = null; status = null },
                label = { Text("Provider API key") },
                placeholder = { Text("Paste key to add or replace") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("settings.ai.provider.key")
            )
            Button(
                onClick = {
                    val secret = apiKey.trim()
                    if (secret.length !in 20..4096 || selectedProvider.isBlank() || saving) return@Button
                    saving = true
                    error = null
                    status = null
                    scope.launch {
                        try {
                            repository.saveAiProviderCredential(session, selectedProvider, secret)
                            apiKey = ""
                            providers = repository.fetchAiProviders(session)
                            status = "Credential saved in the encrypted backend vault."
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            error = failure.message ?: "Credential could not be saved."
                        } finally {
                            saving = false
                        }
                    }
                },
                enabled = !saving && selectedProvider.isNotBlank() && apiKey.trim().length in 20..4096,
                modifier = Modifier.fillMaxWidth().testTag("settings.ai.provider.save")
            ) {
                if (saving) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                else Text("Save encrypted provider key")
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        if (testing || selectedProvider.isBlank() || model.isBlank()) return@Button
                        testing = true
                        error = null
                        status = null
                        scope.launch {
                            try {
                                val response = repository.sendAiMessage(
                                    session = session,
                                    providerId = selectedProvider,
                                    model = model.trim(),
                                    messages = listOf(AiChatMessage("user", "Reply with exactly ROVEX_AI_CONNECTION_OK.")),
                                    fallbackProviders = emptyList(),
                                    taskType = "chat"
                                )
                                status = "Connection verified · ${response.providerId} / ${response.model}"
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                error = failure.message ?: "Provider connection test failed."
                            } finally {
                                testing = false
                            }
                        }
                    },
                    enabled = !testing && selected?.configured == true && model.isNotBlank(),
                    modifier = Modifier.weight(1f).testTag("settings.ai.provider.test")
                ) {
                    if (testing) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Text("Test connection")
                }
                if (selected?.credentialIds?.contains("app") == true) {
                    TextButton(
                        enabled = !deleting && !saving,
                        onClick = {
                            deleting = true
                            error = null
                            status = null
                            scope.launch {
                                try {
                                    repository.deleteAiProviderCredential(session, selectedProvider)
                                    providers = repository.fetchAiProviders(session)
                                    status = "App-stored credential removed."
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (failure: Exception) {
                                    error = failure.message ?: "Credential could not be removed."
                                } finally {
                                    deleting = false
                                }
                            }
                        },
                        modifier = Modifier.testTag("settings.ai.provider.delete")
                    ) { Text(if (deleting) "Removing…" else "Remove key") }
                }
            }
        }
        error?.let { Text(it, color = Coral, fontSize = 10.sp, lineHeight = 15.sp) }
        status?.let { Text(it, color = if (it.startsWith("Connection verified")) Mint else Gold, fontSize = 10.sp, lineHeight = 15.sp) }
        Spacer(Modifier.height(8.dp))
        Eyebrow("Server-owned task routes")
        if (routes.isEmpty()) {
            Text("No task routes are currently available. Configure QUANT_AI_TASK_ROUTES on the backend.", color = Gold, fontSize = 10.sp, lineHeight = 15.sp)
        } else {
            routes.toSortedMap().forEach { (task, route) ->
                Text(
                    "${task.replace('_', ' ')} · ${route.providerId}/${route.model}" +
                        if (route.fallbackProviders.isEmpty()) "" else " · fallback: ${route.fallbackProviders.joinToString()}",
                    color = Muted,
                    fontSize = 10.sp,
                    lineHeight = 15.sp,
                    modifier = Modifier.testTag("settings.ai.route.$task")
                )
            }
        }
    }
}
