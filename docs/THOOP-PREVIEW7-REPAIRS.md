# Preview 7: preserve unobserved Health Connect fields

Preview 6 prevented incomplete background reads from replacing saved data. A remaining successful
partial-grant import could still replace a rich `health-connect` daily aggregate with nulls for
columns it was not allowed to read. The manual import path could also persist partial pages after
a selected read failed.

## Corrected behavior

- Completed, selected and granted read types explicitly identify the fields an import observed.
- A Health Connect specific transaction reads the latest saved daily rows and changes only observed
  columns. Unobserved columns and unsupported legacy VO2, heart-rate and weight values are retained.
- Real observed corrections can decrease a value or clear it. Missing permission or a disabled
  category is not treated as evidence that the source deleted a value.
- Basal calories are observed only when both total and active calorie reads completed. A total-only
  grant cannot turn total energy into basal energy by assuming an unknown active value is zero.
- Source and day identities are validated; other sources and dates are not updated. Empty new
  aggregates are skipped, and summary counts/dates describe actual written rows.
- Every failed selected type or page aborts before persistence, for manual and automatic imports.
  This includes targeted workout-distance reads. Successful partial permissions remain supported;
  skipped types are not failures. Incomplete manual best-effort persistence is intentionally removed
  because it could replace complete records with partial totals. The compatibility flag cannot
  bypass the preservation guard.
- Android version code 560 updates Preview 6 in place with the same signing key.

## Scope and compatibility

The generic AppleDaily upsert, Room schema, migrations, backup/restore format, record identifiers,
import categories and physiological formulas remain unchanged. This is an Android Health Connect
provider behavior; there is no Swift Health Connect importer counterpart. Shared storage primitives
and Apple Health import semantics are unchanged. The −2…+4 metabolic contract, Fitness Age blend,
Worker parsing fixes, custom dock and other Preview 6 repairs remain.

The repair prevents future clobbering. It does not reconstruct values an earlier import already
erased; recovery of those values requires their original source or a known-good backup. It does not
broaden permissions or automatically grant background access. Existing fully observed clearing
behavior is retained within the import window.

## Verification

Real Room and Health Connect record regressions cover limited permissions, disabled categories,
valid reductions and clearing, unsupported legacy values, new sparse days, other sources/dates,
failed pagination and failed workout-distance reads. Repository regressions cover source validation
and transactional preservation. Run the complete Full Debug suite without exclusions and Worker
regressions, then verify the signed APK metadata before publishing or installing an update.
