package com.rovexquant.app.data.model

data class IntelligenceSource(
    val domain: String,
    val url: String,
    val seenAt: String,
    val language: String,
    val sourceCountry: String,
    val title: String = "",
    val summary: String = "",
    val provider: String = "",
    val evidenceClass: String = ""
)

data class SectorImpact(
    val sector: String,
    val direction: String,
    val mechanism: String
)

data class IssuerMapping(
    val exchange: String,
    val symbol: String,
    val isin: String,
    val companyName: String,
    val mappingMethod: String,
    val confidence: Double,
    val evidence: String,
    val mappingScope: String
)

data class MarketEvent(
    val eventId: String,
    val headline: String,
    val eventCategory: String,
    val eventTags: List<String>,
    val sourceArticleCount: Int,
    val independentSourceCount: Int,
    val sources: List<IntelligenceSource>,
    val classificationMethod: String,
    val classificationConfidence: Double,
    val confidenceSemantics: String,
    val sectorImpacts: List<SectorImpact>,
    val stockMappingStatus: String,
    val stockMappingNote: String,
    val impactStatus: String,
    val issuerMappings: List<IssuerMapping> = emptyList()
)

data class IntelligenceFeed(
    val source: String,
    val retrievedAt: String,
    val query: String,
    val timespan: String,
    val articleCount: Int,
    val eventCount: Int,
    val events: List<MarketEvent>,
    val limitations: List<String>,
    val archiveStatus: String = "UNAVAILABLE",
    val issuerMasterStatus: String = "UNAVAILABLE",
    val mappedEventCount: Int = 0
)
