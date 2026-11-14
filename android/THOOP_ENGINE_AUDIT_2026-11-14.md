# THOOP / ALKE — Calculations, outputs, and Lab Marks audit

Branch: `feature/lab-marks-ai-integration-v1`. Stable branch and v13.1.0 tag have not been modified.
Source baseline: `fix/restore-vo2-vitality-detail-clean` at `698ade187976574a2668e6366ea26fe2f02eaef3`.

## Non-negotiable contract

1. THOOP mathematical engines own every numerical score, formula, coefficient and weight.
2. AI interprets custom and built-in lab evidence, including *missing*, *invalid*, *ambiguous* or *stale* results. "No result" is not a normal or zero reading.
3. Only independently approved THOOP numeric rules can affect scoring. Model text cannot introduce a new value, score or biological correction.
4. Cloud AI failures and absent consent never block local scoring or history. Original lab measurements never change.
5. A derived score reports the actual algorithm version and available measured inputs. Older scores with missing trace are explicitly **unknown**; reconstructed evidence must never masquerade as an original trace.
6. Confidence, uncertainty, input dates, formula version and provenance must survive a change in profile or lab history.
7. No unrelated functionality, food logging, existing backup, rolling-history or other data deletion should be modified by this work.

## Source inventory and audit scope

The repository contains **144 Kotlin modules under `com.noop.analytics`**. This review inventoried all 144 and inspected key engine implementations, orchestration, DAO persistence and Coach context; it is **not a laboratory or clinical validation study**, nor a claim of complete line-by-line review of 144 modules. The high-impact decisions below are grounded in actual source code.

| Output family | Production algorithm / observed inputs | Key findings, cautions, recommendation | Patch status |
|---|---|---|---|
| VO₂max | Nes 2011 waist model; Uth HRmax/RHR fallback; weekly RHR, PA proxy, age/sex, dated waist | Nes vs Uth can jump; observed HRmax not used for Uth fallback; as-of-source label could disagree with Lab Book waist. Strongly recommend exercise validation and explicit method per point | Fixes unit validation, method attribution and version/partial input trace; physiological model unchanged |
| Fitness Age | Age, sex, RHR median, strain-derived PA index, waist/weight/height/body fat and bounded custom glucose policy | PA index is only a strain proxy; unknown sex defaults to male coefficients in `coeffs` while confidence is reduced; metabolic overlay needs personal validation; model uncertainty should be shown | Validated original lab units/dates, pinned algorithm version, partial input trace, Lab Book mutation refresh; formula unchanged |
| Vitality / Body Age | RHR, nightly sleep, regularity, HRV, steps; log-hazard/Gompertz model | Engine accepts VO₂ input but orchestration omits it; health-hazard coefficients are wellness estimates, not medical longevity predictions. Do **not** add VO₂ without validation/double-counting test | Pinned version and new output provenance; no new coefficient or input |
| Recovery / Charge | HRV Z-score, RHR, respiratory rate, sleep, skin temp, recovery index, activity balance | Baseline wear quality and HRV/RHR disagreement matter; saturation mitigation is deliberately detected but NOT applied in existing code—do not silently activate | Daily input summaries + fixed version (old values remain) |
| Readiness | HRV, RHR, respiratory rate, acute/chronic training load and monotony | Relative wellness heuristic, not diagnosis. Sparse source coverage can distort readiness; show baseline sample size | Explicit engine version; no numerical change |
| Effort / Strain | HR time series, HRmax estimate, RHR and zone weights | Personal HRmax can be estimated from history or Tanaka; model sensitivity and sensor quality should be tested with reference workouts | Version and partial HR-data trace; formula unchanged |
| Total Calories | Active expenditure plus time-scaled Mifflin-St Jeor resting energy | Sex unknown uses -78 intermediate constant, not an observed physiological value; activity calories are estimates. Lab Book weight does not automatically sync into the profile used here | Pinned version; no changes to expenditure math |
| Adaptive Expenditure | 21–42 day calorie intake and weight trend, water/food-log uncertainty | Good coarse retrospective cross-check, **not** input into daily calories; potential duplicate-day coverage check and unlogged food uncertainties | Pinned version; no changes to formula |
| Sleep performance / staging | In-bed, motion, HR/RR, stage totals, efficiency and sleep need | Default V2 is approximate, not PSG-derived; deep/REM minutes remain uncertain. Never present as clinical sleep stages | Partial input trace in daily output; no rest/staging coefficient change |
| Steps | Prefer real strap/phone counts; WHOOP4 motion-calibrated approximation | Sparse gravity signals mean motion→steps estimation, not counting actual footfalls | No change to step formula; future source QA |
| Oxygen | Health Connect measured calibration, personal feature regression | EXPERIMENTAL estimated oxygen is not direct calibrated SpO₂ measurement and must not be used for clinical interpretation | Pinned version; no math change |
| Stress / illness head-up | HR/RR/HRV, personal baseline; skin temp/resp rate, confounders | Autonomic proxy and illness-like pattern only; cannot diagnose disease. Distinguish lab-confirmed findings from wearable context | No numeric change; next audit phase |
| Training load / recovery forecasts | EWMA load model and next-day weighted projection | Model extrapolations, not actual observed values; missing days distinct from rest zero | Pinned training-load version; no math change |
| Circadian / sleep debt | Cosinor phase; trailing sleep need and daily deficit ledger | Timing interpretation needs coverage; sleep debt is heuristic and shouldn't imply literal physiological debt | No numerical change |

### Newly identified high-priority cross-cutting defects

