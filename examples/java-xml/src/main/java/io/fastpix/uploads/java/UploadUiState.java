package io.fastpix.uploads.java;

// Current upload state shown in the UI.
public final class UploadUiState {
    public boolean active = false;
    public int percent = 0;
    public String fileName = "";
    public String status = "Idle";
}
