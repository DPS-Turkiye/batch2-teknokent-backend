package com.dps.teknokent.service;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.dps.teknokent.domain.MeetingStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.MeetingRepository;

import lombok.RequiredArgsConstructor;

/**
 * Who may use the meeting assistant (bot, live transcript, report) for a Meet code: an admin, or a participant of a
 * Menton meeting on that code, meaning its mentor or a member of its team. The code is all the provider knows, so every
 * meeting row that shares it (a guest team's copy, a reused link) counts.
 */
@Service
@RequiredArgsConstructor
public class AssistantMeetingAccessService {

	private final MeetingRepository meetingRepository;
	private final AppUserRepository appUserRepository;

	/**
	 * @param callerId the signed-in user from a verified bearer token; null when the request came on the shared
	 *                 service credentials, which name nobody and so are refused
	 */
	@Transactional(readOnly = true)
	public void requireAccess(UUID callerId, String platform, String nativeMeetingId) {
		if (callerId == null) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "A signed-in user is required.");
		}
		List<Meeting> meetings = meetingsFor(platform, nativeMeetingId);
		if (meetings.isEmpty()) {
			throw new ResourceNotFoundException("Meeting not found.");
		}
		AppUser caller = appUserRepository.findById(callerId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "A signed-in user is required."));
		if (caller.has(UserRole.ADMIN)) {
			return;
		}
		UUID callerOrganizationId = caller.getOrganization() == null ? null : caller.getOrganization().getId();
		boolean participant = meetings.stream()
				.anyMatch(meeting -> isMentor(meeting, callerId) || isTeamMember(meeting, callerOrganizationId));
		if (!participant) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN,
					"Only a participant of this meeting can use its assistant.");
		}
	}

	private List<Meeting> meetingsFor(String platform, String nativeMeetingId) {
		var parsed = MeetingUrlParser.tryParse(nativeMeetingId).orElse(null);
		if (parsed == null || platform == null
				|| !parsed.platform().equals(platform.trim().toLowerCase(Locale.ROOT))) {
			return List.of();
		}
		String code = parsed.nativeMeetingId();
		return meetingRepository.findByMeetLinkContainingIgnoreCase(code).stream()
				.filter(meeting -> meeting.getStatus() != MeetingStatus.CANCELLED)
				.filter(meeting -> code.equalsIgnoreCase(MeetingRooms.nativeMeetingId(meeting)))
				.toList();
	}

	private static boolean isMentor(Meeting meeting, UUID callerId) {
		return meeting.getMentor() != null && callerId.equals(meeting.getMentor().getId());
	}

	private static boolean isTeamMember(Meeting meeting, UUID callerOrganizationId) {
		return callerOrganizationId != null
				&& meeting.getOrganization() != null
				&& callerOrganizationId.equals(meeting.getOrganization().getId());
	}
}
