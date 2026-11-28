package com.rovexquant.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import com.rovexquant.app.data.local.ResearchCsvBackgroundSyncScheduler
import com.rovexquant.app.ui.QuantTerminal
import com.rovexquant.app.ui.RovexTheme

open class MainActivity : ComponentActivity() {
    protected open fun createWorkspaceViewModelFactory(): ViewModelProvider.Factory? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ResearchCsvBackgroundSyncScheduler.ensureScheduled(applicationContext)
        setContent { RovexTheme { QuantTerminal(createWorkspaceViewModelFactory()) } }
    }
}
