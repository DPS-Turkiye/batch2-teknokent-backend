package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.UUID;

import com.dps.teknokent.entity.OrganizationPendingMember;

public record OrganizationPendingMemberResponse(
		UUID id,
		String fullName,
		String jobTitle,
		String email,
		Instant createdAt) {
	public static OrganizationPendingMemberResponse fromEntity(OrganizationPendingMember member) {
		return new OrganizationPendingMemberResponse(
				member.getId(), member.getFullName(), member.getJobTitle(), member.getEmail(), member.getCreatedAt());
	}
}
