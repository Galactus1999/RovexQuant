from datetime import date, datetime, timezone

import pytest
from pydantic import ValidationError

from rovexquant.intelligence.contracts import (
    ClaimClassification,
    EntityReference,
    EntityType,
    EventLifecycle,
    EventRecord,
    EvidenceRecord,
    EvidenceSourceType,
    EvidenceTrustTier,
    ImpactDirection,
    ImpactEdge,
    ResearchClaim,
)


NOW = datetime(2026, 11, 22, 12, 0, tzinfo=timezone.utc)
HASH = "a" * 64


def test_evidence_record_requires_provenance_and_aware_timestamps():
    evidence = EvidenceRecord(
        evidence_id="ev-1",
        source_id="nse-source",
        source_type=EvidenceSourceType.EXCHANGE_DISCLOSURE,
        publisher="NSE",
        source_url="https://www.nseindia.com/",
        trust_tier=EvidenceTrustTier.PRIMARY_OFFICIAL,
        observed_at=NOW,
        retrieved_at=NOW,
        content_sha256=HASH,
        licence_scope="official-source; display subject to source terms",
    )
    assert evidence.trust_tier == EvidenceTrustTier.PRIMARY_OFFICIAL


def test_evidence_record_rejects_naive_or_reversed_timestamps():
    with pytest.raises(ValidationError):
        EvidenceRecord(
            evidence_id="ev-1",
            source_id="source",
            source_type=EvidenceSourceType.NEWS_REPORT,
            publisher="Publisher",
            trust_tier=EvidenceTrustTier.UNVERIFIED,
            observed_at=datetime(2026, 11, 22),
            retrieved_at=NOW,
            content_sha256=HASH,
            licence_scope="not for redistribution",
        )
    with pytest.raises(ValidationError):
        EvidenceRecord(
            evidence_id="ev-2",
            source_id="source",
            source_type=EvidenceSourceType.NEWS_REPORT,
            publisher="Publisher",
            trust_tier=EvidenceTrustTier.UNVERIFIED,
            observed_at=NOW,
            retrieved_at=datetime(2026, 11, 21, tzinfo=timezone.utc),
            content_sha256=HASH,
            licence_scope="not for redistribution",
        )


def test_entity_reference_rejects_invalid_effective_period():
    with pytest.raises(ValidationError):
        EntityReference(
            entity_id="company:example",
            entity_type=EntityType.LISTED_COMPANY,
            canonical_name="Example Ltd",
            resolution_confidence=0.9,
            valid_from=date(2026, 11, 22),
            valid_to=date(2026, 1, 1),
        )


def test_event_requires_at_least_one_evidence_reference():
    with pytest.raises(ValidationError):
        EventRecord(
            event_id="event-1",
            event_type="geopolitical",
            title="Shipping disruption",
            summary="Reported shipping disruption.",
            lifecycle=EventLifecycle.DETECTED,
            evidence_ids=(),
            detected_at=NOW,
            materiality=0.7,
            extraction_version="event-extractor-v1",
        )


def test_impact_edge_preserves_hypothesis_and_temporal_provenance():
    edge = ImpactEdge(
        impact_id="impact-1",
        source_entity_id="commodity:crude",
        target_entity_id="sector:aviation",
        event_id="event-1",
        mechanism="Higher crude prices may increase aviation fuel costs.",
        direction=ImpactDirection.NEGATIVE,
        horizon="weeks_to_quarters",
        classification=ClaimClassification.HYPOTHESIS,
        evidence_ids=("ev-1",),
        assumptions=("Crude supply disruption persists.",),
        confidence=0.55,
        valid_from=NOW,
    )
    assert edge.classification == ClaimClassification.HYPOTHESIS
    assert edge.assumptions


def test_observed_fact_claim_cannot_be_created_without_evidence():
    with pytest.raises(ValidationError):
        ResearchClaim(
            claim_id="claim-1",
            statement="The company disclosed a new contract.",
            classification=ClaimClassification.OBSERVED_FACT,
            confidence=1.0,
        )


def test_hypothesis_must_declare_assumptions():
    with pytest.raises(ValidationError):
        ResearchClaim(
            claim_id="claim-2",
            statement="A company may benefit from a supply disruption.",
            classification=ClaimClassification.HYPOTHESIS,
            evidence_ids=("ev-1",),
            confidence=0.4,
        )
