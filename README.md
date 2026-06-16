# FastPixUploader SDK (Android)

A production-grade Android library for resumable file uploads to Google Cloud Storage signed session URIs, implementing the full [GCS resumable upload protocol](https://cloud.google.com/storage/docs/performing-resumable-uploads): chunked PUTs with `Content-Range`, server-authoritative resume via `308 Resume Incomplete` + `Range` parsing, automatic status-query (`bytes */TOTAL`) on every resume path, and retry with exponential backoff for transient failures.

## Features

- **Spec-compliant resumable uploads.** Each non-final chunk is a 256 KiB multiple, sent with `Content-Range: bytes start-end/total`. The engine never assumes how much the server has — every resume path issues a status-query PUT first and trusts the server's `Range` header.
- **Explicit upload state machine.** Single enum (`UploadState`) with validated transitions. No boolean soup, no resurrection of terminal sessions.
- **Single-threaded engine.** All state mutations happen on one serial executor. OkHttp callbacks, network-change events, and consumer commands all funnel through it, so there are no races.
- **Smooth, monotonic progress.** Updates fire continuously as each chunk streams (not only at chunk boundaries), throttled to one event per integer-percent change so a 1 GB upload emits ~100 events regardless of chunk size. A `CAS`-guarded high-water mark ensures progress never regresses on retry, even when a chunk fails mid-flight.
- **Resilient cancellation.** Generation-counted in-flight calls absorb late OkHttp callbacks; intentional cancels never leak as `onFailure`.
- **Pause / resume / cancel** with correct lifecycle callbacks.
- **Configurable retry.** Exponential backoff with full jitter, bounded by max delay and max attempts. Distinguishes retryable (408/429/5xx) from fatal (other 4xx, 410 Gone → session expired).
- **Main-thread callbacks by default.** Listener calls land on the main looper unless you supply a different `Executor`.
- **Multi-transport network awareness.** Tracks every connected network with INTERNET capability; treats the device as online so long as at least one transport (WiFi *or* cellular) is up. Transient losses during WiFi/cellular handover don't trip a false `NETWORK_LOST`.

## Prerequisites

- Android 7.0 (API 24) or above
- Kotlin
- A resumable session URI obtained from a `POST .../o?uploadType=resumable&x-goog-resumable=start` (or the equivalent signed URL flow). See FastPix's [Direct Upload API](https://fastpix.com/docs/getting-started/upload-and-play-your-first-video) for credential setup.

## Installation

```kotlin
dependencies {
    implementation("io.fastpix:uploads:2.0.0")
}
```

## Usage

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

Every listener method has a no-op default — you only override what you need.

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

`COMPLETED`, `FAILED`, and `CANCELLED` are terminal — no further callbacks fire.

### `UploadError`

Typed, sealed:
- `InvalidConfiguration`, `FileNotFound`, `FileNotReadable`, `FileEmpty`, `FileReadFailure`
- `NetworkFailure` — transport error, retries exhausted
- `SessionExpired` — HTTP 410, the session URI is dead; mint a new one
- `ClientError(statusCode)` — 4xx other than retryable
- `ServerError(statusCode)` — 5xx after exhausting retries
- `RetryLimitExceeded(attempts)`
- `UnexpectedResponse`

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

- [Homepage](https://www.fastpix.com/)
- [Dashboard](https://dashboard.fastpix.com/)
- [GitHub](https://github.com/FastPix/android-uploads-sdk)
- [API Reference](https://fastpix.com/docs/upload-videos/upload-videos-from-device)
- [GCS resumable upload protocol](https://cloud.google.com/storage/docs/performing-resumable-uploads)
