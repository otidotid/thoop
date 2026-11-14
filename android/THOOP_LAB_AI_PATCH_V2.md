# THOOP Lab interpretation and deterministic output patch

This incremental Android/Cloud Coach patch extends `feature/lab-marks-ai-integration-v1` from
`1b2c520bf653771b505c850f414952c5adcb4408`. It does not deploy a Worker or install an APK.
Stage 4 calculations and Stage 5 provenance remain authoritative; optional AI supplies commentary only.

## Calculation audit and boundaries

| Output/component | Current numerical input path | Patch |
|---|---|---|
| VO2 max weekly estimate | Original waist measurement after unit validation selects existing Nes variant; missing waist selects existing Uth HR-ratio fallback | Stable row selection and the same captured evidence for calculation and method provenance |
| Fitness Age | Existing age/sex/RHR/activity model plus body measurement metabolic blend | Original weight/height/waist/body-fat rows retain raw and canonical units, date, source, ID and timestamp in provenance |
| BMI, waist/height ratio, body fat, metabolic delta | Existing deterministic body measurement calculations | Existing calculations unchanged; input eligibility remains local |
| Custom non-fasting glucose age delta | Explicit user-assigned `glycemic_non_fasting` policy only; standard marker presence suppresses it | Malformed dates/context/units/values cannot crash the engine or hide an older eligible reading; stable sample-day/timestamp/ID order |
| Calories / total calories | Existing profile, HR/activity, Keytel/BMR/TDEE paths | No new direct lab or AI adjustment; profile weight is not silently replaced by Lab Book weight |
| Vitality / Body Age | Weekly RHR, sleep, HRV and steps integration | No direct lab adjustment; weekly integration still does not supply VO2 to Vitality |
| Recovery and its physiological components | Existing HRV/RHR/respiration/sleep/temperature/prior activity logic | No direct lab or AI adjustment |
| Strain and exercise load | Existing duration, HR zones and activity calculations | Unchanged |

The existing glucose-to-age delta is a bounded product heuristic, **not a clinically validated
conversion of glucose into years of age**. No new clinical coefficients, formulas or marker aliases
are introduced. Unit conversions use the original measurement rather than the unitless projection.
The contract version advances to `lab-score-contract-v2`; equation and glucose policy identifiers
remain unchanged because valid-input equations are unchanged.

## Data safety and reliable outputs

There is no database schema change, destructive migration, source-record edit, backup format change,
or historical bulk rewrite. Normal existing recomputation and missing-week fill remain in place.
One captured `WeeklyLabInputs` supplies both calculation and provenance on the normal, manual refresh,
and missing-history paths. Existing source rows remain unchanged. Tied readings now have a stable ID
tie-break. Invalid newest evidence falls back to older eligible evidence. Dates after the score date
are excluded from numerical lab resolution.

The existing fasting-glucose/HbA1c suppressor is still a presence-only projection gate, without raw
unit validation or freshness limits; its provenance now says so. Missing historical weeks still use
the current profile age/sex, preserving pre-existing behavior. Capturing labs is not an atomic
transaction across the different source queries, but computation and provenance use the resulting
same snapshot. This patch does not certify the scientific validity of every legacy model.

## Optional interpretation

Android Coach Settings adds **Share exact Lab Book evidence**, off by default. The existing general
data-sharing and on-device-signals opt-ins must also be enabled. Only an explicit lab question or a
recorded marker name invokes structured interpretation. Ordinary meals and activity chats retain
their existing flow. The Worker must separately enable `ENABLE_LAB_INTERPRETATION=true`; no hosted
configuration is changed by this patch.

Every built-in and arbitrary custom marker remains eligible for interpretation, including qualitative
results. The current request uses the same latest-three-readings-per-marker history as Coach, across
canonical and active strap storage. A batch is limited to 32 observations and 60,000 UTF-8 bytes;
larger snapshots report unavailable rather than silently dropping records. Automatic larger-history
batching is deferred. Existing summary coaching remains available.

