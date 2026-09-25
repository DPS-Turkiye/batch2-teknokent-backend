package com.dps.teknokent.dto.responses;

import java.util.UUID;

import com.dps.teknokent.domain.CoordinatorCandidateStatus;
import com.dps.teknokent.domain.UserAccountStatus;

/** {@code outcome} is what the email was before: NEW (created), PROMOTABLE (promoted) or REACTIVATABLE. */
public record CoordinatorAccountResponse(
		UUID id,
		String email,
		String fullName,
		UserAccountStatus status,
		CoordinatorCandidateStatus outcome) {
}
