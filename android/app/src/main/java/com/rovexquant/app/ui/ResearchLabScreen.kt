package com.rovexquant.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ResearchLabScreen() {
    var capital by rememberSaveable { mutableStateOf("") }
    var risk by rememberSaveable { mutableStateOf("1.0") }
    var preview by remember { mutableStateOf("Enter assumptions to preview your research configuration.") }
    LazyColumn(contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { SectionHeading("Test the idea before risking capital", "Research lab.", "Backtest assumptions, strategy diagnostics and out-of-sample validation.") }
        item {
            TerminalCard {
                Eyebrow("Backtest assumptions")
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(value = capital, onValueChange = { capital = it.filter(Char::isDigit).take(12) }, label = { Text("Starting capital · INR") }, placeholder = { Text("Enter amount in INR") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(9.dp))
                OutlinedTextField(value = risk, onValueChange = { risk = it.filter { char -> char.isDigit() || char == '.' }.take(5) }, label = { Text("Risk per trade · %") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                Button(onClick = {
                    val amount = capital.toLongOrNull()
                    val riskValue = risk.toDoubleOrNull()
                    preview = when {
                        amount == null || amount <= 0 -> "Capital must be a positive INR amount."
                        riskValue == null || riskValue <= 0 || riskValue > 5 -> "Risk per trade must be greater than 0% and no more than 5%."
                        else -> "Assumptions accepted for preview: ₹$amount capital · $riskValue% risk per trade. A historical dataset is still required to run a backtest."
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("Validate assumptions") }
                Spacer(Modifier.height(8.dp))
                Text(preview, color = Muted, fontSize = 11.sp, lineHeight = 17.sp)
            }
        }
        item {
            TerminalCard {
                Eyebrow("Validation protocol")
                Text("Walk-forward · Out-of-sample · Costs · Slippage · Drawdown", color = MaterialTheme.colorScheme.onBackground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, lineHeight = 21.sp)
                Spacer(Modifier.height(7.dp))
                Text("Reports must include expectancy, profit factor, maximum drawdown, turnover, exposure, trade count and tail losses. Win rate alone is not sufficient.", color = Muted, fontSize = 11.sp, lineHeight = 17.sp)
            }
        }
        item { EmptyPanel("No dataset loaded", "Backend CSV parsing is implemented, including intraday gap flags. Android file picker, local persistence and the dataset-to-backtest workflow are still pending.") }
    }
}
