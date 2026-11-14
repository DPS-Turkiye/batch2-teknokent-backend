package com.dps.teknokent.service;

import java.time.Instant;
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

import com.dps.teknokent.domain.ProgramStatus;
import com.dps.teknokent.domain.TeamChangeStatus;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.responses.ProgramTeamMoveResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.DocumentRequestItem;
import com.dps.teknokent.entity.MentorshipRequest;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramStartup;
import com.dps.teknokent.entity.ProgramTeamMove;
import com.dps.teknokent.entity.TeamChangeRequest;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.DocumentRequestItemRepository;
import com.dps.teknokent.repository.DocumentRequestUploadRepository;
import com.dps.teknokent.repository.MentorshipRequestRepository;
import com.dps.teknokent.repository.OrganizationRepository;
import com.dps.teknokent.repository.ProgramCoordinatorRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.repository.ProgramStartupRepository;
import com.dps.teknokent.repository.ProgramTeamMoveRepository;
import com.dps.teknokent.repository.TeamChangeRequestRepository;
import com.dps.teknokent.util.ProgramCoordination;
import com.dps.teknokent.util.Texts;

import lombok.RequiredArgsConstructor;

/**
 * An admin moves a team from one program to another. The team, its members and everything they entered go along
 * untouched, because none of it is kept per program. What happened in the old program stays there: its requests,
 * mentorships, meetings, attendance and documents keep the old program's id, and a mentorship that is running
 * keeps running under it. Only what nobody answered yet is closed: the team's open requests to the old program's
 * mentors, and the old program's document requests the team has uploaded nothing for.
 */
@Service
@RequiredArgsConstructor
public class ProgramTeamMoveService {

	private static final String TEAM_HREF = "/startup";
	private static final String MENTOR_HREF = "/mentor-dashboard";

	private final AppUserService appUserService;
	private final NotificationService notificationService;
	private final EditionService editionService;
	private final ProgramEligibilityService programEligibilityService;
	private final AppUserRepository appUserRepository;
	private final OrganizationRepository organizationRepository;
	private final ProgramRepository programRepository;
	private final ProgramCoordinatorRepository programCoordinatorRepository;
	private final ProgramStartupRepository programStartupRepository;
	private final ProgramTeamMoveRepository programTeamMoveRepository;
	private final MentorshipRequestRepository mentorshipRequestRepository;
	private final TeamChangeRequestRepository teamChangeRequestRepository;
	private final DocumentRequestItemRepository documentRequestItemRepository;
	private final DocumentRequestUploadRepository documentRequestUploadRepository;

	@Transactional
	public ProgramTeamMoveResponse move(UUID actorId, UUID targetProgramId, UUID organizationId, UUID fromProgramId) {
		AppUser admin = requireAdmin(actorId);
		// Locked until the transaction ends, so two admins moving the same team cannot both succeed.
		Organization team = organizationRepository.findByIdForUpdate(organizationId)
				.orElseThrow(() -> new ResourceNotFoundException("Organization not found: " + organizationId));
		Program target = requireProgram(targetProgramId);
		Program from = requireProgram(fromProgramId);
		// Everything is checked before anything is written, so a refused move leaves no trace.
		if (team.isPersonal()) {
			throw new InvalidRequestException(Texts.forRequest("error.teamMovePersonal"));
		}
		if (team.getArchivedAt() != null) {
			throw new InvalidRequestException(Texts.forRequest("error.teamMoveArchived"));
		}
		if (from.getId().equals(target.getId())) {
			throw new InvalidRequestException(Texts.forRequest("error.teamMoveSameProgram"));
		}
		if (target.getStatus() != ProgramStatus.ACTIVE) {
			throw new InvalidRequestException(Texts.forRequest("error.programNotOpen"));
		}
		if (!editionService.serves(target)) {
			throw new InvalidRequestException(Texts.forRequest("error.teamMoveProgramType"));
		}
		ProgramStartup left = programStartupRepository.findByProgramIdAndOrganizationId(from.getId(), organizationId)
				.orElseThrow(() -> new InvalidRequestException(Texts.forRequest("error.teamNotInProgram")));
		programEligibilityService.requireTeam(target, organizationId);

		Instant now = Instant.now();
		List<AppUser> members = appUserRepository.findByOrganizationIdOrderByFullNameAsc(organizationId);
		moveMembership(team, left, from, target, now);
		approve(team);
		moveMembers(members, from, target);
		moveWaitingTeamChanges(team, from, target);
		List<MentorshipRequest> withdrawn = withdrawOpenRequests(team, from);
		dropUnansweredDocumentRequests(team, from);
		ProgramTeamMove move = programTeamMoveRepository.save(new ProgramTeamMove(
				organizationId, from.getId(), target.getId(), admin.getId(), now, left.getCreatedAt()));

		notifyTeam(team, members, from, target);
		notifyCoordinators(team, from, target);
		notifyMentors(team, withdrawn);
		return ProgramTeamMoveResponse.of(move, from, target, withdrawn.size());
	}

