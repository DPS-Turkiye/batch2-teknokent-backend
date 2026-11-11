package com.dps.teknokent.dto.requests;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.dps.teknokent.domain.ProgramType;
import com.dps.teknokent.dto.requests.ReplaceProgramPhasesRequest.PhaseInput;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The admin's "Yeni Program Oluştur" in one request: type, basic info and coordinators. Rules, phases and teams
 * are optional (null keeps the program defaults); the coordinators configure them after the admin creates the draft.
 * Coordinators are existing ones by id, emails with no account yet (invited as new coordinators), or both: at least one
 * and at most {@value #MAX_COORDINATORS} in all.
 */
public record CreateCoordinatorProgramRequest(
		UUID actorId,
		@NotNull ProgramType type,
		@NotBlank @Size(max = 255) String name,
		@Size(max = 2000) String description,
		LocalDate startDate,
		LocalDate endDate,
		@Size(max = 10) List<UUID> coordinatorIds,
		@Size(max = 10) List<@Valid NewCoordinatorInput> newCoordinators,
		@Min(1) @Max(20) Integer defaultMentorCapacity,
		@Min(1) @Max(90) Integer meetingFrequencyDays,
		@Min(1) @Max(180) Integer communicationGapDays,
		@Min(1) @Max(365) Integer bottleneckFlagDays,
		@Size(max = 200) List<@Valid TeamInviteInput> teams,
		@Size(max = 12) List<@Valid PhaseInput> phases,
		UUID syllabusDocumentId) {

	public static final int MAX_COORDINATORS = 10;

	/** Ids and invited emails together: at least one coordinator, at most {@value #MAX_COORDINATORS}. */
	@JsonIgnore
	@AssertTrue(message = "A program needs between 1 and " + MAX_COORDINATORS + " coordinators.")
	public boolean isCoordinatorCountValid() {
		int count = (coordinatorIds == null ? 0 : coordinatorIds.size())
				+ (newCoordinators == null ? 0 : newCoordinators.size());
		return count >= 1 && count <= MAX_COORDINATORS;
	}
}
