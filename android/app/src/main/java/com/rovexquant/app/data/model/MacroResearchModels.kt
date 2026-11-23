package com.rovexquant.app.data.model

data class MacroSeriesObservation(
    val key: String,
    val label: String,
    val frequency: String,
    val unit: String,
    val latestPeriod: String?,
    val latestValue: Double?,
    val source: String
)

data class AmfiNavObservation(
    val schemeCode: Int,
    val schemeName: String,
    val nav: Double,
    val navDate: String
)

data class MacroResearchSummary(
    val retrievedAt: String,
    val worldBankSeries: List<MacroSeriesObservation>,
    val amfiNavCount: Int,
    val amfiExamples: List<AmfiNavObservation>,
    val fredConfigured: Boolean,
    val fredSeriesCount: Int,
    val providerErrors: List<String>
)
