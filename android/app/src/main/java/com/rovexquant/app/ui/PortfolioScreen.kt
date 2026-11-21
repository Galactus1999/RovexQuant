package com.rovexquant.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
fun PortfolioScreen() {
    var capital by rememberSaveable { mutableStateOf("") }
    var risk by rememberSaveable { mutableStateOf("1.0") }
    val amount = capital.toDoubleOrNull() ?: 0.0
    val riskPercent = risk.toDoubleOrNull() ?: 0.0
    val riskBudget = if (amount > 0 && riskPercent in 0.0..5.0) amount * riskPercent / 100.0 else 0.0
    LazyColumn(contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { SectionHeading("Capital-aware, never capital-blind", "Portfolio & risk.", "Set your own capital and risk limits. No fixed account size is assumed.") }
        item {
            TerminalCard {
                Eyebrow("Your risk profile")
                Spacer(Modifier.height(9.dp))
                OutlinedTextField(value = capital, onValueChange = { capital = it.filter(Char::isDigit).take(12) }, label = { Text("Capital · INR") }, placeholder = { Text("Enter your capital") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = risk, onValueChange = { risk = it.filter { char -> char.isDigit() || char == '.' }.take(5) }, label = { Text("Maximum planned risk per trade · %") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(14.dp))
                Text("Indicative risk budget", color = Muted, fontSize = 10.sp)
                Text(if (amount > 0 && riskPercent in 0.0..5.0) "₹" + "%,.0f".format(riskBudget) else "—", color = Mint, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                Text("Capital × risk percentage. This is a planning limit, not a suggested trade.", color = Muted, fontSize = 10.sp, lineHeight = 16.sp)
            }
        }
        item {
            TerminalCard {
                Eyebrow("Exposure controls")
                Text("Position allocation · Sector concentration · Maximum open positions · Daily loss cap", color = MaterialTheme.colorScheme.onBackground, fontSize = 13.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(7.dp))
                Text("Portfolio-wide exposure and drawdown controls will be enforced by the deterministic risk engine, never by an AI model.", color = Muted, fontSize = 11.sp, lineHeight = 17.sp)
            }
        }
        item {
            TerminalCard {
                Eyebrow("Connections")
                Text("Zerodha · Dhan · AI providers", color = MaterialTheme.colorScheme.onBackground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(5.dp))
                Text("Not configured. Optional integrations remain disabled until official API terms, permissions and secure credential storage are implemented.", color = Muted, fontSize = 11.sp, lineHeight = 17.sp)
            }
        }
    }
}
