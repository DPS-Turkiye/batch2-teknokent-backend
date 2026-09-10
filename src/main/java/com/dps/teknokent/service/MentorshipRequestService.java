package com.dps.teknokent.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.MentorshipRequestStatus;
import com.dps.teknokent.domain.MentorshipRequestMessageType;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.AcceptProposedSlotRequest;
import com.dps.teknokent.dto.requests.CreateMentorshipRequestSubmission;
import com.dps.teknokent.dto.requests.RecommendationEvidenceSubmission;
import com.dps.teknokent.dto.requests.RecommendationSnapshotSubmission;
import com.dps.teknokent.dto.requests.DecideMentorshipRequest;
import com.dps.teknokent.dto.requests.PostMentorMessageRequest;
import com.dps.teknokent.dto.requests.ReplyToMentorshipRequest;
import com.dps.teknokent.dto.requests.ScheduleMentorshipRequest;
import com.dps.teknokent.dto.requests.WithdrawMentorshipRequest;
import com.dps.teknokent.dto.responses.MentorAvailabilitySlotResponse;
import com.dps.teknokent.dto.responses.MentorEvidenceResponse;
import com.dps.teknokent.dto.responses.MentorProfileResponse;
import com.dps.teknokent.dto.responses.MentorshipRequestMessageResponse;
import com.dps.teknokent.dto.responses.MentorshipRequestResponse;
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
	private final MentorshipRequestRecommendationRunRepository recommendationRunRepository;
	private final MentorshipRequestMessageRepository mentorshipRequestMessageRepository;
	private final MentorAvailabilitySlotRepository mentorAvailabilitySlotRepository;
	private final MeetingRepository meetingRepository;
	private final DomainLookupService domainLookupService;
	private final MentorProfileService mentorProfileService;
	private final NotificationService notificationService;

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
		return requests.stream()
				.map(this::toResponse)
				.toList();
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

		MentorshipRequest mentorshipRequest = MentorshipRequest.fromRequest(
				request,
				requester,
				domainLookupService.requireOrganization(request.organizationId()),
				mentor);
		MentorshipRequest savedRequest = mentorshipRequestRepository.saveAndFlush(mentorshipRequest);
		if (request.recommendationSnapshot() != null) {
			saveRecommendationSnapshot(savedRequest, request.recommendationSnapshot());
		}
		notificationService.create(
				mentor,
				"Yeni mentorluk talebi",
				requester.getFullName() + " size " + savedRequest.getSupportArea() + " konusunda bir talep gönderdi.",
				"/mentor-dashboard");
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

		String message = TextUtils.trim(decision.message());
		request.recordDecision(decision.status(), message);
		if (decision.status() == MentorshipRequestStatus.MORE_INFO_REQUESTED) {
			saveMessage(request, mentor, MentorshipRequestMessageType.MENTOR_QUESTION, message);
		} else if (decision.status() == MentorshipRequestStatus.COUNTER_PROPOSED) {
			saveMessage(request, mentor, MentorshipRequestMessageType.MENTOR_MESSAGE, message);
		}
		MentorshipRequest savedRequest = mentorshipRequestRepository.saveAndFlush(request);
		if (decision.status() == MentorshipRequestStatus.ACCEPTED) {
			ensureMeetingForAccepted(savedRequest, MeetingSlotTimes.parseFromDecisionMessage(message));
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
		if (request.getStatus() != MentorshipRequestStatus.MORE_INFO_REQUESTED
				&& request.getStatus() != MentorshipRequestStatus.ACCEPTED) {
			throw new InvalidRequestException(
					"A message can only be sent when more information was requested or the request was accepted");
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
				"Mentorluk talebinize yanıt geldi",
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
		if (request.getStatus() != MentorshipRequestStatus.ACCEPTED) {
			throw new InvalidRequestException("Messages can only be sent after the request is accepted");
		}
		saveMessage(request, request.getMentor(), MentorshipRequestMessageType.MENTOR_MESSAGE,
				TextUtils.trim(postMessage.message()));
		notificationService.create(
				request.getRequester(),
				"Mentorunuzdan yeni mesaj",
				request.getMentor().getFullName() + " yeni bir mesaj gönderdi.",
				"/mentor-request/status?request=" + request.getId());
		return toResponse(request);
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
				.findByMentorIdAndStartsAtAfterOrderByStartsAtAsc(request.getMentor().getId(), Instant.now())
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
		if (!slot.getMentor().getId().equals(request.getMentor().getId()) || !slot.getStartsAt().isAfter(Instant.now())) {
			throw new InvalidRequestException("The selected slot is not available for this request");
		}
		if (meetingRepository.existsByMentorIdAndScheduledAtAndStatus(
				request.getMentor().getId(), slot.getStartsAt(), MeetingStatus.SCHEDULED)) {
			throw new InvalidRequestException("This slot has just been booked. Please choose another time");
		}

		Meeting savedMeeting = meetingRepository.saveAndFlush(Meeting.forMentorshipRequest(request, slot));
		notificationService.create(
				request.getMentor(),
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

	private void saveMessage(
			MentorshipRequest request,
			AppUser author,
			MentorshipRequestMessageType messageType,
			String body) {
		mentorshipRequestMessageRepository.saveAndFlush(
				MentorshipRequestMessage.create(request, author, messageType, body));
	}

	private void ensureMeetingForAccepted(MentorshipRequest request, Instant scheduledAt) {
		if (scheduledAt == null) {
			return;
		}
		if (meetingRepository.findByMentorshipRequestId(request.getId()).isPresent()) {
			return;
		}
		meetingRepository.saveAndFlush(Meeting.forLoggedEntry(
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
}
