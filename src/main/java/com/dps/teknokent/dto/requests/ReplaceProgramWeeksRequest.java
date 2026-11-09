package com.dps.teknokent.dto.requests;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** The program's whole weekly plan; an empty list removes it. Week numbers must be unique. */
public record ReplaceProgramWeeksRequest(
		UUID actorId,
		@NotNull @Size(max = 104) List<@Valid @NotNull WeekInput> weeks) {

	public record WeekInput(
			@Min(1) @Max(104) int week,
			@NotBlank @Size(max = 160) String title,
			@Size(max = 8) List<@NotBlank @Size(max = 200) String> topics,
			@Size(max = 8) List<@NotBlank @Size(max = 200) String> activities,
			@Size(max = 8) List<@NotBlank @Size(max = 200) String> deliverables,
			boolean inferred) {
	}
}
