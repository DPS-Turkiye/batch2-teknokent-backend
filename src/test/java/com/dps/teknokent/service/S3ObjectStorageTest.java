package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.URL;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;

import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

@ExtendWith(MockitoExtension.class)
class S3ObjectStorageTest {
	@Mock
	private S3Client s3Client;
	@Mock
	private S3Presigner s3Presigner;

	private S3ObjectStorage storage(String bucket) {
		return new S3ObjectStorage(s3Client, s3Presigner, bucket);
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
	void requiringAMissingObjectIsNotFound() {
		var storage = storage("bucket");
		when(s3Client.getObjectAsBytes(any(GetObjectRequest.class)))
				.thenThrow(NoSuchKeyException.builder().message("missing").build());

		assertThatThrownBy(() -> storage.require("missing.json")).isInstanceOf(ResourceNotFoundException.class);
	}

	@Test
	void returnsObjectBytes() {
		var storage = storage("bucket");
		when(s3Client.getObjectAsBytes(any(GetObjectRequest.class)))
				.thenReturn(ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), new byte[] { 9, 8 }));

		assertThat(storage.get("meetings/id/transcript.json")).containsExactly(9, 8);
	}

	@Test
	void readsAnObjectsSizeWithoutDownloadingIt() {
		var storage = storage("bucket");
		when(s3Client.headObject(any(HeadObjectRequest.class)))
				.thenReturn(HeadObjectResponse.builder().contentLength(2048L).build());

		assertThat(storage.sizeOf("meetings/id/transcript.json")).isEqualTo(2048L);
		verify(s3Client, never()).getObjectAsBytes(any(GetObjectRequest.class));
	}

	@Test
	void hasNoSizeForAMissingObjectAndRefusesWhenTheSizeCannotBeRead() {
		var storage = storage("bucket");
		when(s3Client.headObject(any(HeadObjectRequest.class)))
				.thenThrow(S3Exception.builder().statusCode(404).message("missing").build())
				.thenThrow(S3Exception.builder().statusCode(403).message("denied").build())
				.thenThrow(SdkClientException.create("offline"));

		assertThat(storage.sizeOf("missing.json")).isNull();
		assertThatThrownBy(() -> storage.sizeOf("denied.json")).isInstanceOf(InvalidRequestException.class);
		assertThatThrownBy(() -> storage.sizeOf("offline.json")).isInstanceOf(InvalidRequestException.class);
		assertThatThrownBy(() -> storage("").sizeOf("k")).isInstanceOf(InvalidRequestException.class);
	}

	@Test
	void deletesTheObject() {
		storage("bucket").delete("documents/u/other/model.xlsx");

		verify(s3Client).deleteObject(any(DeleteObjectRequest.class));
	}

	@Test
	void keepsAsciiFilenamesInContentDisposition() {
		assertThat(S3ObjectStorage.inlineContentDisposition("pitch-deck.pdf"))
				.isEqualTo("inline; filename=\"pitch-deck.pdf\"");
	}

	@Test
	void stripsTurkishCharactersFromS3ContentDisposition() {
		assertThat(S3ObjectStorage.inlineContentDisposition("Toplantı Notları.pdf"))
				.isEqualTo("inline; filename=\"Toplant_ Notlar_.pdf\"");
	}

	@Test
	void reportsAFailedDelete() {
		when(s3Client.deleteObject(any(DeleteObjectRequest.class))).thenThrow(new RuntimeException("boom"));

		assertThatThrownBy(() -> storage("bucket").delete("documents/u/other/model.xlsx"))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessage("The file could not be deleted.");
	}

	@Test
	void isConfiguredOnlyWithABucketName() {
		assertThat(storage(null).isConfigured()).isFalse();
		assertThat(storage("").isConfigured()).isFalse();
		assertThat(storage("teknokent-documents").isConfigured()).isTrue();
	}

	@Test
	void refusesEveryOperationWhenNoBucketIsConfigured() {
		var storage = storage(null);

		assertThatThrownBy(() -> storage.get("k")).hasMessage("Object storage is not configured.");
		assertThatThrownBy(() -> storage.delete("k")).hasMessage("Object storage is not configured.");
		assertThatThrownBy(() -> storage.presignPut("k", "application/pdf"))
				.hasMessage("Object storage is not configured.");
		assertThatThrownBy(() -> storage.presignGet("k")).hasMessage("Object storage is not configured.");
		assertThatThrownBy(() -> storage.presignInline("k", "cv.pdf", "application/pdf"))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessage("Object storage is not configured.");
		verifyNoInteractions(s3Client, s3Presigner);
	}

	@Test
	void putsTheBytesUnderTheKeyWithTheirTypeAndLength() throws Exception {
		byte[] content = new byte[] { 1, 2, 3 };

		storage("teknokent-documents").put("documents/u/cv/cv.pdf", content, "application/pdf");

		ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
		ArgumentCaptor<RequestBody> body = ArgumentCaptor.forClass(RequestBody.class);
		verify(s3Client).putObject(request.capture(), body.capture());
		assertThat(request.getValue().bucket()).isEqualTo("teknokent-documents");
		assertThat(request.getValue().key()).isEqualTo("documents/u/cv/cv.pdf");
		assertThat(request.getValue().contentType()).isEqualTo("application/pdf");
		assertThat(request.getValue().contentLength()).isEqualTo(3L);
		try (var sent = body.getValue().contentStreamProvider().newStream()) {
			assertThat(sent.readAllBytes()).containsExactly(1, 2, 3);
		}
	}

	@Test
	void reportsAFailedPut() {
		when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
				.thenThrow(SdkClientException.create("connection reset"));

		assertThatThrownBy(() -> storage("bucket").put("k", new byte[] { 1 }, "text/plain"))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessage("The file could not be stored.");
	}

	@Test
	void readsTheKeyFromTheConfiguredBucket() {
		when(s3Client.getObjectAsBytes(any(GetObjectRequest.class)))
				.thenReturn(ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), new byte[] { 7 }));

		storage("teknokent-documents").get("meetings/id/transcript.json");

		ArgumentCaptor<GetObjectRequest> request = ArgumentCaptor.forClass(GetObjectRequest.class);
		verify(s3Client).getObjectAsBytes(request.capture());
		assertThat(request.getValue().bucket()).isEqualTo("teknokent-documents");
		assertThat(request.getValue().key()).isEqualTo("meetings/id/transcript.json");
	}

	// Only a missing key reads as "no such file"; any other failure must not look like an empty result.
	@Test
	void reportsAFailedReadInsteadOfTreatingItAsMissing() {
		when(s3Client.getObjectAsBytes(any(GetObjectRequest.class)))
				.thenThrow(SdkClientException.create("connection reset"));

		assertThatThrownBy(() -> storage("bucket").get("meetings/id/transcript.json"))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessage("The file could not be read.");
	}

	@Test
	void deletesTheKeyFromTheConfiguredBucket() {
		storage("teknokent-documents").delete("documents/u/other/model.xlsx");

		ArgumentCaptor<DeleteObjectRequest> request = ArgumentCaptor.forClass(DeleteObjectRequest.class);
		verify(s3Client).deleteObject(request.capture());
		assertThat(request.getValue().bucket()).isEqualTo("teknokent-documents");
		assertThat(request.getValue().key()).isEqualTo("documents/u/other/model.xlsx");
	}

	@Test
	void presignsAnUploadForTheKeyAndTypeThatLastsFiveMinutes() throws Exception {
		URL url = URI.create("https://teknokent-documents.s3.amazonaws.com/uploads/a.pdf?X-Amz-Signature=abc").toURL();
		PresignedPutObjectRequest presigned = mock(PresignedPutObjectRequest.class);
		when(presigned.url()).thenReturn(url);
		when(s3Presigner.presignPutObject(any(PutObjectPresignRequest.class))).thenReturn(presigned);

		String link = storage("teknokent-documents").presignPut("uploads/a.pdf", "application/pdf");

		assertThat(link).isEqualTo(url.toString());
		ArgumentCaptor<PutObjectPresignRequest> request = ArgumentCaptor.forClass(PutObjectPresignRequest.class);
		verify(s3Presigner).presignPutObject(request.capture());
		assertThat(request.getValue().signatureDuration()).isEqualTo(Duration.ofMinutes(5));
		assertThat(request.getValue().putObjectRequest().bucket()).isEqualTo("teknokent-documents");
		assertThat(request.getValue().putObjectRequest().key()).isEqualTo("uploads/a.pdf");
		assertThat(request.getValue().putObjectRequest().contentType()).isEqualTo("application/pdf");
	}

	@Test
	void reportsAnUploadLinkThatCouldNotBeSigned() {
		when(s3Presigner.presignPutObject(any(PutObjectPresignRequest.class)))
				.thenThrow(SdkClientException.create("no credentials"));

		assertThatThrownBy(() -> storage("bucket").presignPut("uploads/a.pdf", "application/pdf"))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessage("The upload URL could not be created.");
	}

	@Test
	void presignsAPlainDownloadThatLastsThirtyMinutes() throws Exception {
		URL url = stubPresignedDownload();

		String link = storage("teknokent-documents").presignGet("documents/u/cv/cv.pdf");

		assertThat(link).isEqualTo(url.toString());
		GetObjectPresignRequest request = presignedDownloadRequest();
		assertThat(request.signatureDuration()).isEqualTo(Duration.ofMinutes(30));
		assertThat(request.getObjectRequest().bucket()).isEqualTo("teknokent-documents");
		assertThat(request.getObjectRequest().key()).isEqualTo("documents/u/cv/cv.pdf");
		assertThat(request.getObjectRequest().responseContentType()).isNull();
		assertThat(request.getObjectRequest().responseContentDisposition()).isNull();
	}

	@Test
	void presignsAnInlineDownloadThatOpensInTheBrowserUnderAnAsciiName() throws Exception {
		stubPresignedDownload();

		storage("teknokent-documents").presignInline("documents/u/other/notes.pdf", "Toplantı Notları.pdf",
				"application/pdf");

		GetObjectPresignRequest request = presignedDownloadRequest();
		assertThat(request.signatureDuration()).isEqualTo(Duration.ofMinutes(30));
		assertThat(request.getObjectRequest().key()).isEqualTo("documents/u/other/notes.pdf");
		assertThat(request.getObjectRequest().responseContentType()).isEqualTo("application/pdf");
		assertThat(request.getObjectRequest().responseContentDisposition())
				.isEqualTo("inline; filename=\"Toplant_ Notlar_.pdf\"");
	}

	@Test
	void presignsAnInlineDownloadWithoutATypeWhenNoneIsKnown() throws Exception {
		stubPresignedDownload();

		storage("teknokent-documents").presignInline("documents/u/other/notes.pdf", "notes.pdf", " ");

		GetObjectPresignRequest request = presignedDownloadRequest();
		assertThat(request.getObjectRequest().responseContentType()).isNull();
		assertThat(request.getObjectRequest().responseContentDisposition())
				.isEqualTo("inline; filename=\"notes.pdf\"");
	}

	@Test
	void reportsADownloadLinkThatCouldNotBeSigned() {
		when(s3Presigner.presignGetObject(any(GetObjectPresignRequest.class)))
				.thenThrow(SdkClientException.create("no credentials"));

		assertThatThrownBy(() -> storage("bucket").presignGet("documents/u/cv/cv.pdf"))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessage("The download URL could not be created.");
	}

	@Test
	void namesAFileWithoutANameDocument() {
		assertThat(S3ObjectStorage.inlineContentDisposition(null)).isEqualTo("inline; filename=\"document\"");
		assertThat(S3ObjectStorage.inlineContentDisposition("   ")).isEqualTo("inline; filename=\"document\"");
	}

	// The name ends up inside a quoted header value, so nothing in it may close the quote or start a new line.
	@Test
	void neutralisesQuotesBackslashesAndLineBreaksInTheFileName() {
		assertThat(S3ObjectStorage.inlineContentDisposition("  a\"b\\c\r\nd.pdf "))
				.isEqualTo("inline; filename=\"a_b_c__d.pdf\"");
	}

	private URL stubPresignedDownload() throws Exception {
		URL url = URI.create("https://teknokent-documents.s3.amazonaws.com/object?X-Amz-Signature=abc").toURL();
		PresignedGetObjectRequest presigned = mock(PresignedGetObjectRequest.class);
		when(presigned.url()).thenReturn(url);
		when(s3Presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenReturn(presigned);
		return url;
	}

	private GetObjectPresignRequest presignedDownloadRequest() {
		ArgumentCaptor<GetObjectPresignRequest> request = ArgumentCaptor.forClass(GetObjectPresignRequest.class);
		verify(s3Presigner).presignGetObject(request.capture());
		return request.getValue();
	}
}
