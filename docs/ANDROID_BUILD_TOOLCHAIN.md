# Android build toolchain and dependency integrity

## Supported toolchain

| Component | Pinned version | Compatibility rationale |
|---|---:|---|
| JDK | 17 | Required by the selected Android Gradle Plugin line |
| Android Gradle Plugin | 8.11.1 | Requires Gradle 8.13 or newer; JDK 17 |
| Kotlin Gradle Plugin | 2.2.21 | Official Kotlin matrix lists full compatibility through Gradle 8.14 and AGP 8.11.1 |
| Gradle | 8.14.8 | Latest patch in the 8.14 line at the Phase 1 verification date; includes subsequent 8.14 security fixes |
| Android compile SDK | 35 | Within the supported API range of AGP 8.11.1 |

The previous combination (AGP 8.7.3, Kotlin 2.2.21, Gradle 8.9) was outside the
official KGP compatibility range. The emulator compile emitted a Kotlin daemon
NoSuchMethodError and fell back to another compiler strategy. The toolchain is
now aligned rather than hiding the incompatibility with an in-process compiler
workaround.

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
- The first successful Phase 1 Android build creates the baseline lock state and the
  persistence job commits it to the implementation branch. Subsequent runs consume
  the committed files and fail on unreviewed drift.

For a deliberate dependency update, resolve the intended app/test configurations,
generate the updated lock state and verification checksums with Gradle's
write-lock and write-verification-metadata options, inspect the dependency and
license diff, then commit both files with the dependency change. Never disable
strict verification to get a green build.
