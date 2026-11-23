# P5 — durable intelligence event archive

The event-discovery pipeline persists normalized event fingerprints, source articles, provider provenance, ingestion runs and conservative NSE issuer candidates.

- GDELT remains a global discovery index, not a verified primary source.
- RBI, SEBI and PIB RSS links retain publisher domains and primary-source evidence class.
- Event fingerprints are SHA-256 hashes of normalized headline plus the source seen-date bucket.
- Canonical HTTPS source URLs are deduplicated; UTM and click-tracking parameters are removed.
- Issuer candidates require an exact full company name or exact uppercase NSE symbol in the headline. Three-letter symbols are intentionally not mapped.
- The current Upstox NSE BOD master is used for candidates. It is not a point-in-time universe and mappings are not proof of economic exposure.
- Sector impacts remain deterministic hypotheses; no order execution or automatic recommendation is introduced.

Hourly scheduled refresh: GDELT plus official RBI/SEBI/PIB feeds. Manual refresh and archive read endpoints require an app session.

Endpoints:
- GET /api/v1/app/intelligence/events — on-demand retrieval and archive write.
- POST /api/v1/app/intelligence/refresh — explicit refresh.
- GET /api/v1/app/intelligence/archive — read persisted event history.
- GET /api/v1/app/intelligence/archive/status — ingestion health.