- **Partial date-key ambiguity:** the latest rolling seven-day window may include days after the most recent Saturday while a computed value is saved under that earlier Saturday. Do not make historical clinical decisions from this calendar label without fixing the contract and historical tests.
- **Incomplete per-point trace:** original `scoreInputProvenance` stored only a `sourceId`, and some historical scores have no provenance at all. This patch creates additive nullable formula-version and input-summary fields; true raw-stream fingerprints and a complete per-score input ledger remain future work.
- **Custom Lab Marks:** local Lab Book can store arbitrary test names and units, but old Cloud Coach read the active strap ID and only latest entries; consented Lab Book AI context can therefore miss canonical records or trends.
- **Input units:** a CSV may store lab body measurements with units that differ from the catalog's preferred unit. A bare projected number cannot prove it is in centimetres or kilograms; this patch resolves from original rows with fixed conversion.
- **No assumption that lab abnormality == immediate fitness score penalty.** Evidence-only is the safe default for unknown custom markers.

## Patch implementation (foundation only)

| File or part | What changed | Scores changed? |
|---|---|---|
| `analytics/LabCalculationContract.kt` | Explicit approved lab connections, canonical unit normalization, future-date rejection, custom marker explanation-only unless existing bounded glucose evidence policy applies | **No new formulas**; validates values feeding existing ones |
| `analytics/IntelligenceEngine.kt` | Lab Book original dated values for age/VO₂, correct Nes/Uth label, provenance of weekly VO₂/Fitness Age/Vitality scores | Numerical outputs can correctly change if previously misinterpreted lab units; NO changes to formula coefficients |
| `analytics/ScoreTraceFormatter.kt`, `analytics/IntelligencePersistence.kt` | Observed daily input summaries for computed Recovery, Strain, Rest, active calories where available | No |
| `data/Entities.kt`, `WhoopDatabase.kt` | Database v42→v43: **two nullable columns** added to `scoreInputProvenance`. Existing rows retained, no destructive migration | No |
| `data/WhoopDao.kt`, `WhoopRepository.kt` | Read-only query for stored full score provenance | No |
| `ui/HealthScreen.kt` | Score detail presents recorded algorithm version and observed inputs (or truthfully says unknown legacy) | No |
| `ui/LabBookScreen.kt` | Show approved score relationships and rerun existing Fitness Age/VO₂ refresh after lab changes | Existing score refresh, no formula change |
| `ai/AiCoach.kt` | With existing consent, read built-in/custom Lab Marks from canonical and active IDs, latest 3 results, report missing built-ins and interpret uncertain values. Only natural-language advice, never numeric score writes | No |
| 10 major engine classes | Explicit pinned `ALGORITHM_VERSION` constants for future audit | No |
| Regression tests | Unit normalization, custom-marker gating, as-of dates, legacy provenance and non-destructive schema migration | No |

## Output trace semantics

- The new fields are `scoreInputProvenance.algorithmVersion` and `observedInputs`.
- The trace is captured at compute/write time, but **may be partial**: daily aggregate records do not contain every raw RR or HR sample; UI calls this out. Never pretend this is a complete raw measurement proof.
- Existing values are *not backfilled with guessed version IDs*. If the version is unknown, display "Not recorded (legacy/imported result)."
- A valid custom marker with no approved numerical rule is **still interpretable by AI**, and its relationship to THOOP scores is shown as "evidence-only."

## Acceptance gates before installing an APK

1. The new GitHub Actions workflow `THOOP Lab Score Audit` must complete `assembleFullDebug` and `testFullDebugUnitTest` successfully. If red, do not install the APK.
2. The database migration must start at 42, end at 43, be registered, use only nullable `ALTER TABLE ADD COLUMN`, and pass the existing full chain tests.
3. Rebuild and check actual device backup/restore from schema 42 to 43 (full backup taken before any install), and verify app launch retains Labs, food and health history.
4. On device, compare current Vitals/VO₂/Fitness Age/Vitality/calories/recovery/strain to old app with unchanged inputs; differences require a traceable explanation.
5. Add/edit/delete custom `ApoB`/Magnesium: Coach should recognize it with its own unit/date, note missing or questionable evidence, and unrelated scores must not move.
6. Enter a standard waist in centimetres and separately import an equivalent waist in inches: the same normalized input should be used. Verify matching Nes attribution (or Uth when no validated waist).
7. Disconnect the internet: all local scoring remains available. Simulate a Cloud Coach error; it must never block local metrics.
8. Re-test food logging end-to-end with the currently deployed Cloudflare Worker; this patch has **not** touched Worker JS, but Android regression verification is still required.

## Known unfinished work: do not confuse this patch with full completion

1. The 144 analytics modules need a separate exhaustive full-line audit and numerical differential test fixtures before claiming every component clinically/scientifically validated.
2. Provenance UI currently covers **Health → Vital details**, not every single metric screen; additional screens must consume the same stored record and report source/value/window/algorithm version.
3. Only the existing, **approved** lab-dependent paths can change numerical scores. A rich structured AI findings schema and separately validated evidence policy for new custom markers are not yet integrated into production calculation engines.
4. Immediate **automatic interpretation** when every Lab Book value is saved (without a Coach question) is not implemented. Current interpretation is on the already consented Cloud Coach interaction.
5. Exact historical profile values, complete raw HR/RR input fingerprints, weekly date alignment, and background/calorie source reconciliation require dedicated tests and additional patches.

## Local install/review (Windows)

```powershell
Set-Location C:\Projects\thoop-recovery
git status --short
git fetch origin feature/lab-marks-ai-integration-v1
git switch --track origin/feature/lab-marks-ai-integration-v1
Set-Location .\android
.\gradlew.bat assembleFullDebug testFullDebugUnitTest
```

**Never install over the known-good APK unless compilation + tests pass and an external backup exists.**
The feature branch does not affect the tagged v13.1.0 release or your deployed Cloudflare Worker.
