package com.dps.teknokent.service;

public interface ObjectStorage {

	void put(String key, byte[] content, String contentType);
}
