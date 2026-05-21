# Changelog

All notable changes to this project will be documented in this file.

## [2.0.0]

A full rewrite of the upload engine for spec-compliant, production-grade GCS resumable uploads.
**Breaking:** the public API has changed — see the migration table in the README.

### Added

- **New public API** under `io.fastpix.uploads`:
  - `FastPixUploader` (replaces `FastPixUploadSdk`) with a fluent `Builder` and `start()` / `pause()` / `resume()` / `cancel()` methods.
  - `UploadListener` (replaces `FastPixUploadCallbacks`) with no-op defaults — override only what you need. New callbacks: `onStateChange(UploadState)`, `onPrepared(...)`, `onChunkUploaded(...)`, `onRetryScheduled(...)`, `onCancelled(...)`.
  - `UploadState` enum (`IDLE`, `PREPARING`, `UPLOADING`, `PAUSED`, `RETRYING`, `NETWORK_LOST`, `QUERYING_STATUS`, `COMPLETED`, `FAILED`, `CANCELLED`) for explicit lifecycle modelling.
  - `UploadError` sealed class with typed variants: `InvalidConfiguration`, `FileNotFound`, `FileNotReadable`, `FileEmpty`, `FileReadFailure`, `NetworkFailure`, `SessionExpired` (HTTP 410), `ClientError(code)`, `ServerError(code)`, `RetryLimitExceeded`, `UnexpectedResponse`.
- **GCS spec compliance:**
  - Chunk PUTs with `Content-Range: bytes start-end/total`; non-final chunk size enforced as a multiple of 256 KiB at build time.
  - Server-authoritative resume: parses the `Range` header from `308 Resume Incomplete` and resumes from `last + 1`. Handles 308 without a `Range` header (resume from byte 0) per spec.
  - **Always issues a status query** (`PUT` with `Content-Range: bytes */TOTAL`, empty body) before resuming after pause, network loss, or transient failure — never assumes how much the server has.
  - HTTP 410 → `UploadError.SessionExpired`. Retryable: 408, 429, 5xx. Fatal: other 4xx.
- **Smooth, monotonic progress.** Updates fire as each chunk streams (no longer only at chunk boundaries), throttled to one event per integer-percent change. A CAS-guarded high-water mark ensures progress never regresses on retry.
- **Explicit upload state machine** with validated transitions. Structurally impossible to send a chunk PUT after pause/retry/network-loss without first going through `QUERYING_STATUS`.
- **Single-threaded engine.** All state mutations serialised on one executor; OkHttp callbacks, network events, consumer commands, and retry timers all funnel through it. No race conditions.
- **Multi-transport network awareness.** Tracks the active set of networks with INTERNET capability; only signals offline when *all* transports drop. Transient WiFi/cellular handover events no longer trip false `NETWORK_LOST`.
- **Configurable retry policy.** Full-jitter exponential backoff (`retryBaseDelay`, `retryMaxDelay`), bounded by `maxRetries`.
- **Main-thread callback dispatch by default.** Listener calls land on the main looper; consumers no longer need `runOnUiThread` wrappers. Override via `Builder.callbackExecutor(Executor)`.
- **Optional HTTP logging** via `Builder.debugLogging(true)`. Off by default — release builds no longer leak signed session URIs to logcat.
- **JUnit unit tests** for `GcsResumableProtocol`, `UploadStateMachine`, `RetryPolicy`, and `CallbackDispatcher` (47 tests total).

### Fixed

- **Retries no longer silently disabled after the first successful chunk.** The retry executor is now permanent for the upload's lifetime; previously a `CoroutineScope.cancel()` killed it after chunk 1, causing later failures to hang forever.
- **Network failures are now routed through the retry path.** Previously any `IOException` (broken pipe, DNS hiccup, TLS reset, OkHttp cancel) terminated the upload with `onError`. Now classified, backed off, and resumed via status query.
- **`onSuccess`, `onFailure`, and `onCancelled` now actually fire.** A "defense in depth" terminated-flag silently dropped the terminal callback itself. Removed.
- **`cancel()` no longer double-fires `onError` + `onCancelled`.** OkHttp call cancellation is now correctly distinguished from real failures via a generation counter.
- **`pause()` no longer leaks as `onError`.** Intentional cancellations are detected in `onFailure` and suppressed.
- **`NetworkCallback` no longer leaks across uploads.** Per-uploader registration with explicit `unregisterNetworkCallback` on terminal.
- **Working Wi-Fi no longer falsely reported as `NETWORK_LOST`.** Dropped the `NET_CAPABILITY_VALIDATED` requirement, which is gated on a probe to Google's `connectivitycheck` endpoint and is missing for the first few seconds of a fresh connection (and indefinitely on networks where that endpoint is blocked, e.g. corporate Wi-Fi).
- **Transient losses during WiFi/cellular handover no longer trigger `NETWORK_LOST`.** Network state is now derived from the *set* of matching networks; offline is signalled only when all transports drop.
- **File reads no longer silently misalign on large files.** `RandomAccessFile.seek()` replaces `InputStream.skip()`, which was allowed to skip fewer bytes than requested.
- **OkHttp no longer silently re-PUTs bodies on connection failure.** `retryOnConnectionFailure(false)` lets the upload engine own all retry semantics.
- **Public commands after terminal state no longer crash.** `cancel()` called twice, late OkHttp callbacks, and late connectivity events post-teardown now silently no-op instead of throwing `RejectedExecutionException`.

### Changed

- **Minimum SDK** bumped from API 21 to API 24.
- **Default chunk size** changed from 16 MiB to 8 MiB (the GCS-recommended value).
- **Method renames** (see migration table in README): `setSignedUrl` → `sessionUri`, `setFile` → `file`, `setChunkSize` → `chunkSize`, `setMaxRetries` → `maxRetries`, `setRetryDelay` → `retryBaseDelay` + `retryMaxDelay`, `callback` → `listener`, `startUpload` → `start`, `pauseUploading` → `pause`, `resumeUploading` → `resume`, `abort` → `cancel`.
- **Errors:** `UploadExceptions` and its subclasses replaced with the `UploadError` sealed hierarchy.

### Removed

- `FastPixUploadSdk`, `FastPixUploadCallbacks`, `UploadExceptions`, `StreamingFileRequestBody`, `RetryHelper`, `NetworkHandler`, `UploadEventType`.

## [1.0.1]
- Implemented support for Google Cloud Storage resumable uploads and chunked client uploads.
- Added retry mechanism with exponential backoff for GCS upload failures based on retryable status codes.
- Enabled support for user-provided signed URLs, allowing resumable uploads to work with externally generated session URIs.
- Updated the API endpoint from https://v1.fastpix.io/on-demand/uploads to https://api.fastpix.com/v1/on-demand/upload for obtaining signed URLs.

## [1.0.0]

### Features:

  - **Chunking**: Files are automatically split into chunks (default chunk size is 16MB).
  - **Pause and Resume**: Allows temporarily pausing the upload and resuming after a while.
  - **Retry**:  Uploads might fail due to temporary network failures. Individual chunks are retried for 5 times with exponential backoff to recover automatically from such failures.
  - **Lifecycle Event Listeners**: Provides real-time feedback through various upload lifecycle events.
  - **Error Handling**: Comprehensive error management to notify users of issues during uploads.
  - **Customizability**: Options to customize chunk size and retry attempts.
