"""Deterministic, non-scoring rollups for evidence-linked impact edges."""
from __future__ import annotations

from collections import defaultdict
from typing import Iterable

from pydantic import Field

from rovexquant.intelligence.contracts import (
    ContractModel,
    ImpactDirection,
    ImpactEdge,
)


class ImpactSummary(ContractModel):
    """A transparent grouping of graph edges, not a stock recommendation or alpha score."""

    target_entity_id: str
    impact_count: int = Field(ge=1)
    direction: ImpactDirection
    source_entity_ids: tuple[str, ...]
    event_ids: tuple[str, ...]
    impact_ids: tuple[str, ...]
    evidence_ids: tuple[str, ...]


def _aggregate_direction(directions: set[ImpactDirection]) -> ImpactDirection:
    if ImpactDirection.MIXED in directions:
        return ImpactDirection.MIXED
    has_positive = ImpactDirection.POSITIVE in directions
    has_negative = ImpactDirection.NEGATIVE in directions
    if has_positive and has_negative:
        return ImpactDirection.MIXED
    if has_positive:
        return ImpactDirection.POSITIVE
    if has_negative:
        return ImpactDirection.NEGATIVE
    return ImpactDirection.UNCERTAIN


def summarize_impact_edges(edges: Iterable[ImpactEdge]) -> list[ImpactSummary]:
    """Group impacts by target and preserve opposing evidence without averaging it away.

    The function intentionally does not multiply confidence values or generate a
    buy/sell score. Ranking requires source quality, exposure strength, point-in-time
    financial data and a separately validated methodology.
    """
    grouped: dict[str, list[ImpactEdge]] = defaultdict(list)
    for edge in edges:
        grouped[edge.target_entity_id].append(edge)

    summaries: list[ImpactSummary] = []
    for target_id in sorted(grouped):
        target_edges = grouped[target_id]
        summaries.append(
            ImpactSummary(
                target_entity_id=target_id,
                impact_count=len(target_edges),
                direction=_aggregate_direction({edge.direction for edge in target_edges}),
                source_entity_ids=tuple(sorted({edge.source_entity_id for edge in target_edges})),
                event_ids=tuple(sorted({edge.event_id for edge in target_edges})),
                impact_ids=tuple(sorted({edge.impact_id for edge in target_edges})),
                evidence_ids=tuple(sorted({evidence_id for edge in target_edges for evidence_id in edge.evidence_ids})),
            )
        )
    return summaries
