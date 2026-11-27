package com.rovexquant.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SignalsScreen() {
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("signals.screen"),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SectionHeading(
                "No alerts until deterministic evaluation is connected",
                "Signals.",
                "The signal engine is not active. RovexQuant will not fabricate entries, stops, targets or confidence scores."
            )
        }
        item {
            TerminalCard {
                Eyebrow("Signal engine · not enabled")
                Spacer(Modifier.height(7.dp))
                Text("No signals are being generated.", color = MaterialTheme.colorScheme.onBackground, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(5.dp))
                Text(
                    "This screen currently has no strategy-evaluation service, signal persistence or alert delivery behind it. The lifecycle filters are intentionally removed until there are real signals to filter.",
                    color = Muted,
                    fontSize = 11.sp,
                    lineHeight = 18.sp
                )
            }
        }
        item {
            EmptyPanel(
                "Required before activation",
                "Deterministic strategy evaluation, completed-bar data validation, freshness checks, risk-limit enforcement, persistent signal lifecycle and replay tests."
            )
        }
        item {
            TerminalCard {
                Eyebrow("Execution boundary")
                Text(
                    "No broker order endpoint is connected. AI commentary cannot create, approve or execute a signal.",
                    color = Coral,
                    fontSize = 11.sp,
                    lineHeight = 17.sp
                )
            }
        }
    }
}
