# THOOP Android — Tier 2 history design (not enabled)

This file documents the **THOOP fork's proposed optional online-history feature**. It does **not**
change upstream NOOP's scope in `../AGENTS.md` and `../docs/SCOPE.md`; those documents currently
exclude cloud read-back. A deliberate fork-specific change to that boundary, with explicit user
consent and no NOOP-operated account/server, is required before online history ships.

## Planned calendar-day policy

| Record age as of the phone's local day | Target behaviour |
|---|---|
| Today through 14 days old | Full detail stored locally and readable offline |
| 15 through 21 days old | Compact per-day/per-session local summary; full detail in encrypted user-owned storage |
| 22 days or older | History online only, with explicit download/read-back when requested |

The range is **inclusive**: `today - 14 days` is still full local; `today - 21 days` is still a
local snapshot. Operational data (pairings, definitions, day ownership) and anti-resurrection
tombstones remain on-device and are not treated as historical detail to purge.

This design is not a promise that offline analytics will work for older dates. Any screen,
trend, benchmark, source resolver or score that needs old records must first be adapted to fetch
verified historical data, or explain clearly that the network is required. Sources must remain
separate: WHOOP, Zepp/Amazfit, Garmin, other devices, manual logs and imports must not be merged
solely because their dates match.

## Table inventory

`app/src/main/java/com/noop/data/RetentionPlan.kt` declares every current main-health Room table
(34) and the separate `foodIntake` table (1). The JVM test compares this registry against
`@Entity(tableName = ...)` declarations and rejects tables added without a policy. Lifting sets
must use their parent `liftSession` date; tombstones and reusable definitions remain local.

This is only **classification**. No existing DAO uses this registry, and it performs no archive,
upload, download, database migration, read-limit change or deletion.

## Gates before any automatic offloading

1. Explicitly accept and document the optional cloud read-back boundary for this fork; keep it
   user-configurable and off by default, with no change to BLE sync or existing background jobs.
2. Use authenticated encryption for archive contents and a portable recovery mechanism; a SHA-256
   checksum alone is not encryption. The current `.nooparc`/`.noopbak` ZIPs do not provide this.
3. Include both `noop_whoop.db` **and** `thoop_food_intake.db` in verified backup/restore plans.
4. Design compact snapshots separately for HR/HRV, steps, sleep, workouts, nutrition, journals,
   lab values, mood/cycle metrics, and live/strength sessions, retaining source and quality info.
5. Implement encrypted upload, verified download, read-only history and full restoration tests
   BEFORE allowing any cleanup. Existing monthly archive handling cannot by itself support
   rolling 15/22-day transitions or partial months safely.
6. Run a **dry-run report** of exactly which rows would move and which archives prove they are safe;
   refuse cleanup on missing/unknown tables, failed checks, missing network or restore errors.
7. Enable deletion only after an explicit, separately reviewed change. No auto deletion is included
   in the present patch.

**Background processes remain unchanged.** Neither the BLE foreground service nor any worker,
widget, backup scheduler or automatic sync process is modified by this plan.
