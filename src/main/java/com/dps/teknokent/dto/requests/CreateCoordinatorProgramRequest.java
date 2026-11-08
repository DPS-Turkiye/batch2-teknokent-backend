package com.dps.teknokent.dto.requests;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.dps.teknokent.domain.ProgramType;
import com.dps.teknokent.dto.requests.ReplaceProgramPhasesRequest.PhaseInput;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The admin's "Yeni Program Oluştur" in one request: type, basic info and coordinators. Rules, phases and teams
 * are optional (null keeps the program defaults); the coordinators configure them after the admin creates the draft.
 */
public record CreateCoordinatorProgramRequest(
		UUID actorId,
		@NotNull ProgramType type,
		@NotBlank @Size(max = 255) String name,
		@Size(max = 2000) String description,
		LocalDate startDate,
		LocalDate endDate,
		@NotEmpty @Size(max = 10) List<UUID> coordinatorIds,
		@Min(1) @Max(20) Integer defaultMentorCapacity,
		@Min(1) @Max(90) Integer meetingFrequencyDays,
		@Min(1) @Max(180) Integer communicationGapDays,
		@Min(1) @Max(365) Integer bottleneckFlagDays,
		@Size(max = 200) List<@Valid TeamInviteInput> teams,
		@Size(max = 12) List<@Valid PhaseInput> phases,
		UUID syllabusDocumentId) {
}
