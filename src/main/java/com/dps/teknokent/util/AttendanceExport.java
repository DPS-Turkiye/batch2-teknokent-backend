package com.dps.teknokent.util;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import com.dps.teknokent.domain.AttendanceMethod;
import com.dps.teknokent.dto.responses.ProgramAttendanceReportResponse;

/**
 * The attendance report as a file: one row per participant, one column per session (each week's lesson and
 * workshop, and its weekly self-check-ins from before per-session attendance), then each session's rate.
 */
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
		report.sessions().forEach(session -> headers.add(header(session)));
		headers.add("Katıldığı oturum");

		List<List<Object>> rows = new ArrayList<>();
		for (ProgramAttendanceReportResponse.Participant person : report.participants()) {
			List<Object> row = new ArrayList<>(
					List.of(text(person.name()), text(person.email()), text(person.teamName())));
			person.marks().forEach(mark -> row.add(cell(mark)));
			row.add(person.attendedSessions());
			rows.add(row);
		}
		List<Object> rates = new ArrayList<>(List.of("Katılım oranı", "", ""));
		report.sessions().forEach(session -> rates.add(session.rate() == null ? "" : "%" + session.rate()));
		rates.add("");
		rows.add(rates);

		return format == SessionsExport.Format.CSV
				? TableExport.csv(headers, rows)
				: TableExport.xlsx("Katılım", headers, rows);
	}

	private static String header(ProgramAttendanceReportResponse.Session session) {
		String name = switch (session.kind()) {
		case LESSON -> "Eğitim";
		case WORKSHOP -> "Workshop";
		case WEEK -> "Haftalık (eski)";
		};
		String day = session.date() == null ? "" : " (" + DAY.format(session.date()) + ")";
		return "Hafta " + session.week() + " " + name + day;
	}

	private static String cell(ProgramAttendanceReportResponse.Mark mark) {
		AttendanceMethod method = mark.method();
		if (!mark.attended() || method == null) {
			return "Hayır";
		}
		return switch (method) {
		case QR -> "Evet (QR)";
		case MANUAL -> "Evet (koordinatör)";
		case SELF -> "Evet (haftalık)";
		};
	}

	private static String text(@Nullable String value) {
		return value == null ? "" : value;
	}
}
