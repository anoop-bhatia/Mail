# Modernization status

## Ongoing modernization rule

- Keep `minSdk` at **28**; do not raise it just to remove a warning.
- When a deprecated API has a replacement introduced at a higher Android API level, use the replacement on that level and above, and retain the deprecated API only as the compatibility fallback for API 28 up to the replacement's introduction level.
- If no replacement exists through API 37, record the call site and why no replacement is available; leave the behavior and API unchanged.
- Never suppress a deprecation warning or hide it with a lint baseline or warning flag. Keep javac and lint deprecation diagnostics enabled so warnings remain visible during compilation.

## Retained deprecation warnings — 13 per variant

These are the remaining app-owned production warnings. Each stays because it is needed for compatibility, preserves existing behavior, or has no suitable replacement.

| Site | Warnings | Why it remains |
| --- | ---: | --- |
| `ActivityBase:520`, `Log:255`, `Log:346`, `ServiceBase:57` — `BaseBundle.get` | 4 | Diagnostics inspect heterogeneous extras; typed getters cannot preserve values of unknown types. |
| `ActivityBase:1151` — `onBackPressed` override | 1 | Keeps the `Throwable` catch/log boundary around dispatcher dispatch; no callback wraps that full boundary. |
| `DB` — `endTransaction` override | 1 | Owns the "no current transaction" handling and invalidation refresh; Room's `runInTransaction` dispatches through it. |
| `FragmentCompose:5784` — `CompressFormat.WEBP` | 1 | Compatibility fallback for API 28–29; the newer WebP enum values require API 30+. |
| `Helper:2109`, `Helper:2122` — background-activity start mode | 2 | API 34–35 require `ALLOWED`; API 36+ uses `ALLOW_ALWAYS`. |
| `NotificationHelper:659` — `FULL_WAKE_LOCK`, `ACQUIRE_CAUSES_WAKEUP` | 2 | No background equivalent turns on the screen; the optional feature is retained. |
| `ServiceTTS:241` — `onError(String)` | 1 | Still abstract at SDK 37, so the override is required. |
| `ServiceTileClear:28` — `startActivityAndCollapse(Intent)` | 1 | Compatibility fallback for API 28–33; the `PendingIntent` overload is API 34+. |

## Remaining validation gaps

- There is no `androidTest` source set or `MigrationTestHelper`, so migrations have not been validated against real SQLite. Exported schemas start at version 250, leaving older migrations without schemas to diff against.
- `Migration(998, 999)` is unreachable at the current database version 310. The `onDestructiveMigration` callback is registered, but no `fallbackToDestructiveMigration*` option is enabled, so that override cannot run.
- Repository checks do not validate external OAuth registration, signing-certificate ownership, or live-account behavior.
