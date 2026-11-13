package com.dps.teknokent.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import com.dps.teknokent.domain.MeetingStatus;
import com.dps.teknokent.domain.MentorshipRequestStatus;
import com.dps.teknokent.domain.PeerTopicCategory;
import com.dps.teknokent.domain.ProgramMentorStatus;
import com.dps.teknokent.domain.ProgramStatus;
import com.dps.teknokent.domain.ProgramType;
import com.dps.teknokent.domain.UserRole;
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
		try {
			return peerMentoringService.requirePeerMentor(actorId);
		} catch (ResponseStatusException refused) {
			if (refused.getStatusCode() != HttpStatus.FORBIDDEN) {
				throw refused;
			}
			// A peer mentor whose badge was taken away may still finish the reports of what they did before (Arya,
			// 14 Nov). Only someone who really mentored peers: a lost mentor role from anywhere else is not enough.
			AppUser student = peerMentoringService.requireStudent(actorId);
			if (revokedAt(student) == null || !mentoredPeers(student)) {
				throw refused;
			}
			return student;
		}
	}

	/**
	 * How far the mentor's reports reach: now for a mentor, the moment the badge was taken away for a former one.
	 * Meetings, days and weeks after it are not theirs to report on.
	 */
	Instant until(AppUser mentor) {
		Instant now = calendar.now();
		if (mentor.has(UserRole.MENTOR)) {
			return now;
		}
		Instant revoked = revokedAt(mentor);
		return revoked == null || revoked.isAfter(now) ? now : revoked;
	}

	/** The last day the mentor may report on (today, or the day the badge was taken away). */
	LocalDate lastReportableDay(AppUser mentor) {
		return calendar.dateOf(until(mentor));
	}

	/** When the mentor role was last withdrawn; null for someone who holds it, or never had it. */
	private static Instant revokedAt(AppUser user) {
		return user.lastRevoked(UserRole.MENTOR).orElse(null);
	}

	/** One of the mentor's own peer meetings that has started; cancelled, foreign and future meetings are not found. */
	Meeting requireHeldMeeting(AppUser mentor, UUID meetingId) {
		return meetingRepository.findById(meetingId)
				.filter(meeting -> isOwnPeerMeeting(mentor, meeting))
				.filter(meeting -> heldBy(mentor, meeting))
				.orElseThrow(() -> new ResourceNotFoundException("Peer meeting not found: " + meetingId));
	}

	/** The mentor's peer meetings starting in [from, to) that have already started, oldest first. */
	List<Meeting> heldMeetings(AppUser mentor, Instant from, Instant to) {
		Instant reach = until(mentor);
		Instant until = to.isBefore(reach) ? to : reach;
		if (!from.isBefore(until)) {
			return List.of();
		}
		return meetingRepository
				.findByMentorshipRequest_Mentor_IdAndScheduledAtGreaterThanEqualAndScheduledAtLessThanOrderByScheduledAtAsc(
						mentor.getId(), from, until)
				.stream()
				.filter(meeting -> isOwnPeerMeeting(mentor, meeting))
				.toList();
	}

	/** Whether one of the mentor's peer meetings is still to come before {@code to}. */
	boolean meetingsAhead(AppUser mentor, Instant to) {
		if (!mentor.has(UserRole.MENTOR)) {
			// A former mentor's later meetings are not theirs to report on, so they hold nothing back.
			return false;
		}
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

	/** The meetings of the scope's program (all of them in the mentor's own view). */
	List<Meeting> inScope(AppUser mentor, List<Meeting> meetings, PeerReportScope scope) {
		if (!scope.bound()) {
			return meetings;
		}
		UUID mentorsProgram = programOrNull(mentor);
		return meetings.stream()
				.filter(meeting -> scope.inProgram(Optional.ofNullable(meeting.getMentorshipRequest().getProgram())
						.map(Program::getId)
						.orElse(mentorsProgram)))
				.toList();
	}

	/**
	 * The one running peer program the mentor is accepted in; null when there is none. A mentor in two at once cannot
	 * say which program a day's or week's report belongs to, so that is refused (Arya, 14 Nov).
	 */
	UUID programOf(AppUser mentor) {
		List<UUID> ids = programMentorRepository
				.findByMentorIdAndStatusOrderByCreatedAtDesc(mentor.getId(), ProgramMentorStatus.ACCEPTED).stream()
				.map(ProgramMentor::getProgramId)
				.toList();
		List<UUID> running = programRepository.findAllById(ids).stream()
				.filter(program -> program.getProgramType() == ProgramType.PEER_MENTORING)
				.filter(program -> program.getStatus() == ProgramStatus.ACTIVE)
				.map(Program::getId)
				.distinct()
				.toList();
		if (running.size() > 1) {
			throw new ConflictException(Texts.forRequest("error.peerReportProgramAmbiguous"));
		}
		return running.isEmpty() ? null : running.getFirst();
	}

	/** The program a meeting report belongs to: the request's, else the mentor's running one. */
	UUID programOf(AppUser mentor, Meeting meeting) {
		return Optional.ofNullable(meeting.getMentorshipRequest().getProgram())
				.map(Program::getId)
				.orElseGet(() -> programOf(mentor));
	}

	/** As {@link #programOf(AppUser)}, for reading: an ambiguous mentor gives null instead of a 409. */
	UUID programOrNull(AppUser mentor) {
		try {
			return programOf(mentor);
		} catch (ConflictException ambiguous) {
			return null;
		}
	}

	/** As {@link #programOf(AppUser, Meeting)}, for reading (a prefill, a list): never a 409. */
	UUID programOrNull(AppUser mentor, Meeting meeting) {
		return Optional.ofNullable(meeting.getMentorshipRequest().getProgram())
				.map(Program::getId)
				.orElseGet(() -> programOrNull(mentor));
	}

	/** Whether the user ever mentored on a peer request: the evidence a former peer mentor needs. */
	private boolean mentoredPeers(AppUser user) {
		return mentorshipRequestRepository.findByMentorIdOrderByCreatedAtDesc(user.getId()).stream()
				.anyMatch(request -> PeerTopicCategory.fromText(request.getSupportArea()) != null);
	}

	/** Started before now, and for a former mentor before the badge was taken away. */
	private boolean heldBy(AppUser mentor, Meeting meeting) {
		return meeting.getScheduledAt().isBefore(until(mentor));
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
