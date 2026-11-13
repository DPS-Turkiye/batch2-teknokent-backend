package com.dps.teknokent.service;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.dps.teknokent.domain.MentorshipRequestStatus;
import com.dps.teknokent.domain.ProgramMentorStatus;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.responses.ProgramMentorRemovalResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.MentorshipRequest;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramMentor;
import com.dps.teknokent.entity.ProgramMentorRemoval;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.MentorshipRequestRepository;
import com.dps.teknokent.repository.ProgramCoordinatorRepository;
import com.dps.teknokent.repository.ProgramMentorInviteRepository;
import com.dps.teknokent.repository.ProgramMentorRemovalRepository;
import com.dps.teknokent.repository.ProgramMentorRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.util.ProgramCoordination;
import com.dps.teknokent.util.Texts;

import lombok.RequiredArgsConstructor;

/**
 * An admin takes an accepted mentor out of a program. The mentor leaves that program's pool, so its teams no
 * longer see, get recommended or can ask them; their other programs are untouched. What already began in the
 * program stays there and goes on: mentorships, booked meetings and their history keep the program's id. Only
 * what nobody answered yet is closed: the open requests the program's teams had sent to this mentor.
 */
@Service
@RequiredArgsConstructor
public class ProgramMentorRemovalService {

	private static final String MENTOR_HREF = "/mentor-dashboard";
	private static final String REQUEST_HREF = "/mentor-request/status?request=";
	private static final Set<MentorshipRequestStatus> OPEN_REQUESTS = Arrays.stream(MentorshipRequestStatus.values())
			.filter(MentorshipRequestStatus::isOpen)
			.collect(Collectors.toUnmodifiableSet());

	private final NotificationService notificationService;
	private final EditionService editionService;
	private final AppUserRepository appUserRepository;
	private final ProgramRepository programRepository;
	private final ProgramCoordinatorRepository programCoordinatorRepository;
	private final ProgramMentorRepository programMentorRepository;
	private final ProgramMentorInviteRepository programMentorInviteRepository;
	private final ProgramMentorRemovalRepository programMentorRemovalRepository;
	private final MentorshipRequestRepository mentorshipRequestRepository;

	@Transactional
	public ProgramMentorRemovalResponse remove(UUID actorId, UUID programId, UUID mentorId) {
		AppUser admin = requireAdmin(actorId);
		Program program = programRepository.findById(programId)
				.orElseThrow(() -> new ResourceNotFoundException("Program not found: " + programId));
		AppUser mentor = appUserRepository.findById(mentorId)
				.orElseThrow(() -> new ResourceNotFoundException(Texts.forRequest("error.mentorNotFound", mentorId)));
		// Everything is checked before anything is written, so a refused removal leaves no trace.
		if (!editionService.serves(program)) {
			throw new InvalidRequestException(Texts.forRequest("error.mentorRemovalProgramType"));
		}
		ProgramMentor membership = programMentorRepository.findByProgramIdAndMentorIdForUpdate(programId, mentorId)
				.orElseThrow(() -> new InvalidRequestException(Texts.forRequest("error.mentorRemovalNotInProgram")));
		// An invitation or a request to join that is still open has its own way back in the mentor pool.
		if (membership.getStatus() != ProgramMentorStatus.ACCEPTED) {
			throw new InvalidRequestException(Texts.forRequest("error.mentorRemovalNotAccepted"));
		}

		// Deleted, not marked: every reader of the pool already takes "no row" as "not in the program", and
		// "Programa ata" simply makes a new one.
		programMentorRepository.delete(membership);
		// The signup link the mentor once joined with belonged to that membership; left behind, it would make a
		// later request to join look invited.
		programMentorInviteRepository.findByProgramIdAndMentorId(programId, mentorId)
				.ifPresent(programMentorInviteRepository::delete);
		List<MentorshipRequest> withdrawn = withdrawOpenRequests(mentor, program);
		ProgramMentorRemoval removal = programMentorRemovalRepository.save(new ProgramMentorRemoval(
				mentorId, programId, admin.getId(), Instant.now(), membership.getCreatedAt()));

		notifyMentor(mentor, program);
		notifyCoordinators(admin, mentor, program);
		notifyRequesters(mentor, program, withdrawn);
		return ProgramMentorRemovalResponse.of(removal, program, withdrawn.size());
	}

	/**
	 * An unanswered request would, once accepted, start a mentorship in a program the mentor is no longer in.
	 * Answered requests and running mentorships are not touched, and neither is a request made in another program.
	 */
	private List<MentorshipRequest> withdrawOpenRequests(AppUser mentor, Program program) {
		List<MentorshipRequest> open = mentorshipRequestRepository
				.findByMentorAndProgramForUpdate(mentor.getId(), program.getId(), OPEN_REQUESTS);
		open.forEach(MentorshipRequest::withdraw);
		return mentorshipRequestRepository.saveAll(open);
	}

	private void notifyMentor(AppUser mentor, Program program) {
		if (mentor.getStatus() == UserAccountStatus.DELETED) {
			return;
		}
		notificationService.create(mentor, Texts.get(mentor, "notification.removedFromProgram.title"),
				Texts.get(mentor, "notification.removedFromProgram.body", program.getName()), MENTOR_HREF);
	}

	/** The admin who did it is not told, even when they are the one running the program. */
	private void notifyCoordinators(AppUser admin, AppUser mentor, Program program) {
		ProgramCoordination.runners(program, programCoordinatorRepository, appUserRepository).stream()
				.filter(coordinator -> !coordinator.getId().equals(admin.getId()))
				.forEach(coordinator -> notificationService.create(coordinator,
						Texts.get(coordinator, "notification.mentorRemovedFromProgram.title"),
						Texts.get(coordinator, "notification.mentorRemovedFromProgram.body", mentor.getFullName(),
								program.getName()),
						"/coordinator/programs/" + program.getId()));
	}

	/** One notice per person who asked, however many of their requests were waiting on this mentor. */
	private void notifyRequesters(AppUser mentor, Program program, List<MentorshipRequest> withdrawn) {
		Map<UUID, MentorshipRequest> newestByRequester = new LinkedHashMap<>();
		withdrawn.forEach(request -> newestByRequester.putIfAbsent(request.getRequester().getId(), request));
		newestByRequester.values().stream()
				.filter(request -> request.getRequester().getStatus() != UserAccountStatus.DELETED)
				.forEach(request -> notificationService.create(request.getRequester(),
						Texts.get(request.getRequester(), "notification.requestClosedByMentorRemoval.title"),
						Texts.get(request.getRequester(), "notification.requestClosedByMentorRemoval.body",
								mentor.getFullName(), program.getName()),
						REQUEST_HREF + request.getId()));
	}

	private AppUser requireAdmin(UUID actorId) {
		return appUserRepository.findById(actorId)
				.filter(user -> user.getStatus() == UserAccountStatus.APPROVED && user.has(UserRole.ADMIN))
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,
						Texts.forRequest("error.mentorRemovalAdminOnly")));
	}
}
