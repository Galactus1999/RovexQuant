# Imported CSV background refresh

- The existing ResearchCsvAutoSyncManager remains the only owner of provider refresh and merge logic.
- WorkManager schedules one unique periodic job at a four-hour interval. Android may defer execution; this is not an exact wall-clock schedule.
- Work requires a connected network and a non-low battery state. WorkManager applies bounded exponential retry behavior.
- The worker uses the existing encrypted QuantSessionStore; it never stores credentials in WorkManager input or output.
- An invalid/expired session clears the saved access token and ends the attempt without retrying. The next successful sign-in recreates the schedule.
- If a user-selected SAF folder is configured, the worker rescans its immediate CSV files before refreshing datasets. Revoked folder permission is cleared rather than retried indefinitely.
- CSV parsing limits, dataset identity, idempotent merge, provenance, source validation and the four-hour per-dataset freshness window remain enforced by existing components.
- The feature is disabled/cancelled through the existing Automatic data updates switch. No background work runs while signed out or when the switch is off.
- This is best-effort periodic work. Doze, battery policy, network availability and Android scheduler quotas can delay it. It does not guarantee a refresh at a precise time.
## Execution and dependency integrity

- The worker loads the encrypted session and performs SAF folder scans and local dataset-list reads on `Dispatchers.IO`; network refresh continues through the existing repository and sync manager.
- WorkManager 2.12.0 is pinned in `android/app/gradle.lockfile` and its artifact checksums are pinned in `android/gradle/verification-metadata.xml`. These files are committed; the one-time metadata refresh marker is not part of the merged implementation.
- CI validates strict dependency verification, Kotlin compilation, lint, unit/backend regression, APK identity and signing, Android emulator navigation regression, and the required acceptance gate.
