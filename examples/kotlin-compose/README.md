# kotlin-compose

The upload flow in Kotlin with Jetpack Compose.

## Running

1. Put your `fastpix.token` and `fastpix.secretKey` in the project-root `local.properties`
   (see [../README.md](../README.md)).
2. Open the project in Android Studio, select the `kotlin-compose` module, and Run.
3. Pick a file, tap Start, then use Pause / Resume / Cancel. Send the app to the background and the
   upload keeps going, with progress in a notification.

## Where things live

- `MainActivity.kt` — the Compose screen; collects `UploadManager.state` and calls into it.
- `UploadManager.kt` — holds the running upload and exposes its state as a `StateFlow`.
- `UploadService.kt` — foreground service that keeps the upload alive in the background.
- `FastPixApi.kt` — the "create upload" API call.
- `Config.kt` — credentials and chunk size.

## Want a fuller Compose app?

This screen is intentionally minimal. For a complete Compose app built around the FastPix upload flow,
take a look at [FastPix/android-StreamGate](https://github.com/FastPix/android-StreamGate).

See [../README.md](../README.md) for how the background upload works.
