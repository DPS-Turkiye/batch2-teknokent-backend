package com.dps.teknokent.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.MeetingStatus;
import com.dps.teknokent.domain.MentorshipRequestStatus;
import com.dps.teknokent.domain.NotificationCategory;
import com.dps.teknokent.domain.TranscriptBotConstants;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.CreateMeetingRequest;
import com.dps.teknokent.dto.requests.InviteMeetingGuestRequest;
import com.dps.teknokent.dto.requests.LogMeetingRequest;
import com.dps.teknokent.dto.requests.SubmitMeetingFeedbackRequest;
import com.dps.teknokent.dto.requests.UpdateMeetingRequest;
import com.dps.teknokent.dto.responses.MeetingFeedbackResponse;
import com.dps.teknokent.dto.responses.MeetingResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.MeetingFeedback;
import com.dps.teknokent.entity.MentorshipRequest;
import com.dps.teknokent.exception.ConflictException;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.MeetingFeedbackRepository;
import com.dps.teknokent.repository.MeetingRepository;
import com.dps.teknokent.repository.MentorshipRequestRepository;
import com.dps.teknokent.util.MeetingSlotTimes;
import com.dps.teknokent.util.TextUtils;

@Service
public class MeetingService {

	private final MeetingRepository meetingRepository;
	private final MentorshipRequestRepository mentorshipRequestRepository;
	private final DomainLookupService domainLookupService;
	private final MeetingFeedbackRepository meetingFeedbackRepository;
	private final AppUserRepository appUserRepository;
	private final NotificationService notificationService;
	private final DocumentRequestService documentRequestService;
	private final TranscriptBotCapacityService transcriptBotCapacity;

	public MeetingService(
			MeetingRepository meetingRepository,
			MentorshipRequestRepository mentorshipRequestRepository,
			DomainLookupService domainLookupService,
			MeetingFeedbackRepository meetingFeedbackRepository,
			AppUserRepository appUserRepository,
			NotificationService notificationService,
			DocumentRequestService documentRequestService,
			TranscriptBotCapacityService transcriptBotCapacity) {
		this.meetingRepository = meetingRepository;
		this.mentorshipRequestRepository = mentorshipRequestRepository;
		this.domainLookupService = domainLookupService;
		this.meetingFeedbackRepository = meetingFeedbackRepository;
		this.appUserRepository = appUserRepository;
		this.notificationService = notificationService;
		this.documentRequestService = documentRequestService;
		this.transcriptBotCapacity = transcriptBotCapacity;
	}

	@Transactional(readOnly = true)
	public List<MeetingResponse> findAll(UUID organizationId, UUID mentorId) {
		List<Meeting> meetings;
		if (mentorId != null) {
			meetings = meetingRepository.findByMentorIdOrderByScheduledAtDesc(mentorId);
		} else if (organizationId != null) {
			meetings = meetingRepository.findByOrganizationIdOrderByScheduledAtDesc(organizationId);
		} else {
			meetings = meetingRepository.findAllByOrderByScheduledAtDesc();
		}
		return meetings.stream()
				.map(MeetingResponse::fromEntity)
				.toList();
	}

	@Transactional
	public MeetingResponse create(CreateMeetingRequest request) {
		AppUser mentor = null;
		if (request.mentorId() != null) {
			mentor = domainLookupService.requireUser(request.mentorId());
			if (mentor.getRole() != UserRole.MENTOR) {
				throw new InvalidRequestException("Selected user does not have the MENTOR role");
			}
		}
		Organization organization = domainLookupService.requireOrganization(request.organizationId());
		Meeting meeting = Meeting.fromRequest(request, organization, mentor);
		if (mentor != null) {
			meeting.setMentorshipRequest(resolveAcceptedMentorship(organization, mentor, request.agenda()));
		}
		copyBotDispatchIfSameLink(meeting);
		transcriptBotCapacity.releaseIfFull(meeting);
		Meeting saved = meetingRepository.save(meeting);
		notifyOrgMentees(saved);
		if (mentor != null) {
			documentRequestService.saveTitlesOnMeeting(saved, mentor, request.requestedDocuments());
		}
		return MeetingResponse.fromEntity(saved);
	}

	private void notifyOrgMentees(Meeting meeting) {
		if (meeting.getOrganization() == null) {
			return;
		}
		String mentorName = meeting.getMentor() == null
				? "Mentörünüz"
				: meeting.getMentor().getFullName();
		String when = meeting.getScheduledAt() == null
				? ""
				: " · " + MeetingSlotTimes.formatForPeople(meeting.getScheduledAt());
		String body = mentorName
				+ ", "
				+ meeting.getOrganization().getName()
				+ " için yeni bir görüşme planladı"
				+ when
				+ ".";
		appUserRepository.findByOrganizationIdOrderByFullNameAsc(meeting.getOrganization().getId()).stream()
				.filter(user -> user.getRole() == UserRole.MENTEE)
				.forEach(mentee -> notificationService.create(
						mentee,
						NotificationCategory.MEETING_CHANGE,
						"Yeni görüşme planlandı",
						body,
						"/mentorships?tab=active"));
	}

