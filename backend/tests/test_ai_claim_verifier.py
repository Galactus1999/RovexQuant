from rovexquant.ai.claim_verifier import (
    _overall_status,
    _parse_verifier_json,
    _validate_claims,
    extract_research_source_refs,
)


def test_extract_research_source_refs_keeps_only_explicit_ids_and_urls():
    evidence = (
        "Market snapshot [market:EOD:2026-11-20:NSE:RELIANCE] "
        "Macro note [macro:INFLATION:2026-10] "
        "Event [event:EVT-42] Filing [filing:ANN-8] "
        "https://example.com/source?id=1"
    )
    refs = extract_research_source_refs(evidence)
    assert "market:EOD:2026-11-20:NSE:RELIANCE" in refs
    assert "macro:INFLATION:2026-10" in refs
    assert "event:EVT-42" in refs
    assert "filing:ANN-8" in refs
    assert "https://example.com/source?id=1" in refs


def test_claim_verifier_downgrades_unverifiable_source_quotes():
    evidence = "RELIANCE EOD close was 1,234.50 on 2026-11-20."
    claims = _validate_claims(
        [
            {
                "claim": "The close was 1,234.50.",
                "source_ref": "market:EOD:2026-11-20:NSE:RELIANCE",
                "evidence_quote": "close was 1,234.50",
                "verdict": "supported",
                "reason": "Exact close is present.",
            },
            {
                "claim": "Revenue grew 40%.",
                "source_ref": "market:EOD:2026-11-20:NSE:RELIANCE",
                "evidence_quote": "revenue grew 40%",
                "verdict": "supported",
                "reason": "Claim appears plausible.",
            },
            {
                "claim": "The company announced a merger.",
                "source_ref": "unknown-source",
                "evidence_quote": "announced a merger",
                "verdict": "contradicted",
                "reason": "No merger is visible.",
            },
        ],
        evidence_text=evidence,
        allowed_source_refs={"market:EOD:2026-11-20:NSE:RELIANCE"},
    )
    assert claims[0]["verdict"] == "supported"
    assert claims[1]["verdict"] == "insufficient"
    assert claims[2]["verdict"] == "insufficient"
    assert claims[1]["source_ref"] == ""


def test_claim_verifier_overall_status_is_conservative():
    assert _overall_status([]) == "UNVERIFIED"
    assert _overall_status([{"verdict": "supported"}]) == "VERIFIED"
    assert _overall_status([{"verdict": "supported"}, {"verdict": "insufficient"}]) == "PARTIALLY_VERIFIED"
    assert _overall_status([{"verdict": "supported"}, {"verdict": "contradicted"}]) == "REJECTED"


def test_claim_verifier_requires_structured_json():
    parsed = _parse_verifier_json('{"claims":[{"claim":"x"}]}')
    assert len(parsed["claims"]) == 1
    try:
        _parse_verifier_json("The claims look supported.")
    except ValueError:
        pass
    else:
        raise AssertionError("Unstructured verifier output must be rejected")
