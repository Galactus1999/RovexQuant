"""Typed, provenance-first contracts for the Quant intelligence domain.

These schemas deliberately describe evidence and hypotheses separately. They do not
perform ingestion, entity resolution, graph persistence, or model inference.
"""
from __future__ import annotations

from datetime import date, datetime
from enum import StrEnum
from typing import Any

from pydantic import BaseModel, ConfigDict, Field, HttpUrl, model_validator


class EvidenceSourceType(StrEnum):
    EXCHANGE_DISCLOSURE = "exchange_disclosure"
    COMPANY_FILING = "company_filing"
    COMPANY_PRESENTATION = "company_presentation"
    REGULATORY_RELEASE = "regulatory_release"
    MACRO_RELEASE = "macro_release"
    CENTRAL_BANK = "central_bank"
    GOVERNMENT_RELEASE = "government_release"
    NEWS_REPORT = "news_report"
    MARKET_DATA = "market_data"
    RESEARCH_DATASET = "research_dataset"


class EvidenceTrustTier(StrEnum):
    PRIMARY_OFFICIAL = "primary_official"
    REGULATED_PROVIDER = "regulated_provider"
    ESTABLISHED_PUBLISHER = "established_publisher"
    SECONDARY = "secondary"
    UNVERIFIED = "unverified"


class EntityType(StrEnum):
    COUNTRY = "country"
    ECONOMY = "economy"
    COMMODITY = "commodity"
    INDUSTRY = "industry"
    SECTOR = "sector"
    ORGANISATION = "organisation"
    LISTED_COMPANY = "listed_company"
    INSTRUMENT = "instrument"
    PERSON = "person"
    TECHNOLOGY = "technology"


class EventLifecycle(StrEnum):
    DETECTED = "detected"
    CONFIRMED = "confirmed"
    DEVELOPING = "developing"
    RESOLVED = "resolved"
    DISPUTED = "disputed"
    RETRACTED = "retracted"


class ImpactDirection(StrEnum):
    POSITIVE = "positive"
    NEGATIVE = "negative"
    MIXED = "mixed"
    UNCERTAIN = "uncertain"


class ClaimClassification(StrEnum):
    OBSERVED_FACT = "observed_fact"
    DETERMINISTIC_CALCULATION = "deterministic_calculation"
    INFERENCE = "inference"
    HYPOTHESIS = "hypothesis"


class FreshnessMode(StrEnum):
    LIVE = "live"
    DELAYED = "delayed"
    END_OF_DAY = "eod"
    HISTORICAL = "historical"


class ContractModel(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)


def _require_aware(value: datetime | None, field_name: str) -> None:
    if value is not None and (value.tzinfo is None or value.utcoffset() is None):
        raise ValueError(f"{field_name} must include a timezone offset")


class EvidenceRecord(ContractModel):
    """A pointer and provenance envelope; raw copyrighted source content is not embedded."""

    evidence_id: str = Field(min_length=1, max_length=120)
    source_id: str = Field(min_length=1, max_length=120)
    source_type: EvidenceSourceType
    publisher: str = Field(min_length=1, max_length=200)
    source_url: HttpUrl | None = None
    trust_tier: EvidenceTrustTier
    published_at: datetime | None = None
    observed_at: datetime
    retrieved_at: datetime
    content_sha256: str = Field(pattern=r"^[a-fA-F0-9]{64}$")
    language: str | None = Field(default=None, max_length=24)
    licence_scope: str = Field(min_length=1, max_length=240)
    verification_status: str = Field(default="unverified", max_length=40)

    @model_validator(mode="after")
    def timestamps_are_aware(self):
        for name in ("published_at", "observed_at", "retrieved_at"):
            _require_aware(getattr(self, name), name)
        if self.retrieved_at < self.observed_at:
            raise ValueError("retrieved_at cannot precede observed_at")
        return self


class EntityReference(ContractModel):
    entity_id: str = Field(min_length=1, max_length=160)
    entity_type: EntityType
    canonical_name: str = Field(min_length=1, max_length=240)
    aliases: tuple[str, ...] = Field(default=(), max_length=50)
    external_ids: dict[str, str] = Field(default_factory=dict)
    resolution_confidence: float = Field(ge=0, le=1)
    mapping_evidence_ids: tuple[str, ...] = Field(default=(), max_length=30)
    valid_from: date | None = None
    valid_to: date | None = None

    @model_validator(mode="after")
    def validity_is_ordered(self):
        if self.valid_from and self.valid_to and self.valid_to < self.valid_from:
            raise ValueError("valid_to cannot precede valid_from")
        return self


class EventRecord(ContractModel):
    event_id: str = Field(min_length=1, max_length=120)
    event_type: str = Field(min_length=1, max_length=80)
    title: str = Field(min_length=1, max_length=300)
    summary: str = Field(min_length=1, max_length=4000)
    lifecycle: EventLifecycle = EventLifecycle.DETECTED
    geography_entity_ids: tuple[str, ...] = Field(default=(), max_length=40)
    involved_entity_ids: tuple[str, ...] = Field(default=(), max_length=100)
    evidence_ids: tuple[str, ...] = Field(min_length=1, max_length=100)
    occurred_at: datetime | None = None
    detected_at: datetime
    materiality: float = Field(ge=0, le=1)
    extraction_version: str = Field(min_length=1, max_length=80)

    @model_validator(mode="after")
    def timestamps_are_aware(self):
        _require_aware(self.occurred_at, "occurred_at")
        _require_aware(self.detected_at, "detected_at")
        return self


class ImpactEdge(ContractModel):
    """A typed graph edge; inferred causality is never represented as an observed fact."""

    impact_id: str = Field(min_length=1, max_length=120)
    source_entity_id: str = Field(min_length=1, max_length=160)
    target_entity_id: str = Field(min_length=1, max_length=160)
    event_id: str = Field(min_length=1, max_length=120)
    mechanism: str = Field(min_length=1, max_length=2000)
    direction: ImpactDirection
    horizon: str = Field(min_length=1, max_length=80)
    classification: ClaimClassification
    evidence_ids: tuple[str, ...] = Field(min_length=1, max_length=100)
    assumptions: tuple[str, ...] = Field(default=(), max_length=40)
    confidence: float = Field(ge=0, le=1)
    valid_from: datetime
    valid_to: datetime | None = None
    attributes: dict[str, Any] = Field(default_factory=dict)

    @model_validator(mode="after")
    def edge_is_temporally_valid(self):
        _require_aware(self.valid_from, "valid_from")
        _require_aware(self.valid_to, "valid_to")
        if self.valid_to and self.valid_to < self.valid_from:
            raise ValueError("valid_to cannot precede valid_from")
        if self.classification == ClaimClassification.OBSERVED_FACT and not self.evidence_ids:
            raise ValueError("observed impact facts require evidence references")
        return self


class ResearchClaim(ContractModel):
    claim_id: str = Field(min_length=1, max_length=120)
    statement: str = Field(min_length=1, max_length=2000)
    classification: ClaimClassification
    evidence_ids: tuple[str, ...] = Field(default=(), max_length=100)
    assumptions: tuple[str, ...] = Field(default=(), max_length=40)
    confidence: float = Field(ge=0, le=1)

    @model_validator(mode="after")
    def factual_claim_has_evidence(self):
        if self.classification == ClaimClassification.OBSERVED_FACT and not self.evidence_ids:
            raise ValueError("observed factual claims require evidence references")
        if self.classification == ClaimClassification.HYPOTHESIS and not self.assumptions:
            raise ValueError("hypotheses must state at least one assumption")
        return self
