package com.dps.teknokent.util;

import java.nio.charset.StandardCharsets;

import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** A file the backend hands out for saving. */
public final class Downloads {

	private Downloads() {
	}

	/**
	 * Always an attachment with nosniff, so an uploaded file is never rendered in the app's origin, and never cached,
	 * since every download is personal. The name is sent UTF-8 encoded: program and file names are often Turkish.
	 */
	public static ResponseEntity<byte[]> attachment(byte[] body, String filename, String contentType) {
		return ResponseEntity.ok()
				.contentType(mediaType(contentType))
				.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
						.filename(filename, StandardCharsets.UTF_8)
						.build()
						.toString())
				.header("X-Content-Type-Options", "nosniff")
				.cacheControl(CacheControl.noStore().cachePrivate())
				.body(body);
	}

	// An uploaded file's type comes from the uploader's browser; a malformed one must not turn the download into a 500.
	private static MediaType mediaType(String contentType) {
		if (contentType == null || contentType.isBlank()) {
			return MediaType.APPLICATION_OCTET_STREAM;
		}
		try {
			return MediaType.parseMediaType(contentType);
		} catch (InvalidMediaTypeException malformed) {
			return MediaType.APPLICATION_OCTET_STREAM;
		}
	}
}