	private MentorshipRequest resolveAcceptedMentorship(
			Organization organization,
			AppUser mentor,
			String agenda) {
		Optional<MentorshipRequest> accepted = mentorshipRequestRepository
				.findFirstByMentorIdAndOrganizationIdAndStatusOrderByCreatedAtDesc(
						mentor.getId(), organization.getId(), MentorshipRequestStatus.ACCEPTED);
		if (accepted.isPresent()
				&& meetingRepository.findByMentorshipRequestId(accepted.get().getId()).isEmpty()) {
			return accepted.get();
		}

		for (MentorshipRequestStatus open : List.of(
				MentorshipRequestStatus.PENDING,
				MentorshipRequestStatus.MORE_INFO_REQUESTED,
				MentorshipRequestStatus.COUNTER_PROPOSED)) {
			Optional<MentorshipRequest> openRequest = mentorshipRequestRepository
					.findFirstByMentorIdAndOrganizationIdAndStatusOrderByCreatedAtDesc(
							mentor.getId(), organization.getId(), open);
			if (openRequest.isPresent()
					&& meetingRepository.findByMentorshipRequestId(openRequest.get().getId()).isEmpty()) {
				MentorshipRequest request = openRequest.get();
				request.recordDecision(MentorshipRequestStatus.ACCEPTED, "Takvimden oluşturuldu");
				return mentorshipRequestRepository.saveAndFlush(request);
			}
		}

		AppUser requester = domainLookupService.requireOrganizationMentee(organization.getId());
		MentorshipRequest created = MentorshipRequest.forCalendarMeeting(
				requester, organization, mentor, agenda);
		return mentorshipRequestRepository.saveAndFlush(created);
	}

	@Transactional(readOnly = true)
	public List<MeetingResponse> findRoom(UUID meetingId) {
		return roommates(requireMeeting(meetingId)).stream()
				.map(MeetingResponse::fromEntity)
				.toList();
	}

	@Transactional
	public MeetingResponse inviteGuest(UUID meetingId, InviteMeetingGuestRequest request) {
		if (request.organizationId() == null && request.mentorId() == null) {
			throw new InvalidRequestException("A startup or mentor is required.");
		}
		Meeting source = requireMeeting(meetingId);
		Organization organization = request.organizationId() == null
				? source.getOrganization()
				: domainLookupService.requireOrganization(request.organizationId());
		AppUser mentor = request.mentorId() == null
				? source.getMentor()
				: requireMentor(request.mentorId());
		if (mentor != null) {
			Optional<Meeting> existing = meetingRepository.findByMentorIdAndOrganizationIdAndScheduledAt(
					mentor.getId(), organization.getId(), source.getScheduledAt());
			if (existing.isPresent()) {
				return MeetingResponse.fromEntity(existing.get());
			}
		}
		Meeting copy = source.copyFor(organization, mentor);
		copyBotDispatchIfSameLink(copy);
		return MeetingResponse.fromEntity(meetingRepository.save(copy));
	}

	@Transactional
	public void deleteRoom(UUID meetingId) {
		List<Meeting> room = roommates(requireMeeting(meetingId));
		meetingRepository.deleteAll(room);
	}

	@Transactional(readOnly = true)
	public List<MeetingResponse> findTimelineForRequest(UUID mentorshipRequestId) {
		MentorshipRequest request = requireMentorshipRequest(mentorshipRequestId);
		return meetingRepository
				.findByMentorIdAndOrganizationIdOrderByScheduledAtDesc(
						request.getMentor().getId(), request.getOrganization().getId())
				.stream()
				.map(MeetingResponse::fromEntity)
				.toList();
	}

