package com.dps.teknokent.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

import com.dps.teknokent.dto.responses.ProgramSessionsResponse;

/** The program's sessions report as a file for İŞKUR: one row per mentor, then one per mentee. */
public final class SessionsExport {

	public enum Format {
		CSV,
		XLSX
	}

	private static final List<String> HEADERS = List.of(
			"Rol", "Ad Soyad", "E-posta", "Görüşme sayısı", "Toplam saat", "Görüşme yapılan gün");

	private SessionsExport() {
	}

	public static String filename(ProgramSessionsResponse report, Format format) {
		return "gorusmeler-" + report.from() + "-" + report.to() + (format == Format.CSV ? ".csv" : ".xlsx");
	}

	public static String contentType(Format format) {
		return format == Format.CSV
				? "text/csv; charset=UTF-8"
				: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
	}

	public static byte[] write(ProgramSessionsResponse report, Format format) {
		List<List<Object>> rows = new ArrayList<>();
		report.mentors().forEach(row -> rows.add(line("Mentor", row)));
		report.students().forEach(row -> rows.add(line("Mentee", row)));
		return format == Format.CSV ? TableExport.csv(HEADERS, rows) : TableExport.xlsx("Görüşmeler", HEADERS, rows);
	}

	private static List<Object> line(String role, ProgramSessionsResponse.Row row) {
		BigDecimal hours = BigDecimal.valueOf(row.minutes()).divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP)
				.stripTrailingZeros();
		return List.of(role, text(row.name()), text(row.email()), row.sessions(), hours, row.days());
	}

	private static String text(String value) {
		return value == null ? "" : value;
	}
}
