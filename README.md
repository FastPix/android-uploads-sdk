# FastPix Android Uploads SDK - resumable, chunked video uploads for Android

[![Platform: Android](https://img.shields.io/badge/Platform-Android%2024%2B-3DDC84?logo=android&logoColor=white)](https://developer.android.com/)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.x-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Package: io.fastpix:uploads](https://img.shields.io/badge/GitHub%20Packages-io.fastpix%3Auploads-5D09C7?logo=github)](https://github.com/FastPix/android-uploads-sdk/packages)
[![License: Apache-2.0](https://img.shields.io/github/license/FastPix/android-uploads-sdk)](https://github.com/FastPix/android-uploads-sdk/blob/main/LICENSE)

**Official FastPix Android Uploads SDK** - a production-grade Kotlin library for **resumable, chunked video (and file) uploads** from Android apps to FastPix. It implements the full [Google Cloud Storage resumable upload protocol](https://cloud.google.com/storage/docs/performing-resumable-uploads): 256 KiB-aligned chunked `PUT`s with `Content-Range`, server-authoritative resume via `308 Resume Incomplete`, automatic status queries on every resume, and retry with exponential backoff, so large uploads survive dropped connections and WiFi/cellular handovers.

Add reliable **direct-to-cloud video upload** to your Android app with progress, pause/resume/cancel, and network-aware recovery. Ideal for **UGC and creator apps, camera-capture and screen-recording tools, e-learning and course uploads, and any Android app that uploads video to FastPix**.

**Works with:** Android 7.0 (API 24)+ · Kotlin · OkHttp · package `io.fastpix:uploads` (2.0.0)

📖 **Android upload guide:** https://fastpix.com/docs/upload-videos/set-up-resumable-uploads-for-android &nbsp;·&nbsp; 🚀 **Free account:** https://dashboard.fastpix.com

---

## Start here

If you are adding the FastPix Android Uploads SDK for the first time, follow these steps in order:

1. [Add the GitHub Packages repository](#1-add-the-github-packages-repository)
2. [Add the SDK dependency](#2-add-the-sdk-dependency)
3. [Get a signed upload URL](#3-get-a-signed-upload-url)
4. [Upload a file](#4-upload-a-file)
5. [Handle progress and lifecycle events](#5-handle-progress-and-lifecycle-events)
6. [Verify your upload](#6-verify-your-upload)
7. [Understand the upload workflow](#7-understand-the-upload-workflow)

Do not skip step 1 - the dependency will not resolve without the GitHub Packages repository and a token.

## Features

- **Spec-compliant resumable uploads.** Each non-final chunk is a 256 KiB multiple, sent with `Content-Range: bytes start-end/total`. The engine never assumes how much the server has - every resume path issues a status-query PUT first and trusts the server's `Range` header.
- **Explicit upload state machine.** Single enum (`UploadState`) with validated transitions. No boolean soup, no resurrection of terminal sessions.
- **Single-threaded engine.** All state mutations happen on one serial executor, so OkHttp callbacks, network-change events, and consumer commands never race.
- **Smooth, monotonic progress.** Updates fire continuously as each chunk streams, throttled to one event per integer-percent change, and never regress on retry.
- **Resilient cancellation.** Generation-counted in-flight calls absorb late OkHttp callbacks; intentional cancels never leak as `onFailure`.
- **Pause / resume / cancel** with correct lifecycle callbacks.
- **Configurable retry.** Exponential backoff with full jitter, bounded by max delay and max attempts. Distinguishes retryable (408/429/5xx) from fatal (other 4xx, 410 Gone → session expired).
- **Main-thread callbacks by default.** Listener calls land on the main looper unless you supply a different `Executor`.
- **Multi-transport network awareness.** Treats the device as online while at least one transport (WiFi or cellular) is up, so transient handovers don't trip a false `NETWORK_LOST`.

## Prerequisites

- **Android 7.0 (API 24) or above**, Kotlin.
- **A FastPix account** - sign up on the [FastPix dashboard](https://dashboard.fastpix.com) and generate an **Access Token ID** and **Secret Key** ([guide](https://fastpix.com/docs/getting-started/activate-your-account)).
- **A GitHub account and a Personal Access Token (PAT)** with the `read:packages` scope - the SDK is distributed through GitHub Packages (see [step 1](#1-add-the-github-packages-repository)).
- **A signed upload URL** for each upload, created from the FastPix Direct Upload API (see [step 3](#3-get-a-signed-upload-url)).

---

## 1. Add the GitHub Packages repository

The SDK is published to **GitHub Packages**, which requires authentication even for public packages. Add the repository to your project's `settings.gradle.kts` and supply your GitHub username and a PAT with `read:packages`:

```kotlin
// settings.gradle.kts
import java.util.Properties

val localProperties = Properties()
File(rootProject.projectDir, "local.properties").takeIf { it.exists() }
    ?.inputStream()?.use { localProperties.load(it) }

val githubUser: String? = System.getenv("GITHUB_USER") ?: localProperties.getProperty("github.username")
val githubToken: String? = System.getenv("GITHUB_TOKEN") ?: localProperties.getProperty("github.token")

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://maven.pkg.github.com/FastPix/android-uploads-sdk")
            credentials {
                username = githubUser
                password = githubToken
            }
        }
    }
}
```

Put your credentials in `local.properties` (git-ignored, so they are never committed):

```properties
github.username=YOUR_GITHUB_USERNAME
github.token=YOUR_GITHUB_PERSONAL_ACCESS_TOKEN
```

## 2. Add the SDK dependency

Add the dependency to your app module's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("io.fastpix:uploads:2.0.0")
}
```

Sync Gradle. It resolves `io.fastpix:uploads` from the GitHub Packages repository you added in step 1.

## 3. Get a signed upload URL

The SDK uploads to a **signed resumable session URL** that you create with the FastPix Direct Upload API. Make a `POST` request to `https://api.fastpix.com/v1/on-demand/upload` using **Basic Auth** (your Access Token ID as the username, Secret Key as the password); the response's `data.url` is the session URL you pass to the uploader as `sessionUri`.

> **Create the upload on your server, not in the app.** Your FastPix Secret Key must never ship inside an Android app. Generate the signed URL from your backend and return only `data.url` to the client. See [Upload a video from a device](https://fastpix.com/docs/upload-videos/upload-videos-from-device) and [Set up resumable uploads for Android](https://fastpix.com/docs/upload-videos/set-up-resumable-uploads-for-android). For Android, do not send the `X-Client-Type: web-browser` header (that is web-only).

## 4. Upload a file

Build a `FastPixUploader` with the local file and the signed session URI, then call `start()`:

```kotlin
val uploader = FastPixUploader.Builder(context)
    .file(localFile)
    .sessionUri(gcsResumableSessionUri)
    .chunkSize(8L * 1024 * 1024)      // 8 MiB; must be a multiple of 256 KiB
    .maxRetries(5)
    .retryBaseDelay(2_000L)
    .retryMaxDelay(30_000L)
    .listener(object : UploadListener {
        override fun onStateChange(state: UploadState) { /* drive UI */ }
        override fun onProgress(bytesUploaded: Long, totalBytes: Long, percentage: Double) { /* … */ }
        override fun onPrepared(totalChunks: Int, totalBytes: Long, chunkSize: Long) { /* … */ }
        override fun onChunkUploaded(chunkIndex: Int, totalChunks: Int, bytesAcked: Long) { /* … */ }
        override fun onRetryScheduled(attempt: Int, delayMillis: Long, cause: UploadError) { /* … */ }
        override fun onNetworkStateChange(online: Boolean) { /* … */ }
        override fun onSuccess(elapsedMillis: Long) { /* done */ }
        override fun onFailure(error: UploadError, elapsedMillis: Long) { /* terminal */ }
        override fun onCancelled(elapsedMillis: Long) { /* terminal */ }
    })
    .build()

uploader.start()
uploader.pause()
uploader.resume()
uploader.cancel()
```

Every listener method has a no-op default - you only override what you need.

## 5. Handle progress and lifecycle events

Drive your UI from the `UploadListener` callbacks in step 4:

- `onProgress(bytesUploaded, totalBytes, percentage)` - confirmed-byte progress; safe to bind to a progress bar (it never regresses on retry).
- `onStateChange(state)` - reflect the current [`UploadState`](#uploadstate) (uploading, paused, retrying, and so on).
- `onSuccess(elapsedMillis)` / `onFailure(error, elapsedMillis)` / `onCancelled(elapsedMillis)` - the three terminal outcomes.
- `onRetryScheduled(...)` and `onNetworkStateChange(online)` - surface retry and connectivity status.

Control the upload at runtime with `uploader.pause()`, `uploader.resume()`, and `uploader.cancel()`. On a failure, inspect the typed [`UploadError`](#uploaderror) to decide whether to retry or to mint a fresh session URL (for `SessionExpired`).

## 6. Verify your upload

Your upload succeeded when:

- `onSuccess(elapsedMillis)` fires (the state reaches `COMPLETED`).
- The media appears in your [FastPix dashboard](https://dashboard.fastpix.com) and reaches the `Ready` status once processing finishes - then it has a playback ID you can stream.

If it fails, check the typed `UploadError`: `SessionExpired` (HTTP 410) means the session URL is dead - create a new one and retry; `NetworkFailure` means retries were exhausted; `ClientError`/`ServerError` carry the HTTP status.

## 7. Understand the upload workflow

You create a signed upload on your server, hand the URL to the app, and the SDK streams the file to FastPix in resumable chunks - after which the media is ready to stream.

![How a resumable upload reaches FastPix: your server creates an upload and gets a signed URL, your app receives it as the sessionUri, FastPixUploader uploads the file in resumable 256 KiB-aligned chunks, FastPix ingests and encodes it, and the media becomes Ready in your dashboard.](upload-workflow.png)

---

## Full working examples

The [`examples/`](https://github.com/FastPix/android-uploads-sdk/tree/main/examples) directory has runnable apps that wire the SDK end to end, including fetching a signed upload URL:

- [`kotlin-compose`](https://github.com/FastPix/android-uploads-sdk/tree/main/examples/kotlin-compose) - Jetpack Compose.
- [`kotlin-xml`](https://github.com/FastPix/android-uploads-sdk/tree/main/examples/kotlin-xml) - Kotlin with XML views.
- [`java-xml`](https://github.com/FastPix/android-uploads-sdk/tree/main/examples/java-xml) - Java with XML views.

For a full capture-and-upload demo app, see [android-StreamGate](https://github.com/FastPix/android-StreamGate).

## Public API

### `FastPixUploader.Builder`

| Method | Required | Default | Notes |
| --- | --- | --- | --- |
| `file(File)` | yes | — | Must exist, be readable, non-empty. |
| `sessionUri(String)` | yes | — | GCS resumable session URI (not a one-shot signed URL). |
| `chunkSize(Long)` | no | 8 MiB | Bytes. Must be a multiple of 256 KiB, in [5 MiB, 500 MiB]. |
| `maxRetries(Int)` | no | 5 | Per-failure retry budget. |
| `retryBaseDelay(Long)` | no | 2000 ms | Initial backoff. |
| `retryMaxDelay(Long)` | no | 30000 ms | Cap on a single delay regardless of attempt. |
| `listener(UploadListener)` | no | none | Receives lifecycle events. |
| `callbackExecutor(Executor)` | no | main looper | Where listener callbacks run. |
| `debugLogging(Boolean)` | no | false | Installs `HttpLoggingInterceptor` at BASIC level. |

### `UploadState`

`IDLE → PREPARING → UPLOADING ↔ {PAUSED, RETRYING, NETWORK_LOST, QUERYING_STATUS} → COMPLETED | FAILED | CANCELLED`

`COMPLETED`, `FAILED`, and `CANCELLED` are terminal - no further callbacks fire.

### `UploadError`

Typed, sealed:
- `InvalidConfiguration`, `FileNotFound`, `FileNotReadable`, `FileEmpty`, `FileReadFailure`
- `NetworkFailure` - transport error, retries exhausted
- `SessionExpired` - HTTP 410, the session URI is dead; mint a new one
- `ClientError(statusCode)` - 4xx other than retryable
- `ServerError(statusCode)` - 5xx after exhausting retries
- `RetryLimitExceeded(attempts)`
- `UnexpectedResponse`

## FAQ

**What is the FastPix Android Uploads SDK?**
An official, open-source Kotlin library that uploads video and other files from Android apps to FastPix with resumable, chunked, direct-to-cloud uploads (progress, pause/resume/cancel, retries, network recovery). See [Features](#features).

**How do I install it, and why do I need a GitHub token?**
Add the GitHub Packages repository with a PAT (`read:packages`), then add `implementation("io.fastpix:uploads:2.0.0")`. GitHub Packages requires authentication even for public packages. See [step 1](#1-add-the-github-packages-repository) and [step 2](#2-add-the-sdk-dependency).

**Where do I get the `sessionUri` / signed upload URL?**
Create it with the FastPix Direct Upload API (`POST /v1/on-demand/upload`, Basic Auth) and use `data.url`. Do this on your server so your Secret Key never ships in the app. See [step 3](#3-get-a-signed-upload-url).

**What file and chunk sizes are supported?**
Large files are supported; the SDK streams them in chunks. `chunkSize` defaults to 8 MiB and must be a multiple of 256 KiB, within 5 MiB to 500 MiB. See [Public API](#fastpixuploaderbuilder).

**Does the upload resume after a network drop?**
Yes. On connectivity loss the engine pauses; on recovery it issues a status query and resumes from the last byte the server acknowledged, and it retries transient failures with exponential backoff.

**How do I pause, resume, or cancel an upload?**
Call `uploader.pause()`, `uploader.resume()`, or `uploader.cancel()`. See [step 5](#5-handle-progress-and-lifecycle-events).

**What is the minimum Android version?**
Android 7.0 (API 24) or above.

**I'm on the 1.x API - how do I upgrade?**
The `FastPixUploadSdk` API was replaced by `FastPixUploader`. See [Migrating from 1.x](#migrating-from-1x).

## Related FastPix repositories

| What you want | Repository |
|---|---|
| Play FastPix video on Android | [fastpix-android-player](https://github.com/FastPix/fastpix-android-player) |
| Collect Android playback analytics (QoE) | [android-core-data-sdk](https://github.com/FastPix/android-core-data-sdk) |
| A full capture-and-upload demo app | [android-StreamGate](https://github.com/FastPix/android-StreamGate) |
| Resumable uploads on other platforms | [web-uploads-sdk](https://github.com/FastPix/web-uploads-sdk) · [iOS-Uploads](https://github.com/FastPix/iOS-Uploads) · [flutter-uploads](https://github.com/FastPix/flutter-uploads) · [react-native-uploader](https://github.com/FastPix/react-native-uploader) |
| Create the upload from a backend | [node-sdk](https://github.com/FastPix/node-sdk) · [fastpix-python](https://github.com/FastPix/fastpix-python) |

Browse everything in the [FastPix organization](https://github.com/orgs/FastPix/repositories).

## Migrating from 1.x

The 1.x `FastPixUploadSdk` / `FastPixUploadCallbacks` / `UploadExceptions` API has been replaced.

| 1.x | 2.x |
| --- | --- |
| `FastPixUploadSdk.Builder` | `FastPixUploader.Builder` |
| `.setSignedUrl(url)` | `.sessionUri(uri)` (renamed — it's a session URI, not a signed URL) |
| `.setFile(file)` | `.file(file)` |
| `.setChunkSize(bytes)` | `.chunkSize(bytes)` (now enforces 256 KiB multiple) |
| `.setMaxRetries(n)` | `.maxRetries(n)` |
| `.setRetryDelay(ms)` | `.retryBaseDelay(ms)` + `.retryMaxDelay(ms)` |
| `.callback(cb)` | `.listener(cb)` |
| `.build().startUpload()` | `.build().start()` |
| `pauseUploading()` / `resumeUploading()` / `abort()` | `pause()` / `resume()` / `cancel()` |
| `FastPixUploadCallbacks` interface | `UploadListener` interface, no-op defaults, typed errors |
| `UploadExceptions` subclasses | `UploadError` sealed hierarchy |

## References

- [Set up resumable uploads for Android](https://fastpix.com/docs/upload-videos/set-up-resumable-uploads-for-android) - the official Android upload guide.
- [Upload a video from a device](https://fastpix.com/docs/upload-videos/upload-videos-from-device) - how to create a signed upload URL.
- [Activate your account](https://fastpix.com/docs/getting-started/activate-your-account) - get your Access Token ID and Secret Key.
- [FastPix homepage](https://www.fastpix.com/) · [Dashboard](https://dashboard.fastpix.com/)
- [GCS resumable upload protocol](https://cloud.google.com/storage/docs/performing-resumable-uploads)

## License

This project is licensed under the **Apache License 2.0** - see the [LICENSE](https://github.com/FastPix/android-uploads-sdk/blob/main/LICENSE) file for details.

## Support

For issues or questions, open a [GitHub issue](https://github.com/FastPix/android-uploads-sdk/issues), review the [FastPix documentation](https://fastpix.com/docs), or contact support at [support@fastpix.com](mailto:support@fastpix.com).
