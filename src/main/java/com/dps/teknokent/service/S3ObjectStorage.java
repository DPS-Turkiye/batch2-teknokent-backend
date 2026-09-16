package com.dps.teknokent.service;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.dps.teknokent.exception.InvalidRequestException;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

@Service
public class S3ObjectStorage implements ObjectStorage {

	private static final Duration PUT_URL_TTL = Duration.ofMinutes(5);
	private static final Duration GET_URL_TTL = Duration.ofMinutes(30);

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

	@Override
	public String presignGet(String key) {
		return presign(key, null, null);
	}

	@Override
	public String presignInline(String key, String filename, String contentType) {
		String safe = filename == null ? "document" : filename.replace('\\', '_').replace('"', '_').replace('\n', '_');
		return presign(key, contentType, "inline; filename=\"" + safe + "\"");
	}

	private String presign(String key, String contentType, String contentDisposition) {
		if (!isConfigured()) {
			throw new InvalidRequestException("Object storage is not configured.");
		}
		try {
			var request = GetObjectRequest.builder().bucket(bucket).key(key);
			if (contentType != null && !contentType.isBlank()) {
				request.responseContentType(contentType);
			}
			if (contentDisposition != null && !contentDisposition.isBlank()) {
				request.responseContentDisposition(contentDisposition);
			}
			return s3Presigner.presignGetObject(
					GetObjectPresignRequest.builder()
							.signatureDuration(GET_URL_TTL)
							.getObjectRequest(request.build())
							.build())
					.url()
					.toString();
		} catch (RuntimeException error) {
			throw new InvalidRequestException("The download URL could not be created.");
		}
	}

	@Override
	public void delete(String key) {
		if (!isConfigured()) {
			throw new InvalidRequestException("Object storage is not configured.");
		}
		try {
			s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
		} catch (RuntimeException error) {
			throw new InvalidRequestException("The file could not be deleted.");
		}
	}
}