	private void moveMembership(Organization team, ProgramStartup left, Program from, Program target, Instant now) {
		programStartupRepository.delete(left);
		// A team that "Programa ata" had already listed in the target keeps that row.
		if (programStartupRepository.findByProgramIdAndOrganizationId(target.getId(), team.getId()).isEmpty()) {
			ProgramStartup joined = new ProgramStartup();
			joined.setId(UUID.randomUUID());
			joined.setProgramId(target.getId());
			joined.setOrganizationId(team.getId());
			joined.setCreatedAt(now);
			programStartupRepository.save(joined);
		}
		// A team listed in several programs that chose a third one as active keeps its choice.
		if (team.getActiveProgramId() == null || from.getId().equals(team.getActiveProgramId())) {
			team.setActiveProgramId(target.getId());
		}
	}

	/** The admin's word is final, as when the admin adds a startup to a program. */
	private void approve(Organization team) {
		if (team.getApprovalStatus() == UserAccountStatus.PENDING) {
			team.setApprovalStatus(UserAccountStatus.APPROVED);
		}
		organizationRepository.save(team);
		AppUser founder = team.getFounder();
		if (founder != null && founder.getStatus() == UserAccountStatus.PENDING) {
			appUserService.decide(founder, UserAccountStatus.APPROVED);
		}
	}

	/** A member's own program follows the team; one who signed up into some other program keeps theirs. */
	private void moveMembers(List<AppUser> members, Program from, Program target) {
		List<AppUser> followers = members.stream()
				.filter(member -> from.getId().equals(member.getProgramId()))
				.toList();
		followers.forEach(member -> member.setProgramId(target.getId()));
		appUserRepository.saveAll(followers);
	}

	/** A leave or removal still waiting for a coordinator is decided by the new program's coordinators. */
	private void moveWaitingTeamChanges(Organization team, Program from, Program target) {
		List<TeamChangeRequest> waiting = teamChangeRequestRepository
				.findByOrganizationIdAndStatusOrderByCreatedAtAsc(team.getId(), TeamChangeStatus.PENDING)
				.stream()
				.filter(request -> from.getId().equals(request.getProgramId()))
				.toList();
		waiting.forEach(request -> request.setProgramId(target.getId()));
		teamChangeRequestRepository.saveAll(waiting);
	}

	/**
	 * An unanswered request to a mentor of the old program would, once accepted, start a mentorship in the new
	 * program with a mentor who is not in its pool. Answered requests and running mentorships are not touched.
	 */
	private List<MentorshipRequest> withdrawOpenRequests(Organization team, Program from) {
		List<MentorshipRequest> open = mentorshipRequestRepository
				.findByOrganizationIdOrderByCreatedAtDesc(team.getId())
				.stream()
				.filter(request -> request.getStatus().isOpen())
				.filter(request -> request.getProgram() != null && from.getId().equals(request.getProgram().getId()))
				.toList();
		open.forEach(MentorshipRequest::withdraw);
		return mentorshipRequestRepository.saveAll(open);
	}

