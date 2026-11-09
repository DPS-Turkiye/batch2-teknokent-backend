package com.dps.teknokent.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
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
import com.dps.teknokent.util.Texts;

@Service
public class MeetingService {

	private static final int MAX_RATED_LOOKUP = 50;

	private final MeetingRepository meetingRepository;
	private final MentorshipRequestRepository mentorshipRequestRepository;
	private final DomainLookupService domainLookupService;
	private final MeetingFeedbackRepository meetingFeedbackRepository;
	private final AppUserRepository appUserRepository;
	private final NotificationService notificationService;
	private final DocumentRequestService documentRequestService;
	private final TranscriptBotCapacityService transcriptBotCapacity;
	private final ApplicationEventPublisher eventPublisher;

	public MeetingService(
			MeetingRepository meetingRepository,
			MentorshipRequestRepository mentorshipRequestRepository,
			DomainLookupService domainLookupService,
			MeetingFeedbackRepository meetingFeedbackRepository,
			AppUserRepository appUserRepository,
			NotificationService notificationService,
			DocumentRequestService documentRequestService,
			TranscriptBotCapacityService transcriptBotCapacity,
			ApplicationEventPublisher eventPublisher) {
		this.meetingRepository = meetingRepository;
		this.mentorshipRequestRepository = mentorshipRequestRepository;
		this.domainLookupService = domainLookupService;
		this.meetingFeedbackRepository = meetingFeedbackRepository;
		this.appUserRepository = appUserRepository;
		this.notificationService = notificationService;
		this.documentRequestService = documentRequestService;
		this.transcriptBotCapacity = transcriptBotCapacity;
		this.eventPublisher = eventPublisher;
	}

