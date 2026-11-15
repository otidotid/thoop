# THOOP v13.2.0 — Metabolic preview 1

Android Full Debug prerelease from `feature/lab-marks-ai-integration-v1`.
Package: `com.otidotid.thoop.debug`; version: `13.2.0-debug`; version code: `558`.

Lab Book observations now pass through consent-checked AI interpretation and strict source validation.
An optional AI metabolic age adjustment then feeds THOOP's existing fixed 80/20 Fitness Age calculation.
The experimental input replaces the local metabolic component and separate custom-glucose addition,
avoiding double counting. Other calculation engines keep their existing inputs and formulas.

## Changes

- Interpret complete lab histories in bounded batches, with cancellation, consent rechecks and explicit failures.
- Accept only a bounded 0–4-year metabolic estimate matching the original dated readings, profile and request.
- Preserve original lab records and keep an immutable local audit of accepted estimates and previous outputs.
- Revalidate saved estimates on calculation; expire them after 30 score-date days or source/profile changes.
- Retain the existing local calculation when no accepted estimate is available.
- Remove the large calculation-audit block above the score; identify AI metabolic input with a concise label.
- Repair the 30 previous JVM failures, including real database upgrades and workout replacement safeguards.

## Validation and limits

The preceding implementation passed 6,853 JVM tests with zero failures and six existing fixture-dependent
skips, plus 45 Worker tests. Release-specific build and test results accompany the downloadable verification
report and GitHub Actions run. Tests use simulated model responses; they do not establish clinical accuracy.
The AI input is an experimental wellness estimate, not a validated laboratory-to-age conversion.

This is an Android-only feature preview. The shared marketing version is aligned for repository versioning;
no iOS or macOS feature build is included.

## Installation

The APK uses the same signing certificate as the working THOOP v3 debug build supplied for this release.
Update that package in place using Android's installer or `adb install -r`. Keep the existing app and its
storage. A signature mismatch means the installed app used a different key; retain it and build with that
original key rather than uninstalling it.

The complete Worker bundle, install guide, source patch and checksums are attached to the release.
Deploy the Worker bundle to the existing Cloudflare service and set both `ENABLE_LAB_INTERPRETATION`
and `ENABLE_METABOLIC_ESTIMATE` to the text value `true`. Preserve its existing AI and DB bindings.
In Coach settings, enable the sharing controls and optional AI metabolic estimate, then select
**Review labs and update Fitness Age**. The install guide provides the exact controls.

The estimate uses the latest reading per marker on or before the current weekly score date, the most recent
Saturday. The first release remains bounded to one 120,000-byte metabolic request; oversized snapshots
fall back explicitly. The whole-history interpretation path has no 32-observation total limit.
