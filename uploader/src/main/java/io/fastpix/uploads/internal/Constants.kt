package io.fastpix.uploads.internal

/**
 * Constants used in the SDK
 */
// Development
//https://venus-dashboard.fastpix.dev/media
object Constants {
    const val MEDIA_TYPE = "application/json; charset=UTF-8"
    const val MULTIPART_URL = "https://v1.fastpix.io/on-demand/uploads/multipart"
    const val MINIMUM_CHUNK_SIZE = 5242880 // In Bytes
    const val MAXIMUM_CHUNK_SIZE = 524288000 // In Bytes
    const val SOMETHING_WENT_WRONG = "Something went wrong"
}