package com.dps.teknokent.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.Test;

import com.dps.teknokent.dto.responses.ProgramSessionsResponse;
import com.dps.teknokent.dto.responses.ProgramSessionsResponse.Row;

class SessionsExportTest {

	private final ProgramSessionsResponse report = new ProgramSessionsResponse(
			UUID.randomUUID(),
			LocalDate.of(2026, 11, 1),
			LocalDate.of(2026, 11, 30),
			List.of(new Row(UUID.randomUUID(), "Zeynep Şahin", "zeynep@example.edu.tr", 3, 90, 2)),
			List.of(new Row(UUID.randomUUID(), "=HYPERLINK(\"x\")", "a@x.tr, b@x.tr", 1, 50, 1)));

	@Test
	void csvHasABomOneRowPerPersonAndNoLiveFormulas() {
		String csv = new String(SessionsExport.write(report, SessionsExport.Format.CSV), StandardCharsets.UTF_8);

		assertThat(csv).startsWith("﻿Rol,Ad Soyad,E-posta,Görüşme sayısı,Toplam saat,Görüşme yapılan gün\r\n");
		assertThat(csv).contains("Mentor,Zeynep Şahin,zeynep@example.edu.tr,3,1.5,2\r\n");
		assertThat(csv).contains("Mentee,\"'=HYPERLINK(\"\"x\"\")\",\"a@x.tr, b@x.tr\",1,0.83,1\r\n");
		assertThat(SessionsExport.filename(report, SessionsExport.Format.CSV))
				.isEqualTo("gorusmeler-2026-11-01-2026-11-30.csv");
	}

	@Test
	void xlsxIsAWorkbookWithNumbersAsNumbersAndTextEscaped() throws IOException {
		Map<String, String> parts = unzip(SessionsExport.write(report, SessionsExport.Format.XLSX));

		assertThat(parts).containsKeys("[Content_Types].xml", "_rels/.rels", "xl/workbook.xml",
				"xl/_rels/workbook.xml.rels", "xl/worksheets/sheet1.xml");
		String sheet = parts.get("xl/worksheets/sheet1.xml");
		assertThat(sheet).contains("<t>Zeynep Şahin</t>", "<c><v>3</v></c><c><v>1.5</v></c><c><v>2</v></c>");
		assertThat(sheet).contains("<t>=HYPERLINK(&quot;x&quot;)</t>").doesNotContain("<f>");
	}

	private static Map<String, String> unzip(byte[] bytes) throws IOException {
		Map<String, String> parts = new HashMap<>();
		try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
			for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
				parts.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
			}
		}
		return parts;
	}
}