	/**
	 * The team no longer owes the old program the documents it has not sent. Whatever it did upload stays with
	 * the old program, and {@link DocumentRequestService#replaceOnProgram} leaves it there.
	 */
	private void dropUnansweredDocumentRequests(Organization team, Program from) {
		List<DocumentRequestItem> asked = documentRequestItemRepository
				.findByOrganizationIdOrderByCreatedAtDescSortOrderAsc(team.getId())
				.stream()
				.filter(item -> from.getId().equals(item.getProgramId()))
				.toList();
		if (asked.isEmpty()) {
			return;
		}
		Set<UUID> answered = documentRequestUploadRepository
				.findByItemIdInOrderByCreatedAtAsc(asked.stream().map(DocumentRequestItem::getId).toList())
				.stream()
				.map(upload -> upload.getItem().getId())
				.collect(Collectors.toSet());
		documentRequestItemRepository.deleteAll(
				asked.stream().filter(item -> !answered.contains(item.getId())).toList());
	}

	private void notifyTeam(Organization team, List<AppUser> members, Program from, Program target) {
		Map<UUID, AppUser> recipients = new LinkedHashMap<>();
		if (team.getFounder() != null) {
			recipients.put(team.getFounder().getId(), team.getFounder());
		}
		members.forEach(member -> recipients.putIfAbsent(member.getId(), member));
		recipients.values().stream()
				.filter(member -> member.getStatus() != UserAccountStatus.DELETED)
				.forEach(member -> notificationService.create(member,
						Texts.get(member, "notification.teamMoved.title"),
						Texts.get(member, "notification.teamMoved.body", team.getName(), from.getName(),
								target.getName()),
						TEAM_HREF));
	}

	/** Someone who runs both programs is told once, as the team's new coordinator. */
	private void notifyCoordinators(Organization team, Program from, Program target) {
		Map<UUID, List<AppUser>> runners = ProgramCoordination.runners(
				List.of(from, target), programCoordinatorRepository, appUserRepository);
		List<AppUser> receiving = runners.getOrDefault(target.getId(), List.of());
		receiving.forEach(coordinator -> notifyCoordinator(coordinator, "notification.teamMovedIn", team, from,
				target, target));
		runners.getOrDefault(from.getId(), List.of()).stream()
				.filter(coordinator -> receiving.stream().noneMatch(other -> other.getId().equals(coordinator.getId())))
				.forEach(coordinator -> notifyCoordinator(coordinator, "notification.teamMovedOut", team, from,
						target, from));
	}

	private void notifyCoordinator(AppUser coordinator, String key, Organization team, Program from, Program target,
			Program own) {
		notificationService.create(coordinator, Texts.get(coordinator, key + ".title"),
				Texts.get(coordinator, key + ".body", team.getName(), from.getName(), target.getName()),
				"/coordinator/programs/" + own.getId());
	}

	/** One notice per mentor, however many of the team's requests were waiting on them. */
	private void notifyMentors(Organization team, List<MentorshipRequest> withdrawn) {
		Map<UUID, AppUser> mentors = new LinkedHashMap<>();
		withdrawn.forEach(request -> mentors.putIfAbsent(request.getMentor().getId(), request.getMentor()));
		mentors.values().stream()
				.filter(mentor -> mentor.getStatus() != UserAccountStatus.DELETED)
				.forEach(mentor -> notificationService.create(mentor,
						Texts.get(mentor, "notification.requestClosedByTeamMove.title"),
						Texts.get(mentor, "notification.requestClosedByTeamMove.body", team.getName()),
						MENTOR_HREF));
	}

	private Program requireProgram(UUID programId) {
		return programRepository.findById(programId)
				.orElseThrow(() -> new ResourceNotFoundException("Program not found: " + programId));
	}

	private AppUser requireAdmin(UUID actorId) {
		return appUserRepository.findById(actorId)
				.filter(user -> user.getStatus() == UserAccountStatus.APPROVED && user.has(UserRole.ADMIN))
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,
						Texts.forRequest("error.teamMoveAdminOnly")));
	}
}