The explicit grant covers exact original values, units, source labels, dates/timestamps, sample
conditions, names, notes and report ranges. Persistent row and device IDs remain local; opaque
references and SHA-256 fingerprints bind replies to source snapshots. Free-text names/notes may
themselves contain identifying information; users choose whether to share them.

The response has a strict versioned schema for identity uncertainty, qualitative confidence, domains,
interpretation, unverified evidence and limitations. Unknown properties (including scores, values,
units, weights and coefficients), duplicate/unknown references, altered fingerprints, wrong types,
oversized payloads and malformed responses fail closed. Android re-reads originals to reject edits
or deletions that occurred while the request was in flight. Eligibility is computed locally from
original rows, never from AI identity/domain claims. Eligibility does not assert actual use in a score.

AI prose and citations remain unverified. They are labeled accordingly and are never fed to any
numerical engine or used to edit a record. The local appendix has a recognizable boundary and is
removed from all outgoing provider history, including after consent is revoked. A response may omit
findings; each missing finding is explicitly unavailable. The display caps at 10,000 characters and
discloses clipping. Provider errors, timeout (12 seconds), old Workers, disabled feature and payload
limits preserve ordinary Coach/local calculation behavior. The complete request cap is checked
before attaching optional evidence. No structured lab request/result is written to Worker memory.
Ordinary Coach conversation memory keeps its existing behavior.

Structured interpretation currently targets the Cloud Coach path only. Direct/custom provider
summary coaching is retained. This Android-specific v42/v43 branch integration does not modify
Swift models, shared backup schemas, BLE, Health Connect, food intake persistence or release IDs.
New consent text uses English fallback resources. No version/signing change is needed for this
source feature patch; a separately approved release should advance release versions together.

## Verification

- Actual production entity/contract/policy/Fitness Age/Vitality/total-calorie Kotlin sources compiled
  with JDK 17, cached Kotlin 1.9.24 and real Room annotations/JSON/JUnit dependencies: 73 tests passed.
- Four weekly calculation/provenance golden tests also passed locally using extracted unchanged
  production method bodies; this is a partial source check, not an Android APK build.
- Worker Node tests: 33 passed, including all existing tests. The base Worker had 23/24 passing;
  the existing failure was a walk question misclassified as food preview. The narrow classifier fix
  retains food previews and is covered by regression tests.
- The branch's previous full Android CI at the base commit built its APK successfully, then reported
  6,768 tests, 30 failures and 6 skipped. The failures include unrelated source/schema/permission/UI
  drift. They are not waived or hidden by this patch. The patched workflow runs focused regressions,
  the full suite, and uploads test reports so the exact new result can be compared with this baseline.
- Full patched APK/test status is reported with the delivered commit. Device/BLE behavior and live
  model quality are not hardware-tested. No live personal lab data is used in tests.

## Install and review safely

Use a separate checkout/worktree. Export a normal THOOP backup before installing an app update.
Do not uninstall THOOP, clear its storage, import a database over existing data, or use `git reset --hard`.
Fetch the feature branch, create a separate local review branch at the delivered commit, open its
`android` folder in Android Studio and use JDK 17 / the configured SDK (this branch uses compileSdk 35).
Run `assembleFullDebug` and `testFullDebugUnitTest`, with `--no-build-cache --rerun-tasks` after the
branch switch. Keep your existing `local.properties` and signing material private and unchanged.

The Full Debug app ID is `com.otidotid.thoop.debug`, distinct from release `com.otidotid.thoop`.
An app installed under another ID has separate storage and may start empty. Updating the existing
debug installation requires its same signing key; an incompatible-signature error must not be
resolved by uninstalling the data-bearing app. A release update must use the existing release key.

To enable the optional Worker feature later, deploy the repository's `cloud-coach/worker.js` with its
new imported `lab-interpretation.js` module using the existing Worker process, then set the environment
flag. Existing clients and a disabled flag retain existing chat behavior. Worker deployment is not
required for the local safety/provenance improvements and is not performed here.
