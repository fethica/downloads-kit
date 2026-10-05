# downloads-kit

Offline downloads for Android audio apps built on Media3. Your app hands the library a URL, a title and an optional group; the library queues the transfer in a foreground service, keeps the bytes in a cache that never evicts, and reports each item's state as a Kotlin flow. A player reads a downloaded item from disk through the same URL it streams from.

The library handles storage, the download service, the notification and the state. Your app owns the catalog, the navigation, the notification channel and the names of everything stored on the phone.

## Modules

- **`downloads`**: headless, depends on Media3 and kotlinx-coroutines only. One process-wide `MediaDownloads` that owns the download index and the cache, a foreground download service, a Wi-Fi-only switch, per-item `DownloadState` flows, grouping helpers, failure classification with stable analytics keys, and a read-only `DataSource.Factory` for the player.
- **`downloads-compose`**: optional Material 3 UI over `downloads`. `OfflineButton` (one button per item, every state drawn), `OfflineScreen` (a two-level library: groups, then one group's items, with a transfers strip, a Wi-Fi-only switch and confirmed removal), byte formatting, and strings in English, French and Arabic. Every resource starts with `downloads_`.

## Install

No version is published yet. Once one is, the modules come from JitPack:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        maven("https://jitpack.io")
    }
}

// build.gradle.kts
dependencies {
    implementation("com.github.fethica.downloads-kit:downloads:<version>")
    // Optional, brings `downloads` with it:
    implementation("com.github.fethica.downloads-kit:downloads-compose:<version>")
}
```

## Quick start

Install the library once, from `Application.onCreate`, after the notification channel exists. The library never creates a channel: the download notification goes into the one you name.

```kotlin
class App : Application() {
    lateinit var downloads: MediaDownloads

    override fun onCreate() {
        super.onCreate()
        createDownloadNotificationChannel() // yours, id "downloads" by default
        downloads = MediaDownloads.install(
            this,
            DownloadsConfig(
                // Required: the folder under filesDir that holds the downloaded bytes.
                cacheDirName = "downloads",
                notificationSmallIcon = R.drawable.ic_stat_download,
                // The Wi-Fi-only flag: either the built-in SharedPreferences store...
                preferencesName = "downloads",
                // ...or your own store: settings = MyDownloadsSettings(),
                notificationContentIntent = contentIntent,
            ),
        )
    }
}
```

`cacheDirName` is required, and so is one of `preferencesName` or `settings`: a config with neither throws. When both are given, `settings` wins. The other parameters have defaults:

| Parameter | Default | Notes |
| --- | --- | --- |
| `notificationChannelId` | `"downloads"` | A channel your app has already created. |
| `foregroundNotificationId` | `7301` | Change it if it collides with one of yours. `0` throws: Media3 reads it as "no foreground notification". |
| `maxParallelDownloads` | `2` | |
| `schedulerJobId` | `null` | A `JobScheduler` id, used below API 31 only. Null schedules nothing. |
| `userAgent`, `httpDataSourceFactory` | `null` | Pass your own HTTP stack to reuse it. |
| `serviceClass` | `MediaDownloadService` | The service every command is sent to. See below. |

Then, anywhere in the app:

```kotlin
downloads.request(id = "item-1", uri = uri, title = "Title", subtitle = "Subtitle", group = "album-7")
downloads.state("item-1")   // Flow<DownloadState>
downloads.all()             // Flow<List<DownloadEntry>>
downloads.remove("item-1")
downloads.removeGroup("album-7")
downloads.wifiOnly.value = false
```

On the player side, wrap the data source factory you already use. A downloaded item plays from disk, anything else streams as before, and nothing streamed is written to the cache:

```kotlin
val factory = downloads.cacheDataSourceFactory(upstream = myHttpDataSourceFactory)
```

With `downloads-compose`, `OfflineButton(state, onClick)` draws one item's button and `OfflineScreen(...)` the whole offline library. The screen takes its colours, shapes and type from your `MaterialTheme`, and the open group is your state, so your top bar can name it and your back button can close it.

### Your own service class

The library's manifest declares `MediaDownloadService`, so most apps add nothing. An app that needs the service under its own class name (for example to keep an existing component name) subclasses `MediaDownloadService`, declares the subclass with the same `dataSync` type and RESTART filter, removes the library's entry, and passes the subclass in the config:

```xml
<service
    android:name="com.fethica.downloadskit.MediaDownloadService"
    tools:node="remove" />
