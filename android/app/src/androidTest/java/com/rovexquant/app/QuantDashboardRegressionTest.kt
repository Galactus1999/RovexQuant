package com.rovexquant.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
        composeRule.onNodeWithTag("quant.header").assertIsDisplayed()
        composeRule.onNodeWithText("Market pulse.").assertIsDisplayed()
        composeRule.onNodeWithTag("market.feed.status").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("market.sector.heading").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("sector.index.NIFTY BANK").performScrollTo().assertIsDisplayed()
    }

    @Test fun bottomNavigationOpensAllWorkspacesAndSectorCoverage() {
        composeRule.onNodeWithText("Scan").performClick()
        composeRule.onNodeWithText("Strategy scanner.").assertIsDisplayed()
        composeRule.onNodeWithText("No local dataset loaded").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Signals").performClick()
        composeRule.onNodeWithText("Signal centre.").assertIsDisplayed()
        composeRule.onNodeWithText("Lab").performClick()
        composeRule.onNodeWithText("Research lab.").assertIsDisplayed()
        composeRule.onNodeWithText("Import OHLCV CSV").assertIsDisplayed()
        composeRule.onNodeWithText("Portfolio").performClick()
        composeRule.onNodeWithText("Portfolio & risk.").assertIsDisplayed()
        composeRule.onNodeWithTag("portfolio.entry").assertIsDisplayed()
    }

    @Test fun portfolioCalculatesRiskBasedShareQuantity() {
        composeRule.onNodeWithText("Portfolio").performClick()
        composeRule.onNodeWithTag("portfolio.capital").performTextInput("5000")
        composeRule.onNodeWithTag("portfolio.allocation").performTextClearance()
        composeRule.onNodeWithTag("portfolio.allocation").performTextInput("100")
        composeRule.onNodeWithTag("portfolio.entry").performTextInput("250")
        composeRule.onNodeWithTag("portfolio.stop").performTextInput("244")
        composeRule.onNodeWithTag("portfolio.quantity").assertTextEquals("8 shares")
        composeRule.onNodeWithTag("portfolio.stopLoss").assertTextEquals("Planned loss at stop  ₹48.00")
    }
}
