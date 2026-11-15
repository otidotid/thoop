# Preview 5 regression repairs

Based on `35f14377c50bade7fe0caa7a5e1aa49f4bf0aefb`. This repair preserves the custom dock,
20B food-estimation path, separate AI rate buckets, compact Drive interface and signed −2…+4
metabolic input introduced in Preview 5.

## Corrected behavior

- Android version code 559 updates both previous codes 557 and 558; version name is 13.2.0.
- A shared Responses parser handles completed assistant output for chat, nutrition, lab interpretation
  and metabolic estimates. It retains legacy result compatibility, rejects partial/failed/refused/tool
  output and does not expose reasoning. The semantic food classifier uses this same parser.
- Drive authentication, transient rate limits, storage quota and other authorization failures have
  separate outcomes. Rate-limited backups retain their schedule and credentials and retry with backoff.
  Real authentication rejection still offers reconnection. Original local/remote backups are unchanged.
- Background Health Connect import checks provider feature support, selected record grants and the
  separate background-read grant before accessing records. The UI requests that access only when the
  user enables automatic import. Unsupported providers retain manual import. Failed/partial reads
  produce retry/failure status instead of a false successful import.
- The dock editor scrolls within a bounded viewport while Reset and Done remain reachable. Explicitly
  removing all optional items remains saved; missing/invalid settings still use Preview 5 defaults.
- Navigation tests exercise the actual customizable navigation contract. Metabolic tests cover −2,
  negative fractions, zero, +4, invalid boundaries, durable round-trip and the unchanged 80/20 blend.

## Compatibility

Health Connect is pinned to 1.1.0-alpha11: the compatible SDK that provides feature-checked background
access for both platform and APK providers without changing the repository's Android Gradle plugin,
Gradle, Kotlin or compile SDK. Its dependency lock changes accompany the SDK declaration. These APIs
are opted into only at the background-feature check. No additional record categories are requested.

No Room schema or migration, original Lab Book writer, physiological coefficient, stored raw sample,
or restore replacement rule is changed. Android-only Health Connect and dock behavior has no Swift
runtime counterpart; the shared marketing version is aligned for repository versioning.

## Verification

Run the entire Full Debug JVM suite and `node --test` in `cloud-coach`; no tests are excluded. New
regressions cover real intercepted Drive responses, persisted scheduler preferences, denied background
access, failed/empty/partial import results, coroutine cancellation, real dock preference reloads,
canonical Responses output, food shorthand, and signed metabolic boundaries.

CI retains complete test reports even if a check fails. Tagged-source automation retains an existing
owner-published release and its matching signing certificate. A newly generated generic CI debug
artifact is a draft, so it cannot silently replace an update built with the owner's private key.

The shipped update must use the same package and signing certificate as the installed debug app.
Do not uninstall or clear storage to resolve a version/signature mismatch. Runtime Health Connect
permission prompts and live Google/Cloudflare behavior still require device/account checks.
