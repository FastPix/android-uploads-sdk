# Example apps

Three small apps that upload a file to FastPix with the [`:uploader`](../uploader) SDK. They all do
the same thing — pick a file, create an upload, and push it in the background with pause/resume/cancel
— just in different stacks, so copy whichever one matches your project:

- **[kotlin-xml](kotlin-xml)** — Kotlin, XML views
- **[kotlin-compose](kotlin-compose)** — Kotlin, Jetpack Compose
- **[java-xml](java-xml)** — Java, XML views

## Setup

Add your FastPix credentials to the project-root `local.properties` (it's gitignored, so they stay
out of the repo):

```properties
fastpix.token=YOUR_ACCESS_TOKEN_ID
fastpix.secretKey=YOUR_SECRET_KEY
```

You'll find them in the [FastPix dashboard](https://dashboard.fastpix.com/) under Access Tokens. For
a real app, create the upload from your own backend instead, so the secret key never ships in the APK.

## Running

Open the project in Android Studio, pick one of the example modules from the run dropdown, and hit
Run. (The command-line `./gradlew` needs JDK 17 or 21 — Gradle 8.11 won't run on newer JDKs.)

## How the background upload works

Each app keeps its uploader in a small singleton (`UploadManager`) instead of the Activity, and runs
a foreground service (`UploadService`) while an upload is in flight. That's what lets the upload keep
going when you leave the screen or rotate the device, with progress shown in a notification.

It's deliberately not persisted — if the app's process is killed, the upload stops with it. If you
need uploads to survive that, move the work into WorkManager and recreate the session from a saved
session URL and file path; the SDK re-checks the server's offset on resume, so it continues from where
it left off.
