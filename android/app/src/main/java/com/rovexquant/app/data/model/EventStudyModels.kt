package com.rovexquant.app.data.model

data class CorporateActionEvidence(
    val exDate: String,
    val actionType: String,
    val rawSubject: String,
    val companyName: String,
    val ratioNum: Long?,
    val ratioDen: Long?,
    val cashAmount: Double?
)

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
    val priceBasis: String,
    val corporateActions: List<CorporateActionEvidence>,
    val limitations: List<String>
)
