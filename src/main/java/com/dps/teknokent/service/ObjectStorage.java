package com.dps.teknokent.service;

public interface ObjectStorage {

	boolean isConfigured();

	void put(String key, byte[] content, String contentType);

	byte[] get(String key);

	String presignPut(String key, String contentType);

	String presignGet(String key);

	default String presignInline(String key, String filename, String contentType) {
		return presignGet(key);
	}

	void delete(String key);
}
