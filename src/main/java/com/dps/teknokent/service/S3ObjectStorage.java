package com.dps.teknokent.service;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.dps.teknokent.exception.InvalidRequestException;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

@Service
public class S3ObjectStorage implements ObjectStorage {

	private static final Duration PUT_URL_TTL = Duration.ofMinutes(5);

	private final S3Client s3Client;
	private final S3Presigner s3Presigner;
	private final String bucket;

	public S3ObjectStorage(
			S3Client s3Client,
			S3Presigner s3Presigner,
			@Value("${app.s3.bucket:}") String bucket) {
		this.s3Client = s3Client;
		this.s3Presigner = s3Presigner;
		this.bucket = bucket;
	}

	@Override
	public boolean isConfigured() {
		return bucket != null && !bucket.isBlank();
	}

	@Override
	public void put(String key, byte[] content, String contentType) {
		if (!isConfigured()) {
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

	@Override
	public byte[] get(String key) {
		if (!isConfigured()) {
			throw new InvalidRequestException("Object storage is not configured.");
		}
		try {
			return s3Client.getObjectAsBytes(
					GetObjectRequest.builder().bucket(bucket).key(key).build())
					.asByteArray();
		} catch (NoSuchKeyException missing) {
			return null;
		} catch (RuntimeException error) {
			throw new InvalidRequestException("The file could not be read.");
		}
	}

	@Override
	public String presignPut(String key, String contentType) {
		if (!isConfigured()) {
			throw new InvalidRequestException("Object storage is not configured.");
		}
		try {
			return s3Presigner.presignPutObject(
					PutObjectPresignRequest.builder()
							.signatureDuration(PUT_URL_TTL)
							.putObjectRequest(PutObjectRequest.builder()
									.bucket(bucket)
									.key(key)
									.contentType(contentType)
									.build())
							.build())
					.url()
					.toString();
		} catch (RuntimeException error) {
			throw new InvalidRequestException("The upload URL could not be created.");
		}
	}
}
