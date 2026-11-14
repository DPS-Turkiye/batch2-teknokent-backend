package com.dps.teknokent.dto.requests;

import java.time.YearMonth;

import com.dps.teknokent.domain.PeerExperienceKind;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** One experience card from the peer-mentor wizard; months as "yyyy-MM", the end left out while it goes on. */
public record PeerExperienceCardRequest(
		@NotNull PeerExperienceKind kind,
		@NotBlank @Size(max = 200) String title,
		@Size(max = 200) String organization,
		@NotNull YearMonth startedOn,
		YearMonth endedOn,
		@Size(max = 300) String summary) {
}
