package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import com.dps.teknokent.domain.OcrMode;
import com.dps.teknokent.exception.InvalidRequestException;

class PdfDocumentTextExtractorTest {
	private static final String FULL_PAGE = "A page with more than enough readable text to need no OCR at all";

	private final PdfDocumentTextExtractor extractor = new PdfDocumentTextExtractor();

	@TempDir
	Path tempDir;

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

		var result = extractor.extract(pdf, OcrMode.OFF);

		assertThat(result.text()).contains("[Page 1]", "Java Spring Boot mentor platform");
		assertThat(result.pageCount()).isEqualTo(1);
		assertThat(result.ocrUsed()).isFalse();
	}

	@Test
	void keepsEveryPageUnderItsOwnMarkerInOrder() throws Exception {
		var result = extractor.extract(pdf("Problem and customer", "", "Team and traction"), OcrMode.OFF);

		assertThat(result.text())
				.isEqualTo("[Page 1]\nProblem and customer\n\n[Page 2]\n\n\n[Page 3]\nTeam and traction");
		assertThat(result.pageCount()).isEqualTo(3);
		assertThat(result.ocrPages()).isEmpty();
		assertThat(result.ocrUsed()).isFalse();
	}

	@Test
	void aPdfWithoutPagesIsRefused() throws Exception {
		byte[] noPages = pdf();

		assertThatThrownBy(() -> extractor.extract(noPages, OcrMode.AUTO))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessage("The uploaded PDF has no pages.");
	}

	@Test
	void bytesThatAreNotAPdfAreRefused() {
		byte[] notAPdf = "PK word/document.xml".getBytes(StandardCharsets.UTF_8);

		assertThatThrownBy(() -> extractor.extract(notAPdf, OcrMode.OFF))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessage("The PDF is damaged, encrypted, or could not be read.");
	}

	@Test
	void aPasswordProtectedPdfIsRefused() throws Exception {
		byte[] locked;
		try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
			document.addPage(new PDPage());
			StandardProtectionPolicy policy = new StandardProtectionPolicy("owner-secret", "reader-secret",
					new AccessPermission());
			policy.setEncryptionKeyLength(128);
			document.protect(policy);
			document.save(output);
			locked = output.toByteArray();
		}

		assertThatThrownBy(() -> extractor.extract(locked, OcrMode.OFF))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessage("The PDF is damaged, encrypted, or could not be read.");
	}

	@Test
	void autoModeNeverStartsTheOcrProgramForPagesThatAlreadyHaveText() throws Exception {
		ReflectionTestUtils.setField(extractor, "tesseractExecutable", tempDir.resolve("not-installed").toString());

		var result = extractor.extract(pdf(FULL_PAGE), OcrMode.AUTO);

		assertThat(result.text()).isEqualTo("[Page 1]\n" + FULL_PAGE);
		assertThat(result.ocrPages()).isEmpty();
		assertThat(result.ocrUsed()).isFalse();
	}

	@Test
	void autoModeReadsOnlyThePagesWithAlmostNoTextThroughOcr() throws Exception {
		useOcrProgram("echo 'Scanned page text'");

		var result = extractor.extract(pdf(FULL_PAGE, "", "p. 3"), OcrMode.AUTO);

		assertThat(result.text()).isEqualTo(
				"[Page 1]\n" + FULL_PAGE + "\n\n[Page 2]\nScanned page text\n\n[Page 3]\nScanned page text");
		assertThat(result.pageCount()).isEqualTo(3);
		assertThat(result.ocrPages()).containsExactly(2, 3);
		assertThat(result.ocrUsed()).isTrue();
	}

	@Test
	void forceModeReadsEvenAPageFullOfTextThroughOcr() throws Exception {
		useOcrProgram("echo 'What the scan really says'");

		var result = extractor.extract(pdf(FULL_PAGE), OcrMode.FORCE);

		assertThat(result.text()).isEqualTo("[Page 1]\nWhat the scan really says");
		assertThat(result.ocrPages()).containsExactly(1);
		assertThat(result.ocrUsed()).isTrue();
	}

	@Test
	void ocrGetsThePageAsAnImageAndTheConfiguredLanguagesAndTheImageIsRemovedAfterwards() throws Exception {
		// Exit 3 when the page image is missing or empty; otherwise print the arguments back as the "text".
		useOcrProgram("test -s \"$1\" || exit 3\nprintf '%s\\n' \"$*\"");
		ReflectionTestUtils.setField(extractor, "ocrLanguages", "deu+eng");

		var result = extractor.extract(pdf(""), OcrMode.AUTO);

		String arguments = result.text().substring("[Page 1]\n".length());
		String afterTheImage = " stdout -l deu+eng --psm 6";
		assertThat(arguments).endsWith(afterTheImage);
		Path image = Path.of(arguments.substring(0, arguments.length() - afterTheImage.length()));
		assertThat(image.getFileName().toString()).startsWith("teknokent-ocr-").endsWith(".png");
		assertThat(image).doesNotExist();
	}

	@Test
	void anOcrRunThatFindsNothingKeepsWhatThePdfItselfSaid() throws Exception {
		useOcrProgram("printf '  \\n'");

		var result = extractor.extract(pdf("p. 3"), OcrMode.AUTO);

		assertThat(result.text()).isEqualTo("[Page 1]\np. 3");
		assertThat(result.ocrPages()).containsExactly(1);
	}

	@Test
	void aFailingOcrProgramIsReportedWithAHintAboutTheLanguageData() throws Exception {
		useOcrProgram("echo 'Error opening data file tur.traineddata'\nexit 1");
		byte[] scan = pdf("");

		assertThatThrownBy(() -> extractor.extract(scan, OcrMode.AUTO))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessage("Local OCR failed. Verify that Turkish and English Tesseract data are installed.");
	}

	@Test
	void aMissingOcrProgramIsReportedAsUnavailable() throws Exception {
		ReflectionTestUtils.setField(extractor, "tesseractExecutable", tempDir.resolve("not-installed").toString());
		ReflectionTestUtils.setField(extractor, "ocrLanguages", "tur+eng");
		byte[] scan = pdf("");

		assertThatThrownBy(() -> extractor.extract(scan, OcrMode.AUTO))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessage("Local OCR is unavailable. Install Tesseract or choose OCR off.");
	}

	// Each OCR page can take the whole timeout on the request's thread, and "force" is the caller's choice.
	@Test
	void ocrStopsAfterItsPageCapAndTheRestKeepTheirOwnText() throws Exception {
		useOcrProgram("echo 'Scanned page text'");
		String[] pages = new String[PdfDocumentTextExtractor.MAX_OCR_PAGES + 2];
		Arrays.fill(pages, "p. 3");

		var result = extractor.extract(pdf(pages), OcrMode.FORCE);

		assertThat(result.pageCount()).isEqualTo(PdfDocumentTextExtractor.MAX_OCR_PAGES + 2);
		assertThat(result.ocrPages()).hasSize(PdfDocumentTextExtractor.MAX_OCR_PAGES)
				.startsWith(1).endsWith(PdfDocumentTextExtractor.MAX_OCR_PAGES);
		assertThat(result.text())
				.contains("[Page " + PdfDocumentTextExtractor.MAX_OCR_PAGES + "]\nScanned page text")
				.endsWith("[Page " + (PdfDocumentTextExtractor.MAX_OCR_PAGES + 2) + "]\np. 3");
	}

	// Output larger than a pipe's buffer used to block the program until the timeout, then fail the whole parse.
	@Test
	void ocrOutputLargerThanAPipeCanHoldIsReadInFull() throws Exception {
		useOcrProgram("i=0\nwhile [ $i -lt 4000 ]; do echo 'A long line of recognised text from a dense page.'; "
				+ "i=$((i+1)); done");
		ReflectionTestUtils.setField(extractor, "ocrTimeoutSeconds", 10L);

		var result = extractor.extract(pdf(""), OcrMode.AUTO);

		assertThat(result.text().split("\n")).hasSize(4001);
	}

	@Test
	void anOcrRunThatOutlastsItsTimeLimitIsStopped() throws Exception {
		useOcrProgram("exec sleep 30");
		// Zero seconds: the program is still running when the wait ends, so the test never waits itself.
		ReflectionTestUtils.setField(extractor, "ocrTimeoutSeconds", 0L);
		byte[] scan = pdf("");

		assertThatThrownBy(() -> extractor.extract(scan, OcrMode.AUTO))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessage("Local OCR timed out while reading the PDF.");
	}

	/**
	 * Stands in for Tesseract, which the build machines do not have: a shell script with the given body, started
	 * exactly as the real program would be.
	 */
	private void useOcrProgram(String scriptBody) throws IOException {
		Path program = tempDir.resolve("tesseract");
		Files.writeString(program, "#!/bin/sh\n" + scriptBody + "\n", StandardCharsets.UTF_8);
		boolean runnable = program.toFile().setExecutable(true) && Files.isExecutable(program)
				&& Files.isExecutable(Path.of("/bin/sh"));
		assumeTrue(runnable, "needs /bin/sh and a temp folder that programs may run from");
		ReflectionTestUtils.setField(extractor, "tesseractExecutable", program.toString());
		ReflectionTestUtils.setField(extractor, "ocrLanguages", "tur+eng");
		ReflectionTestUtils.setField(extractor, "ocrTimeoutSeconds", 30L);
	}

	/** One small page per text; an empty text gives a page with nothing on it, as a scan has for the text layer. */
	private static byte[] pdf(String... pageTexts) throws IOException {
		try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
			for (String text : pageTexts) {
				PDPage page = new PDPage(new PDRectangle(420, 120));
				document.addPage(page);
				if (!text.isEmpty()) {
					try (PDPageContentStream content = new PDPageContentStream(document, page)) {
						content.beginText();
						content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
						content.newLineAtOffset(10, 60);
						content.showText(text);
						content.endText();
					}
				}
			}
			document.save(output);
			return output.toByteArray();
		}
	}
}
