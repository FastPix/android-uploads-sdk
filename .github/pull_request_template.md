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
// If you added/updated code examples, include them here
private val fastPixDataSDK = FastPixDataSDK()
val videoDataDetails = VideoDataDetails(
    videoId = UUID.randomUUID().toString(),
    videoTitle = "My Video"
).apply {
    videoSeries = "Demo Series"
    videoProducer = "Demo Producer"
    videoContentType = "VOD"
    // ..etc
}
// Optional
val playerDataDetails = PlayerDataDetails(
    playerName = "media3",
    playerVersion = "latest-version"
)
// Optional
val customDataDetails = CustomDataDetails().apply {
    customField1 = "Custom Value 1"
    customField2 = "Custom Value 2"
    // ..etc
}

fastPixDataSDK = FastPixBaseMedia3Player(
    context = this,
    playerView = binding.playerView,
    exoPlayer = exoPlayer,
    workSpaceId = "workspace-key",
    playerDataDetails = playerDataDetails,
    videoDataDetails = videoDataDetails,
    customDataDetails = customDataDetails
)
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
