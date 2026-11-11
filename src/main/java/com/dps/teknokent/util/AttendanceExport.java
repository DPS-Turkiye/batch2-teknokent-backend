package com.dps.teknokent.util;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import com.dps.teknokent.dto.responses.ProgramAttendanceReportResponse;

/** The weekly attendance report as a file: one row per participant, one column per week, then each week's rate. */
public final class AttendanceExport {

	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM");

	private AttendanceExport() {
	}

	public static String filename(ProgramAttendanceReportResponse report, SessionsExport.Format format) {
		String range = report.from() == null || report.to() == null ? "tum" : report.from() + "-" + report.to();
		return "katilim-" + range + (format == SessionsExport.Format.CSV ? ".csv" : ".xlsx");
	}

	public static byte[] write(ProgramAttendanceReportResponse report, SessionsExport.Format format) {
		List<String> headers = new ArrayList<>(List.of("Ad Soyad", "E-posta", "Takım"));
		report.weeks().forEach(week -> headers.add(
				"Hafta " + week.number() + " (" + DAY.format(week.start()) + "–" + DAY.format(week.end()) + ")"));
		headers.add("Katıldığı hafta");

		List<List<Object>> rows = new ArrayList<>();
		for (ProgramAttendanceReportResponse.Participant person : report.participants()) {
			List<Object> row = new ArrayList<>(List.of(text(person.name()), text(person.email()), text(person.teamName())));
			person.attended().forEach(attended -> row.add(attended ? "Evet" : "Hayır"));
			row.add(person.attendedWeeks());
			rows.add(row);
		}
		List<Object> rates = new ArrayList<>(List.of("Haftalık katılım oranı", "", ""));
		report.weeks().forEach(week -> rates.add(week.rate() == null ? "" : "%" + week.rate()));
		rates.add("");
		rows.add(rates);

		return format == SessionsExport.Format.CSV
				? TableExport.csv(headers, rows)
				: TableExport.xlsx("Katılım", headers, rows);
	}

	private static String text(String value) {
		return value == null ? "" : value;
	}
}
