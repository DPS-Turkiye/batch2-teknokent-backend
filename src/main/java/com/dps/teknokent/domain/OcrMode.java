package com.dps.teknokent.domain;

import java.util.Optional;

/** Which PDF pages go through local OCR. The frontend sends the lowercase wire value. */
public enum OcrMode {
	/** Only the text layer the PDF itself carries. */
	OFF("off"),
	/** OCR for the pages whose text layer is almost empty, as a scan's is. */
	AUTO("auto"),
	/** OCR for every page, for a PDF whose text layer is wrong. */
	FORCE("force");

	private final String wireValue;

	OcrMode(String wireValue) {
		this.wireValue = wireValue;
	}

	public String wireValue() {
		return wireValue;
	}

	/** Exact match only, as before the enum: "AUTO" or " auto" was refused then too. */
	public static Optional<OcrMode> fromWire(String value) {
		for (OcrMode mode : values()) {
			if (mode.wireValue.equals(value)) {
				return Optional.of(mode);
			}
		}
		return Optional.empty();
	}
}
