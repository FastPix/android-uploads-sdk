# FastPixUploader SDK (Android)

The **FastPixUploader SDK** is a robust Android library designed for chunked file uploads using signed URLs. It simplifies large file uploads by splitting files into smaller chunks, ensuring smooth and reliable transfers with built-in retry and progress tracking mechanisms.

# Features:

- **Chunking:** Files are automatically split into chunks (configurable, default size is 16MB/chunk).
- **Pause and Resume:** Allows temporarily pausing the upload and resuming after a while.
- **Retry:** Uploads might fail due to temporary network failures. Individual chunks are retried for 5 times with exponential backoff to recover automatically from such failures.
- **Lifecycle Event Listeners:** Listen to various upload lifecycle events to provide real-time feedback to users.
- **Error Handling and Reporting:** Comprehensive error handling to manage upload failures gracefully and inform users of issues.
- **Customizability:** Developers can customize the chunk size and retry attempts based on their specific needs and network conditions.

# Prerequisites:

## Getting started with FastPix:
- Android 5.0 (API 21) or above
- Kotlin project (Java-compatible via interfaces)
- To get started with SDK, you will need a signed URL.
- To make API requests, you'll need a valid **Access Token** and **Secret Key**. See the [Basic Authentication Guide](https://docs.fastpix.io/docs/basic-authentication) for details on retrieving these credentials.
- Once you have your credentials, use the [Upload media from device](https://docs.fastpix.io/reference/direct-upload-video-media) API to generate a signed URL for uploading media.

## Installation:

Add the SDK module to your project and include it as a dependency if distributed as an module.

- Add to your app's build.gradle:

```groovy
dependencies {
    implementation("io.fastpix.upload:x.x.x") //latest version 1.0.1
}
```
## Basic Usage

## Import

```kotlin
package io.fastpix.uploadsdk
```  

## Integration

```kotlin  
val sdk = FastPixUploadSdk.Builder(this)
    .setFile(file)
    .setSignedUrl(_signedUrl.orEmpty())
    .setChunkSize(16 * 1024 * 1024) // Chunk Size in Byte
    .setMaxRetries(3) 
    .callback(new object : FastPixUploadCallbacks {
        override fun onProgressUpdate(progress: Double) {
             /* ... */
        }
        override fun onPauseUploading() {
            // Handle Pause State
        }
        override fun onResumeUploading() {
            // Handle Resume State
        }
        override fun onAbort() {
            // Handle Abort State
        }
        override fun onUploadInit() {
            // Handle Abort State
        }
        override fun onChunkHanlded(
            totalChunks: Int,
            fileSizeInBytes: Long,
            currentChunk: Int,
            currentChunkSizeInBytes: Long
        ) {
            // Update the UI according chunk data
        }
        override fun onSuccess(timiMillis: Long) {
            // Time to complete the Upload Process
        }
        override fun onChunkUploadingFailed(
            failedChunkRetries: Int,
            chunkCount: Int,
            chunkSize: Int
        ) {
            // Handle Chunk Upload Failure
        }
        override fun onError(error: String, timeMillis: Long) {
            // Handle error message
        }
        override fun onNetworkStateChanged(isOnline: Boolean) {
            // Handle Network Changes
        }

    })
    .setRetryDelay(2000) // Retry Delay  
    .build()
// Starts the Uploading Process
sdk.startUpload()
```  

## Managing Uploads

You can control the upload lifecycle with the following methods:

- **Pause an Upload:**

  ```kotlin
  sdk.pauseUpload() // Pauses the current upload
  ```

- **Resume an Upload:**

  ```kotlin
  sdk.resumeUpload() // Resume the current upload
  ```

- **Abort an Upload:**

  ```kotlin
  sdk.abort(); // Abort the current upload

  
---  

## Parameters Accepted

The upload function accepts the following parameters:

| Name                | Type                                | Required | Description                                                                                                                                                   |
| ------------------- | ----------------------------------- | -------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `endpoint`          | `string` or `() => Promise<string>` | Required | The signed URL endpoint where the file will be uploaded. Can be a static string or a function returning a `Promise` that resolves to the upload URL.          |
| `file`              | `File` or `Object`                  | Required | The file object to be uploaded. Typically a `File` retrieved from an `<input type="file" />` element, but can also be a generic object representing the file. |
| `chunkSize`         | `number` (in KB)                    | Optional | Size of each chunk in kilobytes. Default is `16384` KB (16 MB).<br>**Minimum:** 5120 KB (5 MB), **Maximum:** 512000 KB (500 MB).                              |
| `maxFileBytesKB`       | `number` (in KB)                    | Optional | Maximum allowed file size for upload, specified in kilobytes. Files exceeding this limit will be rejected.                                                    |
| `maxRetryAttempt` | `number`                            | Optional | Number of retry attempts per chunk in case of failure. Default is `5`.                                                                                        |                                    

  
---  

## Callbacks

Implement `FastPixUploadCallback` to handle various upload events:

| Method                                                                                                 | Description                                                          |  
|--------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------|  
| `onProgressUpdate(progress: Double)`                                                                   | Called with total upload progress (0.0 - 100.0)                      |  
| `onSuccess(timiMillis: Long)`                                                                          | Called when all chunks are successfully uploaded                     |  
| `onError(error: String, timeMillis: Long)`                                                             | Called when any fatal error occurs in uploading process              |
| `onChunkUploadingFailed(failedChunkRetries: Int, chunkCount: Int, chunkSize: Long)`                    | Called when any fatal error occurs when uploading a individual chunk |  
| `onNetworkStateChange(isOnline: Boolean)`                                                              | Called when device's connectivity status changes                     |  
| `onUploadInit()`                                                                                       | Called once upload initialization starts                             |  
| `onChunkHandled(totalChunks:Int,filSizeInBytes:Long,currentChunk:Int,  currentChunkSizeInBytes: Long)` | Provide Information About the Chunks Uploading                       |

  
---  


# References 

- [Homepage](https://www.fastpix.io/)
- [Dashboard](https://dashboard.fastpix.io/login?redirect=https://dashboard.fastpix.io/)
- [GitHub](https://github.com/FastPix/Android-Uploads.git)
- [API Reference](https://docs.fastpix.io/reference/on-demand-overview)

# Detailed Usage:

For more detailed steps and advanced usage, please refer to the official [FastPix Documentation](https://docs.fastpix.io/docs/upload-sdk-for-android).
