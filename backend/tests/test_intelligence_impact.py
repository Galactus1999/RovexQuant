from datetime import datetime, timezone

from rovexquant.intelligence.contracts import (
    ClaimClassification,
    ImpactDirection,
    ImpactEdge,
)
from rovexquant.intelligence.impact import summarize_impact_edges


NOW = datetime(2026, 11, 22, 12, 0, tzinfo=timezone.utc)


def make_edge(
    impact_id: str,
    source: str,
    target: str,
    event: str,
    direction: ImpactDirection,
    evidence: tuple[str, ...],
) -> ImpactEdge:
    return ImpactEdge(
        impact_id=impact_id,
        source_entity_id=source,
        target_entity_id=target,
        event_id=event,
        mechanism="A documented transmission mechanism to be evaluated.",
        direction=direction,
        horizon="weeks_to_quarters",
        classification=ClaimClassification.INFERENCE,
        evidence_ids=evidence,
        assumptions=("The stated exposure remains current.",),
        confidence=0.5,
        valid_from=NOW,
    )


def test_rollup_groups_by_target_and_preserves_all_evidence():
    summaries = summarize_impact_edges(
        [
            make_edge("i-2", "commodity:crude", "sector:aviation", "e-1", ImpactDirection.NEGATIVE, ("ev-2",)),
            make_edge("i-1", "country:example", "sector:aviation", "e-2", ImpactDirection.NEGATIVE, ("ev-1", "ev-2")),
            make_edge("i-3", "tech:battery", "sector:power", "e-3", ImpactDirection.POSITIVE, ("ev-3",)),
        ]
    )
    assert [item.target_entity_id for item in summaries] == ["sector:aviation", "sector:power"]
    aviation = summaries[0]
    assert aviation.impact_count == 2
    assert aviation.source_entity_ids == ("commodity:crude", "country:example")
    assert aviation.event_ids == ("e-1", "e-2")
    assert aviation.evidence_ids == ("ev-1", "ev-2")


def test_rollup_marks_opposing_impacts_as_mixed_without_averaging():
    summaries = summarize_impact_edges(
        [
            make_edge("i-positive", "source:a", "company:x", "e-1", ImpactDirection.POSITIVE, ("ev-1",)),
            make_edge("i-negative", "source:b", "company:x", "e-2", ImpactDirection.NEGATIVE, ("ev-2",)),
        ]
    )
    assert len(summaries) == 1
    assert summaries[0].direction == ImpactDirection.MIXED
    assert summaries[0].impact_count == 2


def test_explicit_mixed_and_uncertain_directions_are_not_promoted():
    mixed = summarize_impact_edges(
        [make_edge("i-mixed", "source:a", "company:x", "e-1", ImpactDirection.MIXED, ("ev-1",))]
    )
    uncertain = summarize_impact_edges(
        [make_edge("i-uncertain", "source:b", "company:y", "e-2", ImpactDirection.UNCERTAIN, ("ev-2",))]
    )
    assert mixed[0].direction == ImpactDirection.MIXED
    assert uncertain[0].direction == ImpactDirection.UNCERTAIN


def test_empty_input_returns_no_synthetic_impact():
    assert summarize_impact_edges([]) == []
