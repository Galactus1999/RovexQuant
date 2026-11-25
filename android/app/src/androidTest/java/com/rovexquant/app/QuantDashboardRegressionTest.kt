package com.rovexquant.app

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
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

    private fun clickIndicator(key: String) {
        val tag = "instrument.chart.indicator.$key.toggle"
        // The controls live in a nested horizontal scroller inside the screen's
        // vertical scroller. Scroll the horizontal container itself so the
        // pointer injected by performClick lands inside the clipped viewport.
        // Explicitly return the vertical screen scroller to its start. The
        // control row then reveals the requested chip using its horizontal axis.
        composeRule.onNodeWithTag("instrument.research.screen")
            .performSemanticsAction(SemanticsActions.ScrollBy) { scrollBy ->
                scrollBy(0f, -10_000f)
            }
        composeRule.onNodeWithTag("instrument.chart.indicator.controls")
            .performScrollToNode(hasTestTag(tag))
        composeRule.onNodeWithTag(tag).assertIsDisplayed().performClick()
        composeRule.waitForIdle()
    }

    @Test fun appStartsWithQuantMarketPulseAndHonestOfflineState() {
        waitForDashboard()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("quant.header").assertIsDisplayed()
        composeRule.onNodeWithText("Market Pulse").assertIsDisplayed()
        composeRule.onNodeWithTag("market.pulse.list").performScrollToNode(hasTestTag("market.eod.snapshot"))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("market.eod.snapshot").assertIsDisplayed()
        composeRule.onNodeWithText("END OF DAY").assertIsDisplayed()
        // The EOD card intentionally collapses mover rows by default. Wait for
        // the asynchronous snapshot payload, then explicitly expand it before
        // asserting that the fixture symbol is rendered.
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("2450 stocks", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Show market movers").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("FIXTURE").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
        composeRule.onAllNodesWithTag("market.ingestion.status").assertCountEquals(0)
        composeRule.onNodeWithTag("market.pulse.list").performScrollToNode(hasText("FIXTURE"))
        composeRule.waitForIdle()
        composeRule.onNodeWithText("FIXTURE").assertIsDisplayed()
        composeRule.onNodeWithTag("market.pulse.list").performScrollToNode(hasTestTag("market.sector.heading"))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("market.sector.heading").assertIsDisplayed()
        composeRule.onNodeWithTag("market.pulse.list")
            .performScrollToNode(hasTestTag("market.sector.heatmap.open"))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("market.sector.heatmap.open").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("market.sector.heatmap.dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("market.sector.heatmap.grid").assertIsDisplayed()
        composeRule.onNodeWithTag("sector.heatmap.NIFTY BANK").assertIsDisplayed()
        composeRule.onNodeWithTag("sector.heatmap.NIFTY BANK").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("instrument.research.screen").assertIsDisplayed()
        composeRule.onNodeWithText("NIFTY BANK").assertIsDisplayed()
        composeRule.onNodeWithTag("instrument.overview.panel").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("52-week high").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("52-week low").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("INDEX COMPOSITION & BREADTH").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("INDEX & SECTOR DEVELOPMENTS").performScrollTo().assertIsDisplayed()
        composeRule.waitUntil(15_000) {
            composeRule.onAllNodesWithTag("instrument.chart.candles")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Traded volume is not published for this index series.")
            .performScrollTo().assertIsDisplayed()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("instrument.chart.candles")
            .performScrollTo().assertIsDisplayed()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithTag("instrument.chart.volume.toggle").assertCountEquals(0)
        for (timeframe in listOf("1m", "5m", "10m", "15m", "30m", "1h", "4h", "1d")) {
            composeRule.onAllNodesWithTag("instrument.chart.timeframe.$timeframe").assertCountEquals(1)
        }
        composeRule.onNodeWithTag("instrument.chart.timeframes").performScrollTo()
        composeRule.onNodeWithTag("instrument.chart.timeframe.5m").performClick()
        composeRule.waitUntil(15_000) {
            composeRule.onAllNodesWithText("Upstox V3 intraday candles", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("instrument.chart.timeframe.5m").assertIsSelected()
        composeRule.onNodeWithTag("instrument.chart.time.labels").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("instrument.chart.time.labels")
            .assert(hasContentDescription("Intraday chart time labels", substring = true))
            .assert(hasContentDescription(":", substring = true))
        composeRule.onAllNodesWithTag("instrument.chart.volume.toggle").assertCountEquals(0)
        composeRule.waitUntil(15_000) {
            runCatching {
                composeRule.onNodeWithTag("instrument.chart.indicator.rsi.toggle").assertIsEnabled()
            }.isSuccess
        }
        composeRule.waitForIdle()
        clickIndicator("rsi")
        composeRule.waitForIdle()
        // Prove the control state changed before waiting for its dependent pane.
        composeRule.onNodeWithTag("instrument.chart.indicator.rsi.toggle").assertIsSelected()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("instrument.chart.indicator.rsi.pane")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
        composeRule.onAllNodesWithTag("instrument.chart.indicator.rsi.pane").assertCountEquals(1)
        composeRule.onNodeWithTag("instrument.chart.indicator.rsi.pane.plot")
            .assert(hasContentDescription("non-null plotted samples", substring = true))
        composeRule.onNodeWithTag("instrument.chart.indicator.rsi.toggle").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("instrument.chart.indicator.rsi.toggle").assertIsNotSelected()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("instrument.chart.indicator.rsi.pane").fetchSemanticsNodes().isEmpty()
        }
        composeRule.waitForIdle()
        clickIndicator("macd")
        composeRule.waitForIdle()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("instrument.chart.indicator.macd.pane")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
        composeRule.onAllNodesWithTag("instrument.chart.indicator.macd.pane").assertCountEquals(1)
        composeRule.onNodeWithTag("instrument.chart.indicator.macd.pane.plot")
            .assert(hasContentDescription("non-null plotted samples", substring = true))
        composeRule.waitForIdle()
        clickIndicator("atr")
        composeRule.waitForIdle()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("instrument.chart.indicator.atr.pane")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
        composeRule.onAllNodesWithTag("instrument.chart.indicator.atr.pane").assertCountEquals(1)
        composeRule.onNodeWithTag("instrument.chart.indicator.atr.pane.plot")
            .assert(hasContentDescription("non-null plotted samples", substring = true))
        composeRule.waitForIdle()
        clickIndicator("adx")
        composeRule.waitForIdle()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("instrument.chart.indicator.adx.pane")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
        composeRule.onAllNodesWithTag("instrument.chart.indicator.adx.pane").assertCountEquals(1)
        composeRule.onNodeWithTag("instrument.chart.indicator.adx.pane.plot")
            .assert(hasContentDescription("non-null plotted samples", substring = true))
        composeRule.waitForIdle()
        for (key in listOf("sma", "ema", "bollinger")) {
            clickIndicator(key)
            composeRule.onNodeWithTag("instrument.chart.indicator.$key.toggle").assertIsSelected()
        }
        composeRule.onNodeWithText("← Back to Pulse").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("market.pulse.list").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("market.pulse.list")
            .performScrollToNode(hasTestTag("market.eod.snapshot"))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("market.eod.snapshot").assertIsDisplayed()
    }

    @Test fun globalInstrumentSearchFindsCompaniesAndIndicesByName() {
        waitForDashboard()
        composeRule.onNodeWithTag("instrument.global.search.open").performClick()
        composeRule.onNodeWithTag("instrument.global.search.dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("instrument.global.search.input").performTextInput("Reliance Industries")
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("instrument.global.search.result.RELIANCE")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("RELIANCE INDUSTRIES LTD").assertIsDisplayed()
        composeRule.onNodeWithTag("instrument.global.search.result.RELIANCE").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("instrument.research.screen").assertIsDisplayed()
        composeRule.onNodeWithText("RELIANCE INDUSTRIES LTD").assertIsDisplayed()
    }

    @Test fun bottomNavigationOpensAllWorkspacesAndSectorCoverage() {
        waitForDashboard()
        composeRule.onNodeWithText("Scan").performClick()
        composeRule.onNodeWithText("Strategy scanner.").assertIsDisplayed()
        composeRule.onNodeWithTag("scanner.list").performScrollToNode(hasText("No local dataset loaded"))
        composeRule.onNodeWithText("No local dataset loaded").assertIsDisplayed()
        composeRule.onNodeWithText("More").performClick()
        composeRule.onNodeWithText("Signals").performClick()
        composeRule.onNodeWithText("Signal centre.").assertIsDisplayed()
        composeRule.onNodeWithText("Lab").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Research lab.").assertIsDisplayed()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("research.lab.list").performScrollToNode(hasText("Choose CSV folder"))
        composeRule.onNodeWithText("AUTOMATIC CSV FOLDER IMPORT").assertIsDisplayed()
        composeRule.onNodeWithText("Choose CSV folder").assertIsDisplayed()
        composeRule.onNodeWithTag("research.lab.list").performScrollToNode(hasText("Import OHLCV CSV"))
        composeRule.onNodeWithText("Import OHLCV CSV").assertIsDisplayed()
        composeRule.onNodeWithText("More").performClick()
        composeRule.onNodeWithText("Portfolio").performClick()
        composeRule.onNodeWithText("Portfolio & risk.").assertIsDisplayed()
        composeRule.onNodeWithTag("portfolio.entry").performScrollTo()
        composeRule.onNodeWithTag("portfolio.entry").assertIsDisplayed()
    }



    @Test fun settingsWorkspaceSeparatesProviderCredentialsFromMarketDashboard() {
        waitForDashboard()
        composeRule.onNodeWithTag("quant.settings.open").performClick()
        composeRule.onNodeWithTag("settings.screen").assertIsDisplayed()
        composeRule.onNodeWithText("Settings").assertIsDisplayed()
        composeRule.onNodeWithTag("settings.screen").performScrollToNode(hasTestTag("settings.database.refresh"))
        composeRule.onNodeWithTag("settings.database.refresh").assertIsDisplayed()
        composeRule.onNodeWithTag("settings.screen").performScrollToNode(hasTestTag("settings.upstox.token"))
        composeRule.onNodeWithTag("settings.upstox.token").assertIsDisplayed()
        composeRule.onNodeWithTag("settings.screen").performScrollToNode(hasText("Open AI provider settings"))
        composeRule.onNodeWithText("Open AI provider settings").assertIsDisplayed()
        composeRule.onNodeWithTag("settings.screen").performScrollToNode(hasText("Close"))
        composeRule.onNodeWithText("Close").performClick()
        composeRule.onNodeWithText("Market Pulse").assertIsDisplayed()
    }

    @Test fun aiWorkspaceConnectsChatAndEodRecoveryActions() {
        waitForDashboard()
        composeRule.onNodeWithText("Scan").performClick()
        composeRule.onNodeWithTag("scanner.list").performScrollToNode(hasText("Open AI data recovery"))
        composeRule.onNodeWithText("Open AI data recovery").performClick()
        composeRule.onNodeWithText("Quant Intelligence.").assertIsDisplayed()

        // The research history is below the initial viewport in a LazyColumn.
        // Scroll its container into composition before waiting for lazy children.
        composeRule.onNodeWithTag("ai.workspace.list")
            .performScrollToNode(hasTestTag("ai.research.history"))
        composeRule.onNodeWithTag("ai.workspace.list")
            .performScrollToNode(hasTestTag("ai.research.history"))
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("ai.research.run.fixture-research").fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithTag("ai.research.budget.daily").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("ai.workspace.list")
            .performScrollToNode(hasTestTag("ai.research.run.fixture-research"))
        composeRule.onNodeWithTag("ai.research.run.fixture-research").assertIsDisplayed()
        composeRule.onNodeWithTag("ai.research.budget.daily")
            .assertTextContains("200 / 10000 tokens")
        composeRule.onNodeWithTag("ai.research.run.open.fixture-research").performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("ai.research.run.detail.response").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("ai.research.run.detail.response")
            .assertTextContains("Deterministic archived research detail.")
        composeRule.onNodeWithTag("ai.research.run.detail.request")
            .assertTextContains("Fixture research prompt")
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
        composeRule.onNodeWithTag("ai.workspace.list").performScrollToNode(hasTestTag("ai.recovery.symbol.find"))
        composeRule.onNodeWithTag("ai.recovery.symbol.find").performClick()
        composeRule.onNodeWithTag("instrument.global.search.input").performTextInput("Reliance Industries")
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("instrument.global.search.result.RELIANCE")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("instrument.global.search.result.RELIANCE").performClick()
        composeRule.onNodeWithTag("ai.recovery.symbol")
            .assertTextEquals("NSE/BSE trading symbol", "RELIANCE")
        composeRule.onNodeWithTag("ai.workspace.list").performScrollToNode(hasTestTag("ai.recovery.run"))
        composeRule.onNodeWithTag("ai.recovery.run").performClick()
        composeRule.onNodeWithTag("ai.workspace.list").performScrollToNode(
            hasText("Deterministic EOD strategy fixture response.")
        )
        composeRule.onNodeWithText("Deterministic EOD strategy fixture response.").assertIsDisplayed()

        // Research tools must load deterministic market evidence and attach it to
        // the draft; the independent briefing panel must then render its output.
        composeRule.onNodeWithTag("ai.workspace.list").performScrollToNode(hasTestTag("ai.tool.market"))
        composeRule.onNodeWithTag("ai.tool.market").assertIsDisplayed()
        composeRule.onNodeWithTag("ai.workspace.list").performScrollToNode(hasTestTag("ai.tool.events"))
        composeRule.onNodeWithTag("ai.tool.events").assertIsDisplayed()
        composeRule.onNodeWithTag("ai.workspace.list").performScrollToNode(hasTestTag("ai.tool.macro"))
        composeRule.onNodeWithTag("ai.tool.macro").assertIsDisplayed()
        composeRule.onNodeWithTag("ai.workspace.list").performScrollToNode(hasTestTag("ai.tool.market"))
        composeRule.onNodeWithTag("ai.tool.market").performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("NSE EOD snapshot attached", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("ai.workspace.list").performScrollToNode(hasTestTag("ai.chat.input"))
        composeRule.onNodeWithTag("ai.chat.input").assertTextContains("NSE EOD SNAPSHOT", substring = true)
        composeRule.onNodeWithTag("ai.workspace.list").performScrollToNode(hasTestTag("ai.brief.generate"))
        composeRule.onNodeWithTag("ai.brief.generate").assertIsDisplayed()
        composeRule.onNodeWithTag("ai.brief.generate").performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("Deterministic AI fixture response.").fetchSemanticsNodes().size >= 2
        }
        composeRule.onNodeWithTag("ai.workspace.list").performScrollToNode(hasTestTag("ai.brief.output"))
        composeRule.onNodeWithTag("ai.brief.output").assertTextContains("Deterministic AI fixture response.")
        composeRule.onNodeWithTag("ai.workspace.list").performScrollToNode(
            hasText("No machine-verifiable source references returned.", substring = true)
        )
        composeRule.onNodeWithText("No machine-verifiable source references returned.", substring = true).assertIsDisplayed()
    }

    @Test fun dalalosConnectorStoresKeyAndRequiresPerCallQuotaConfirmation() {
        waitForDashboard()
        composeRule.onNodeWithText("Scan").performClick()
        composeRule.onNodeWithTag("scanner.list").performScrollToNode(hasText("Open AI data recovery"))
        composeRule.onNodeWithText("Open AI data recovery").performClick()
        composeRule.onNodeWithText("Quant Intelligence.").assertIsDisplayed()

        composeRule.onNodeWithTag("ai.workspace.list")
            .performScrollToNode(hasTestTag("ai.dalalos.panel"))
        composeRule.onNodeWithTag("ai.dalalos.key").performTextInput("d".repeat(32))
        composeRule.onNodeWithTag("ai.dalalos.terms").performClick()
        // Typing opens the IME and can move the save control below the viewport.
        // Scroll it into the visible area before injecting the click.
        composeRule.onNodeWithTag("ai.dalalos.save").performScrollTo().assertIsDisplayed().assertIsEnabled()
        composeRule.onNodeWithTag("ai.dalalos.terms").assertIsOn()
        composeRule.onNodeWithTag("ai.dalalos.save").assertIsEnabled()
        composeRule.onNodeWithTag("ai.dalalos.save").performClick()
        // Save is below the key field; return to the status row before asserting it.
        composeRule.onNodeWithTag("ai.workspace.list")
            .performScrollToNode(hasTestTag("ai.dalalos.panel"))
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("KEY STORED").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("ai.dalalos.discover").performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("ai.dalalos.enable.get_company_exposures")
                .fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithTag("ai.dalalos.enable.get_company_exposures").performClick()
        composeRule.onNodeWithTag("ai.dalalos.tool.get_company_exposures").performClick()
        composeRule.onNodeWithTag("ai.dalalos.arguments").performTextClearance()
        composeRule.onNodeWithTag("ai.dalalos.arguments").performTextInput("{\"query\":\"RELIANCE\"}")
        composeRule.onNodeWithTag("ai.dalalos.run").performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("get_company_exposures").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithTag("ai.dalalos.enable.get_filing_extract").performClick()
        composeRule.onNodeWithTag("ai.dalalos.tool.get_filing_extract").performClick()
        composeRule.onNodeWithTag("ai.dalalos.run").assertIsNotEnabled()
        composeRule.onNodeWithTag("ai.dalalos.confirm.quota").performClick()
        composeRule.onNodeWithTag("ai.dalalos.run").assertIsEnabled()
        composeRule.onNodeWithTag("ai.dalalos.run").performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("get_filing_extract").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun aiWorkspaceUsesConfiguredServerTaskRoute() {
        waitForDashboard()
        composeRule.onNodeWithText("Scan").performClick()
        composeRule.onNodeWithTag("scanner.list").performScrollToNode(hasText("Open AI data recovery"))
        composeRule.onNodeWithText("Open AI data recovery").performClick()
        composeRule.onNodeWithText("Quant Intelligence.").assertIsDisplayed()

        composeRule.onNodeWithTag("ai.workspace.list")
            .performScrollToNode(hasTestTag("ai.task.auto"))
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("ai.task.route").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("ai.task.auto").performClick()
        composeRule.onNodeWithTag("ai.workspace.list")
            .performScrollToNode(hasTestTag("ai.chat.input"))
        composeRule.onNodeWithTag("ai.chat.input").performTextInput("Review this supplied evidence using the research route.")
        composeRule.onNodeWithTag("ai.workspace.list").performScrollToNode(hasTestTag("ai.chat.send"))
        composeRule.onNodeWithTag("ai.chat.send").assertIsDisplayed().assertIsEnabled()
        composeRule.onNodeWithTag("ai.chat.send").performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("Deterministic AI fixture response.").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun intelligenceCentreLoadsEventEvidenceAndSectorTransmission() {
        waitForDashboard()
        composeRule.onNodeWithText("Intel").performClick()
        composeRule.onNodeWithText("AI Intelligence Centre").assertIsDisplayed()
        composeRule.onNodeWithTag("intelligence.screen")
            .performScrollToNode(hasText("No research run yet"))
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
        composeRule.onNodeWithText("More").performClick()
        composeRule.onNodeWithText("Portfolio").performClick()
        composeRule.onNodeWithTag("portfolio.screen")
            .performScrollToNode(hasTestTag("portfolio.capital"))
        composeRule.onNodeWithTag("portfolio.capital").performTextInput("5000")
        composeRule.onNodeWithTag("portfolio.screen")
            .performScrollToNode(hasTestTag("portfolio.allocation"))
        composeRule.onNodeWithTag("portfolio.allocation").performTextClearance()
        composeRule.onNodeWithTag("portfolio.allocation").performTextInput("100")
        composeRule.onNodeWithTag("portfolio.screen")
            .performScrollToNode(hasTestTag("portfolio.entry"))
        composeRule.onNodeWithTag("portfolio.entry").performTextInput("250")
        composeRule.onNodeWithTag("portfolio.screen")
            .performScrollToNode(hasTestTag("portfolio.stop"))
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
            composeRule.onAllNodesWithText("Market Pulse").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Market Pulse").assertIsDisplayed()
    }
}
