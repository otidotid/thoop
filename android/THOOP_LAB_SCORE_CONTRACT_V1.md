# THOOP / ALKE Lab Marks intelligence — Stage 4 & 5 patch

Status: **implementation foundation, not complete AI-to-engine integration**.
Branch: `feature/lab-marks-ai-integration-v1`.
Baseline: `fix/restore-vo2-vitality-detail-clean`.
No Cloudflare Worker change; no database schema migration; no original Lab Book records deleted.

## Architecture and ownership

```text
[Lab Book: any built-in/custom marker, raw + history]
                        |
              [AI reads with consent]
                        |
           [Interpretation, NOT scores]
                        |
       [STRICT deterministic input gate]
                        |
[THOOP engine: versioned and reproducible math]
                        |
     [THOOP output + method provenance]
```

**Hard rule:** the AI cannot write a numerical THOOP score, coefficient, weight, biomarker value or correction factor. A custom marker is readable and interpretable, but numeric score influence requires a named, independently validated THOOP algorithm/policy, not merely an LLM recommendation.

## Stage 4 audit and current status

| Component | Existing lab inputs in code | Patch behavior | Missing for full personal calibration |
|---|---|---|---|
| VO₂ max | Validated waist via Nes model; Uth without waist | Normalize body measurements to canonical units. Stamp actual Nes/Uth method used; reject impossible waist/future readings. Trigger local refresh after a Lab Book edit. | Exercise-based validation / explicit measured VO₂ max calibration and uncertainty estimates |
| Fitness Age / THOOP Age | Waist, weight, height, body fat; named optional non-fasting glycaemic policy | Convert/validate each original Lab Book row before math; honor as-of date and existing bounded custom glucose policy. | Versioned broader lab-evidence policies after validation; avoid treating every abnormal test as age penalty |
| Calories / TDEE | Profile, HR/activity; **no generic Lab Book interface** | **Unchanged.** Do not inject arbitrary lab corrections into resting or activity calories. | Investigate validated body-composition/profile sync separately and test parity |
| Vitality / Body Age | RHR, sleep, HRV, steps; no direct lab coefficient in current orchestrator | **Unchanged.** Explain custom labs but no direct score shifts. | Validate model, double-counting and health outcomes; do not infer direct mortality risk from one lab |
| Recovery / Readiness | Sleep and physiological signals, not direct Lab Book values | **Unchanged.** Do not treat single lab markers as instant recovery score changes. | Context-aware coaching after verified lab interpretation, without changing the recovery formula |
| Strain / exercise load | HR zones, duration and training signals | **Unchanged**; no lab-directed numeric output. | Calibrate HRmax and exercise HR signal quality using legitimate exercise data |

## Stage 5 output safeguards

1. The `LabCalculationContract` enumerates actual lab-dependent outputs and marks every output as THOOP-owned, not AI writable.
2. `resolveLatest` accepts the newest valid original reading **not later than the score's date**, with unit conversion for body measurements. The original row is not modified.
3. For the asynchronous Fitness Age paths, `IntelligenceEngine` uses the SAME resolved waist value for the Nes/Uth formula and the per-point `vo2max_est` provenance. A stale profile waist cannot mislabel the method.
4. The Lab Book marker detail displays its validated numerical score relationships. Unknown custom markers explicitly read as explanation-only.
5. All existing historical values remain in storage; no sweeping backfill or automatic deletion is introduced.

## Stage 1–3 compatibility / what is actually wired now

- `AiCoach.buildSignalsContext` now reads canonical `my-whoop` as well as distinct active-strap legacy records, preserving up to three dated readings per built-in **and custom** marker; uses `displayName`, units, sources, reference range, notes, meal conditions.
- The existing **two consent gates** are preserved: health-data consent plus Coach Signals sharing. No background cloud export of Lab Book data was added.
- AI can explain unusual/new custom markers *via conversation*, but it still returns natural language; a durable, reviewed, structured AI findings record feeding broader scores is **not yet implemented**.
- Custom markers without the explicitly existing `glycemic_non_fasting` evidence profile are **not** silently incorporated into score formulas.
- Offline health scores continue to work without AI. Cloudflare `worker.js` and the food-logging contract were **not touched** in this branch.

## Tests included

`LabCalculationContractTest` covers ten hard boundaries:

- All score contracts are AI-readonly;
- arbitrary custom marker -> evidence only;
- approved built-in waist -> intended metric paths;
- explicit known nonfasting glucose policy only;
- future sample -> no historical score leakage;
- inches/pounds -> canonical cm/kg conversion;
- unsupported units -> no numeric score input;
- newer invalid lab -> fall back to older valid dated lab;
- nonfinite/bad-dated readings -> ignored;
- no false claims that Calories, Vitality or Recovery already consume lab values.

The pre-existing `Vo2maxFallbackTest`, `Vo2MaxWaistProfileTest`, `CustomLabAgePolicyTest`, `Vo2MaxTrendProvenanceTest`, calorie and recovery tests must also pass.

## Apply safely (Windows PowerShell)

```powershell
Set-Location C:\Projects\thoop-recovery
git status --short
git fetch origin feature/lab-marks-ai-integration-v1
git switch -c lab-ai-review --track origin/feature/lab-marks-ai-integration-v1
Set-Location .\android
.\gradlew.bat :app:testFullDebugUnitTest --tests "com.noop.analytics.LabCalculationContractTest"
.\gradlew.bat :app:testFullDebugUnitTest --tests "com.noop.analytics.Vo2maxFallbackTest" --tests "com.noop.analytics.Vo2MaxWaistProfileTest"
.\gradlew.bat :app:assembleFullDebug
```

Stop on any compile or test failure; do not install over your working APK until checks pass. This branch may be inspected without merging or changing the existing working branch. The v13.1.0 release tag is untouched.

## Acceptance smoke test on Android

1. Confirm existing Health, Recovery, Calories, Vitality and workouts remain unchanged.
2. In Lab Book add a custom `ApoB` or `Magnesium` marker: it appears in Coach's Lab Book interpretation context when you opted into signals; it should NOT change an unrelated numeric score.
3. Add a built-in waist reading (cm); refresh Fitness Age and verify that the estimated VO₂ method is Nes and the score is consistent with the new measurement.
4. Edit that waist in its supported units and check the computation reflects the validated value, without creating a 36 cm result for a 36 inch input.
5. Delete the waist and refresh: use Uth fallback; the resulting point must carry Uth attribution, even if profile waist remains set.
6. Add a future-dated waist and verify it does not alter an earlier historical weekly calculation.
7. Confirm the food-logging **Nutrition Review → SAVE TO CALORIES INTAKE** flow still works with the independently deployed Worker.

## Known follow-on milestones (not claimed complete)

After this Stage 4/5 foundation passes CI and device verification, implement a separate `LabInterpretationV1` schema and isolated Worker endpoint for structured per-marker AI findings, with explicit source IDs, versioned reference evidence, date validity, unit identity and strict THOOP-only input dispatch. The dispatcher must reject scores, formula weights, unapproved custom aliases, and unsupported direct links. Add independent per-component validation before expanding numerical lab effects. Preserve local source rows and all preexisting calculations.