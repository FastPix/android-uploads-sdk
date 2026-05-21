package io.fastpix.uploads

sealed class UploadError(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause) {

    class InvalidConfiguration(message: String) : UploadError(message)

    class FileNotFound(path: String?) :
        UploadError("File not found${path?.let { ": $it" } ?: ""}.")

    class FileNotReadable(path: String?) :
        UploadError("File is not readable${path?.let { ": $it" } ?: ""}.")

    class FileEmpty(path: String?) :
        UploadError("File has no content${path?.let { ": $it" } ?: ""}.")

    class FileReadFailure(cause: Throwable) :
        UploadError("Failed to read file: ${cause.message}", cause)

    class NetworkFailure(cause: Throwable) :
        UploadError("Network error: ${cause.message}", cause)

    class SessionExpired :
        UploadError(
            "Resumable upload session has expired (HTTP 410). A new signed session URI must be generated."
        )

    class ClientError(val statusCode: Int, body: String?) :
        UploadError("Upload rejected by server (HTTP $statusCode)${body?.let { ": $it" } ?: ""}.")

    class ServerError(val statusCode: Int, body: String?) :
        UploadError("Server error after retries (HTTP $statusCode)${body?.let { ": $it" } ?: ""}.")

    class RetryLimitExceeded(val attempts: Int, lastCause: Throwable? = null) :
        UploadError("Exceeded $attempts retry attempts.", lastCause)

    class UnexpectedResponse(message: String) : UploadError(message)
}
