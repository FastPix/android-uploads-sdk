# FastPix Resumable Uploads SDK - Documentation PR

## Documentation Changes

### What Changed
- [ ] New documentation added
- [ ] Existing documentation updated
- [ ] Documentation errors fixed
- [ ] Code examples updated
- [ ] Links and references updated

### Files Modified
- [ ] README.md
- [ ] docs/ files
- [ ] USAGE.md
- [ ] CONTRIBUTING.md
- [ ] Other: _______________

### Summary
**Brief description of changes:**

<!-- What documentation was added, updated, or fixed? -->

### Code Examples
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

### Testing
- [ ] All code examples tested
- [ ] Links verified
- [ ] Grammar checked
- [ ] Formatting consistent

### Review Checklist
- [ ] Content is accurate
- [ ] Code examples work
- [ ] Links are working
- [ ] Grammar is correct
- [ ] Formatting is consistent

---

**Ready for review!**
