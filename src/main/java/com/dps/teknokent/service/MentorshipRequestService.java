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
import com.dps.teknokent.dto.requests.CreateMentorshipRequestSubmission;
import com.dps.teknokent.dto.requests.RecommendationEvidenceSubmission;
import com.dps.teknokent.dto.requests.RecommendationSnapshotSubmission;
import com.dps.teknokent.dto.requests.DecideMentorshipRequest;
import com.dps.teknokent.dto.requests.ReplyToMentorshipRequest;
import com.dps.teknokent.dto.requests.ScheduleMentorshipRequest;
import com.dps.teknokent.dto.requests.WithdrawMentorshipRequest;
import com.dps.teknokent.dto.responses.MentorAvailabilitySlotResponse;
import com.dps.teknokent.dto.responses.MentorEvidenceResponse;
import com.dps.teknokent.dto.responses.MentorProfileResponse;
import com.dps.teknokent.dto.responses.MentorshipRequestMessageResponse;
import com.dps.teknokent.dto.responses.MentorshipRequestResponse;
import com.dps.teknokent.dto.responses.RecommendationItemResponse;
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

	@Transactional(readOnly = true)
	public List<MentorshipRequestResponse> findAll(UUID requesterId) {
		List<MentorshipRequest> requests = requesterId == null
				? mentorshipRequestRepository.findAllByOrderByCreatedAtDesc()
				: mentorshipRequestRepository.findByRequesterIdOrderByCreatedAtDesc(requesterId);
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

		MentorshipRequest mentorshipRequest = new MentorshipRequest();
		mentorshipRequest.setRequester(requester);
		mentorshipRequest.setOrganization(domainLookupService.requireOrganization(request.organizationId()));
		mentorshipRequest.setMentor(mentor);
		mentorshipRequest.setRequestedForName(request.requestedForName().trim());
		mentorshipRequest.setSupportArea(request.supportArea().trim());
		mentorshipRequest.setSummary(request.summary().trim());
		mentorshipRequest.setUrgency(request.urgency());
		mentorshipRequest.setMentorshipFormat(request.mentorshipFormat());
		mentorshipRequest.setMeetingMode(request.meetingMode());
		mentorshipRequest.setStatus(MentorshipRequestStatus.PENDING);
		mentorshipRequest.setRequesterMessage(normalizeOptionalText(request.requesterMessage()));
		MentorshipRequest savedRequest = mentorshipRequestRepository.saveAndFlush(mentorshipRequest);
		if (request.recommendationSnapshot() != null) {
			saveRecommendationSnapshot(savedRequest, request.recommendationSnapshot());
		}
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
				&& decision.status() != MentorshipRequestStatus.MORE_INFO_REQUESTED) {
			throw new InvalidRequestException("Decision status must be ACCEPTED, DECLINED, or MORE_INFO_REQUESTED");
		}

		String message = decision.message().trim();
		request.setStatus(decision.status());
		if (decision.status() == MentorshipRequestStatus.DECLINED) {
			request.setRejectionReason(message);
		} else {
			request.setMentorMessage(message);
		}
		if (decision.status() == MentorshipRequestStatus.MORE_INFO_REQUESTED) {
			saveMessage(request, mentor, MentorshipRequestMessageType.MENTOR_QUESTION, message);
		}
		return toResponse(mentorshipRequestRepository.saveAndFlush(request));
	}

	@Transactional
	public MentorshipRequestResponse reply(UUID id, ReplyToMentorshipRequest reply) {
		MentorshipRequest request = requireRequest(id);
		if (!request.getRequester().getId().equals(reply.requesterId())) {
			throw new InvalidRequestException("Only the requester can reply to this request");
		}
		if (request.getStatus() != MentorshipRequestStatus.MORE_INFO_REQUESTED) {
			throw new InvalidRequestException("A reply can only be sent when more information was requested");
		}
		saveMessage(request, request.getRequester(), MentorshipRequestMessageType.MENTEE_REPLY, reply.message().trim());
		request.setStatus(MentorshipRequestStatus.PENDING);
		return toResponse(mentorshipRequestRepository.saveAndFlush(request));
	}

	@Transactional
	public MentorshipRequestResponse withdraw(UUID id, WithdrawMentorshipRequest withdrawal) {
		MentorshipRequest request = requireRequest(id);
		if (!request.getRequester().getId().equals(withdrawal.requesterId())) {
			throw new InvalidRequestException("Only the requester can withdraw this request");
		}
		if (request.getStatus() != MentorshipRequestStatus.PENDING
				&& request.getStatus() != MentorshipRequestStatus.MORE_INFO_REQUESTED) {
			throw new InvalidRequestException("Only an unanswered request can be withdrawn");
		}
		request.setStatus(MentorshipRequestStatus.WITHDRAWN);
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
				.map(slot -> new MentorAvailabilitySlotResponse(slot.getId(), slot.getStartsAt(), slot.getDurationMinutes()))
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

		Meeting meeting = new Meeting();
		meeting.setMentorshipRequest(request);
		meeting.setOrganization(request.getOrganization());
		meeting.setMentor(request.getMentor());
		meeting.setScheduledAt(slot.getStartsAt());
		meeting.setDurationMinutes(slot.getDurationMinutes());
		meeting.setAgenda(request.getSupportArea() + " — Mentorluk Görüşmesi");
		meeting.setStatus(MeetingStatus.SCHEDULED);
		return toMeetingResponse(meetingRepository.saveAndFlush(meeting));
	}

	@Transactional(readOnly = true)
	public List<MentorProfileResponse> findAlternatives(UUID id) {
		return mentorProfileService.findAllExcept(requireRequest(id).getMentor().getId());
	}

	private String normalizeOptionalText(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		return value.trim();
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

		MentorshipRequestRecommendationRun run = new MentorshipRequestRecommendationRun();
		run.setRequest(request);
		run.setMatchingMethod(snapshot.matchingMethod().trim());
		run.setMatchingModel(normalizeOptionalText(snapshot.matchingModel()));
		run.setGeneratedAt(snapshot.generatedAt());
		run.setStartupName(normalizeOptionalText(snapshot.startupName()));
		run.setStartupSector(normalizeOptionalText(snapshot.startupSector()));
		run.setStartupStage(normalizeOptionalText(snapshot.startupStage()));
		run.setStartupSummary(normalizeOptionalText(snapshot.startupSummary()));
		run.setStartupTechnologies(snapshot.startupTechnologies().stream().map(String::trim).distinct().toList());

		List<MentorshipRequestRecommendationItem> items = snapshot.recommendations().stream()
				.map(submission -> {
					MentorProfileResponse canonicalMentor = canonicalMentors.get(submission.mentorId());
					MentorshipRequestRecommendationItem item = new MentorshipRequestRecommendationItem();
					item.setRun(run);
					item.setMentor(domainLookupService.requireUser(submission.mentorId()));
					item.setRank(submission.rank());
					item.setSelected(submission.selected());
					item.setMentorName(canonicalMentor.fullName());
					item.setProfessionalTitle(canonicalMentor.professionalTitle());
					item.setCompany(canonicalMentor.company());
					item.setReasons(submission.reasons().stream().map(String::trim).distinct().toList());
					item.setEvidence(submission.evidence().stream()
							.map(evidence -> toValidatedEvidenceSnapshot(canonicalMentor, evidence))
							.toList());
					return item;
				})
				.toList();
		run.setRecommendations(items);
		recommendationRunRepository.saveAndFlush(run);
	}

	private RecommendationEvidenceSnapshot toValidatedEvidenceSnapshot(
			MentorProfileResponse mentor, RecommendationEvidenceSubmission submission) {
		MentorEvidenceResponse canonical = mentor.recommendationEvidence().stream()
				.filter(evidence -> evidence.category().equals(submission.category().trim())
						&& evidence.claim().equals(submission.claim().trim())
						&& evidence.quote().equals(submission.quote().trim())
						&& java.util.Objects.equals(evidence.sourcePage(), submission.sourcePage())
						&& evidence.profileVersion().equals(submission.profileVersion().trim()))
				.findFirst()
				.orElseThrow(() -> new InvalidRequestException(
						"Recommendation evidence must come from the mentor's confirmed profile"));
		RecommendationEvidenceSnapshot snapshot = new RecommendationEvidenceSnapshot();
		snapshot.setCategory(canonical.category());
		snapshot.setClaim(canonical.claim());
		snapshot.setQuote(canonical.quote());
		snapshot.setSourcePage(canonical.sourcePage());
		snapshot.setProfileVersion(canonical.profileVersion());
		return snapshot;
	}

	private MentorshipRequest requireRequest(UUID id) {
		return mentorshipRequestRepository.findById(id)
				.orElseThrow(() -> new ResourceNotFoundException("Mentorship request not found: " + id));
	}

	private MeetingResponse toMeetingResponse(Meeting meeting) {
		return new MeetingResponse(
				meeting.getId(),
				meeting.getMentorshipRequest() == null ? null : meeting.getMentorshipRequest().getId(),
				meeting.getOrganization().getId(),
				meeting.getOrganization().getName(),
				meeting.getMentor() == null ? null : meeting.getMentor().getId(),
				meeting.getMentor() == null ? null : meeting.getMentor().getFullName(),
				meeting.getScheduledAt(),
				meeting.getDurationMinutes(),
				meeting.getAgenda(),
				meeting.getNotes(),
				meeting.getOutcomes(),
				meeting.getMeetLink(),
				meeting.getStatus());
	}

	private void saveMessage(
			MentorshipRequest request,
			AppUser author,
			MentorshipRequestMessageType messageType,
			String body) {
		MentorshipRequestMessage message = new MentorshipRequestMessage();
		message.setRequest(request);
		message.setAuthor(author);
		message.setMessageType(messageType);
		message.setBody(body);
		mentorshipRequestMessageRepository.saveAndFlush(message);
	}

	private MentorshipRequestResponse toResponse(MentorshipRequest request) {
		List<MentorshipRequestMessageResponse> messages = mentorshipRequestMessageRepository
				.findByRequestIdOrderByCreatedAtAsc(request.getId())
				.stream()
				.map(message -> new MentorshipRequestMessageResponse(
						message.getId(),
						message.getAuthor().getId(),
						message.getAuthor().getFullName(),
						message.getMessageType(),
						message.getBody(),
						message.getCreatedAt()))
				.toList();
		RecommendationSnapshotResponse recommendationSnapshot = recommendationRunRepository.findByRequestId(request.getId())
				.map(run -> new RecommendationSnapshotResponse(
						run.getMatchingMethod(),
						run.getMatchingModel(),
						run.getGeneratedAt(),
						run.getStartupName(),
						run.getStartupSector(),
						run.getStartupStage(),
						run.getStartupSummary(),
						List.copyOf(run.getStartupTechnologies()),
						run.getRecommendations().stream()
								.sorted(java.util.Comparator.comparingInt(MentorshipRequestRecommendationItem::getRank))
								.map(item -> new RecommendationItemResponse(
										item.getMentor().getId(), item.getRank(), item.isSelected(),
										item.getMentorName(), item.getProfessionalTitle(), item.getCompany(),
										List.copyOf(item.getReasons()),
										item.getEvidence().stream()
												.map(evidence -> new MentorEvidenceResponse(
														evidence.getCategory(), evidence.getClaim(), evidence.getQuote(),
														evidence.getSourcePage(), evidence.getProfileVersion()))
												.toList()))
								.toList()))
				.orElse(null);
		return new MentorshipRequestResponse(
				request.getId(),
				request.getRequester().getId(),
				request.getRequester().getFullName(),
				request.getOrganization().getId(),
				request.getOrganization().getName(),
				request.getMentor().getId(),
				request.getMentor().getFullName(),
				request.getRequestedForName(),
				request.getSupportArea(),
				request.getSummary(),
				request.getUrgency(),
				request.getMentorshipFormat(),
				request.getMeetingMode(),
				request.getStatus(),
				request.getRequesterMessage(),
				request.getMentorMessage(),
				request.getRejectionReason(),
				request.getCreatedAt(),
				request.getUpdatedAt(),
				messages,
				recommendationSnapshot);
	}
}
