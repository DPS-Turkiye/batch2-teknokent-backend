package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.UUID;

import com.dps.teknokent.domain.TeamChangeKind;
import com.dps.teknokent.domain.TeamChangeStatus;
import com.dps.teknokent.entity.TeamChangeRequest;

public record TeamChangeResponse(UUID id, TeamChangeKind kind, TeamChangeStatus status, UUID organizationId,
		String organizationName, UUID memberId, String memberName, String memberEmail, UUID requestedById,
		String requestedByName, Instant createdAt) {

	public static TeamChangeResponse of(TeamChangeRequest request) {
		return new TeamChangeResponse(request.getId(), request.getKind(), request.getStatus(),
				request.getOrganization().getId(), request.getOrganization().getName(), request.getMember().getId(),
				request.getMember().getFullName(), request.getMember().getEmail(), request.getRequestedBy().getId(),
				request.getRequestedBy().getFullName(), request.getCreatedAt());
	}
}
