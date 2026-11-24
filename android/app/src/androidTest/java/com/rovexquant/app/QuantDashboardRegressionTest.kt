package com.rovexquant.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Workspace tests start from a deterministic authenticated fixture. They do not
 * exercise the login flow; authentication has its own test host and test class.
 */
@RunWith(AndroidJUnit4::class)
class QuantDashboardRegressionTest {
    @get:Rule val composeRule = createAndroidComposeRule<QuantTestActivity>()

    private fun waitForDashboard() {
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("quant.header").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun appStartsWithQuantMarketPulseAndHonestOfflineState() {
        waitForDashboard()
        composeRule.onNodeWithTag("quant.header").assertIsDisplayed()
        composeRule.onNodeWithText("Market pulse.").assertIsDisplayed()
        composeRule.onNodeWithTag("market.pulse.list").performScrollToNode(hasTestTag("market.eod.snapshot"))
        composeRule.onNodeWithTag("market.eod.snapshot").assertIsDisplayed()
        composeRule.onNodeWithText("END OF DAY").assertIsDisplayed()
        // The EOD card intentionally collapses mover rows by default. Wait for
        // the asynchronous snapshot payload, then explicitly expand it before
        // asserting that the fixture symbol is rendered.
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("2450 priced symbols", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Show market movers").performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("FIXTURE").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("market.pulse.list")
            .performScrollToNode(hasTestTag("market.ingestion.status"))
        composeRule.onNodeWithTag("market.ingestion.status").assertIsDisplayed()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("Persisted EOD data", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("market.pulse.list")
            .performScrollToNode(hasText("Persisted EOD data", substring = true))
        composeRule.onNodeWithText("Persisted EOD data", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("market.pulse.list").performScrollToNode(hasText("FIXTURE"))
        composeRule.onNodeWithText("FIXTURE").assertIsDisplayed()
        composeRule.onNodeWithTag("market.pulse.list").performScrollToNode(hasTestTag("market.feed.status"))
        composeRule.onNodeWithTag("market.feed.status").assertIsDisplayed()
        composeRule.onNodeWithTag("market.pulse.list").performScrollToNode(hasTestTag("market.sector.heading"))
        composeRule.onNodeWithTag("market.sector.heading").assertIsDisplayed()
        composeRule.onNodeWithTag("market.pulse.list").performScrollToNode(hasTestTag("sector.index.NIFTY BANK"))
        composeRule.onNodeWithTag("sector.index.NIFTY BANK").assertIsDisplayed()
    }

    @Test fun bottomNavigationOpensAllWorkspacesAndSectorCoverage() {
        waitForDashboard()
        composeRule.onNodeWithText("Scan").performClick()
        composeRule.onNodeWithText("Strategy scanner.").assertIsDisplayed()
        composeRule.onNodeWithTag("scanner.list").performScrollToNode(hasText("No local dataset loaded"))
        composeRule.onNodeWithText("No local dataset loaded").assertIsDisplayed()
        composeRule.onNodeWithText("Signals").performClick()
        composeRule.onNodeWithText("Signal centre.").assertIsDisplayed()
        composeRule.onNodeWithText("Lab").performClick()
        composeRule.onNodeWithText("Research lab.").assertIsDisplayed()
        composeRule.onNodeWithTag("research.lab.list").performScrollToNode(hasText("Import OHLCV CSV"))
        composeRule.onNodeWithText("Import OHLCV CSV").assertIsDisplayed()
        composeRule.onNodeWithText("Portfolio").performClick()
        composeRule.onNodeWithText("Portfolio & risk.").assertIsDisplayed()
        composeRule.onNodeWithTag("portfolio.entry").assertIsDisplayed()
    }


    @Test fun aiWorkspaceConnectsChatAndEodRecoveryActions() {
        waitForDashboard()
        composeRule.onNodeWithText("Scan").performClick()
        composeRule.onNodeWithTag("scanner.list").performScrollToNode(hasText("Open AI data recovery"))
        composeRule.onNodeWithText("Open AI data recovery").performClick()
        composeRule.onNodeWithText("Quant Intelligence.").assertIsDisplayed()
        composeRule.onNodeWithText("Groq", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("ai.workspace.list").performScrollToNode(hasTestTag("ai.model"))
        composeRule.onNodeWithTag("ai.model").performTextInput("test-model")
        composeRule.onNodeWithTag("ai.workspace.list").performScrollToNode(hasTestTag("ai.chat.input"))
        composeRule.onNodeWithTag("ai.chat.input").performTextInput("Explain the RSI indicator.")
        composeRule.onNodeWithTag("ai.workspace.list").performScrollToNode(hasTestTag("ai.chat.send"))
        composeRule.onNodeWithTag("ai.chat.send").assertIsDisplayed()
        composeRule.onNodeWithTag("ai.chat.send").performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("Deterministic AI fixture response.").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("ai.workspace.list").performScrollToNode(hasTestTag("ai.recovery.run"))
        composeRule.onNodeWithTag("ai.recovery.run").performClick()
        composeRule.onNodeWithTag("ai.workspace.list").performScrollToNode(
            hasText("Deterministic EOD strategy fixture response.")
        )
        composeRule.onNodeWithText("Deterministic EOD strategy fixture response.").assertIsDisplayed()
    }

    @Test fun intelligenceCentreLoadsEventEvidenceAndSectorTransmission() {
        waitForDashboard()
        composeRule.onNodeWithText("Intel").performClick()
        composeRule.onNodeWithText("AI Intelligence Centre").assertIsDisplayed()
        composeRule.onNodeWithText("No research run yet").assertIsDisplayed()
        composeRule.onNodeWithText("Refresh intelligence").performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("intelligence.feed.ready")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("intelligence.screen")
            .performScrollToNode(hasTestTag("intelligence.feed.ready"))
        listOf(
            "AVIATION",
            "Fuel costs may pressure operating margins.",
            "Company and instrument mapping is not yet enabled.",
            "↗ fixture.example · seen 20261122T090000Z"
        ).forEach { evidence ->
            composeRule.onNodeWithTag("intelligence.screen").performScrollToNode(hasText(evidence))
            composeRule.onNodeWithText(evidence).assertIsDisplayed()
        }
    }

    @Test fun portfolioCalculatesRiskBasedShareQuantity() {
        waitForDashboard()
        composeRule.onNodeWithText("Portfolio").performClick()
        composeRule.onNodeWithTag("portfolio.capital").performTextInput("5000")
        composeRule.onNodeWithTag("portfolio.allocation").performTextClearance()
        composeRule.onNodeWithTag("portfolio.allocation").performTextInput("100")
        composeRule.onNodeWithTag("portfolio.entry").performTextInput("250")
        composeRule.onNodeWithTag("portfolio.stop").performTextInput("244")
        composeRule.onNodeWithTag("portfolio.quantity").assertTextEquals("8 shares")
        composeRule.onNodeWithTag("portfolio.stopLoss").assertTextEquals("Planned loss at stop  ₹48.00")
    }

    @Test fun signOutReturnsToLoginAndClearsWorkspaceState() {
        waitForDashboard()
        composeRule.onNodeWithText("Sign out").performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("login.password").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Connect securely").assertIsDisplayed()
    }
}

/** Authentication is tested independently from dashboard/workspace rendering. */
@RunWith(AndroidJUnit4::class)
class QuantLoginRegressionTest {
    @get:Rule val composeRule = createAndroidComposeRule<QuantLoginTestActivity>()

    private fun waitForLoginForm() {
        composeRule.waitUntil(10_000) {
            val fieldsReady =
                composeRule.onAllNodesWithTag("login.password").fetchSemanticsNodes().isNotEmpty()
            val controlsReady = runCatching {
                composeRule.onNodeWithTag("login.submit").assertIsDisplayed()
                composeRule.onNodeWithTag("login.baseUrl").assertTextContains("https://quant.test")
                composeRule.onNodeWithTag("login.username").assertTextContains("quant-test")
            }.isSuccess
            fieldsReady && controlsReady
        }
    }

    @Test fun loginFailureRemainsVisibleAndCanBeRetried() {
        waitForLoginForm()
        composeRule.onNodeWithTag("login.baseUrl").performTextClearance()
        composeRule.onNodeWithTag("login.baseUrl").performTextInput("https://quant.test")
        composeRule.onNodeWithTag("login.username").performTextClearance()
        composeRule.onNodeWithTag("login.username").performTextInput("quant-test")
        composeRule.activity.fakeRepository.rejectLogin = true
        composeRule.onNodeWithTag("login.password").performTextInput("wrong-password")
        composeRule.onNodeWithTag("login.submit").performScrollTo()
        composeRule.onNodeWithTag("login.submit").assertIsDisplayed()
        composeRule.onNodeWithTag("login.submit").performClick()
        composeRule.waitUntil(30_000) {
            composeRule.onAllNodesWithText("Test credentials rejected.").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.activity.fakeRepository.rejectLogin = false
        waitForLoginForm()
        composeRule.onNodeWithTag("login.password").performTextClearance()
        composeRule.onNodeWithTag("login.password").performTextInput("correct-password")
        composeRule.onNodeWithTag("login.submit").performScrollTo()
        composeRule.onNodeWithTag("login.submit").performClick()
        composeRule.waitUntil(30_000) {
            composeRule.onAllNodesWithText("Market pulse.").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Market pulse.").assertIsDisplayed()
    }
}
