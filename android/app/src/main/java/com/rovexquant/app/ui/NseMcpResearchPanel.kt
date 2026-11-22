package com.rovexquant.app.ui

import com.rovexquant.app.data.model.NseMcpCatalog
import com.rovexquant.app.data.model.NseMcpResult
import com.rovexquant.app.data.model.NseMcpTool
import com.rovexquant.app.data.model.QuantSession
import com.rovexquant.app.data.repository.QuantDataRepository

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
fun NseMcpResearchPanel(session: QuantSession, repository: QuantDataRepository) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var server by remember { mutableStateOf("bhavcopy") }
    var catalogs by remember { mutableStateOf<List<NseMcpCatalog>>(emptyList()) }
    var selectedTool by remember { mutableStateOf<NseMcpTool?>(null) }
    var arguments by remember { mutableStateOf("{}") }
    var loadingCatalog by remember { mutableStateOf(false) }
    var calling by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<NseMcpResult?>(null) }

    val currentCatalog = catalogs.firstOrNull { it.server == server }
    val readOnlyTools = currentCatalog?.tools?.filter { it.readOnly }.orEmpty()

    fun loadCatalog(targetServer: String = server) {
        loadingCatalog = true
        error = null
        result = null
        selectedTool = null
        arguments = "{}"
        scope.launch {
            try {
                val loaded = repository.fetchNseMcpTools(session, targetServer)
                catalogs = catalogs.filterNot { old -> loaded.any { it.server == old.server } } + loaded
                val selectedServer = if (targetServer == "both") server else targetServer
                val selected = loaded.firstOrNull { it.server == selectedServer }?.tools?.firstOrNull { it.readOnly }
                selectedTool = selected
                arguments = "{}"
                if (selected == null) error = loaded.firstOrNull { it.server == selectedServer }?.failure
                    ?: "NSE did not publish any eligible read-only tools for this source."
            } catch (failure: Exception) {
                error = failure.message ?: "Could not discover NSE research tools."
            } finally {
                loadingCatalog = false
            }
        }
    }

    LaunchedEffect(session) { loadCatalog("both") }

    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        TerminalCard {
            Eyebrow("Official NSE MCP · on-demand research")
            Spacer(Modifier.height(6.dp))
            Text(
                "Private educational research only. Bhavcopy is end-of-day; CM market data is delayed, not tick-by-tick live.",
                color = Muted, fontSize = 10.sp, lineHeight = 16.sp
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = server == "bhavcopy",
                    enabled = !loadingCatalog && !calling,
                    onClick = { if (server != "bhavcopy") { server = "bhavcopy"; loadCatalog("bhavcopy") } },
                    label = { Text("Bhavcopy · EOD") }
                )
                FilterChip(
                    selected = server == "cmmkt",
                    enabled = !loadingCatalog && !calling,
                    onClick = { if (server != "cmmkt") { server = "cmmkt"; loadCatalog("cmmkt") } },
                    label = { Text("CM market · delayed") }
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    if (loadingCatalog) "Discovering official tools…" else "${readOnlyTools.size} read-only tools available",
                    color = Muted, fontSize = 10.sp
                )
                TextButton(onClick = { loadCatalog("both") }, enabled = !loadingCatalog && !calling) {
                    Text("Refresh tools")
                }
            }
            if (loadingCatalog) CircularProgressIndicator()
            if (readOnlyTools.isNotEmpty()) {
                Text("SELECT A RESEARCH TOOL", color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    readOnlyTools.forEach { tool ->
                        FilterChip(
                            selected = selectedTool?.name == tool.name,
                            enabled = !loadingCatalog && !calling,
                            onClick = {
                                selectedTool = tool
                                arguments = "{}"
                                result = null
                                error = null
                            },
                            label = { Text(tool.name, maxLines = 1) }
                        )
                    }
                }
                selectedTool?.let { tool ->
                    Text(tool.description.ifBlank { "No description supplied by NSE." }, color = Muted, fontSize = 10.sp, lineHeight = 15.sp)
                    Text("INPUT SCHEMA", color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    Text(
                        tool.inputSchema.toString(2).take(5000),
                        color = Cyan, fontSize = 9.sp, lineHeight = 13.sp
                    )
                    OutlinedTextField(
                        value = arguments,
                        onValueChange = { arguments = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Tool arguments · JSON object") },
                        minLines = 3,
                        maxLines = 7,
                        enabled = !calling
                    )
                    Button(
                        onClick = {
                            val chosen = selectedTool ?: return@Button
                            calling = true
                            error = null
                            result = null
                            scope.launch {
                                try {
                                    val response = repository.callNseMcpTool(session, server, chosen.name, arguments)
                                    result = response
                                } catch (failure: Exception) {
                                    error = failure.message ?: "NSE research request failed."
                                } finally {
                                    calling = false
                                }
                            }
                        },
                        enabled = !calling && !loadingCatalog && selectedTool != null,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (calling) CircularProgressIndicator() else Text("Run research request")
                    }
                }
            }
            error?.let {
                Text(it, color = Coral, fontSize = 10.sp, lineHeight = 15.sp)
            }
        }
        result?.let { response ->
            TerminalCard {
                Eyebrow("NSE response · ${response.dataMode}")
                Spacer(Modifier.height(6.dp))
                Text(response.provider, color = Mint, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Source timestamp: ${response.sourceTimestamp ?: "not supplied by NSE"}" +
                        (response.expectedDelay?.let { " · Expected delay: $it" } ?: ""),
                    color = Muted, fontSize = 10.sp, lineHeight = 15.sp
                )
                TextButton(onClick = { clipboard.setText(AnnotatedString(response.resultJson)) }) {
                    Text("Copy full response")
                }
                Spacer(Modifier.height(7.dp))
                Text(response.resultJson.take(16000), color = MaterialTheme.colorScheme.onBackground, fontSize = 9.sp, lineHeight = 13.sp)
                if (response.resultJson.length > 16000) {
                    Text("Response display truncated at 16 KB. The API response itself was not stored.", color = Gold, fontSize = 9.sp)
                }
            }
        }
        Text(
            "No raw MCP archive, redistribution, commercial use, order execution or AI-model training. Do not use this research connector as an automated trading feed.",
            color = Muted, fontSize = 9.sp, lineHeight = 14.sp,
            modifier = Modifier.padding(horizontal = 3.dp)
        )
    }
}
