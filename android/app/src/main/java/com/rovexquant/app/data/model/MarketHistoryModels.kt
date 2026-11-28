package com.rovexquant.app.data.model

import com.rovexquant.app.domain.OhlcvBar
import java.time.LocalDate

data class MarketHistoryTarget(
    val symbol: String,
    val fromDate: LocalDate
)

data class MarketHistoryBulkResult(
    val barsBySymbol: Map<String, List<OhlcvBar>>,
    val providersBySymbol: Map<String, String>
)
