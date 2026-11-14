package com.dps.teknokent.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class PdfBytesTest {

	@Test
	void acceptsAFileWithTheHeaderAndAnEndOfFileMarker() {
		assertThat(PdfBytes.looksComplete(bytes("%PDF-1.7\n1 0 obj\n%%EOF\n"))).isTrue();
	}

	@Test
	void acceptsPaddingAfterTheEndOfFileMarker() {
		assertThat(PdfBytes.looksComplete(bytes("%PDF-1.4\n%%EOF" + "\n".repeat(100)))).isTrue();
	}

	@Test
	void rejectsAFileWithoutThePdfHeader() {
		assertThat(PdfBytes.looksComplete(bytes("PK\u0003\u0004 %%EOF"))).isFalse();
	}

	@Test
	void rejectsATruncatedFile() {
		assertThat(PdfBytes.looksComplete(bytes("%PDF-1.7\n1 0 obj\n"))).isFalse();
	}

	@Test
	void rejectsAnEndOfFileMarkerFarFromTheEnd() {
		assertThat(PdfBytes.looksComplete(bytes("%PDF-1.7\n%%EOF" + "x".repeat(5000)))).isFalse();
	}

	@Test
	void rejectsBytesShorterThanTheHeader() {
		assertThat(PdfBytes.looksComplete(bytes("%PD"))).isFalse();
		assertThat(PdfBytes.looksComplete(new byte[0])).isFalse();
	}

	private static byte[] bytes(String text) {
		return text.getBytes(StandardCharsets.ISO_8859_1);
	}
}
