package com.dps.teknokent.dto.responses;

public record TranscriptUploadUrlResponse(
		String url,
		String method,
		String key,
		String contentType) {
}
