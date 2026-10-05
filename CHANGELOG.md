# Changelog

## Unreleased

- Added `MediaDownloads`, the process-wide owner of the Media3 download index and a never-evicting cache, installed once from `Application.onCreate` with a `DownloadsConfig`.
- Added `DownloadsConfig` with required storage names (`cacheDirName`, and `preferencesName` or a host `DownloadsSettings` for the Wi-Fi-only flag), a host-created notification channel, a configurable foreground notification id that rejects `0`, an optional below-API-31 scheduler job id, and a selectable `serviceClass` for hosts that declare their own `MediaDownloadService` subclass.
- Added `MediaDownloadService`, a `dataSync` foreground download service declared by the library's manifest, with a background-start fallback to a foreground start.
- Added per-item `DownloadState` flows, the full `DownloadEntry` list, storage used, a persisted Wi-Fi-only switch, and listener callbacks for start, completion, failure, removal and clear-all.
- Added grouping helpers (`groupDownloads`, `idsInGroup`, `activeTransfers`, `UNGROUPED_KEY`, with a blank group filed as no group), byte formatting, and `DownloadFailureReason` with stable analytics keys.
- Added `cacheDataSourceFactory`, a read-only `DataSource.Factory` that plays downloaded items from disk through the same URL and opens the cache lazily on the player's loading thread.
- Added the optional `downloads-compose` module: `OfflineButton`, the two-level `OfflineScreen` with a transfers strip and confirmed removal, `GroupLink` for a host's "open this group" action, and strings in English, French and Arabic.
- Added a sample app that downloads self-generated WAV fixtures from a loopback HTTP server, with failure switches in debug builds and a SHA-256 check that reads each item back from the cache.
