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
    val providerErrors: List<String>,
    val persistedObservationCount: Int = 0,
    val rbiDbieSeriesCount: Int = 0,
    val providerStatuses: List<String> = emptyList()
)


data class MarketIntegrityDifference(
    val kind: String,
    val symbol: String,
    val series: String,
    val field: String?,
    val officialValue: String?,
    val storedValue: String?
)

data class MarketReconciliationReport(
    val tradingDate: String,
    val status: String,
    val officialRows: Int,
    val referenceRows: Int,
    val matchedRows: Int,
    val mismatchRows: Int,
    val missingReferenceRows: Int,
    val missingOfficialRows: Int,
    val sourceUrl: String,
    val differences: List<MarketIntegrityDifference>
)

data class HistoricalBackfillSummary(
    val year: Int,
    val status: String,
    val rowsSeen: Long,
    val rowsUpserted: Long,
    val completedAt: String?
)

data class DerivedBackfillSummary(
    val year: Int,
    val datasetKind: String,
    val status: String,
    val rowsSeen: Long,
    val rowsUpserted: Long,
    val completedAt: String?
)

data class MarketIntegrityStatus(
    val exchange: String,
    val status: String,
    val latestReport: MarketReconciliationReport?,
    val backfills: List<HistoricalBackfillSummary>,
    val derivedBackfills: List<DerivedBackfillSummary>
)
