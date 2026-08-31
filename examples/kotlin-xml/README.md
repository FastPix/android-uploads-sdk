# kotlin-xml

The upload flow in Kotlin with plain XML views.

## Running

1. Put your `fastpix.token` and `fastpix.secretKey` in the project-root `local.properties`
   (see [../README.md](../README.md)).
2. Open the project in Android Studio, select the `kotlin-xml` module, and Run.
3. Pick a file, tap Start, then use Pause / Resume / Abort. Send the app to the background and the
   upload keeps going, with progress in a notification.

## Where things live

- `MainActivity.kt` — the screen; gets a signed URL, hands off to `UploadManager`, and observes its state.
- `UploadManager.kt` — holds the running upload and exposes its state as a `StateFlow`.
- `UploadService.kt` — foreground service that keeps the upload alive in the background.
- `OkHttpHelper.kt` — the "create upload" API call.
- `Config.kt` — credentials and chunk size.

See [../README.md](../README.md) for how the background upload works.
