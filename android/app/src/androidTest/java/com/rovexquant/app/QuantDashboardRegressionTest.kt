package com.rovexquant.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QuantDashboardRegressionTest {
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

    @Test fun appStartsWithQuantMarketPulseAndHonestOfflineState() {
        composeRule.onNodeWithText("QUANT").assertIsDisplayed()
        composeRule.onNodeWithText("Market pulse.").assertIsDisplayed()
        composeRule.onNodeWithText("Feed not connected").assertIsDisplayed()
        composeRule.onNodeWithText("MARKET DATA NOT CONNECTED").assertIsDisplayed()
    }

    @Test fun bottomNavigationOpensAllWorkspaces() {
        composeRule.onNodeWithText("Scan").performClick()
        composeRule.onNodeWithText("FIND SETUPS, NOT STORIES").assertIsDisplayed()
        composeRule.onNodeWithText("Signals").performClick()
        composeRule.onNodeWithText("Signal centre.").assertIsDisplayed()
        composeRule.onNodeWithText("Lab").performClick()
        composeRule.onNodeWithText("Research lab.").assertIsDisplayed()
        composeRule.onNodeWithText("Import OHLCV CSV").assertIsDisplayed()
        composeRule.onNodeWithText("Portfolio").performClick()
        composeRule.onNodeWithText("Portfolio & risk.").assertIsDisplayed()
        composeRule.onNodeWithText("Planned entry price · ₹").assertIsDisplayed()
    }

    @Test fun portfolioCalculatesRiskBasedShareQuantity() {
        composeRule.onNodeWithText("Portfolio").performClick()
        composeRule.onNodeWithText("Capital · INR").performTextInput("5000")
        composeRule.onNodeWithText("Maximum capital allocation · %").performTextClearance()
        composeRule.onNodeWithText("Maximum capital allocation · %").performTextInput("100")
        composeRule.onNodeWithText("Planned entry price · ₹").performTextInput("250")
        composeRule.onNodeWithText("Stop-loss price · ₹").performTextInput("244")
        composeRule.onNodeWithText("8 shares").assertIsDisplayed()
        composeRule.onNodeWithText("Planned loss at stop  ₹48.00").assertIsDisplayed()
    }
}
