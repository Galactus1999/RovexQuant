package com.rovexquant.app.data.model

data class DataHealthItem(
    val name: String,
    val status: String,
    val updatedAt: String?,
    val ageHours: Double?,
    val freshness: String,
    val records: Long?,
    val detail: String?
)

data class MarketDataHealth(
    val exchange: String,
    val overallStatus: String,
    val generatedAt: String,
    val marketDataMode: String,
    val eod: DataHealthItem,
    val macroProviders: List<DataHealthItem>,
    val intelligence: DataHealthItem,
    val derivedBackfills: List<DataHealthItem>,
    val reconciliation: DataHealthItem,
    val notes: List<String>
)