	@Transactional(readOnly = true)
	public List<MeetingResponse> findAll(UUID organizationId, UUID mentorId) {
		List<Meeting> meetings;
		if (mentorId != null) {
			meetings = meetingRepository.findByMentorIdOrderByScheduledAtDesc(mentorId);
		} else if (organizationId != null) {
			meetings = meetingRepository.findByOrganizationIdOrderByScheduledAtDesc(organizationId);
		} else {
			// A session without a startup sends a blank organization, which arrives as null: not "every meeting".
			throw new InvalidRequestException("mentorId or organizationId is required");
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
			if (!mentor.has(UserRole.MENTOR)) {
				throw new InvalidRequestException("Selected user does not have the MENTOR role");
			}
		}
		Organization organization = domainLookupService.requireOrganization(request.organizationId());
		Meeting meeting = Meeting.fromRequest(request, organization, mentor);
		if (mentor != null) {
			meeting.setMentorshipRequest(resolveAcceptedMentorship(organization, mentor, request.agenda()));
		}
		copyBotDispatchIfSameLink(meeting);
		transcriptBotCapacity.requireSlot(meeting);
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
		appUserRepository.findByOrganizationIdOrderByFullNameAsc(meeting.getOrganization().getId()).stream()
				.filter(user -> user.has(UserRole.MENTEE))
				.forEach(mentee -> notificationService.create(
						mentee,
						NotificationCategory.MEETING_CHANGE,
						Texts.get(mentee, "notification.meetingPlanned.title"),
						Texts.get(mentee, "notification.meetingPlanned.body",
								meeting.getMentor() == null
										? Texts.get(mentee, "notification.yourMentor")
										: meeting.getMentor().getFullName(),
								meeting.getOrganization().getName(),
								meeting.getScheduledAt() == null
										? ""
										: " · " + MeetingSlotTimes.formatForPeople(meeting.getScheduledAt(), mentee.getPreferredLanguage())),
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
		BotSlot before = meeting == null ? null : BotSlot.of(meeting);
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
		// Only a new time or a newly asked-for bot is checked, so editing notes never trips on an older booking.
		if (!BotSlot.of(meeting).equals(before)) {
			transcriptBotCapacity.requireSlot(meeting);
		}
		Meeting saved = meetingRepository.saveAndFlush(meeting);
		documentRequestService.copyFromRequestToMeeting(request, saved);
		return MeetingResponse.fromEntity(saved);
	}

	/** For a recorded meeting about to move outside this service: refuses when its new time has no free bot. */
	public void requireTranscriptBotSlot(Meeting meeting, Instant start, Integer durationMinutes) {
		transcriptBotCapacity.requireSlot(meeting, start, durationMinutes);
	}

	private record BotSlot(Instant start, Integer durationMinutes, boolean recorded) {
		static BotSlot of(Meeting meeting) {
			return new BotSlot(meeting.getScheduledAt(), meeting.getDurationMinutes(), meeting.isTranscribeRequested());
		}
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
			boolean wasRecorded = meeting.isTranscribeRequested();
			meeting.setTranscribeRequested(Boolean.TRUE.equals(request.transcribeRequested()));
			if (!wasRecorded) {
				transcriptBotCapacity.requireSlot(meeting);
			}
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

		MeetingFeedbackResponse saved = MeetingFeedbackResponse.fromEntity(meetingFeedbackRepository.saveAndFlush(feedback));
		Set<UUID> affected = new HashSet<>(Set.of(authorId));
		if (meeting.getMentor() != null) {
			affected.add(meeting.getMentor().getId());
		}
		eventPublisher.publishEvent(new AchievementsMayChangeEvent(affected));
		return saved;
	}

	/**
	 * The mentor's "Görüşme yapıldı" on a meeting that has started, or its undo. It covers the whole room, so a
	 * group session counts once for the mentor and once for each team in it.
	 */
	@Transactional
	public MeetingResponse confirmHeld(UUID meetingId, UUID mentorId, boolean held) {
		Meeting meeting = requireMeeting(meetingId);
		if (meeting.getMentor() == null || !meeting.getMentor().getId().equals(mentorId)) {
			throw new InvalidRequestException("Only the meeting's mentor can confirm that it was held.");
		}
		if (held && meeting.getStatus() == MeetingStatus.CANCELLED) {
			throw new InvalidRequestException("A cancelled meeting cannot be confirmed as held.");
		}
		Instant now = Instant.now();
		if (held && meeting.getScheduledAt().isAfter(now)) {
			throw new InvalidRequestException("A meeting can be confirmed as held only after it starts.");
		}
		// A reused Meet link joins older meetings into the room too, so only the same mentor's same slot counts.
		List<Meeting> room = roommates(meeting).stream()
				.filter(mate -> mate.getMentor() != null && mate.getMentor().getId().equals(mentorId))
				.filter(mate -> meeting.getScheduledAt().equals(mate.getScheduledAt()))
				.toList();
		for (Meeting mate : room) {
			if (!held) {
				mate.setHeldConfirmedAt(null);
			} else if (mate.getHeldConfirmedAt() == null && mate.getStatus() != MeetingStatus.CANCELLED) {
				mate.setHeldConfirmedAt(now);
			}
		}
		meetingRepository.saveAll(room);
		if (held) {
			// The mentees who already left feedback can reach "Aktif Mentee" once the session counts as held.
			Set<UUID> affected = new HashSet<>(Set.of(mentorId));
			meetingFeedbackRepository.findByMeetingIdInAndAuthorRole(room.stream().map(Meeting::getId).toList(),
					UserRole.MENTEE).forEach(item -> affected.add(item.getAuthor().getId()));
			eventPublisher.publishEvent(new AchievementsMayChangeEvent(affected));
		}
		return MeetingResponse.fromEntity(meeting);
	}

	@Transactional(readOnly = true)
	public Optional<MeetingFeedbackResponse> findFeedback(UUID meetingId, UUID authorId) {
		return meetingFeedbackRepository.findByMeetingIdAndAuthorId(meetingId, authorId)
				.map(MeetingFeedbackResponse::fromEntity);
	}

	@Transactional(readOnly = true)
	public List<UUID> ratedMeetingIds(List<UUID> meetingIds, UUID authorId) {
		if (meetingIds.size() > MAX_RATED_LOOKUP) {
			throw new InvalidRequestException("Too many meetings in one lookup.");
		}
		if (meetingIds.isEmpty()) {
			return List.of();
		}
		return meetingFeedbackRepository.findByMeetingIdInAndAuthorId(meetingIds, authorId).stream()
				.map(feedback -> feedback.getMeeting().getId())
				.toList();
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
		if (!mentor.has(UserRole.MENTOR)) {
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
