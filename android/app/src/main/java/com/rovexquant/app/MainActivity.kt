package com.rovexquant.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.rovexquant.app.ui.QuantTerminal
import com.rovexquant.app.ui.RovexTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RovexTheme {
                QuantTerminal()
            }
        }
    }
}
