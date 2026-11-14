package com.dps.teknokent.util;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** A cheap structural check on uploaded PDF bytes, run before anything stores or parses them. */
public final class PdfBytes {

	private static final byte[] HEADER = { '%', 'P', 'D', 'F', '-' };
	// Writers may put a little after the trailer (a newline, incremental-update padding), so the marker is looked
	// for near the end rather than at it.
	private static final int EOF_SCAN_BYTES = 4 * 1024;

	private PdfBytes() {
	}

	/** Starts with the PDF header and has an end-of-file marker near its end: not a truncated or renamed file. */
	public static boolean looksComplete(byte[] bytes) {
		return bytes.length >= HEADER.length
				&& Arrays.equals(bytes, 0, HEADER.length, HEADER, 0, HEADER.length)
				&& hasEofMarker(bytes);
	}

	private static boolean hasEofMarker(byte[] bytes) {
		int scanLength = Math.min(bytes.length, EOF_SCAN_BYTES);
		return new String(bytes, bytes.length - scanLength, scanLength, StandardCharsets.ISO_8859_1).contains("%%EOF");
	}
}
