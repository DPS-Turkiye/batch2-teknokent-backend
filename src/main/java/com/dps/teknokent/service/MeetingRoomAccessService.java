package com.dps.teknokent.service;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.MeetingRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MeetingRoomAccessService {

	private final MeetingRepository meetingRepository;
	private final AppUserRepository appUserRepository;

	@Transactional(readOnly = true)
	public boolean isMeetingVisibleTo(UUID meetingId, UUID organizationId) {
		return meetingRepository.existsByIdAndOrganizationId(meetingId, organizationId);
	}

	/**
	 * Whether a socket for a sign-in that began at {@code signedInAt} may keep working in the organization's
	 * meeting rooms: the account is still approved, still on that team, and no admin ended its sign-ins since.
	 */
	@Transactional(readOnly = true)
	public boolean isStillMemberOf(UUID userId, UUID organizationId, Instant signedInAt) {
		return appUserRepository.findById(userId)
				.filter(user -> user.getStatus() == UserAccountStatus.APPROVED)
				.filter(user -> user.getOrganization() != null
						&& organizationId.equals(user.getOrganization().getId()))
				.filter(user -> user.getSignInsRevokedAt() == null
						|| user.getSignInsRevokedAt().isBefore(signedInAt))
				.isPresent();
	}
}