```

```kotlin
DownloadsConfig(..., serviceClass = MyDownloadService::class.java)
```

Exactly one download service should be declared.

## What a host must know

- **Storage names are yours and must never change silently.** `cacheDirName`, the preferences file and your own catalog name where downloads live on your users' phones. The library gives them no defaults because a wrong default strands every downloaded file without an error.
- **Downloads live in two places.** The bytes and the cache's `.uid` file are under `filesDir/<cacheDirName>` (the `file` backup domain). The download index is Media3's database, `exoplayer_internal.db`, at `Context.getDatabasePath("exoplayer_internal.db")` (the `database` backup domain). The index, the `.uid` file and the bytes are kept or dropped together: a backup rule that excludes the cache directory must also exclude that database, or a restore brings back completed downloads with no bytes behind them. Apply the same rule to your own catalog if it records downloads.
- **The Wi-Fi-only flag store.** With `preferencesName`, the flag lives in that SharedPreferences file under the key `wifi_only`, default `true`, read off the main thread. With `settings`, it lives wherever your `DownloadsSettings` keeps it.
- **No resume on reboot from Android 12.** Media3 uses a scheduler below API 31 only, so on API 31 and later queued downloads do not resume by themselves after a reboot. They resume on the next app launch, which starts the download service.
- **Foreground service type `dataSync`.** The library's manifest declares the service with `foregroundServiceType="dataSync"` and the `FOREGROUND_SERVICE_DATA_SYNC` and `POST_NOTIFICATIONS` permissions. On API 33 and later your app asks for the notification permission at runtime.
- **Cache keys are URLs.** A request carries no custom cache key, so the player finds the downloaded bytes through the same URL. Request an item with the URL it will be played from.
- **Failure keys are stable.** `DownloadFailureReason.key` (`unknown`, `network`, `http`, `storage_full`) is safe to send to analytics.

## Sample app

`sample` (application id `com.fethica.downloadskit.sample`) installs the library with its own storage names, channel, icon and notification id, and downloads three WAV files it generates itself from a loopback HTTP server inside the app. Every fixture request stays on `127.0.0.1`, but the sample keeps the library's download requirements: a download only starts on a connected, validated network, and an unmetered one while Wi-Fi only is on (the default). In airplane mode, or on Wi-Fi without internet access, Start stays queued. Verify on an already completed item is the operation that works fully offline.

| Fixture | Size | SHA-256 |
| --- | --- | --- |
| `tone-small.wav` | 65,536 bytes | `f15c2a9e666ff4032681a9f1120c544c2dfbdc17b42291af0fd99d489c2d1693` |
| `tone-medium.wav` | 262,144 bytes | `785f16552b64ce827a6ed17bcc6681f125ceca763403d17089f98adcb7585335` |
| `tone-large.wav` | 1,048,576 bytes | `6acb03de3aade2b2c14e9cadaf364629cf69ea3c70727eb9e1d185483882e34e` |

Each file is one second of a sine tone followed by silence, generated deterministically (see `FixtureCatalog`). The server supports GET, HEAD and single byte ranges. Debug builds add switches for a first-byte delay, slow chunks, a 404, a 500 and a mid-transfer cut. The Library tab shows the `downloads-compose` screen. The Diagnostics tab verifies each completed item by reading it back through `cacheDataSourceFactory` with an upstream that fails if it is ever opened, then comparing the SHA-256. There is no playback.

Cleartext HTTP to `127.0.0.1` is allowed in the debug build only.

The server reuses the previous launch's port so request URLs, and therefore cache keys, stay the same. When that port is taken, it binds another one and Diagnostics shows the change. Completed items still verify through the URL they were saved with, but unfinished downloads restored from the previous launch keep pointing at the old port: they fail or wait until Retry requests them again on the new one.

## Build and test

You need the Android SDK (`local.properties` with `sdk.dir`, or `ANDROID_HOME`) and JDK 21.

```sh
./gradlew :downloads:testDebugUnitTest :downloads-compose:testDebugUnitTest :sample:testDebugUnitTest
./gradlew :downloads:assembleRelease :downloads-compose:assembleRelease :sample:assembleDebug
```

All tests run on the JVM; the legacy-store compatibility tests use Robolectric. No device is needed.

## Requirements

- Android 7.0+ (API 24); the library is built against compileSdk 36 with minor API level 1 (`release(36) { minorApiLevel = 1 }`), so a source or JitPack build needs the Android 36.1 platform; a consumer compiles against the published AAR with its own compileSdk 36 or later
- Media3 1.9.2
- Compose BOM 2026.01.01 (`downloads-compose` only)

## License

MIT. See [LICENSE](LICENSE).
