"""Deterministic event normalization and Indian-sector impact hypotheses.

This module intentionally does not call an LLM. Classification and sector mapping
are transparent rule-based hypotheses that retain their evidence and limitations.
"""
from __future__ import annotations

import hashlib
import re
import unicodedata
from collections import OrderedDict
from typing import Any

_STOP_WORDS = {
    "a", "an", "and", "as", "at", "by", "for", "from", "in", "into", "is",
    "of", "on", "or", "the", "to", "with",
}

_EVENT_RULES: tuple[tuple[str, tuple[str, ...]], ...] = (
    ("WAR_CONFLICT", (
        "war", "airstrike", "missile", "invasion", "military", "armed conflict",
        "border clash", "ceasefire", "attack on", "drone strike", "hostilities",
    )),
    ("MONETARY_POLICY", (
        "interest rate", "rate cut", "rate hike", "central bank", "reserve bank",
        "rbi policy", "federal reserve", "repo rate", "inflation", "monetary policy",
    )),
    ("GEOPOLITICS_TRADE", (
        "sanction", "tariff", "export ban", "trade restriction", "embargo",
        "trade deal", "diplomatic", "geopolitical", "import duty",
    )),
    ("COMMODITY_SUPPLY", (
        "crude oil", "oil supply", "natural gas", "lng", "copper", "lithium",
        "coal", "gold price", "commodity shortage", "supply disruption",
    )),
    ("TECHNOLOGY_INNOVATION", (
        "artificial intelligence", "ai model", "semiconductor", "chipmaking",
        "quantum computing", "battery breakthrough", "robotics", "invention",
        "patent", "technology breakthrough", "new technology",
    )),
    ("REGULATION_POLICY", (
        "sebi", "regulation", "regulatory", "government policy", "union budget",
        "budget announcement", "subsidy", "production linked incentive", "tax change",
    )),
    ("CORPORATE_DEVELOPMENT", (
        "quarterly results", "earnings", "merger", "acquisition", "order win",
        "contract win", "bankruptcy", "capital expenditure", "capex", "guidance",
    )),
)

# Direction describes a plausible first-order exposure channel, not a return forecast.
_SECTOR_IMPACTS: dict[str, tuple[tuple[str, str, str], ...]] = {
    "WAR_CONFLICT": (
        ("DEFENCE", "POTENTIAL_SUPPORTIVE", "Higher perceived security needs may alter procurement expectations; actual benefit requires confirmed orders and execution."),
        ("AVIATION", "POTENTIAL_NEGATIVE", "Airspace disruption, rerouting, insurance and fuel costs may pressure operators."),
        ("LOGISTICS", "POTENTIAL_NEGATIVE", "Route disruption, freight and insurance costs may rise."),
        ("OIL_GAS", "MIXED", "Supply risk may lift prices, while downstream margins and policy intervention can move differently."),
        ("CHEMICALS", "POTENTIAL_NEGATIVE", "Energy and feedstock volatility can pressure input costs."),
    ),
    "MONETARY_POLICY": (
        ("BANKS", "MIXED", "Funding costs, deposit repricing, loan growth and net interest margins can move in different directions."),
        ("NBFC", "POTENTIAL_NEGATIVE", "Higher funding costs and weaker credit demand may pressure spreads and growth."),
        ("REALTY", "POTENTIAL_NEGATIVE", "Higher borrowing costs can reduce affordability and demand."),
        ("AUTO", "POTENTIAL_NEGATIVE", "Financing costs may affect vehicle affordability and demand."),
        ("CONSUMER_DURABLES", "POTENTIAL_NEGATIVE", "Rate-sensitive discretionary demand may weaken."),
    ),
    "GEOPOLITICS_TRADE": (
        ("IT_SERVICES", "MIXED", "Cross-border demand, client budgets, visa policy and currency effects require company-specific exposure checks."),
        ("AUTO", "MIXED", "Tariffs and supply-chain rules can affect export competitiveness and imported components differently."),
        ("PHARMA", "MIXED", "Market access, API sourcing and regulatory restrictions can create both risks and opportunities."),
        ("METALS", "MIXED", "Trade barriers can alter export realizations, imports and global benchmark pricing."),
        ("CHEMICALS", "MIXED", "Feedstock sourcing and export access are company- and product-specific."),
    ),
    "COMMODITY_SUPPLY": (
        ("OIL_GAS_UPSTREAM", "POTENTIAL_SUPPORTIVE", "Higher benchmark realizations may support upstream producers, subject to their actual production mix."),
        ("OIL_MARKETING", "POTENTIAL_NEGATIVE", "Higher crude input costs may pressure marketing margins unless pass-through is timely."),
        ("AVIATION", "POTENTIAL_NEGATIVE", "Fuel is a material operating cost and price shocks can compress margins."),
        ("PAINTS", "POTENTIAL_NEGATIVE", "Crude-linked inputs and packaging costs can raise costs."),
        ("CHEMICALS", "MIXED", "Feedstock costs and selling-price pass-through differ substantially by product."),
        ("FERTILISERS", "MIXED", "Gas/feedstock costs and administered pricing or subsidy policy jointly determine exposure."),
        ("LOGISTICS", "POTENTIAL_NEGATIVE", "Fuel and freight costs can increase."),
    ),
    "TECHNOLOGY_INNOVATION": (
        ("IT_SERVICES", "MIXED", "New capabilities may create implementation demand while automating existing service revenue."),
        ("ELECTRONICS", "POTENTIAL_SUPPORTIVE", "Adoption may expand component and manufacturing demand; local capacity and customer wins must be verified."),
        ("TELECOM", "MIXED", "Infrastructure demand may rise, but capex, spectrum and monetisation determine economics."),
        ("CAPITAL_GOODS", "MIXED", "Automation and infrastructure adoption can create orders subject to execution and return on capital."),
    ),
}

