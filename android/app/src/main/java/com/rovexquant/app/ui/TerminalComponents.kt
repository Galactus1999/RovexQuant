package com.rovexquant.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun TerminalCard(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Panel)
        .border(1.dp, Line.copy(alpha = .8f), RoundedCornerShape(22.dp)).padding(17.dp), content = content)
}
@Composable
fun SectionHeading(kicker: String, title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp)) {
        Text(kicker.uppercase(), color = Mint, fontSize = 9.sp, letterSpacing = 1.6.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(5.dp))
        Text(title, color = MaterialTheme.colorScheme.onBackground, fontSize = 27.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(5.dp))
        Text(subtitle, color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
    }
}
@Composable
fun Eyebrow(text: String) { Text(text.uppercase(), color = Mint, fontSize = 9.sp, letterSpacing = 1.35.sp, fontWeight = FontWeight.Bold) }
@Composable
fun MetricTile(label: String, value: String, hint: String, accent: Color = Mint, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(18.dp)).background(Panel).border(1.dp, Line, RoundedCornerShape(18.dp)).padding(14.dp)) {
        Text(label.uppercase(), color = Muted, fontSize = 9.sp, letterSpacing = .8.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp)); Text(value, color = accent, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(3.dp)); Text(hint, color = Muted, fontSize = 10.sp)
    }
}
@Composable
fun StatusPill(label: String, color: Color = Gold) {
    Row(Modifier.clip(CircleShape).background(color.copy(alpha = .10f)).border(1.dp, color.copy(alpha = .25f), CircleShape).padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(5.dp).clip(CircleShape).background(color)); Spacer(Modifier.width(6.dp))
        Text(label.uppercase(), color = color, fontSize = 8.sp, letterSpacing = .8.sp, fontWeight = FontWeight.Bold)
    }
}
@Composable
fun EmptyPanel(title: String, body: String, accent: Color = Cyan) {
    TerminalCard {
        Row(verticalAlignment = Alignment.Top) {
            Box(Modifier.size(32.dp).clip(RoundedCornerShape(11.dp)).background(accent.copy(alpha = .12f)), contentAlignment = Alignment.Center) { Text("i", color = accent, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = MaterialTheme.colorScheme.onBackground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(5.dp)); Text(body, color = Muted, fontSize = 11.sp, lineHeight = 17.sp)
            }
        }
    }
}
@Composable
fun TerminalHeader(status: String = "Research · Offline", onSignOut: (() -> Unit)? = null, onOpenSettings: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(13.dp)).background(PanelRaised), contentAlignment = Alignment.Center) { Text("Q", color = Mint, fontSize = 24.sp, fontWeight = FontWeight.Black) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("QUANT", color = MaterialTheme.colorScheme.onBackground, fontSize = 15.sp, fontWeight = FontWeight.Black, letterSpacing = 1.8.sp, modifier = Modifier.testTag("quant.header"))
            Text("INDIAN MARKET INTELLIGENCE", color = Muted, fontSize = 8.sp, letterSpacing = 1.25.sp)
        }
        if (onOpenSettings != null) {
            Text("⚙", color = MaterialTheme.colorScheme.onBackground, fontSize = 20.sp,
                modifier = Modifier.clickable { onOpenSettings() }.padding(horizontal = 8.dp).testTag("quant.settings.open"))
        }
        if (onSignOut != null) {
            Text("Sign out", color = Muted, fontSize = 9.sp, modifier = Modifier.clickable { onSignOut() }.padding(horizontal = 6.dp))
        }
        StatusPill(status)
    }
}
