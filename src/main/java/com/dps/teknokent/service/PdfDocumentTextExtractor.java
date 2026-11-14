package com.dps.teknokent.service;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.dps.teknokent.domain.OcrMode;
import com.dps.teknokent.exception.InvalidRequestException;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class PdfDocumentTextExtractor {

	/**
	 * Each OCR page is a 200 DPI render plus a Tesseract run of up to the timeout, on the request's own thread, and
	 * "force" comes from the request. Past the cap the remaining pages keep the PDF's own text layer (empty for a
	 * scan) and are left out of {@link ExtractionResult#ocrPages()}. A CV or a pitch deck's key pages fit easily.
	 */
	static final int MAX_OCR_PAGES = 10;
	/** Far more than any page's text; guards the heap against a runaway OCR program. */
	private static final int MAX_OCR_OUTPUT_BYTES = 1 << 20;

	@Value("${profile-parsing.ocr.executable:tesseract}")
	private String tesseractExecutable;
	@Value("${profile-parsing.ocr.languages:tur+eng}")
	private String ocrLanguages;
	@Value("${profile-parsing.ocr.timeout-seconds:45}")
	private long ocrTimeoutSeconds;

	public ExtractionResult extract(byte[] pdfBytes, OcrMode ocrMode) {
		try (PDDocument document = Loader.loadPDF(pdfBytes)) {
			if (document.getNumberOfPages() == 0) {
				throw new InvalidRequestException("The uploaded PDF has no pages.");
			}
			PDFTextStripper stripper = new PDFTextStripper();
			List<String> pages = new ArrayList<>();
			for (int page = 1; page <= document.getNumberOfPages(); page++) {
				stripper.setStartPage(page);
				stripper.setEndPage(page);
				pages.add(stripper.getText(document).trim());
			}

			List<Integer> ocrPages = selectOcrPages(pages, ocrMode);
			if (!ocrPages.isEmpty()) {
				PDFRenderer renderer = new PDFRenderer(document);
				for (int pageIndex : ocrPages) {
					String ocrText = runTesseract(renderer.renderImageWithDPI(pageIndex, 200));
					if (!ocrText.isBlank()) {
						pages.set(pageIndex, ocrText.trim());
					}
				}
			}
			StringBuilder output = new StringBuilder();
			for (int index = 0; index < pages.size(); index++) {
				output.append("[Page ").append(index + 1).append("]\n")
						.append(pages.get(index)).append("\n\n");
			}
			return new ExtractionResult(output.toString().trim(), document.getNumberOfPages(),
					ocrPages.stream().map(index -> index + 1).toList(), !ocrPages.isEmpty());
		} catch (InvalidRequestException error) {
			throw error;
		} catch (IOException error) {
			throw new InvalidRequestException("The PDF is damaged, encrypted, or could not be read.");
		}
	}

	private static List<Integer> selectOcrPages(List<String> pages, OcrMode mode) {
		if (mode == OcrMode.OFF) {
			return List.of();
		}
		List<Integer> selected = new ArrayList<>();
		for (int index = 0; index < pages.size(); index++) {
			int usefulCharacters = pages.get(index).replaceAll("\\s", "").length();
			if (mode == OcrMode.FORCE || usefulCharacters < 40) {
				selected.add(index);
			}
		}
		if (selected.size() > MAX_OCR_PAGES) {
			log.info("OCR limited to the first {} of {} pages that needed it", MAX_OCR_PAGES, selected.size());
			return selected.subList(0, MAX_OCR_PAGES);
		}
		return selected;
	}

	private String runTesseract(BufferedImage image) {
		Path imagePath = null;
		Path outputPath = null;
		try {
			imagePath = Files.createTempFile("teknokent-ocr-", ".png");
			outputPath = Files.createTempFile("teknokent-ocr-", ".txt");
			ImageIO.write(image, "png", imagePath.toFile());
			// Into a file rather than a pipe: a pipe nobody reads while the program runs fills up, and the program
			// then blocks on it until the timeout.
			Process process = new ProcessBuilder(tesseractExecutable, imagePath.toString(), "stdout",
					"-l", ocrLanguages, "--psm", "6")
					.redirectErrorStream(true)
					.redirectOutput(outputPath.toFile())
					.start();
			boolean finished = process.waitFor(ocrTimeoutSeconds, TimeUnit.SECONDS);
			if (!finished) {
				process.destroyForcibly();
				throw new InvalidRequestException("Local OCR timed out while reading the PDF.");
			}
			if (process.exitValue() != 0) {
				throw new InvalidRequestException(
						"Local OCR failed. Verify that Turkish and English Tesseract data are installed.");
			}
			try (InputStream output = Files.newInputStream(outputPath)) {
				return new String(output.readNBytes(MAX_OCR_OUTPUT_BYTES), StandardCharsets.UTF_8);
			}
		} catch (InterruptedException error) {
			Thread.currentThread().interrupt();
			throw new InvalidRequestException("Local OCR was interrupted.");
		} catch (IOException error) {
			throw new InvalidRequestException("Local OCR is unavailable. Install Tesseract or choose OCR off.");
		} finally {
			deleteQuietly(imagePath);
			deleteQuietly(outputPath);
		}
	}

	private static void deleteQuietly(@Nullable Path path) {
		if (path == null) {
			return;
		}
		try {
			Files.deleteIfExists(path);
		} catch (IOException ignored) {
			// A page image or text left in the temp folder is harmless; the OCR result is already decided.
		}
	}

	public record ExtractionResult(String text, int pageCount, List<Integer> ocrPages, boolean ocrUsed) {
	}
}
