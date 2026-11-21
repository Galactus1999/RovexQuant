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
import com.rovexquant.app.domain.PositionSizingCalculator
import java.util.Locale

@Composable
fun PortfolioScreen() {
    var capital by rememberSaveable { mutableStateOf("") }
    var risk by rememberSaveable { mutableStateOf("1.0") }
    var entry by rememberSaveable { mutableStateOf("") }
    var stop by rememberSaveable { mutableStateOf("") }
    var allocation by rememberSaveable { mutableStateOf("25") }
    val sizing = PositionSizingCalculator.calculate(
        capital.toDoubleOrNull() ?: Double.NaN,
        risk.toDoubleOrNull() ?: Double.NaN,
        entry.toDoubleOrNull() ?: Double.NaN,
        stop.toDoubleOrNull() ?: Double.NaN,
        allocation.toDoubleOrNull() ?: Double.NaN
    )
    LazyColumn(contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { SectionHeading("Capital-aware, never capital-blind", "Portfolio & risk.", "Calculate a cash-equity position from your own capital, stop distance and risk limit.") }
        item {
            TerminalCard {
                Eyebrow("Your risk profile")
                Spacer(Modifier.height(9.dp))
                OutlinedTextField(value = capital, onValueChange = { capital = it.filter { c -> c.isDigit() || c == '.' }.take(14) }, label = { Text("Capital · INR") }, placeholder = { Text("₹5,000 or more") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = risk, onValueChange = { risk = it.filter { c -> c.isDigit() || c == '.' }.take(5) }, label = { Text("Maximum planned risk per trade · % (up to 5)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = allocation, onValueChange = { allocation = it.filter { c -> c.isDigit() || c == '.' }.take(5) }, label = { Text("Maximum capital allocation · %") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = entry, onValueChange = { entry = it.filter { c -> c.isDigit() || c == '.' }.take(12) }, label = { Text("Planned entry price · ₹") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = stop, onValueChange = { stop = it.filter { c -> c.isDigit() || c == '.' }.take(12) }, label = { Text("Stop-loss price · ₹") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(14.dp))
                Text("Position sizing result", color = Muted, fontSize = 10.sp)
                if (sizing == null) {
                    Text("Enter valid capital, risk, allocation, entry and a stop below entry.", color = Gold, fontSize = 12.sp, lineHeight = 18.sp)
                } else if (sizing.quantity == 0) {
                    Text("Risk/allocation budget is too small to buy one share at this entry price.", color = Gold, fontSize = 12.sp, lineHeight = 18.sp)
                } else {
                    Text(sizing.quantity.toString() + " shares", color = Mint, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Text("Estimated investment  " + String.format(Locale.US, "₹%,.2f", sizing.investment), color = MaterialTheme.colorScheme.onBackground, fontSize = 12.sp)
                    Text("Planned loss at stop  " + String.format(Locale.US, "₹%,.2f", sizing.plannedStopLoss), color = Coral, fontSize = 12.sp)
                    Text("Risk budget  " + String.format(Locale.US, "₹%,.2f", sizing.riskBudget) + " · Risk/share " + String.format(Locale.US, "₹%.2f", sizing.riskPerShare), color = Muted, fontSize = 10.sp, lineHeight = 16.sp)
                }
                Spacer(Modifier.height(8.dp))
                Text("Cash-equity estimate only. Excludes brokerage, statutory charges, slippage, partial fills and gap-through-stop losses. It does not place an order.", color = Muted, fontSize = 10.sp, lineHeight = 16.sp)
            }
        }
        item {
            TerminalCard {
                Eyebrow("Portfolio safeguards")
                Text("Position allocation · Sector concentration · Maximum open positions · Daily loss cap", color = MaterialTheme.colorScheme.onBackground, fontSize = 13.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(7.dp))
                Text("Portfolio-wide exposure and drawdown controls will be enforced by the deterministic risk engine, never by an AI model.", color = Muted, fontSize = 11.sp, lineHeight = 17.sp)
            }
        }
        item {
            TerminalCard {
                Eyebrow("Connections")
                Text("Dhan · Zerodha · Upstox · Angel One", color = MaterialTheme.colorScheme.onBackground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(5.dp))
                Text("Not configured. Broker integrations remain disabled until official API terms, permissions and secure credential storage are implemented.", color = Muted, fontSize = 11.sp, lineHeight = 17.sp)
            }
        }
    }
}
