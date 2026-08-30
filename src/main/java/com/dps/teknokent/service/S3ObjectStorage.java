package com.dps.teknokent.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.dps.teknokent.exception.InvalidRequestException;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@Service
public class S3ObjectStorage implements ObjectStorage {

	private final S3Client s3Client;
	private final String bucket;

	public S3ObjectStorage(S3Client s3Client, @Value("${app.s3.bucket:}") String bucket) {
		this.s3Client = s3Client;
		this.bucket = bucket;
	}

	@Override
	public void put(String key, byte[] content, String contentType) {
		if (bucket == null || bucket.isBlank()) {
			throw new InvalidRequestException("Object storage is not configured.");
		}
		try {
			s3Client.putObject(
					PutObjectRequest.builder()
							.bucket(bucket)
							.key(key)
							.contentType(contentType)
							.contentLength((long) content.length)
							.build(),
					RequestBody.fromBytes(content));
		} catch (RuntimeException error) {
			throw new InvalidRequestException("The file could not be stored.");
		}
	}
}