	@Transactional
	public MeetingResponse logMeeting(UUID mentorshipRequestId, LogMeetingRequest logRequest) {
		MentorshipRequest request = requireMentorshipRequest(mentorshipRequestId);
		if (request.getStatus() != MentorshipRequestStatus.ACCEPTED) {
			throw new InvalidRequestException("Meetings can only be logged for accepted requests");
		}
		if (!request.getMentor().getId().equals(logRequest.mentorId())) {
			throw new InvalidRequestException("Only the assigned mentor can log a meeting for this request");
		}
		Meeting meeting = meetingRepository.findByMentorshipRequestId(request.getId()).orElse(null);
		if (meeting == null) {
			meeting = Meeting.forLoggedEntry(
					request,
					logRequest.scheduledAt(),
					logRequest.durationMinutes(),
					logRequest.agenda(),
					logRequest.notes(),
					logRequest.outcomes(),
					logRequest.status(),
					logRequest.meetLink(),
					Boolean.TRUE.equals(logRequest.transcribeRequested()));
		} else {
			if (logRequest.scheduledAt() != null) {
				meeting.setScheduledAt(logRequest.scheduledAt());
			}
			if (logRequest.durationMinutes() != null) {
				meeting.setDurationMinutes(logRequest.durationMinutes());
			}
			if (logRequest.agenda() != null) {
				meeting.setAgenda(TextUtils.normalizeOptional(logRequest.agenda()));
			}
			if (logRequest.notes() != null) {
				meeting.setNotes(TextUtils.normalizeOptional(logRequest.notes()));
			}
			if (logRequest.outcomes() != null) {
				meeting.setOutcomes(TextUtils.normalizeOptional(logRequest.outcomes()));
			}
			if (logRequest.status() != null) {
				meeting.setStatus(logRequest.status());
			}
			if (logRequest.meetLink() != null) {
				meeting.setMeetLink(TextUtils.normalizeOptional(logRequest.meetLink()));
			}
			if (logRequest.transcribeRequested() != null) {
				meeting.setTranscribeRequested(Boolean.TRUE.equals(logRequest.transcribeRequested()));
			}
		}
		copyBotDispatchIfSameLink(meeting);
		if (Boolean.TRUE.equals(logRequest.transcribeRequested())) {
			transcriptBotCapacity.releaseIfFull(meeting);
		}
		Meeting saved = meetingRepository.saveAndFlush(meeting);
		documentRequestService.copyFromRequestToMeeting(request, saved);
		return MeetingResponse.fromEntity(saved);
	}

	/** For a meeting moved outside this service: drops its bot when the new slot is already full. */
	public boolean releaseTranscriptBotIfFull(Meeting meeting) {
		return transcriptBotCapacity.releaseIfFull(meeting);
	}

	@Transactional
	public MeetingResponse update(UUID meetingId, UpdateMeetingRequest request) {
		Meeting meeting = meetingRepository.findById(meetingId)
				.orElseThrow(() -> new ResourceNotFoundException("Meeting not found: " + meetingId));
		if (request.notes() != null) {
			meeting.setNotes(request.notes());
		}
		if (request.outcomes() != null) {
			meeting.setOutcomes(request.outcomes());
		}
		if (request.status() != null) {
			meeting.setStatus(request.status());
		}
		if (request.meetLink() != null) {
			meeting.setMeetLink(TextUtils.normalizeOptional(request.meetLink()));
			copyBotDispatchIfSameLink(meeting);
		}
		if (request.transcribeRequested() != null) {
			meeting.setTranscribeRequested(Boolean.TRUE.equals(request.transcribeRequested()));
			transcriptBotCapacity.releaseIfFull(meeting);
		}
		if (Boolean.TRUE.equals(request.transcriptBotDispatched())
				|| request.status() == MeetingStatus.COMPLETED) {
			Instant dispatched = meeting.getBotDispatchedAt() == null
					? Instant.now()
					: meeting.getBotDispatchedAt();
			List<Meeting> room = roommates(meeting);
			for (Meeting mate : room) {
				if (mate.getBotDispatchedAt() == null) {
					mate.setBotDispatchedAt(dispatched);
				}
				if (request.status() != null) {
					mate.setStatus(request.status());
				}
			}
			meetingRepository.saveAll(room);
		}
		return MeetingResponse.fromEntity(meetingRepository.saveAndFlush(meeting));
	}

	@Transactional
	public List<UUID> markBotStopped(String nativeMeetingId) {
		if (nativeMeetingId == null || nativeMeetingId.isBlank()) {
			return List.of();
		}
		String id = nativeMeetingId.trim();
		Instant now = Instant.now();
		List<Meeting> stopped = meetingRepository
				.findByStatusAndMeetLinkContainingIgnoreCase(MeetingStatus.SCHEDULED, id)
				.stream()
				.filter(meeting -> id.equalsIgnoreCase(nativeMeetingIdOf(meeting)))
				// An ended slot that had a bot is closed too; left SCHEDULED, the watchdog stopped its bot every 30 s.
				.filter(meeting -> isRunningSlot(meeting, now)
						|| (meeting.getBotDispatchedAt() != null && hasEnded(meeting, now)))
				.toList();
		for (Meeting meeting : stopped) {
			meeting.setStatus(MeetingStatus.COMPLETED);
			if (meeting.getBotDispatchedAt() == null) {
				meeting.setBotDispatchedAt(now);
			}
		}
		meetingRepository.saveAll(stopped);
		return stopped.stream().map(Meeting::getId).toList();
	}

