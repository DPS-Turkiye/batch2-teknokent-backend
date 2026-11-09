package com.dps.teknokent.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Writes a table as CSV or as a minimal XLSX, without a spreadsheet library. Shared by the report exports. */
public final class TableExport {

	private TableExport() {
	}

	/** CSV with a BOM, so Excel opens the Turkish letters as UTF-8. */
	public static byte[] csv(List<String> headers, List<List<Object>> rows) {
		StringBuilder out = new StringBuilder("﻿");
		appendCsvLine(out, headers);
		rows.forEach(row -> appendCsvLine(out, row));
		return out.toString().getBytes(StandardCharsets.UTF_8);
	}

	private static void appendCsvLine(StringBuilder out, List<?> cells) {
		for (int i = 0; i < cells.size(); i++) {
			if (i > 0) {
				out.append(',');
			}
			Object cell = cells.get(i);
			out.append(cell instanceof BigDecimal number ? number.toPlainString() : csvText(String.valueOf(cell)));
		}
		out.append("\r\n");
	}

	/** Quotes when needed, and keeps a name like "=1+1" from running as a formula when Excel opens the file. */
	private static String csvText(String value) {
		String safe = !value.isEmpty() && "=+-@\t\r".indexOf(value.charAt(0)) >= 0 ? "'" + value : value;
		if (safe.contains(",") || safe.contains("\"") || safe.contains("\n") || safe.contains("\r")) {
			return "\"" + safe.replace("\"", "\"\"") + "\"";
		}
		return safe;
	}

	/** A one-sheet workbook; numbers stay numbers, everything else is inline text. */
	public static byte[] xlsx(String sheetName, List<String> headers, List<List<Object>> rows) {
		StringBuilder sheet = new StringBuilder(
				"<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
						+ "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>");
		appendXlsxRow(sheet, headers);
		rows.forEach(row -> appendXlsxRow(sheet, row));
		sheet.append("</sheetData></worksheet>");

		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
			entry(zip, "[Content_Types].xml",
					"<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
							+ "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
							+ "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
							+ "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
							+ "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
							+ "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
							+ "</Types>");
			entry(zip, "_rels/.rels",
					"<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
							+ "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
							+ "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
							+ "</Relationships>");
			entry(zip, "xl/workbook.xml",
					"<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
							+ "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
							+ "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
							+ "<sheets><sheet name=\"" + xml(sheetName) + "\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
			entry(zip, "xl/_rels/workbook.xml.rels",
					"<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
							+ "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
							+ "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>"
							+ "</Relationships>");
			entry(zip, "xl/worksheets/sheet1.xml", sheet.toString());
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return bytes.toByteArray();
	}

	private static void appendXlsxRow(StringBuilder sheet, List<?> cells) {
		sheet.append("<row>");
		for (Object cell : cells) {
			if (cell instanceof Number number) {
				String value = number instanceof BigDecimal decimal ? decimal.toPlainString() : number.toString();
				sheet.append("<c><v>").append(value).append("</v></c>");
			} else {
				sheet.append("<c t=\"inlineStr\"><is><t>").append(xml(String.valueOf(cell))).append("</t></is></c>");
			}
		}
		sheet.append("</row>");
	}

	private static String xml(String value) {
		StringBuilder out = new StringBuilder(value.length());
		for (char c : value.toCharArray()) {
			switch (c) {
				case '&' -> out.append("&amp;");
				case '<' -> out.append("&lt;");
				case '>' -> out.append("&gt;");
				case '"' -> out.append("&quot;");
				default -> {
					if (c >= 0x20 || c == '\t' || c == '\n' || c == '\r') {
						out.append(c);
					}
				}
			}
		}
		return out.toString();
	}

	private static void entry(ZipOutputStream zip, String name, String content) throws IOException {
		zip.putNextEntry(new ZipEntry(name));
		zip.write(content.getBytes(StandardCharsets.UTF_8));
		zip.closeEntry();
	}
}
