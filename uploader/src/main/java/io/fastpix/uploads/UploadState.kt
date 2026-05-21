package io.fastpix.uploads

enum class UploadState {
    IDLE,
    PREPARING,
    UPLOADING,
    PAUSED,
    RETRYING,
    NETWORK_LOST,
    QUERYING_STATUS,
    COMPLETED,
    FAILED,
    CANCELLED;

    val isTerminal: Boolean
        get() = this == COMPLETED || this == FAILED || this == CANCELLED
}
