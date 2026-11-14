package com.dps.teknokent.service;

import org.jspecify.annotations.Nullable;

import com.dps.teknokent.exception.ResourceNotFoundException;

public interface ObjectStorage {

	boolean isConfigured();

	void put(String key, byte[] content, String contentType);

	/** The stored bytes, or null when there is no such object. */
	byte @Nullable [] get(String key);

	/** The stored bytes; a row whose object is gone from the bucket answers 404 rather than failing later. */
	default byte[] require(String key) {
		byte[] content = get(key);
		if (content == null) {
			throw new ResourceNotFoundException("The file is no longer in storage.");
		}
		return content;
	}

	/** The stored object's size in bytes, or null when there is no such object; read without downloading it. */
	@Nullable
	Long sizeOf(String key);

	String presignPut(String key, String contentType);

	String presignGet(String key);

	default String presignInline(String key, String filename, String contentType) {
		return presignGet(key);
	}

	void delete(String key);
}
