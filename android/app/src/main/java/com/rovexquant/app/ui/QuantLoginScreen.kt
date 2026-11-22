package com.rovexquant.app.ui

import com.rovexquant.app.data.local.QuantSessionStore
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun QuantLoginScreen(
    savedConnection: QuantSessionStore.SavedConnection,
    loading: Boolean,
    error: String?,
    onLogin: (String, String, String) -> Unit
) {
    var baseUrl by remember(savedConnection.baseUrl) { mutableStateOf(savedConnection.baseUrl) }
    var username by remember(savedConnection.username) { mutableStateOf(savedConnection.username) }
    var password by remember { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().background(Ink).imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("QUANT", color = Mint, fontSize = 30.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
        Spacer(Modifier.height(6.dp))
        Text("Secure market-data sign in", color = MaterialTheme.colorScheme.onBackground, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text("Your backend and username are remembered on this device. Your password is never saved.", color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(22.dp))
        OutlinedTextField(baseUrl, { baseUrl = it }, Modifier.fillMaxWidth(), label = { Text("Backend HTTPS URL") }, singleLine = true)
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(username, { username = it }, Modifier.fillMaxWidth(), label = { Text("Username") }, singleLine = true)
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
        Spacer(Modifier.height(14.dp))
        error?.let { Text(it, color = Color(0xFFFF8B80), fontSize = 11.sp) }
        Button(
            onClick = { onLogin(baseUrl, username, password) },
            enabled = !loading && baseUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(14.dp)
        ) {
            if (loading) CircularProgressIndicator() else Text("Connect securely")
        }
        Spacer(Modifier.height(14.dp))
        Text("Your session is encrypted with Android Keystore and restored automatically until it expires. Use HTTPS only.", color = Muted, fontSize = 10.sp, lineHeight = 15.sp)
    }
}
