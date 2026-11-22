# Android build toolchain and dependency integrity

## Supported toolchain

| Component | Pinned version | Compatibility rationale |
|---|---:|---|
| JDK | 17 | Required by the selected Android Gradle Plugin line |
| Android Gradle Plugin | 8.11.1 | Requires Gradle 8.13 or newer; JDK 17 |
| Kotlin Gradle Plugin | 2.2.21 | Official Kotlin matrix lists full compatibility through Gradle 8.14 and AGP 8.11.1 |
| Gradle | 8.14.8 | Latest patch in the 8.14 line at the Phase 1 verification date; includes subsequent 8.14 security fixes |
| Android compile SDK | 35 | Within the supported API range of AGP 8.11.1 |

The previous combination (AGP 8.7.3, Kotlin 2.2.21, Gradle 8.9) was also within
Kotlin's published compatibility range. CI nevertheless exposed a Kotlin
compiler-daemon NoSuchMethodError. The verification metadata contained two
kotlin-daemon-client versions (2.2.21 and 2.4.10); the latter is associated with
the license-report plugin's newer Kotlin build dependency. The license plugin
is now applied through an isolated buildscript classpath that excludes its
compiler/daemon dependency, and CI asserts that critical compiler/daemon
artifacts match the app's pinned Kotlin Gradle Plugin. Silent compiler fallback
is disabled so a recurrence fails visibly instead of being reported as a
successful compile.

Compatibility references:
- Kotlin: https://kotlinlang.org/docs/gradle-configure-project.html
- Android Gradle Plugin: https://developer.android.com/build/releases/about-agp
- Gradle 8.14.8 release notes: https://docs.gradle.org/8.14.8/release-notes.html
- Gradle release checksums: https://gradle.org/release-checksums/

## Dependency integrity

- All resolvable app configurations are dependency-locked.
- Lock mode is STRICT: a newly resolved configuration without lock state fails.
- The app lockfile is android/app/gradle.lockfile and must be committed.
- Artifact and Gradle module metadata SHA-256 checksums are in android/gradle/verification-metadata.xml.
- CI uses strict dependency verification for the normal build and the emulator instrumentation job.
- License inventory generation and the runtime dependency allowlist are separate required steps.
- The first successful Phase 1 Android build created the baseline lock state and the
  persistence job committed it to the implementation branch.
- A one-time, explicit refresh marker is required for intentional lock/checksum changes;
  CI removes that marker after validating and committing the new baseline.
- Subsequent runs consume committed files and fail on unreviewed drift.

For a deliberate dependency update, resolve the intended app/test configurations,
generate the updated lock state and verification checksums with Gradle's
write-lock and write-verification-metadata options, inspect the dependency and
license diff, then commit both files with the dependency change. Never disable
strict verification to get a green build.
