# ALKE — Product Vision, Visual Redesign & Independence RFC (Draft 0.1)

**Status:** Proposal only; no app/source migration or data mutation authorized by this document.  
**Requested reference release:** [v13.1.0](https://github.com/otidotid/thoop/releases/tag/v13.1.0), published 13 November 2026.  
**Target:** Android first; cross-platform modules assessed subsequently.  
**Product name:** **ALKE**

## North star

**Clarity up front. Depth when you want it.** ALKE is a premium, local-first health/performance app. The landing experience should be beautifully simple, with complete data, history, provenance, analysis, import, export, backup, and restoration one or two layers deeper. No metric, record, or feature is removed for aesthetic reasons.

### Design direction (concept, not implemented)

- **Identity:** newly created ALKE geometric monogram, independent colors and typography; midnight/navy background, signal mint, muted warm highlights.
- **Typography:** Inter as preferred brand/UI family (license review before bundling); accessible native fallback for Android.
- **Information architecture:** `Today` (quiet daily overview) → `Insights` (trends/compare/filter) → `Data Vault` (complete history, imports, exports, encrypted backup, archives).
- **Data access:** all cards have a drill-in path; show metric definition, source, timestamp, raw/history availability where meaningful.
- **Animations:** short, useful transitions; reduced-motion and contrast options.
- **Visual concept reference:** local ChatGPT deliverables include a 3-screen PNG board, logo exports, a six-page PDF, and a nine-second MP4. UI numbers are illustrative, not real personal data.

## Baseline facts and migration hazards observed

- The v13.1.0 release describes a working **passphrase-encrypted manual Google Drive full-backup-and-restore flow tested between two physical Android phones**, a main health database, separate food DB, and monthly archives. This release report says categories should still be individually verified after restoration.
- `android/app/build.gradle.kts` at v13.1.0 defines `applicationId = "com.otidotid.thoop"` and Kotlin `namespace = "com.noop"`. It also has `THOOP_CLOUD_COACH_URL` and `THOOP_GOOGLE_WEB_CLIENT_ID` build-time inputs.
- `android/THOOP_RECOVERY_V2.md` documents `THOOPENC-v1`, `.noopbak` compatibility, Google OAuth/Drive app identity risks, and data categories not covered by the backup, including some media, diagnostic files, secrets, pairing and non-whitelisted preferences.
- `android/THOOP_NO_DELETE_HISTORY.md` demands **never delete source health, food, training, journal, or sensor records as a result of the Tier 2 retention controls**. Preserve this principle through the redesign.
- Repository `LICENSE` identifies the upstream original code as **PolyForm Noncommercial 1.0.0** and includes `Required Notice: Copyright 2026 NoopApp`. The notice, distribution obligations, and relevant `NOTICE` and `ATTRIBUTION.md` content must be maintained for inherited code.
- **Repository topology risk:** GitHub's compare endpoint returned "No common ancestor between v13.1.0 and main". Verify the release tag lineage and reconcile a trustworthy build baseline before attempting to merge release-derived source code to `main`. This RFC branch starts from `main` and contains documents only.

## Meaning of "independent"

1. **Brand independence:** ALKE is the only visible consumer-facing product name. Replace old names in launcher, onboarding, dashboard, settings, notifications, widgets, help, share/export labels, screenshots, badges, app listing, release notes, support channels, update checks and user-facing strings.
2. **Distribution independence:** ALKE-owned repository/release process, versioning, metadata, signing controls, changelog, documentation, secrets handling, and update endpoints.
3. **Technical independence:** map all external code/dependency relationships and replace inherited subsystems with original, tested implementations where ownership demands it. Separate BLE protocols, device drivers, analytics, data/migrations, UX and cloud integrations behind stable contracts.
4. **Legal independence:** assess license obligations before commercial plans. A UI rename or repository move does **not** remove PolyForm Noncommercial restrictions on inherited code. Source-license notices and mandatory attribution remain in a clearly labeled **Licenses & Credits** section or legal files until underlying code has been legitimately replaced or separately licensed. Seek qualified counsel before any commercial release.

**Do not blindly delete** `noop` / `thoop` strings: some are source identifiers, protocol/API contracts, backup format names, OAuth settings, database names and legally required notices. Classify each string as (a) visible brand to change; (b) compatibility identifier to preserve or migrate; or (c) legal attribution to preserve.

## Identity and data-migration decision

### Recommended safer rollout

- **Step A:** Rebrand in place. Keep the existing Android application ID, release signing identity and restore compatibility initially while user-facing branding becomes ALKE. This supports in-place app updates without forfeiting the current sandbox data.
- **Step B:** Introduce an *explicit, tested bridge* for a future independently named application ID (tentative name only). Android cannot treat a different package/application ID as an ordinary in-place upgrade; both apps have separate sandboxes. Provide verified encrypted export/import between IDs. Ensure migration can recover if interrupted.
- **Step C:** Reconfigure Google OAuth and Drive scope/folder access intentionally. Test discovery of existing backups and restore on a clean phone before switching OAuth clients, application IDs, signing keys, or cloud naming.
- **Step D:** If introducing ALKE-native archive/backup file extensions or headers, retain a dual-reader for old `.noopbak`, `.nooparc`, `THOOPENC-v1` and other shipped formats. Prefer maintaining the existing binary storage contract during the UI stage.

### Core invariants

- No existing user database records deleted or rewritten just to support branding.
- No metric calculation, origin/provenance, or analytics definition changes during the UI-only phase.
- No silent downgrade of encrypted recovery or archive verification.
- Maintain compatibility with full/legacy backups, food history, monthly archives, data-source imports and Health Connect.
- Maintain BLE background service, live data, GPS sessions, sensor access, sleep/workout history and feature parity.
- Tests include installation **over v13.1.0**, full record count and sample-value checks, old backup read, offline mode, and two physical-device restore with a fresh install. Validate media/settings limitations honestly.

## Workstreams and release gates

| Phase | Output | Blocking acceptance gate |
| --- | --- | --- |
| 0 — Inventory | Map product strings, package names, services, storage, routes, integration contracts, licenses | No unresolved storage/OAuth identity changes |
| 1 — New visual identity | Original ALKE logo/app icon, design tokens, Today, Insights, Data Vault mockups and prototypes | Navigation to every existing metric and feature remains possible |
| 2 — Product UI | Replace user-facing legacy branding, rationalize screens, accessibility and interactions | Feature parity and screenshot review |
| 3 — Migration engineering | Package/keystore strategy, legacy readers, OAuth and Drive restore path | In-place update + migration + restore verification on physical Android devices |
| 4 — Independence | ALKE release tooling, modular ownership, original implementation replacements and legal review | No accidental license violations, no upstream service reliance except documented dependencies |
| 5 — Beta and launch | Staged release, diagnostics, rollback and disaster recovery plan | All critical data integrity tests pass |

## Next engineering spike (without touching production)

1. Confirm the exact release tag SHA and build inputs, and explain its separation from main's commit history.
2. Collect branding references in Android `ui/`, `res/`, manifests, Gradle, notification channels, build properties and backups.
3. Draft a source/target ID matrix: display name, applicationId, namespace, package names, backup extensions/magic, Drive folder, OAuth client, signing cert, deep links, update endpoints, settings keys, DB file names, widgets, integrations.
4. Build a baseline test fixture with row counts and representative records **without including private user data in GitHub**.
5. Create a small Android-only UI proof of concept that reads existing repositories without changing schema, calculations, backup bytes or external integrations.

### Out of scope for this RFC

No automatic data deletion, no renaming of installed package/application ID yet, no database migration, no modification of the public release, no alteration of licenses or notices, and no claim that the concept visuals are a working APK.

**Design intent:** remove THOOP and NOOP as visible brands while preserving legally required provenance and necessary backward compatibility. The ultimate goal is real product/implementation ownership, not misleading erasure of development history.