def _normalize_title(title: str) -> str:
    normalized = unicodedata.normalize("NFKC", title).casefold()
    tokens = re.findall(r"[a-z0-9]+", normalized)
    return " ".join(token for token in tokens if token not in _STOP_WORDS)


def _classify(title: str) -> tuple[str, list[str]]:
    lowered = unicodedata.normalize("NFKC", title).casefold()
    matches: list[tuple[str, int]] = []
    for category, phrases in _EVENT_RULES:
        hits = sum(1 for phrase in phrases if phrase in lowered)
        if hits:
            matches.append((category, hits))
    if not matches:
        return "GENERAL_MARKET_NEWS", []
    priority = {name: index for index, (name, _) in enumerate(_EVENT_RULES)}
    matches.sort(key=lambda item: priority[item[0]])
    return matches[0][0], [category for category, _ in matches]


def analyze_articles(articles: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Deduplicate exact normalized headlines and attach explainable sector channels."""
    grouped: OrderedDict[tuple[str, str], list[dict[str, Any]]] = OrderedDict()
    for article in articles:
        title = article.get("title")
        if not isinstance(title, str) or not title.strip():
            continue
        normalized = _normalize_title(title)
        if not normalized:
            continue
        seen_at = str(article.get("seen_at") or "")
        day = seen_at[:8] if re.fullmatch(r"\d{8}T\d{6}Z", seen_at) else "unknown"
        grouped.setdefault((normalized, day), []).append(article)

    events: list[dict[str, Any]] = []
    for (normalized, day), group in grouped.items():
        representative = group[0]
        category, tags = _classify(str(representative["title"]))
        sector_impacts = [
            {"sector": sector, "direction": direction, "mechanism": mechanism}
            for sector, direction, mechanism in _SECTOR_IMPACTS.get(category, ())
        ]
        unique_sources: OrderedDict[str, dict[str, str]] = OrderedDict()
        for item in group:
            domain = str(item.get("domain") or "")
            url = str(item.get("url") or "")
            key = domain.casefold() or url
            if key and key not in unique_sources:
                unique_sources[key] = {
                    "domain": domain,
                    "url": url,
                    "seen_at": str(item.get("seen_at") or ""),
                    "language": str(item.get("language") or ""),
                    "source_country": str(item.get("source_country") or ""),
                }
        rule_hits = sum(1 for phrase in dict(_EVENT_RULES).get(category, ()) if phrase in str(representative["title"]).casefold())
        confidence = min(0.80, 0.45 + (0.07 * min(rule_hits, 3)) + (0.05 if len(unique_sources) > 1 else 0.0))
        event_key = f"{day}:{normalized}".encode("utf-8")
        event_id = hashlib.sha256(event_key).hexdigest()[:20]
        events.append({
            "event_id": event_id,
            "headline": str(representative["title"]),
            "event_category": category,
            "event_tags": tags,
            "source_article_count": len(group),
            "independent_source_count": len(unique_sources),
            "sources": list(unique_sources.values()),
            "classification_method": "DETERMINISTIC_KEYWORD_RULES",
            "classification_confidence": round(confidence, 2),
            "confidence_semantics": "Heuristic rule-match score; not a calibrated probability.",
            "sector_impacts": sector_impacts,
            "stock_mapping_status": "NOT_RESOLVED",
            "stock_mapping_note": "Company and instrument-level exposure mapping is intentionally withheld until a canonical, source-verified listed-company master is integrated.",
            "impact_status": "HYPOTHESIS_REQUIRES_COMPANY_LEVEL_VERIFICATION" if sector_impacts else "NO_SECTOR_IMPACT_RULE",
        })
    events.sort(
        key=lambda item: max((source["seen_at"] for source in item["sources"]), default=""),
        reverse=True,
    )
    return events
