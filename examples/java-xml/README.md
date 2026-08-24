# java-xml

The upload flow in Java with XML views. The app is pure Java (no Kotlin plugin) and talks to the
Kotlin `:uploader` SDK directly, so it's a handy reference if your codebase is Java.

## Running

1. Put your `fastpix.token` and `fastpix.secretKey` in the project-root `local.properties`
   (see [../README.md](../README.md)).
2. Open the project in Android Studio, select the `java-xml` module, and Run.
3. Pick a file, tap Start, then use Pause / Resume / Abort. Send the app to the background and the
   upload keeps going, with progress in a notification.

## Where things live

- `MainActivity.java` — the screen; observes `UploadManager` between `onStart` and `onStop`.
- `UploadManager.java` — holds the running upload and notifies observers.
- `UploadService.java` — foreground service that keeps the upload alive in the background.
- `FastPixApi.java` — the "create upload" API call.
- `Config.java` — credentials and chunk size.

One Java quirk worth knowing: the SDK's `UploadListener` is a Kotlin interface, so the anonymous
implementation has to override all nine callbacks — Java doesn't see Kotlin's default methods.

See [../README.md](../README.md) for how the background upload works.
