package com.rovexquant.app.data.model

data class EventStudyResult(
    val exchange: String,
    val symbol: String,
    val eventDate: String,
    val anchorDate: String,
    val firstPostEventDate: String,
    val lastPostEventDate: String,
    val preEventReturn: Double?,
    val cumulativeReturn: Double?,
    val marketAdjustedReturn: Double?,
    val cumulativeAbnormalReturn: Double?,
    val benchmarkSymbol: String?,
    val benchmarkMatches: Int,
    val postBarsUsed: Int,
    val method: String,
    val limitations: List<String>
)
