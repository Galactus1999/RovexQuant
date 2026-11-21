package com.rovexquant.app.domain

import kotlin.math.floor

data class PositionSizeResult(
    val riskBudget: Double,
    val riskPerShare: Double,
    val quantity: Int,
    val investment: Double,
    val plannedStopLoss: Double
)

object PositionSizingCalculator {
    /** Cash-equity quantity calculator. Costs, slippage and overnight gaps are excluded. */
    fun calculate(
        capital: Double,
        riskPercent: Double,
        entryPrice: Double,
        stopPrice: Double,
        maxAllocationPercent: Double = 100.0
    ): PositionSizeResult? {
        val inputs = listOf(capital, riskPercent, entryPrice, stopPrice, maxAllocationPercent)
        if (inputs.any { !it.isFinite() }) return null
        if (capital <= 0.0 || riskPercent <= 0.0 || riskPercent > 5.0) return null
        if (entryPrice <= 0.0 || stopPrice <= 0.0 || stopPrice >= entryPrice) return null
        if (maxAllocationPercent <= 0.0 || maxAllocationPercent > 100.0) return null
        val riskBudget = capital * riskPercent / 100.0
        val riskPerShare = entryPrice - stopPrice
        val allocationBudget = capital * maxAllocationPercent / 100.0
        val quantityByRisk = floor(riskBudget / riskPerShare).toInt()
        val quantityByAllocation = floor(allocationBudget / entryPrice).toInt()
        val quantity = minOf(quantityByRisk, quantityByAllocation).coerceAtLeast(0)
        return PositionSizeResult(riskBudget, riskPerShare, quantity, quantity * entryPrice, quantity * riskPerShare)
    }
}
