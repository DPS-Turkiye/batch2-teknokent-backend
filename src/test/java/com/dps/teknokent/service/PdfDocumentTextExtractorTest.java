package com.dps.teknokent.service;

import java.io.ByteArrayOutputStream;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PdfDocumentTextExtractorTest {
	private final PdfDocumentTextExtractor extractor = new PdfDocumentTextExtractor();

	@Test
	void extractsPageAwareTextWithoutOcr() throws Exception {
		byte[] pdf;
		try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
			PDPage page = new PDPage();
			document.addPage(page);
			try (PDPageContentStream content = new PDPageContentStream(document, page)) {
				content.beginText();
				content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
				content.newLineAtOffset(50, 700);
				content.showText("Java Spring Boot mentor platform");
				content.endText();
			}
			document.save(output);
			pdf = output.toByteArray();
		}

		var result = extractor.extract(pdf, "off");

		assertThat(result.text()).contains("[Page 1]", "Java Spring Boot mentor platform");
		assertThat(result.pageCount()).isEqualTo(1);
		assertThat(result.ocrUsed()).isFalse();
	}
}
