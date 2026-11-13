package com.dps.teknokent.service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;

import com.dps.teknokent.domain.MeetingStatus;
import com.dps.teknokent.domain.MentorshipRequestStatus;
import com.dps.teknokent.domain.PeerTopicCategory;
import com.dps.teknokent.domain.ProgramMentorStatus;
import com.dps.teknokent.domain.ProgramStatus;
import com.dps.teknokent.domain.ProgramType;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.entity.MentorshipRequest;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramMentor;
import com.dps.teknokent.exception.ConflictException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.MeetingRepository;
import com.dps.teknokent.repository.MentorshipRequestRepository;
import com.dps.teknokent.repository.ProgramMentorRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.util.Texts;

import lombok.RequiredArgsConstructor;

/**
 * Who may write peer-mentor reports, and about which meetings: a peer mentor, only about the meetings on peer requests
 * they mentor. Anything else is "not found", so a report id never tells someone else that it exists.
 */
@Component
@RequiredArgsConstructor
class PeerReportAccess {

	private final PeerMentoringService peerMentoringService;
	private final MeetingRepository meetingRepository;
	private final MentorshipRequestRepository mentorshipRequestRepository;
	private final ProgramMentorRepository programMentorRepository;
	private final ProgramRepository programRepository;
	private final PeerReportCalendar calendar;

	AppUser requireMentor(UUID actorId) {
		return peerMentoringService.requirePeerMentor(actorId);
	}

	/** One of the mentor's own peer meetings that has started; cancelled, foreign and future meetings are not found. */
	Meeting requireHeldMeeting(AppUser mentor, UUID meetingId) {
		return meetingRepository.findById(meetingId)
				.filter(meeting -> isOwnPeerMeeting(mentor, meeting))
				.filter(this::held)
				.orElseThrow(() -> new ResourceNotFoundException("Peer meeting not found: " + meetingId));
	}

	/** The mentor's peer meetings starting in [from, to) that have already started, oldest first. */
	List<Meeting> heldMeetings(AppUser mentor, Instant from, Instant to) {
		Instant until = to.isBefore(calendar.now()) ? to : calendar.now();
		if (!from.isBefore(until)) {
			return List.of();
		}
		return meetingRepository
				.findByMentorshipRequest_Mentor_IdAndScheduledAtGreaterThanEqualAndScheduledAtLessThanOrderByScheduledAtAsc(
						mentor.getId(), from, until)
				.stream()
				.filter(meeting -> isOwnPeerMeeting(mentor, meeting))
				.filter(this::held)
				.toList();
	}

	/** Whether one of the mentor's peer meetings is still to come before {@code to}. */
	boolean meetingsAhead(AppUser mentor, Instant to) {
		Instant now = calendar.now();
		return now.isBefore(to) && meetingRepository
				.findByMentorshipRequest_Mentor_IdAndScheduledAtGreaterThanEqualAndScheduledAtLessThanOrderByScheduledAtAsc(
						mentor.getId(), now, to)
				.stream()
				.anyMatch(meeting -> isOwnPeerMeeting(mentor, meeting));
	}

	/**
	 * Saves, turning a lost race into a 409: two first saves of the same report (two tabs, an autosave) hit the
	 * unique key, and two concurrent edits the version check.
	 */
	static <T> T savedOrConflict(Supplier<T> save) {
		try {
			return save.get();
		} catch (DataIntegrityViolationException | ObjectOptimisticLockingFailureException exception) {
			throw new ConflictException(Texts.forRequest("error.peerReportConcurrent"));
		}
	}

	/** Every non-cancelled meeting between the mentor and this mentee, oldest first. */
	List<Meeting> meetingsWith(AppUser mentor, UUID menteeId) {
		return meetingRepository
				.findByMentorshipRequest_Mentor_IdAndMentorshipRequest_Requester_IdOrderByScheduledAtAsc(
						mentor.getId(), menteeId)
				.stream()
				.filter(meeting -> isOwnPeerMeeting(mentor, meeting))
				.toList();
	}

	/** Whether the user is the mentee of an accepted peer request of the mentor's (an activity may name only them). */
	boolean isMenteeOf(AppUser mentor, UUID menteeId) {
		return mentorshipRequestRepository.findByMentorIdOrderByCreatedAtDesc(mentor.getId()).stream()
				.filter(request -> request.getStatus() == MentorshipRequestStatus.ACCEPTED)
				.filter(request -> PeerTopicCategory.fromText(request.getSupportArea()) != null)
				.anyMatch(request -> request.getRequester().getId().equals(menteeId));
	}

	/** The newest running peer program the mentor is accepted in; null when there is none. */
	UUID programOf(AppUser mentor) {
		List<UUID> ids = programMentorRepository
				.findByMentorIdAndStatusOrderByCreatedAtDesc(mentor.getId(), ProgramMentorStatus.ACCEPTED).stream()
				.map(ProgramMentor::getProgramId)
				.toList();
		return programRepository.findAllById(ids).stream()
				.filter(program -> program.getProgramType() == ProgramType.PEER_MENTORING)
				.filter(program -> program.getStatus() == ProgramStatus.ACTIVE)
				.max(Comparator.comparing(Program::getCreatedAt, Comparator.nullsFirst(Comparator.naturalOrder())))
				.map(Program::getId)
				.orElse(null);
	}

	/** The program a meeting report belongs to: the request's, else the mentor's running one. */
	UUID programOf(AppUser mentor, Meeting meeting) {
		return Optional.ofNullable(meeting.getMentorshipRequest().getProgram())
				.map(Program::getId)
				.orElseGet(() -> programOf(mentor));
	}

	private boolean held(Meeting meeting) {
		return meeting.getScheduledAt().isBefore(calendar.now());
	}

	private static boolean isOwnPeerMeeting(AppUser mentor, Meeting meeting) {
		MentorshipRequest request = meeting.getMentorshipRequest();
		return request != null
				&& meeting.getStatus() != MeetingStatus.CANCELLED
				&& request.getMentor() != null
				&& request.getMentor().getId().equals(mentor.getId())
				&& PeerTopicCategory.fromText(request.getSupportArea()) != null;
	}
}