	private static String nativeMeetingIdOf(Meeting meeting) {
		String meetLink = meeting.getMeetLink();
		if (meetLink == null || meetLink.isBlank()) {
			return null;
		}
		try {
			return MeetingUrlParser.parse(meetLink).nativeMeetingId();
		} catch (RuntimeException unsupportedLink) {
			return null;
		}
	}

	private static boolean hasEnded(Meeting meeting, Instant now) {
		Instant start = meeting.getScheduledAt();
		return start != null && now.isAfter(start.plus(durationOf(meeting)
				+ TranscriptBotConstants.DISPATCH_GRACE_MINUTES, ChronoUnit.MINUTES));
	}

	private static int durationOf(Meeting meeting) {
		return meeting.getDurationMinutes() == null
				? TranscriptBotConstants.DEFAULT_DURATION_MINUTES
				: meeting.getDurationMinutes();
	}

	private static boolean isRunningSlot(Meeting meeting, Instant now) {
		Instant start = meeting.getScheduledAt();
		if (start == null || start.isAfter(now.plus(TranscriptBotConstants.BOT_JOIN_LEAD_MINUTES, ChronoUnit.MINUTES))) {
			return false;
		}
		return !hasEnded(meeting, now);
	}

	@Transactional
	public MeetingFeedbackResponse submitFeedback(
			UUID meetingId, UUID authorId, SubmitMeetingFeedbackRequest request) {
		Meeting meeting = domainLookupService.requireMeeting(meetingId);
		AppUser author = domainLookupService.requireUser(authorId);
		requireMeetingParticipant(meeting, author);

		if (meetingFeedbackRepository.findByMeetingIdAndAuthorId(meetingId, authorId).isPresent()) {
			throw new ConflictException("Feedback already submitted for this meeting.");
		}

		MeetingFeedback feedback = new MeetingFeedback();
		feedback.setMeeting(meeting);
		feedback.setAuthor(author);
		feedback.setCreatedAt(Instant.now());
		feedback.getRatings().addAll(request.ratings());
		feedback.getBenefits().addAll(request.benefits());

		return MeetingFeedbackResponse.fromEntity(meetingFeedbackRepository.saveAndFlush(feedback));
	}

	@Transactional(readOnly = true)
	public MeetingFeedbackResponse findFeedback(UUID meetingId, UUID authorId) {
		return meetingFeedbackRepository.findByMeetingIdAndAuthorId(meetingId, authorId)
				.map(MeetingFeedbackResponse::fromEntity)
				.orElseThrow(() -> new ResourceNotFoundException(
						"Feedback not found for meeting: " + meetingId));
	}

	private void requireMeetingParticipant(Meeting meeting, AppUser author) {
		boolean isMentor = meeting.getMentor() != null
				&& meeting.getMentor().getId().equals(author.getId());
		boolean isOrganizationMember = author.getOrganization() != null
				&& meeting.getOrganization() != null
				&& author.getOrganization().getId().equals(meeting.getOrganization().getId());
		if (!isMentor && !isOrganizationMember) {
			throw new InvalidRequestException("Only a meeting participant can leave feedback.");
		}
	}

	private MentorshipRequest requireMentorshipRequest(UUID mentorshipRequestId) {
		return mentorshipRequestRepository.findById(mentorshipRequestId)
				.orElseThrow(() -> new ResourceNotFoundException(
						"Mentorship request not found: " + mentorshipRequestId));
	}

	private Meeting requireMeeting(UUID meetingId) {
		return meetingRepository.findById(meetingId)
				.orElseThrow(() -> new ResourceNotFoundException("Meeting not found: " + meetingId));
	}

	private AppUser requireMentor(UUID mentorId) {
		AppUser mentor = domainLookupService.requireUser(mentorId);
		if (mentor.getRole() != UserRole.MENTOR) {
			throw new InvalidRequestException("Selected user does not have the MENTOR role");
		}
		return mentor;
	}

	private List<Meeting> roommates(Meeting meeting) {
		return MeetingRooms.of(meeting, meetingRepository);
	}

	private void copyBotDispatchIfSameLink(Meeting meeting) {
		String meetLink = meeting.getMeetLink();
		if (meetLink == null || meetLink.isBlank() || meeting.getBotDispatchedAt() != null) {
			return;
		}
		meetingRepository.findFirstByMeetLinkAndBotDispatchedAtIsNotNull(meetLink)
				.ifPresent(existing -> meeting.setBotDispatchedAt(existing.getBotDispatchedAt()));
	}
}
