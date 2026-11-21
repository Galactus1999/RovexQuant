package com.rovexquant.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QuantDashboardRegressionTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun dashboardRendersProductIdentityAndCoreSections() {
        composeRule.onNodeWithText("RovexQuant").assertIsDisplayed()
        composeRule.onNodeWithText("Quantitative research • Paper trading • Signals only").assertIsDisplayed()
        composeRule.onNodeWithText("Data: Local / not connected").assertIsDisplayed()
        composeRule.onNodeWithText("Broker connections: Not configured").assertIsDisplayed()
        composeRule.onNodeWithText("AI analysis: Optional / not configured").assertIsDisplayed()
    }

    @Test
    fun dashboardDoesNotClaimLiveFeedOrBrokerConnection() {
        composeRule.onNodeWithText("Data: Local / not connected").assertIsDisplayed()
        composeRule.onNodeWithText("Broker connections: Not configured").assertIsDisplayed()
    }
}
