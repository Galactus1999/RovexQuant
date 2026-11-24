package com.rovexquant.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveNavigationTest {
    @Test
    fun navigationRailUsesExpandedWindowWidthBreakpoint() {
        assertFalse(shouldUseNavigationRail(599))
        assertFalse(shouldUseNavigationRail(600))
        assertFalse(shouldUseNavigationRail(839))
        assertTrue(shouldUseNavigationRail(840))
        assertTrue(shouldUseNavigationRail(1200))
    }
}
