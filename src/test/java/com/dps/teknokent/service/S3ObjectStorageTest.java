package com.dps.teknokent.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dps.teknokent.exception.InvalidRequestException;

import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutBucketCorsRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class S3ObjectStorageTest {
	@Mock
	private S3Client s3Client;

	private S3ObjectStorage storage(String bucket) {
		return new S3ObjectStorage(s3Client, null, bucket);
	}

	@Test
	void isNotConfiguredWhenBucketIsBlank() {
		var storage = storage("  ");

		assertThat(storage.isConfigured()).isFalse();
		assertThatThrownBy(() -> storage.put("k", new byte[] { 1 }, "text/plain"))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessage("Object storage is not configured.");
	}

	@Test
	void returnsNullWhenObjectIsMissing() {
		var storage = storage("bucket");
		when(s3Client.getObjectAsBytes(any(GetObjectRequest.class)))
				.thenThrow(NoSuchKeyException.builder().message("missing").build());

		assertThat(storage.get("missing.json")).isNull();
	}

	@Test
	void writesBucketCorsWhenConfigured() {
		var storage = storage("bucket");

		storage.allowBrowserPuts();

		verify(s3Client).putBucketCors(any(PutBucketCorsRequest.class));
	}

	@Test
	void returnsObjectBytes() {
		var storage = storage("bucket");
		when(s3Client.getObjectAsBytes(any(GetObjectRequest.class)))
				.thenReturn(ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), new byte[] { 9, 8 }));

		assertThat(storage.get("meetings/id/transcript.json")).containsExactly(9, 8);
	}
}
