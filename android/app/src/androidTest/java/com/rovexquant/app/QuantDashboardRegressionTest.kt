package com.rovexquant.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
        composeRule.onNodeWithText("Find setups, not stories").assertIsDisplayed()
        composeRule.onNodeWithText("Signals").performClick()
        composeRule.onNodeWithText("Signal centre.").assertIsDisplayed()
        composeRule.onNodeWithText("Lab").performClick()
        composeRule.onNodeWithText("Test the idea before risking capital").assertIsDisplayed()
        composeRule.onNodeWithText("Portfolio").performClick()
        composeRule.onNodeWithText("Portfolio & risk.").assertIsDisplayed()
        composeRule.onNodeWithText("Planned entry price · ₹").assertIsDisplayed()
    }
}
