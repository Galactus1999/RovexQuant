package com.rovexquant.app

import android.os.Looper
import android.util.Log
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOn
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
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Workspace tests start from a deterministic authenticated fixture. They do not
 * exercise the login flow; authentication has its own test host and test class.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class QuantDashboardRegressionTest {
    // Compose 1.7.6's v1 Android test environment creates its internal TestScope
    // from this rule's effect dispatcher, then drains that scope with runTest during
    // teardown. A supplied StandardTestDispatcher queues repeatOnLifecycle cleanup
    // until that drain; it is then resumed on the instrumentation thread, where
    // LifecycleRegistry.removeObserver is forbidden. Keep the v1 default
    // UnconfinedTestDispatcher so cleanup resumes inline on the main-thread
    // lifecycle transition.
    @get:Rule val composeRule = createAndroidComposeRule<QuantTestActivity>()

    private fun waitForDashboard() {
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("quant.header").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForAutomaticProviderReadiness() {
        // The provider status is populated by an asynchronous backend fetch.
        // Wait for the current user-facing contract, not the retired task-route
        // string that exposed a pinned model ID.
        composeRule.onNodeWithTag("ai.workspace.list").performScrollToIndex(1)
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("Research ready")
                .fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithTag("ai.open.settings")
                    .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Research ready").assertIsDisplayed()
        composeRule.onNodeWithTag("ai.open.settings").assertIsDisplayed()
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

    @Test fun statusOrbOpensDiagnosticsAndRetryReconnectsAndRefreshes() {
        waitForDashboard()
        val activity = composeRule.activity
        composeRule.waitUntil(10_000) {
            activity.fakeRepository.marketStreamStartCount > 0 &&
                activity.fakeRepository.marketIngestionRequestCount > 0
        }
        val initialStreamStarts = activity.fakeRepository.marketStreamStartCount
        val initialIngestionRequests = activity.fakeRepository.marketIngestionRequestCount

        composeRule.onNode(
            hasContentDescription("System status", substring = true)
        ).performClick()
        composeRule.onNodeWithTag("quant.diagnostic.drawer").assertIsDisplayed()
        composeRule.onNodeWithTag("quant.diagnostic.detail.0").assertTextContains("Market stream", substring = true)
        composeRule.onNodeWithTag("quant.diagnostic.detail.2").assertTextContains("NSE EOD snapshot", substring = true)

        composeRule.onNodeWithTag("quant.diagnostic.retry").performClick()
        composeRule.waitUntil(15_000) {
            activity.fakeRepository.marketStreamStartCount > initialStreamStarts &&
                activity.fakeRepository.marketIngestionRequestCount > initialIngestionRequests
        }
        composeRule.onAllNodesWithTag("quant.diagnostic.drawer").assertCountEquals(0)
    }

    @Test fun visualStrategyPlaygroundUsesUpstoxHistoryAndPeekOpensCopilot() {
        waitForDashboard()
        val activity = composeRule.activity
        val initialHistoryRequests = activity.fakeRepository.instrumentHistoryRequestCount

        composeRule.onNodeWithTag("market.pulse.list")
            .performScrollToNode(hasTestTag("strategy.run"))
        composeRule.onNodeWithText("Ready to analyze Swing · Donchian breakout · RSI < 30 on RELIANCE")
            .assertIsDisplayed()
        composeRule.onNodeWithTag("strategy.run").performClick()
        composeRule.waitUntil(10_000) {
            activity.fakeRepository.instrumentHistoryRequestCount > initialHistoryRequests &&
                composeRule.onAllNodesWithTag("strategy.metric.rsi.14").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(activity.fakeRepository.lastInstrumentProvider == "UPSTOX_V3_HISTORICAL")
        assertTrue(activity.fakeRepository.lastInstrumentSymbol == "RELIANCE")
        assertTrue(activity.fakeRepository.lastAiRequestPrompt?.contains("User-selected planned risk budget") == true)

        composeRule.onNodeWithTag("strategy.metric.rsi.14")
            .performSemanticsAction(SemanticsActions.OnLongClick) { onLongClick -> onLongClick() }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("strategy.metric.peek").fetchSemanticsNodes().isNotEmpty()
        }
        // The peek is rendered below the evidence metrics inside the same LazyColumn
        // item. Bring it into the viewport before asserting display or clicking it.
        composeRule.onNodeWithTag("market.pulse.list")
            .performScrollToNode(hasTestTag("strategy.metric.peek"))
        composeRule.onNodeWithTag("strategy.metric.peek").assertIsDisplayed()
        composeRule.onNodeWithTag("strategy.metric.ask.copilot").performClick()
        // A semantics node can exist while the lower portion of a modal sheet is
        // clipped. Wait for actual viewport visibility, not composition alone.
        composeRule.waitUntil(10_000) {
            runCatching {
                composeRule.onNodeWithTag("copilot.input").assertIsDisplayed()
                composeRule.onNodeWithTag("copilot.send").assertIsDisplayed()
            }.isSuccess
        }
        composeRule.onNodeWithTag("copilot.input").assertIsDisplayed()
        composeRule.onNodeWithTag("copilot.send").assertIsDisplayed()
        val chatRequestsBeforeCopilotSend = activity.fakeRepository.aiChatRequestCount
        composeRule.onNodeWithTag("copilot.send").performClick()
        composeRule.waitUntil(10_000) {
            activity.fakeRepository.aiChatRequestCount > chatRequestsBeforeCopilotSend &&
                composeRule.onAllNodesWithTag("copilot.message.content.1").fetchSemanticsNodes().isNotEmpty()
        }
        // Assert the actual Text semantics node, not its unmerged Column parent.
        composeRule.onNodeWithTag("copilot.message.content.1")
            .assertTextEquals("Deterministic AI fixture response.")
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

    @Test fun indexAndSectorInstrumentResearchRenderValidatedCharts() {
        waitForDashboard()

        fun openInstrumentChart(symbol: String) {
            composeRule.onNodeWithTag("instrument.global.search.open").performClick()
            composeRule.onNodeWithTag("instrument.global.search.input").performTextInput(symbol)
            composeRule.waitUntil(10_000) {
                composeRule.onAllNodesWithTag("instrument.global.search.result.$symbol")
                    .fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithTag("instrument.global.search.result.$symbol").performClick()
            composeRule.waitUntil(15_000) {
                composeRule.onAllNodesWithTag("instrument.chart.candles")
                    .fetchSemanticsNodes().isNotEmpty()
            }
            // A semantics node can exist outside the viewport. Bring each nested
            // chart component into view before asserting visibility.
            composeRule.onNodeWithTag("instrument.research.screen")
                .performScrollToNode(hasTestTag("instrument.chart.candles"))
            composeRule.onNodeWithTag("instrument.chart.candles").assertIsDisplayed()
            composeRule.onNodeWithTag("instrument.research.screen")
                .performScrollToNode(hasTestTag("instrument.chart.indicator.controls"))
            composeRule.onNodeWithTag("instrument.chart.indicator.controls").assertIsDisplayed()
            composeRule.onNodeWithText("Last price").performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithText("52-week high").performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithText("Annualised volatility").performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithText("PRICE PERFORMANCE").performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithText("INDEX COMPOSITION & BREADTH")
                .performScrollTo().assertIsDisplayed()
        }

        openInstrumentChart("NIFTY 50")
        // This disclaimer is rendered inside the index-only breadth card.
        // Scroll the actual leaf node so Compose resolves its nearest vertical
        // scroll parent and computes visibility from the leaf's measured bounds.
        composeRule.onNodeWithTag("instrument.overview.index.breadth.limitation")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("← Back to Pulse").performClick()
        openInstrumentChart("NIFTY BANK")
    }

    @Test fun bottomNavigationOpensAllWorkspacesAndSectorCoverage() {
        waitForDashboard()
        composeRule.onNodeWithText("Scan").performClick()
        composeRule.onNodeWithText("Strategy scanner.").assertIsDisplayed()
        composeRule.onNodeWithTag("scanner.list").performScrollToNode(hasText("No local dataset loaded"))
        composeRule.onNodeWithText("No local dataset loaded").assertIsDisplayed()
        composeRule.onNodeWithText("More").performClick()
        composeRule.onNodeWithText("Signals").performClick()
        composeRule.onNodeWithText("Signals.").assertIsDisplayed()
        composeRule.onNodeWithText("No signals are being generated.").assertIsDisplayed()
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
        composeRule.onNodeWithText("Position sizing.").assertIsDisplayed()
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
        // The provider panel is LazyColumn item 3 (0-based); it is not composed
        // until the list reaches that item. Do not wait for an off-screen lazy child.
        composeRule.onNodeWithTag("settings.screen").performScrollToIndex(3)
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("settings.ai.provider.key").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("settings.screen")
            .performScrollToNode(hasTestTag("settings.ai.provider.test"))
        composeRule.onNodeWithTag("settings.ai.provider.key").assertExists()
        composeRule.onNodeWithTag("settings.ai.provider.test").assertIsDisplayed().assertIsEnabled()
        val activity = composeRule.activity
        val requestsBeforeTest = activity.fakeRepository.aiChatRequestCount
        composeRule.onNodeWithTag("settings.ai.provider.test").performClick()
        composeRule.waitUntil(10_000) {
            activity.fakeRepository.aiChatRequestCount > requestsBeforeTest
        }
        // The result is below the connection button in the same settings card.
        composeRule.onNodeWithTag("settings.screen")
            .performScrollToNode(hasText("Connection verified", substring = true))
        composeRule.onNodeWithText("Connection verified", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("settings.screen").performScrollToNode(hasText("Close"))
        composeRule.onNodeWithText("Close").performClick()
        composeRule.onNodeWithText("Market Pulse").assertIsDisplayed()
    }

    @Test fun aiWorkspaceConnectsResearchToolsAndEodRecoveryActions() {
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
            .assertTextContains("200 / 10000 tokens", substring = true)
        composeRule.onNodeWithTag("ai.research.run.open.fixture-research").performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("ai.research.run.detail.response").fetchSemanticsNodes().isNotEmpty()
        }
        // The archive stores JSON payloads; assert the expected content within
        // the serialized object rather than requiring it to equal the whole node.
        composeRule.onNodeWithTag("ai.research.run.detail.response")
            .assertTextContains("Deterministic archived research detail.", substring = true)
        composeRule.onNodeWithTag("ai.research.run.detail.request")
            .assertTextContains("Fixture research prompt", substring = true)
        // Provider/model configuration is deliberately outside this research
        // workspace. Verify the server-owned route as read-only state instead of
        // testing the retired provider selector and embedded chat panel.
        waitForAutomaticProviderReadiness()
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
        // Attached tool evidence is consumed directly by the dedicated briefing
        // action; there is no hidden chat input in the AI Lab.
        composeRule.onNodeWithTag("ai.workspace.list").performScrollToNode(hasTestTag("ai.brief.generate"))
        composeRule.onNodeWithTag("ai.brief.generate").assertIsEnabled().performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("ai.brief.output").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("ai.workspace.list").performScrollToNode(hasTestTag("ai.brief.output"))
        composeRule.onNodeWithTag("ai.brief.output").assertTextContains("Deterministic AI fixture response.")
        composeRule.onNodeWithTag("ai.workspace.list").performScrollToNode(
            hasText("No machine-verifiable source references returned.", substring = true)
        )
        composeRule.onNodeWithText("No machine-verifiable source references returned.", substring = true).assertIsDisplayed()
    }

    @Test fun aiWorkspaceUsesAutomaticProviderDiscoveryAndProviderSwitches() {
        waitForDashboard()
        composeRule.onNodeWithText("Scan").performClick()
        composeRule.onNodeWithTag("scanner.list").performScrollToIndex(8)
        composeRule.onNodeWithTag("scanner.ai.recovery").performScrollTo().performClick()
        composeRule.onNodeWithText("Quant Intelligence.").assertIsDisplayed()

        waitForAutomaticProviderReadiness()
        composeRule.onNodeWithTag("ai.open.settings").performClick()
        composeRule.onNodeWithTag("settings.screen").assertIsDisplayed()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("settings.ai.provider.toggle.groq")
                .fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithTag("settings.ai.provider.key")
                    .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("settings.ai.provider.toggle.groq").assertIsOn()
        composeRule.onNodeWithText(
            "Turn providers on or off. RovexQuant discovers a compatible chat model automatically."
        ).assertIsDisplayed()
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
