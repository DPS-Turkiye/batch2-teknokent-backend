package com.dps.teknokent.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.MentorshipRequestStatus;
import com.dps.teknokent.domain.MentorshipRequestMessageType;
import com.dps.teknokent.domain.MentorshipWorkspaceTab;
import com.dps.teknokent.domain.NotificationCategory;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.AcceptProposedSlotRequest;
import com.dps.teknokent.dto.requests.CreateMentorshipRequestSubmission;
import com.dps.teknokent.dto.requests.RecommendationEvidenceSubmission;
import com.dps.teknokent.dto.requests.RecommendationSnapshotSubmission;
import com.dps.teknokent.dto.requests.DecideMentorshipRequest;
import com.dps.teknokent.dto.requests.PostMentorMessageRequest;
import com.dps.teknokent.dto.requests.UpdatePlannedSessionsRequest;
import com.dps.teknokent.dto.requests.ReplyToMentorshipRequest;
import com.dps.teknokent.dto.requests.ScheduleMentorshipRequest;
import com.dps.teknokent.dto.requests.WithdrawMentorshipRequest;
import com.dps.teknokent.dto.responses.MentorAvailabilitySlotResponse;
import com.dps.teknokent.dto.responses.MentorEvidenceResponse;
import com.dps.teknokent.dto.responses.MentorProfileResponse;
import com.dps.teknokent.dto.responses.MentorshipRequestMessageResponse;
import com.dps.teknokent.dto.responses.MentorshipRequestResponse;
import com.dps.teknokent.dto.responses.PageResponse;
import com.dps.teknokent.dto.responses.RecommendationSnapshotResponse;
import com.dps.teknokent.dto.responses.MeetingResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.MentorshipRequest;
import com.dps.teknokent.entity.MentorshipRequestMessage;
import com.dps.teknokent.entity.MentorshipRequestRecommendationItem;
import com.dps.teknokent.entity.MentorshipRequestRecommendationRun;
import com.dps.teknokent.entity.RecommendationEvidenceSnapshot;
import com.dps.teknokent.entity.MentorAvailabilitySlot;
import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.MentorAvailabilitySlotRepository;
import com.dps.teknokent.repository.MentorshipRequestMessageRepository;
import com.dps.teknokent.repository.MentorshipRequestRepository;
import com.dps.teknokent.repository.MentorshipRequestRecommendationRunRepository;
import com.dps.teknokent.repository.MentorshipRequestWorkspaceQuery;
import com.dps.teknokent.repository.MeetingRepository;
import com.dps.teknokent.domain.MeetingStatus;
import com.dps.teknokent.util.MeetingSlotTimes;
import com.dps.teknokent.util.ProposedSlots;
import com.dps.teknokent.util.TextUtils;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MentorshipRequestService {

	private final MentorshipRequestRepository mentorshipRequestRepository;
	private final MentorshipRequestWorkspaceQuery mentorshipRequestWorkspaceQuery;
	private final MentorshipRequestRecommendationRunRepository recommendationRunRepository;
	private final MentorshipRequestMessageRepository mentorshipRequestMessageRepository;
	private final MentorAvailabilitySlotRepository mentorAvailabilitySlotRepository;
	private final MeetingRepository meetingRepository;
	private final DomainLookupService domainLookupService;
	private final MentorProfileService mentorProfileService;
	private final NotificationService notificationService;
	private final MailService mailService;
	private final DocumentRequestService documentRequestService;

	@Transactional(readOnly = true)
	public List<MentorshipRequestResponse> findAll(UUID requesterId, UUID mentorId, UUID organizationId) {
		List<MentorshipRequest> requests;
		if (mentorId != null) {
			requests = mentorshipRequestRepository.findByMentorIdOrderByCreatedAtDesc(mentorId);
		} else if (organizationId != null) {
			requests = mentorshipRequestRepository.findByOrganizationIdOrderByCreatedAtDesc(organizationId);
		} else if (requesterId != null) {
			requests = mentorshipRequestRepository.findByRequesterIdOrderByCreatedAtDesc(requesterId);
		} else {
			requests = mentorshipRequestRepository.findAllByOrderByCreatedAtDesc();
		}
		return toResponses(requests);
	}

	@Transactional(readOnly = true)
	public PageResponse<MentorshipRequestResponse> findWorkspacePage(
			UUID mentorId,
			UUID organizationId,
			MentorshipWorkspaceTab tab,
			Pageable pageable) {
		Pageable safePageable = PageRequest.of(
				Math.max(pageable.getPageNumber(), 0),
				Math.max(pageable.getPageSize(), 1),
				pageable.getSort().isSorted() ? pageable.getSort() : Sort.by(Sort.Direction.DESC, "createdAt"));
		Page<MentorshipRequest> page = mentorshipRequestWorkspaceQuery.findPage(
				mentorId,
				organizationId,
				tab,
				safePageable,
				Instant.now());
		return PageResponse.of(
				toResponses(page.getContent()), page.getNumber(), page.getSize(), page.getTotalElements());
	}

	@Transactional(readOnly = true)
	public MentorshipRequestResponse findById(UUID id) {
		return mentorshipRequestRepository.findById(id)
				.map(this::toResponse)
				.orElseThrow(() -> new ResourceNotFoundException("Mentorship request not found: " + id));
	}

	@Transactional
	public MentorshipRequestResponse create(CreateMentorshipRequestSubmission request) {
		AppUser requester = domainLookupService.requireUser(request.requesterId());
		if (requester.getRole() != UserRole.MENTEE) {
			throw new InvalidRequestException("Requester must have the MENTEE role");
		}

		AppUser mentor = domainLookupService.requireUser(request.mentorId());
		if (mentor.getRole() != UserRole.MENTOR) {
			throw new InvalidRequestException("Selected user does not have the MENTOR role");
		}
		if (!MeetingSlotTimes.allBookable(ProposedSlots.preferred(request.requesterMessage()))) {
			throw new InvalidRequestException(MeetingSlotTimes.TOO_SOON_MESSAGE);
		}

		MentorshipRequest mentorshipRequest = MentorshipRequest.fromRequest(
				request,
				requester,
				domainLookupService.requireOrganization(request.organizationId()),
				mentor);
		MentorshipRequest savedRequest = mentorshipRequestRepository.saveAndFlush(mentorshipRequest);
		if (request.recommendationSnapshot() != null) {
			saveRecommendationSnapshot(savedRequest, request.recommendationSnapshot());
		}
		String title = "Yeni mentorluk talebi";
		String body = requester.getFullName() + " size " + savedRequest.getSupportArea()
				+ " konusunda bir talep gönderdi.";
		notificationService.create(mentor, title, body, "/mentor-dashboard");
		mailService.sendAfterCommit(
				mentor.getEmail(),
				title,
				"Merhaba " + mentor.getFullName() + ",\n\n" + body
						+ "\n\nTalebi görmek için: " + mailService.link("/mentor-dashboard") + "\n\nMenton");
		return toResponse(savedRequest);
	}

	@Transactional
	public MentorshipRequestResponse decide(UUID id, DecideMentorshipRequest decision) {
		MentorshipRequest request = requireRequest(id);
		AppUser mentor = domainLookupService.requireUser(decision.mentorId());
		if (!request.getMentor().getId().equals(mentor.getId()) || mentor.getRole() != UserRole.MENTOR) {
			throw new InvalidRequestException("Only the assigned mentor can decide this request");
		}
		if (request.getStatus() != MentorshipRequestStatus.PENDING
				&& request.getStatus() != MentorshipRequestStatus.MORE_INFO_REQUESTED) {
			throw new InvalidRequestException("Only pending requests can be decided");
		}
		if (decision.status() != MentorshipRequestStatus.ACCEPTED
				&& decision.status() != MentorshipRequestStatus.DECLINED
				&& decision.status() != MentorshipRequestStatus.MORE_INFO_REQUESTED
				&& decision.status() != MentorshipRequestStatus.COUNTER_PROPOSED) {
			throw new InvalidRequestException(
					"Decision status must be ACCEPTED, DECLINED, MORE_INFO_REQUESTED, or COUNTER_PROPOSED");
		}
		if (decision.status() == MentorshipRequestStatus.COUNTER_PROPOSED
				&& ProposedSlots.parse(decision.message()).isEmpty()) {
			throw new InvalidRequestException("At least one proposed slot is required");
		}
		if (decision.status() == MentorshipRequestStatus.COUNTER_PROPOSED
				&& !MeetingSlotTimes.allBookable(ProposedSlots.parse(decision.message()))) {
			throw new InvalidRequestException(MeetingSlotTimes.TOO_SOON_MESSAGE);
		}

		String message = TextUtils.trim(decision.message());
		request.recordDecision(decision.status(), message);
		if (decision.status() == MentorshipRequestStatus.MORE_INFO_REQUESTED) {
			saveMessage(request, mentor, MentorshipRequestMessageType.MENTOR_QUESTION, message);
		} else if (decision.status() == MentorshipRequestStatus.COUNTER_PROPOSED) {
			saveMessage(request, mentor, MentorshipRequestMessageType.MENTOR_MESSAGE, message);
		}
		MentorshipRequest savedRequest = mentorshipRequestRepository.saveAndFlush(request);
		if (decision.status() != MentorshipRequestStatus.DECLINED) {
			documentRequestService.saveTitlesOnRequest(savedRequest, mentor, decision.requestedDocuments());
		}
		if (decision.status() == MentorshipRequestStatus.ACCEPTED) {
			Instant chosen = MeetingSlotTimes.parseFromDecisionMessage(message);
			ensureMeetingForAccepted(savedRequest, chosen != null ? chosen : firstOpenPreferredSlot(savedRequest));
		}
		notificationService.create(
				request.getRequester(),
				decision.status() == MentorshipRequestStatus.ACCEPTED
						? "Mentorluk talebiniz kabul edildi"
						: decision.status() == MentorshipRequestStatus.DECLINED
								? "Mentorluk talebiniz sonuçlandı"
								: decision.status() == MentorshipRequestStatus.COUNTER_PROPOSED
										? "Mentorunuz alternatif saat önerdi"
										: "Mentorunuz ek bilgi istedi",
				message,
				"/mentor-request/status?request=" + request.getId());
		return toResponse(savedRequest);
	}

	@Transactional
	public MentorshipRequestResponse acceptProposedSlot(UUID id, AcceptProposedSlotRequest acceptance) {
		MentorshipRequest request = requireRequest(id);
		if (!request.getRequester().getId().equals(acceptance.requesterId())) {
			throw new InvalidRequestException("Only the requester can accept a proposed slot");
		}
		if (request.getStatus() != MentorshipRequestStatus.COUNTER_PROPOSED) {
			throw new InvalidRequestException("Only counter-proposed requests can accept a slot");
		}
		String selectedSlot = TextUtils.trim(acceptance.selectedSlot());
		if (!ProposedSlots.contains(request.getMentorMessage(), selectedSlot)) {
			throw new InvalidRequestException("Selected slot is not among the mentor proposals");
		}
		request.recordDecision(MentorshipRequestStatus.ACCEPTED, "Görüşme saati: " + selectedSlot);
		MentorshipRequest savedRequest = mentorshipRequestRepository.saveAndFlush(request);
		ensureMeetingForAccepted(savedRequest, MeetingSlotTimes.parseMachineSlot(selectedSlot));
		notificationService.create(
				request.getMentor(),
				NotificationCategory.MEETING_CHANGE,
				"Mentee görüşme saatini seçti",
				request.getRequester().getFullName() + " önerdiğiniz saatlerden birini kabul etti.",
				"/mentorships");
		return toResponse(savedRequest);
	}

	@Transactional
	public MentorshipRequestResponse reply(UUID id, ReplyToMentorshipRequest reply) {
		MentorshipRequest request = requireRequest(id);
		if (!request.getRequester().getId().equals(reply.requesterId())) {
			throw new InvalidRequestException("Only the requester can reply to this request");
		}
		if (!allowsMessaging(request.getStatus())) {
			throw new InvalidRequestException("A message can only be sent on an open mentorship request");
		}
		boolean isReplyingToInfoRequest = request.getStatus() == MentorshipRequestStatus.MORE_INFO_REQUESTED;
		saveMessage(request, request.getRequester(),
				isReplyingToInfoRequest ? MentorshipRequestMessageType.MENTEE_REPLY
						: MentorshipRequestMessageType.MENTEE_MESSAGE,
				TextUtils.trim(reply.message()));
		if (isReplyingToInfoRequest) {
			request.markReplied();
		}
		notificationService.create(
				request.getMentor(),
				NotificationCategory.MESSAGE,
				isReplyingToInfoRequest ? "Ek bilgi talebinize yanıt geldi" : "Startup'tan yeni mesaj",
				request.getRequester().getFullName() + " yeni bir mesaj gönderdi.",
				"/mentor-dashboard");
		return toResponse(mentorshipRequestRepository.saveAndFlush(request));
	}

	@Transactional
	public MentorshipRequestResponse postMentorMessage(UUID id, PostMentorMessageRequest postMessage) {
		MentorshipRequest request = requireRequest(id);
		if (!request.getMentor().getId().equals(postMessage.mentorId())) {
			throw new InvalidRequestException("Only the assigned mentor can message this request");
		}
		if (!allowsMessaging(request.getStatus())) {
			throw new InvalidRequestException("Messages can only be sent on an open mentorship request");
		}
		saveMessage(request, request.getMentor(), MentorshipRequestMessageType.MENTOR_MESSAGE,
				TextUtils.trim(postMessage.message()));
		notificationService.create(
				request.getRequester(),
				NotificationCategory.MESSAGE,
				"Mentorunuzdan yeni mesaj",
				request.getMentor().getFullName() + " yeni bir mesaj gönderdi.",
				"/mentor-request/status?request=" + request.getId());
		return toResponse(request);
	}

	@Transactional
	public MentorshipRequestResponse updatePlannedSessions(UUID id, UpdatePlannedSessionsRequest update) {
		MentorshipRequest request = requireRequest(id);
		if (!request.getMentor().getId().equals(update.mentorId())) {
			throw new InvalidRequestException("Only the assigned mentor can plan the sessions of this request");
		}
		if (request.getStatus() != MentorshipRequestStatus.ACCEPTED) {
			throw new InvalidRequestException("Sessions can only be planned after the request is accepted");
		}
		request.planSessions(update.plannedSessionCount());
		return toResponse(mentorshipRequestRepository.saveAndFlush(request));
	}

	@Transactional
	public MentorshipRequestResponse markRead(UUID id, UUID actorId) {
		MentorshipRequest request = requireRequest(id);
		AppUser reader = domainLookupService.requireUser(actorId);
		if (!takesPartIn(request, reader)) {
			throw new InvalidRequestException("You are not part of this conversation");
		}
		request.markReadBy(reader, Instant.now());
		return toResponse(mentorshipRequestRepository.saveAndFlush(request));
	}

	@Transactional
	public MentorshipRequestResponse withdraw(UUID id, WithdrawMentorshipRequest withdrawal) {
		MentorshipRequest request = requireRequest(id);
		if (!request.getRequester().getId().equals(withdrawal.requesterId())) {
			throw new InvalidRequestException("Only the requester can withdraw this request");
		}
		if (request.getStatus() != MentorshipRequestStatus.PENDING
				&& request.getStatus() != MentorshipRequestStatus.MORE_INFO_REQUESTED
				&& request.getStatus() != MentorshipRequestStatus.COUNTER_PROPOSED) {
			throw new InvalidRequestException("Only an unanswered request can be withdrawn");
		}
		request.withdraw();
		return toResponse(mentorshipRequestRepository.saveAndFlush(request));
	}

	@Transactional(readOnly = true)
	public List<MentorAvailabilitySlotResponse> findAvailability(UUID id) {
		MentorshipRequest request = requireRequest(id);
		if (request.getStatus() != MentorshipRequestStatus.ACCEPTED) {
			throw new InvalidRequestException("Availability is available after the request is accepted");
		}
		return mentorAvailabilitySlotRepository
				.findByMentorIdAndStartsAtAfterOrderByStartsAtAsc(
						request.getMentor().getId(), MeetingSlotTimes.earliestBookable())
				.stream()
				.filter(slot -> !meetingRepository.existsByMentorIdAndScheduledAtAndStatus(
						request.getMentor().getId(), slot.getStartsAt(), MeetingStatus.SCHEDULED))
				.map(slot -> new MentorAvailabilitySlotResponse(slot.getId(), slot.getStartsAt(), slot.getDurationMinutes(), false))
				.toList();
	}

	@Transactional
	public MeetingResponse scheduleMeeting(UUID id, ScheduleMentorshipRequest booking) {
		MentorshipRequest request = requireRequest(id);
		if (!request.getRequester().getId().equals(booking.requesterId())) {
			throw new InvalidRequestException("Only the requester can schedule this meeting");
		}
		if (request.getStatus() != MentorshipRequestStatus.ACCEPTED) {
			throw new InvalidRequestException("A meeting can only be scheduled after the request is accepted");
		}
		if (meetingRepository.findByMentorshipRequestId(request.getId()).isPresent()) {
			throw new InvalidRequestException("A meeting has already been scheduled for this request");
		}

		MentorAvailabilitySlot slot = mentorAvailabilitySlotRepository.findById(booking.slotId())
				.orElseThrow(() -> new ResourceNotFoundException("Availability slot not found: " + booking.slotId()));
		if (!slot.getMentor().getId().equals(request.getMentor().getId())) {
			throw new InvalidRequestException("The selected slot is not available for this request");
		}
		if (slot.getStartsAt().isBefore(MeetingSlotTimes.earliestBookable())) {
			throw new InvalidRequestException(MeetingSlotTimes.TOO_SOON_MESSAGE);
		}
		if (meetingRepository.existsByMentorIdAndScheduledAtAndStatus(
				request.getMentor().getId(), slot.getStartsAt(), MeetingStatus.SCHEDULED)) {
			throw new InvalidRequestException("This slot has just been booked. Please choose another time");
		}

		Meeting savedMeeting = meetingRepository.saveAndFlush(Meeting.forMentorshipRequest(request, slot));
		notificationService.create(
				request.getMentor(),
				NotificationCategory.MEETING_CHANGE,
				"Yeni görüşme planlandı",
				request.getRequester().getFullName() + " mentorluk görüşmesi için bir zaman seçti.",
				"/mentor-dashboard/calendar");
		return MeetingResponse.fromEntity(savedMeeting);
	}

	@Transactional(readOnly = true)
	public List<MentorProfileResponse> findAlternatives(UUID id) {
		return mentorProfileService.findAllExcept(requireRequest(id).getMentor().getId());
	}

	private void saveRecommendationSnapshot(
			MentorshipRequest request,
			RecommendationSnapshotSubmission snapshot) {
		long selectedCount = snapshot.recommendations().stream().filter(item -> item.selected()).count();
		boolean selectedMentorMatches = snapshot.recommendations().stream()
				.anyMatch(item -> item.selected() && item.mentorId().equals(request.getMentor().getId()));
		Set<Integer> ranks = snapshot.recommendations().stream().map(item -> item.rank()).collect(Collectors.toSet());
		Set<UUID> mentorIds = snapshot.recommendations().stream().map(item -> item.mentorId()).collect(Collectors.toSet());
		if (selectedCount != 1 || !selectedMentorMatches) {
			throw new InvalidRequestException("Recommendation snapshot must select the request mentor exactly once");
		}
		if (ranks.size() != snapshot.recommendations().size()
				|| mentorIds.size() != snapshot.recommendations().size()) {
			throw new InvalidRequestException("Recommendation snapshot ranks and mentors must be unique");
		}

		Map<UUID, MentorProfileResponse> canonicalMentors = mentorProfileService.findAll().stream()
				.collect(Collectors.toMap(MentorProfileResponse::id, Function.identity()));
		if (!canonicalMentors.keySet().containsAll(mentorIds)) {
			throw new InvalidRequestException("Recommendation snapshot contains an unknown mentor");
		}

		MentorshipRequestRecommendationRun run =
				MentorshipRequestRecommendationRun.fromSubmission(request, snapshot);

		List<MentorshipRequestRecommendationItem> items = snapshot.recommendations().stream()
				.map(submission -> {
					MentorProfileResponse canonicalMentor = canonicalMentors.get(submission.mentorId());
					return MentorshipRequestRecommendationItem.fromSubmission(
							run, submission, domainLookupService.requireUser(submission.mentorId()), canonicalMentor,
							submission.evidence().stream()
									.map(evidence -> toValidatedEvidenceSnapshot(canonicalMentor, evidence))
									.toList());
				})
				.toList();
		run.replaceRecommendations(items);
		recommendationRunRepository.saveAndFlush(run);
	}

	private RecommendationEvidenceSnapshot toValidatedEvidenceSnapshot(
			MentorProfileResponse mentor, RecommendationEvidenceSubmission submission) {
		MentorEvidenceResponse canonical = mentor.recommendationEvidence().stream()
				.filter(evidence -> evidence.category().equals(TextUtils.trim(submission.category()))
						&& evidence.claim().equals(TextUtils.trim(submission.claim()))
						&& evidence.quote().equals(TextUtils.trim(submission.quote()))
						&& java.util.Objects.equals(evidence.sourcePage(), submission.sourcePage())
						&& evidence.profileVersion().equals(TextUtils.trim(submission.profileVersion())))
				.findFirst()
				.orElseThrow(() -> new InvalidRequestException(
						"Recommendation evidence must come from the mentor's confirmed profile"));
		return RecommendationEvidenceSnapshot.fromEvidence(canonical);
	}

	private MentorshipRequest requireRequest(UUID id) {
		return mentorshipRequestRepository.findById(id)
				.orElseThrow(() -> new ResourceNotFoundException("Mentorship request not found: " + id));
	}

	private static boolean allowsMessaging(MentorshipRequestStatus status) {
		return status == MentorshipRequestStatus.PENDING
				|| status == MentorshipRequestStatus.MORE_INFO_REQUESTED
				|| status == MentorshipRequestStatus.COUNTER_PROPOSED
				|| status == MentorshipRequestStatus.ACCEPTED;
	}

	private void saveMessage(
			MentorshipRequest request,
			AppUser author,
			MentorshipRequestMessageType messageType,
			String body) {
		mentorshipRequestMessageRepository.saveAndFlush(
				MentorshipRequestMessage.create(request, author, messageType, body));
		// Writing a message means the author has read everything before it.
		request.markReadBy(author, Instant.now());
	}

	// The mentor, or anyone on the startup's team: the team shares one read mark.
	private static boolean takesPartIn(MentorshipRequest request, AppUser user) {
		if (user.getId().equals(request.getMentor().getId())) {
			return true;
		}
		return user.getOrganization() != null
				&& user.getOrganization().getId().equals(request.getOrganization().getId());
	}

	/**
	 * An accept that names no time (the quick accept on the mentor home) books the first time the mentee picked
	 * that is still ahead and free on the mentor's calendar, so the mentee isn't asked to pick again.
	 */
	private Instant firstOpenPreferredSlot(MentorshipRequest request) {
		Instant now = Instant.now();
		return ProposedSlots.preferred(request.getRequesterMessage()).stream()
				.map(MeetingSlotTimes::parseMachineSlot)
				.filter(Objects::nonNull)
				.filter(startsAt -> startsAt.isAfter(now))
				.filter(startsAt -> !meetingRepository.existsByMentorIdAndScheduledAtAndStatus(
						request.getMentor().getId(), startsAt, MeetingStatus.SCHEDULED))
				.findFirst()
				.orElse(null);
	}

	private void ensureMeetingForAccepted(MentorshipRequest request, Instant scheduledAt) {
		Meeting meeting = meetingRepository.findByMentorshipRequestId(request.getId()).orElse(null);
		if (meeting == null) {
			if (scheduledAt == null) {
				return;
			}
			meeting = meetingRepository.saveAndFlush(Meeting.forLoggedEntry(
					request,
					scheduledAt,
					45,
					request.getSupportArea() + " — Mentorluk Görüşmesi",
					null,
					null,
					MeetingStatus.SCHEDULED,
					null,
					false));
		}
		documentRequestService.copyFromRequestToMeeting(request, meeting);
	}

	private MentorshipRequestResponse toResponse(MentorshipRequest request) {
		List<MentorshipRequestMessageResponse> messages = mentorshipRequestMessageRepository
				.findByRequestIdOrderByCreatedAtAsc(request.getId())
				.stream()
				.map(MentorshipRequestMessageResponse::fromEntity)
				.toList();
		RecommendationSnapshotResponse recommendationSnapshot = recommendationRunRepository.findByRequestId(request.getId())
				.map(RecommendationSnapshotResponse::fromEntity)
				.orElse(null);
		return MentorshipRequestResponse.fromEntity(request, messages, recommendationSnapshot);
	}

	// Lists are polled (phone Mesajlar every 15 s), so messages and snapshots load once for the whole list.
	private List<MentorshipRequestResponse> toResponses(List<MentorshipRequest> requests) {
		if (requests.isEmpty()) {
			return List.of();
		}
		List<UUID> ids = requests.stream().map(MentorshipRequest::getId).toList();
		Map<UUID, List<MentorshipRequestMessageResponse>> messages = mentorshipRequestMessageRepository
				.findByRequestIdInOrderByCreatedAtAsc(ids)
				.stream()
				.collect(Collectors.groupingBy(
						message -> message.getRequest().getId(),
						Collectors.mapping(MentorshipRequestMessageResponse::fromEntity, Collectors.toList())));
		Map<UUID, RecommendationSnapshotResponse> snapshots = recommendationRunRepository.findByRequestIdIn(ids)
				.stream()
				.collect(Collectors.toMap(run -> run.getRequest().getId(), RecommendationSnapshotResponse::fromEntity));
		return requests.stream()
				.map(request -> MentorshipRequestResponse.fromEntity(
						request,
						messages.getOrDefault(request.getId(), List.of()),
						snapshots.get(request.getId())))
				.toList();
	}
}
