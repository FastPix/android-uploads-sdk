package io.fastpix.uploads.java;

// Credentials come from local.properties (see README). In production, sign uploads on your
// backend so the secret key never ships in the APK.
public final class Config {
    private Config() {}

    public static final String TOKEN = BuildConfig.FASTPIX_TOKEN;
    public static final String SECRET_KEY = BuildConfig.FASTPIX_SECRET_KEY;

    /** 8 MiB — must be a multiple of 256 KiB (GCS requirement, enforced by the SDK). */
    public static final long CHUNK_SIZE = 8L * 1024 * 1024;
}
