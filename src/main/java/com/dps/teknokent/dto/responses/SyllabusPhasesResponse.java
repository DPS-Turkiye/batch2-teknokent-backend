package com.dps.teknokent.dto.responses;

import java.time.LocalDate;
import java.util.List;

/** Phases proposed from a syllabus. Nothing is saved until the coordinator confirms them. */
public record SyllabusPhasesResponse(List<Phase> phases, int pageCount) {

	public record Phase(String name, String expectedOutput, LocalDate startDate, LocalDate endDate) {
	}
}
