package com.dps.teknokent.service;

import java.awt.image.BufferedImage;
import java.io.IOException;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.dps.teknokent.exception.InvalidRequestException;

@Service
public class PdfDocumentTextExtractor {

	@Value("${profile-parsing.ocr.executable:tesseract}")
	private String tesseractExecutable;
	@Value("${profile-parsing.ocr.languages:tur+eng}")
	private String ocrLanguages;
	@Value("${profile-parsing.ocr.timeout-seconds:45}")
	private long ocrTimeoutSeconds;

	public ExtractionResult extract(byte[] pdfBytes, String ocrMode) {
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
					if (!ocrText.isBlank())
						pages.set(pageIndex, ocrText.trim());
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

	private List<Integer> selectOcrPages(List<String> pages, String mode) {
		if ("off".equals(mode))
			return List.of();
		List<Integer> selected = new ArrayList<>();
		for (int index = 0; index < pages.size(); index++) {
			int usefulCharacters = pages.get(index).replaceAll("\\s", "").length();
			if ("force".equals(mode) || usefulCharacters < 40)
				selected.add(index);
		}
		return selected;
	}

	private String runTesseract(BufferedImage image) {
		Path imagePath = null;
		try {
			imagePath = Files.createTempFile("teknokent-ocr-", ".png");
			ImageIO.write(image, "png", imagePath.toFile());
			Process process = new ProcessBuilder(tesseractExecutable, imagePath.toString(), "stdout",
					"-l", ocrLanguages, "--psm", "6")
					.redirectErrorStream(true)
					.start();
			boolean finished = process.waitFor(ocrTimeoutSeconds, TimeUnit.SECONDS);
			if (!finished) {
				process.destroyForcibly();
				throw new InvalidRequestException("Local OCR timed out while reading the PDF.");
			}
			String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			if (process.exitValue() != 0) {
				throw new InvalidRequestException(
						"Local OCR failed. Verify that Turkish and English Tesseract data are installed.");
			}
			return output;
		} catch (InterruptedException error) {
			Thread.currentThread().interrupt();
			throw new InvalidRequestException("Local OCR was interrupted.");
		} catch (IOException error) {
			throw new InvalidRequestException("Local OCR is unavailable. Install Tesseract or choose OCR off.");
		} finally {
			if (imagePath != null) {
				try {
					Files.deleteIfExists(imagePath);
				} catch (IOException ignored) {
				}
			}
		}
	}

	public record ExtractionResult(String text, int pageCount, List<Integer> ocrPages, boolean ocrUsed) {
	}
}
