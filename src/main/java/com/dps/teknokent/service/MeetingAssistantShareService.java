package com.dps.teknokent.service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.PublishMeetingAssistantShareRequest;
import com.dps.teknokent.dto.responses.MeetingAssistantShareResponse;
import com.dps.teknokent.dto.responses.TranscriptSegmentResponse;
import com.dps.teknokent.dto.responses.TranscriptUploadUrlResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.entity.MeetingAssistantShare;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.MeetingAssistantShareRepository;
import com.dps.teknokent.repository.MeetingRepository;
import com.dps.teknokent.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MeetingAssistantShareService {

	private final DomainLookupService domainLookupService;
	private final MeetingAssistantShareRepository shareRepository;
	private final MeetingRepository meetingRepository;
	private final JsonUtils jsonUtils;
	private final ObjectStorage objectStorage;
	private final MeetingNoteService meetingNoteService;
	private final PlatformTransactionManager transactionManager;

	@Transactional(readOnly = true)
	public MeetingAssistantShareResponse find(UUID meetingId, UUID organizationId) {
		return find(meetingId, organizationId, null);
	}

	@Transactional(readOnly = true)
	public MeetingAssistantShareResponse find(UUID meetingId, UUID organizationId, UUID viewerId) {
		Meeting meeting = requireVisibleMeeting(meetingId, organizationId, viewerId);
		boolean mentorViewer = isMeetingMentor(meeting, viewerId);
		MeetingAssistantShare share = shareRepository.findByMeetingId(meeting.getId()).orElse(null);
		if (!hasReadableShare(share, mentorViewer)) {
			share = publishedShareFromRoommates(meeting, share);
		}
		if (share == null) {
			return MeetingAssistantShareResponse.empty(meeting.getId());
		}
		return toResponse(meeting.getId(), share, mentorViewer);
	}

	/**
	 * S3 runs between two short transactions (check, then record) so its round trip does not hold a pool
	 * connection. The mentor check comes first, so nobody else can write the meeting's objects.
	 */
	public MeetingAssistantShareResponse publish(
			UUID meetingId,
			PublishMeetingAssistantShareRequest request) {
		readOnly().executeWithoutResult(status -> requireMeetingMentor(meetingId, request.actorId()));

		String kind = request.kind() == null ? "" : request.kind().trim().toUpperCase();
		boolean transcriptKind = kind.startsWith("TRANSCRIPT");
		JsonNode report = null;
		Stored stored;
		if ("TRANSCRIPT".equals(kind)) {
			JsonNode transcript = request.transcript();
			if (transcript == null || !transcript.isArray() || transcript.isEmpty()) {
				throw new InvalidRequestException("Transcript is required.");
			}
			stored = storeJson(transcriptObjectKey(meetingId), jsonUtils.writeValue(transcript));
		} else if ("TRANSCRIPT_S3".equals(kind)) {
			readUpload(transcriptObjectKey(meetingId), "Transcript", node -> node.isArray() && !node.isEmpty());
			stored = new Stored(transcriptObjectKey(meetingId), null);
		} else if ("REPORT".equals(kind)) {
			report = request.report();
			if (report == null || !report.isObject()) {
				throw new InvalidRequestException("Report is required.");
			}
			stored = storeJson(reportObjectKey(meetingId), jsonUtils.writeValue(report));
		} else if ("REPORT_S3".equals(kind)) {
			report = readUpload(reportObjectKey(meetingId), "Report", JsonNode::isObject);
			stored = new Stored(reportObjectKey(meetingId), null);
		} else {
			throw new InvalidRequestException("kind must be TRANSCRIPT, TRANSCRIPT_S3, REPORT or REPORT_S3.");
		}

		JsonNode publishedReport = report;
		return writing().execute(status -> {
			MentorMeeting checked = requireMeetingMentor(meetingId, request.actorId());
			Meeting meeting = checked.meeting();
			MeetingAssistantShare share = shareRepository.findByMeetingId(meeting.getId())
					.orElseGet(() -> MeetingAssistantShare.emptyFor(meeting));
			share.setPublishedBy(checked.actor());
			if (transcriptKind) {
				applyTranscript(share, stored);
				share.setTranscriptPublishedAt(Instant.now());
			} else {
				applyReport(share, stored);
				share.setReportPublishedAt(Instant.now());
			}
			MeetingAssistantShare saved = shareRepository.saveAndFlush(share);
			copyPublishedShareToRoommates(meeting, saved, kind);
			if (publishedReport != null) {
				for (Meeting mate : roommates(meeting)) {
					meetingNoteService.addReportActionItems(mate.getId(), publishedReport);
				}
			}
			return toResponse(meeting.getId(), saved, true);
		});
	}

	@Transactional(readOnly = true)
	public TranscriptUploadUrlResponse createTranscriptUpload(UUID meetingId, UUID actorId) {
		return createUpload(meetingId, actorId, transcriptObjectKey(meetingId));
	}

	@Transactional(readOnly = true)
	public TranscriptUploadUrlResponse createReportUpload(UUID meetingId, UUID actorId) {
		return createUpload(meetingId, actorId, reportObjectKey(meetingId));
	}

	private TranscriptUploadUrlResponse createUpload(UUID meetingId, UUID actorId, String key) {
		requireMeetingMentor(meetingId, actorId);
		if (!objectStorage.isConfigured()) {
			throw new InvalidRequestException("Object storage is not configured.");
		}
		return new TranscriptUploadUrlResponse(
				objectStorage.presignPut(key, "application/json"),
				"PUT",
				key,
				"application/json");
	}

	public boolean storeGeneratedTranscript(UUID meetingId, List<TranscriptSegmentResponse> segments) {
		if (segments == null || segments.isEmpty() || alreadyStored(meetingId, this::hasStoredTranscript)) {
			return false;
		}
		Stored stored = storeJson(transcriptObjectKey(meetingId), jsonUtils.writeValue(segments));
		return writing().execute(status -> {
			Meeting meeting = domainLookupService.requireMeeting(meetingId);
			MeetingAssistantShare share = shareRepository.findByMeetingId(meeting.getId())
					.orElseGet(() -> MeetingAssistantShare.emptyFor(meeting));
			if (hasStoredTranscript(share)) {
				return false;
			}
			applyTranscript(share, stored);
			// "Transkript gönderilsin" was the mentor's go-ahead to share it, so the team sees it without a second step.
			if (meeting.isTranscribeRequested()) {
				share.setTranscriptPublishedAt(Instant.now());
			}
			shareRepository.saveAndFlush(share);
			return true;
		});
	}

	public boolean storeGeneratedReport(UUID meetingId, Object report) {
		if (alreadyStored(meetingId, this::hasStoredReport)) {
			return false;
		}
		Stored stored = storeJson(reportObjectKey(meetingId), jsonUtils.writeValue(report));
		return writing().execute(status -> {
			Meeting meeting = domainLookupService.requireMeeting(meetingId);
			MeetingAssistantShare share = shareRepository.findByMeetingId(meeting.getId())
					.orElseGet(() -> MeetingAssistantShare.emptyFor(meeting));
			if (hasStoredReport(share)) {
				return false;
			}
			applyReport(share, stored);
			share.setReportPublishedAt(Instant.now());
			shareRepository.saveAndFlush(share);
			meetingNoteService.addReportActionItems(meeting.getId(), jsonUtils.valueToTree(report));
			return true;
		});
	}

	private boolean alreadyStored(UUID meetingId, Predicate<MeetingAssistantShare> stored) {
		return Boolean.TRUE.equals(readOnly().execute(status -> {
			Meeting meeting = domainLookupService.requireMeeting(meetingId);
			return shareRepository.findByMeetingId(meeting.getId()).filter(stored).isPresent();
		}));
	}

	/** Where a transcript or report lives: an S3 key, or the JSON itself when storage is not configured. */
	private record Stored(String s3Key, String json) {
	}

	private Stored storeJson(String key, String json) {
		if (!objectStorage.isConfigured()) {
			return new Stored(null, json);
		}
		objectStorage.put(key, json.getBytes(StandardCharsets.UTF_8), "application/json");
		return new Stored(key, null);
	}

	private JsonNode readUpload(String key, String what, Predicate<JsonNode> valid) {
		if (!objectStorage.isConfigured()) {
			throw new InvalidRequestException("Object storage is not configured.");
		}
		byte[] bytes = objectStorage.get(key);
		if (bytes == null || bytes.length == 0) {
			throw new InvalidRequestException(what + " upload was not found.");
		}
		JsonNode parsed = jsonUtils.readTree(new String(bytes, StandardCharsets.UTF_8));
		if (parsed == null || !valid.test(parsed)) {
			throw new InvalidRequestException(what + " is required.");
		}
		return parsed;
	}

	private static void applyTranscript(MeetingAssistantShare share, Stored stored) {
		if (stored.s3Key() != null) {
			share.setTranscriptS3Key(stored.s3Key());
			share.setTranscript(null);
		} else {
			share.setTranscript(stored.json());
		}
	}

	private static void applyReport(MeetingAssistantShare share, Stored stored) {
		if (stored.s3Key() != null) {
			share.setReportS3Key(stored.s3Key());
			share.setReport(null);
		} else {
			share.setReport(stored.json());
		}
	}

	private TransactionTemplate readOnly() {
		TransactionTemplate template = new TransactionTemplate(transactionManager);
		template.setReadOnly(true);
		return template;
	}

	private TransactionTemplate writing() {
		return new TransactionTemplate(transactionManager);
	}

	static String transcriptObjectKey(UUID meetingId) {
		return "meetings/" + meetingId + "/transcript.json";
	}

	static String reportObjectKey(UUID meetingId) {
		return "meetings/" + meetingId + "/report.json";
	}

	private MeetingAssistantShareResponse toResponse(
			UUID meetingId,
			MeetingAssistantShare share,
			boolean includeUnpublishedTranscript) {
		boolean showTranscript = includeUnpublishedTranscript || share.getTranscriptPublishedAt() != null;
		boolean showReport = includeUnpublishedTranscript || share.getReportPublishedAt() != null;
		StoredJson transcript = showTranscript
				? readStoredJson(share.getTranscriptS3Key(), share.getTranscript())
				: StoredJson.missing();
		StoredJson report = showReport
				? readStoredJson(share.getReportS3Key(), share.getReport())
				: StoredJson.missing();
		return new MeetingAssistantShareResponse(
				meetingId,
				transcript.body(),
				transcript.url(),
				share.getTranscriptPublishedAt(),
				report.body(),
				report.url(),
				share.getReportPublishedAt());
	}

	private boolean hasReadableShare(MeetingAssistantShare share, boolean mentorViewer) {
		return hasVisibleShare(share, mentorViewer);
	}

	private boolean hasVisibleShare(MeetingAssistantShare share, boolean mentorViewer) {
		if (share == null) {
			return false;
		}
		if (mentorViewer) {
			return hasStoredTranscript(share) || hasStoredReport(share);
		}
		return share.getTranscriptPublishedAt() != null || share.getReportPublishedAt() != null;
	}

	private MeetingAssistantShare publishedShareFromRoommates(
			Meeting meeting,
			MeetingAssistantShare current) {
		for (Meeting mate : roommates(meeting)) {
			if (mate.getId().equals(meeting.getId())) {
				continue;
			}
			MeetingAssistantShare other = shareRepository.findByMeetingId(mate.getId()).orElse(null);
			if (hasVisibleShare(other, false)) {
				return other;
			}
		}
		return current;
	}

	private void copyPublishedShareToRoommates(
			Meeting source,
			MeetingAssistantShare sourceShare,
			String kind) {
		boolean transcript = kind.startsWith("TRANSCRIPT");
		for (Meeting mate : roommates(source)) {
			if (mate.getId().equals(source.getId())) {
				continue;
			}
			MeetingAssistantShare share = shareRepository.findByMeetingId(mate.getId())
					.orElseGet(() -> MeetingAssistantShare.emptyFor(mate));
			share.setPublishedBy(sourceShare.getPublishedBy());
			if (transcript) {
				share.setTranscriptS3Key(sourceShare.getTranscriptS3Key());
				share.setTranscript(sourceShare.getTranscript());
				share.setTranscriptPublishedAt(sourceShare.getTranscriptPublishedAt());
			} else {
				share.setReportS3Key(sourceShare.getReportS3Key());
				share.setReport(sourceShare.getReport());
				share.setReportPublishedAt(sourceShare.getReportPublishedAt());
			}
			shareRepository.saveAndFlush(share);
		}
	}

	private List<Meeting> roommates(Meeting meeting) {
		return MeetingRooms.of(meeting, meetingRepository);
	}

	private boolean hasStoredTranscript(MeetingAssistantShare share) {
		return hasText(share.getTranscriptS3Key()) || hasText(share.getTranscript());
	}

	private boolean hasStoredReport(MeetingAssistantShare share) {
		return hasText(share.getReportS3Key()) || hasText(share.getReport());
	}

	private boolean hasText(String value) {
		return value != null && !value.isBlank();
	}

	private StoredJson readStoredJson(String s3Key, String jsonb) {
		if (hasText(s3Key) && objectStorage.isConfigured()) {
			try {
				String url = objectStorage.presignGet(s3Key);
				if (hasText(url)) {
					return new StoredJson(null, url);
				}
			} catch (RuntimeException ignored) {
				return new StoredJson(parseOrNull(jsonb), null);
			}
		}
		return new StoredJson(parseOrNull(jsonb), null);
	}

	private record StoredJson(JsonNode body, String url) {
		static StoredJson missing() {
			return new StoredJson(null, null);
		}
	}

	private JsonNode parseOrNull(String json) {
		if (json == null || json.isBlank()) {
			return null;
		}
		try {
			return jsonUtils.readTree(json);
		} catch (RuntimeException ignored) {
			return null;
		}
	}

	private boolean isMeetingMentor(Meeting meeting, UUID viewerId) {
		return viewerId != null
				&& meeting.getMentor() != null
				&& meeting.getMentor().getId().equals(viewerId);
	}

	private record MentorMeeting(AppUser actor, Meeting meeting) {
	}

	private MentorMeeting requireMeetingMentor(UUID meetingId, UUID actorId) {
		AppUser actor = requireApprovedMentor(actorId);
		Meeting meeting = domainLookupService.requireMeeting(meetingId);
		if (meeting.getMentor() == null || !meeting.getMentor().getId().equals(actor.getId())) {
			throw new InvalidRequestException("Only the meeting's mentor can publish to this notebook.");
		}
		return new MentorMeeting(actor, meeting);
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
		boolean meetingMentor = isMeetingMentor(meeting, viewerId);
		if (!inOrganization && !meetingMentor) {
			throw new ResourceNotFoundException("Meeting not found: " + meetingId);
		}
		return meeting;
	}
}
