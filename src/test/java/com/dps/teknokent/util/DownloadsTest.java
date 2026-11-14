package com.dps.teknokent.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

class DownloadsTest {

	@Test
	void isAnUncachedAttachmentTheBrowserMustNotSniff() {
		ResponseEntity<byte[]> response = Downloads.attachment(bytes("a,b"), "rapor.csv", "text/csv");

		assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.parseMediaType("text/csv"));
		assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).startsWith("attachment;");
		assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
		assertThat(response.getHeaders().getCacheControl()).contains("no-store");
		assertThat(response.getBody()).isEqualTo(bytes("a,b"));
	}

	@Test
	void encodesATurkishFileName() {
		ResponseEntity<byte[]> response = Downloads.attachment(bytes("x"), "Yoklama_Şubat.xlsx", "text/csv");

		assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
				.contains("filename*=UTF-8''Yoklama_%C5%9Eubat.xlsx");
	}

	@Test
	void fallsBackToABinaryTypeWhenTheStoredTypeIsMissingOrMalformed() {
		assertThat(Downloads.attachment(bytes("x"), "a", "not a type").getHeaders().getContentType())
				.isEqualTo(MediaType.APPLICATION_OCTET_STREAM);
		assertThat(Downloads.attachment(bytes("x"), "a", null).getHeaders().getContentType())
				.isEqualTo(MediaType.APPLICATION_OCTET_STREAM);
	}

	private static byte[] bytes(String text) {
		return text.getBytes(StandardCharsets.UTF_8);
	}
}
