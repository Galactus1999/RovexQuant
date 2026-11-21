package com.rovexquant.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PositionSizingTest {
    @Test fun sizesFiveThousandCapitalFromRiskAndStopDistance() {
        val result = PositionSizingCalculator.calculate(5_000.0, 1.0, 250.0, 244.0)
        requireNotNull(result)
        assertEquals(50.0, result.riskBudget, 0.0001)
        assertEquals(8, result.quantity)
        assertEquals(2_000.0, result.investment, 0.0001)
        assertEquals(48.0, result.plannedStopLoss, 0.0001)
    }
    @Test fun supportsLargeCapitalAndAllocationLimit() {
        val result = PositionSizingCalculator.calculate(1_000_000.0, 0.5, 1_000.0, 950.0, 20.0)
        requireNotNull(result)
        assertEquals(100, result.quantity)
        assertEquals(100_000.0, result.investment, 0.0001)
        assertEquals(5_000.0, result.plannedStopLoss, 0.0001)
    }
    @Test fun capsQuantityByAvailableAllocation() {
        val result = PositionSizingCalculator.calculate(10_000.0, 5.0, 100.0, 99.0, 10.0)
        requireNotNull(result)
        assertEquals(10, result.quantity)
    }
    @Test fun rejectsInvalidStopsAndRiskAssumptions() {
        assertNull(PositionSizingCalculator.calculate(10_000.0, 1.0, 100.0, 100.0))
        assertNull(PositionSizingCalculator.calculate(10_000.0, 5.1, 100.0, 90.0))
        assertNull(PositionSizingCalculator.calculate(10_000.0, 1.0, 90.0, 100.0))
        assertNull(PositionSizingCalculator.calculate(Double.NaN, 1.0, 100.0, 90.0))
    }
}
