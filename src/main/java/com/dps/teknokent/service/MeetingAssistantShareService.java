package com.dps.teknokent.service;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.PublishMeetingAssistantShareRequest;
import com.dps.teknokent.dto.responses.MeetingAssistantShareResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.entity.MeetingAssistantShare;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.MeetingAssistantShareRepository;
import com.dps.teknokent.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MeetingAssistantShareService {

	private final DomainLookupService domainLookupService;
	private final MeetingAssistantShareRepository shareRepository;
	private final JsonUtils jsonUtils;

	@Transactional(readOnly = true)
	public MeetingAssistantShareResponse find(UUID meetingId, UUID organizationId) {
		return find(meetingId, organizationId, null);
	}

	@Transactional(readOnly = true)
	public MeetingAssistantShareResponse find(UUID meetingId, UUID organizationId, UUID viewerId) {
		Meeting meeting = requireVisibleMeeting(meetingId, organizationId, viewerId);
		return shareRepository.findByMeetingId(meeting.getId())
				.map(this::toResponse)
				.orElseGet(() -> MeetingAssistantShareResponse.empty(meeting.getId()));
	}

	@Transactional
	public MeetingAssistantShareResponse publish(
			UUID meetingId,
			PublishMeetingAssistantShareRequest request) {
		AppUser actor = requireApprovedMentor(request.actorId());
		Meeting meeting = domainLookupService.requireMeeting(meetingId);
		if (meeting.getMentor() == null || !meeting.getMentor().getId().equals(actor.getId())) {
			throw new InvalidRequestException("Only the meeting's mentor can publish to this notebook.");
		}

		MeetingAssistantShare share = shareRepository.findByMeetingId(meeting.getId())
				.orElseGet(() -> MeetingAssistantShare.emptyFor(meeting));
		share.setPublishedBy(actor);

		String kind = request.kind() == null ? "" : request.kind().trim().toUpperCase();
		if ("TRANSCRIPT".equals(kind)) {
			JsonNode transcript = request.transcript();
			if (transcript == null || !transcript.isArray() || transcript.isEmpty()) {
				throw new InvalidRequestException("Transcript is required.");
			}
			share.setTranscript(jsonUtils.writeValue(transcript));
			share.setTranscriptPublishedAt(Instant.now());
		} else if ("REPORT".equals(kind)) {
			JsonNode report = request.report();
			if (report == null || !report.isObject()) {
				throw new InvalidRequestException("Report is required.");
			}
			share.setReport(jsonUtils.writeValue(report));
			share.setReportPublishedAt(Instant.now());
		} else {
			throw new InvalidRequestException("kind must be TRANSCRIPT or REPORT.");
		}

		return toResponse(shareRepository.saveAndFlush(share));
	}

	private MeetingAssistantShareResponse toResponse(MeetingAssistantShare share) {
		return new MeetingAssistantShareResponse(
				share.getMeeting().getId(),
				parseOrNull(share.getTranscript()),
				share.getTranscriptPublishedAt(),
				parseOrNull(share.getReport()),
				share.getReportPublishedAt());
	}

	private JsonNode parseOrNull(String json) {
		if (json == null || json.isBlank()) {
			return null;
		}
		return jsonUtils.readTree(json);
	}

	private AppUser requireApprovedMentor(UUID actorId) {
		if (actorId == null) {
			throw new InvalidRequestException("A signed-in user is required.");
		}
		AppUser actor = domainLookupService.requireUser(actorId);
		if (actor.getRole() != UserRole.MENTOR) {
			throw new InvalidRequestException("Only a mentor can do this.");
		}
		if (actor.getStatus() != UserAccountStatus.APPROVED) {
			throw new InvalidRequestException("Only an approved mentor can publish to a notebook.");
		}
		return actor;
	}

	private Meeting requireVisibleMeeting(UUID meetingId, UUID organizationId, UUID viewerId) {
		Meeting meeting = domainLookupService.requireMeeting(meetingId);
		boolean inOrganization = organizationId != null
				&& meeting.getOrganization().getId().equals(organizationId);
		boolean meetingMentor = viewerId != null
				&& meeting.getMentor() != null
				&& meeting.getMentor().getId().equals(viewerId);
		if (!inOrganization && !meetingMentor) {
			throw new ResourceNotFoundException("Meeting not found: " + meetingId);
		}
		return meeting;
	}
}
