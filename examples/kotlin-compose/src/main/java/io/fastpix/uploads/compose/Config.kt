package io.fastpix.uploads.compose

// Credentials come from local.properties (see README). In production, sign uploads on your
// backend so the secret key never ships in the APK.
object Config {
    val TOKEN = BuildConfig.FASTPIX_TOKEN
    val SECRET_KEY = BuildConfig.FASTPIX_SECRET_KEY

    /** 8 MiB — must be a multiple of 256 KiB (GCS requirement, enforced by the SDK). */
    const val CHUNK_SIZE = 8L * 1024 * 1024
}
