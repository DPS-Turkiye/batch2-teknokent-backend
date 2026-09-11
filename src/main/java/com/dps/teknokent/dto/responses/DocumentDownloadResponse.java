package com.dps.teknokent.dto.responses;

/** A short-lived presigned link; callers open it rather than proxying the bytes. */
public record DocumentDownloadResponse(String url, String filename, String contentType) {
}
