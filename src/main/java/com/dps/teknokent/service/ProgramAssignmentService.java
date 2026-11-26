package com.dps.teknokent.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.ActionItemStatus;
import com.dps.teknokent.domain.NotificationCategory;
import com.dps.teknokent.domain.ProgramAssignmentSubmissionState;
import com.dps.teknokent.domain.ProgramStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.CreateProgramAssignmentRequest;
import com.dps.teknokent.dto.responses.ProgramAssignmentResponse;
import com.dps.teknokent.entity.ActionItem;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramAssignment;
import com.dps.teknokent.entity.ProgramPhase;
import com.dps.teknokent.entity.ProgramStartup;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.ActionItemRepository;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.OrganizationRepository;
import com.dps.teknokent.repository.ProgramAssignmentRepository;
import com.dps.teknokent.repository.ProgramPhaseRepository;
import com.dps.teknokent.repository.ProgramStartupRepository;
import com.dps.teknokent.repository.StoredDocumentRepository;
import com.dps.teknokent.util.MeetingSlotTimes;
import com.dps.teknokent.util.TextUtils;
import com.dps.teknokent.util.Texts;

/**
 * "Ödev ver": a coordinator gives one Ödev to every team of a program. Each team gets its own Süreç copy and hands
 * it in there; the coordinator's list shows who did. A team that joins later gets the program's Ödevler that are
 * still open (no due date, or due today or later) the next time its Süreç or the coordinator's list is read.
 */
@Service
public class ProgramAssignmentService {

	private static final Comparator<ProgramAssignmentResponse.TeamSubmission> BY_TEAM_NAME = Comparator
			.comparing(team -> team.organizationName().toLowerCase(Locale.ROOT));

	private final CoordinatorProgramService coordinatorProgramService;
	private final ProgramAssignmentRepository programAssignmentRepository;
	private final ActionItemRepository actionItemRepository;
	private final ProgramStartupRepository programStartupRepository;
	private final ProgramPhaseRepository programPhaseRepository;
	private final OrganizationRepository organizationRepository;
	private final AppUserRepository appUserRepository;
	private final StoredDocumentRepository storedDocumentRepository;
	private final NotificationService notificationService;
	private final Clock clock;

	@Autowired
	public ProgramAssignmentService(CoordinatorProgramService coordinatorProgramService,
			ProgramAssignmentRepository programAssignmentRepository, ActionItemRepository actionItemRepository,
			ProgramStartupRepository programStartupRepository, ProgramPhaseRepository programPhaseRepository,
			OrganizationRepository organizationRepository, AppUserRepository appUserRepository,
			StoredDocumentRepository storedDocumentRepository, NotificationService notificationService) {
		this(coordinatorProgramService, programAssignmentRepository, actionItemRepository, programStartupRepository,
				programPhaseRepository, organizationRepository, appUserRepository, storedDocumentRepository,
				notificationService, Clock.systemUTC());
	}

	ProgramAssignmentService(CoordinatorProgramService coordinatorProgramService,
			ProgramAssignmentRepository programAssignmentRepository, ActionItemRepository actionItemRepository,
			ProgramStartupRepository programStartupRepository, ProgramPhaseRepository programPhaseRepository,
			OrganizationRepository organizationRepository, AppUserRepository appUserRepository,
			StoredDocumentRepository storedDocumentRepository, NotificationService notificationService,
			Clock clock) {
		this.coordinatorProgramService = coordinatorProgramService;
		this.programAssignmentRepository = programAssignmentRepository;
		this.actionItemRepository = actionItemRepository;
		this.programStartupRepository = programStartupRepository;
		this.programPhaseRepository = programPhaseRepository;
		this.organizationRepository = organizationRepository;
		this.appUserRepository = appUserRepository;
		this.storedDocumentRepository = storedDocumentRepository;
		this.notificationService = notificationService;
		this.clock = clock;
	}

	/** The program's Ödevler, newest first, each with every current team's standing. */
	@Transactional
	public List<ProgramAssignmentResponse> list(UUID actorId, UUID programId) {
		Program program = coordinatorProgramService.requireManagedProgram(actorId, programId);
		List<Organization> teams = teamsOf(program.getId());
		List<ProgramAssignment> assignments = programAssignmentRepository
				.findByProgramIdOrderByCreatedAtDesc(program.getId());
		handOutMissing(assignments, teams);
		return describe(assignments, teams);
	}

	/** Creates the Ödev and hands a copy to every current team of the program. */
	@Transactional
	public ProgramAssignmentResponse create(UUID actorId, UUID programId, CreateProgramAssignmentRequest request) {
		Program program = coordinatorProgramService.requireManagedProgram(actorId, programId);
		if (program.getStatus() == ProgramStatus.COMPLETED) {
			throw new InvalidRequestException(Texts.forRequest("error.programAssignmentArchived"));
		}
		// A due date already behind would make every team late the moment it is given.
		if (request.dueDate() != null && request.dueDate().isBefore(today())) {
			throw new InvalidRequestException(Texts.forRequest("error.programAssignmentDuePassed"));
		}
		AppUser actor = appUserRepository.findById(actorId)
				.orElseThrow(() -> new ResourceNotFoundException("User not found: " + actorId));

		ProgramAssignment assignment = new ProgramAssignment();
		assignment.setId(UUID.randomUUID());
		assignment.setProgramId(program.getId());
		assignment.setTitle(TextUtils.trim(request.title()));
		assignment.setDescription(TextUtils.normalizeOptional(request.description()));
		assignment.setDueDate(request.dueDate());
		assignment.setProgramPhaseId(requirePhaseOf(program.getId(), request.phaseId()));
		assignment.setFileRequired(request.fileRequired());
		assignment.setCreatedBy(actor);
		assignment.setCreatedAt(clock.instant());
		programAssignmentRepository.saveAndFlush(assignment);

		List<Organization> teams = teamsOf(program.getId());
		handOutMissing(List.of(assignment), teams);
		return describe(List.of(assignment), teams).getFirst();
	}

	/**
	 * Gives a team that joined after a program Ödev was made the ones still open. Ödevler whose due date has passed
	 * are left out: the team could only ever be late on them.
	 */
	@Transactional
	public void catchUp(Organization team) {
		if (!team.isApprovedForProgram()) {
			return;
		}
		Set<UUID> programIds = programsOf(team);
		if (programIds.isEmpty()) {
			return;
		}
		Set<UUID> held = new HashSet<>(actionItemRepository.findProgramAssignmentIdsOfOrganization(team.getId()));
		LocalDate today = today();
		for (ProgramAssignment assignment : programAssignmentRepository.findByProgramIdIn(programIds)) {
			if (!held.contains(assignment.getId()) && assignment.openOn(today)) {
				handOut(assignment, team);
			}
		}
	}

	private void handOutMissing(List<ProgramAssignment> assignments, List<Organization> teams) {
		if (assignments.isEmpty() || teams.isEmpty()) {
			return;
		}
		Set<String> held = actionItemRepository
				.findByProgramAssignmentIdIn(assignments.stream().map(ProgramAssignment::getId).toList())
				.stream()
				.map(item -> key(item.getProgramAssignment().getId(), item.getOrganization().getId()))
				.collect(Collectors.toSet());
		LocalDate today = today();
		for (ProgramAssignment assignment : assignments) {
			if (!assignment.openOn(today)) {
				continue;
			}
			for (Organization team : teams) {
				if (!held.contains(key(assignment.getId(), team.getId()))) {
					handOut(assignment, team);
				}
			}
		}
		actionItemRepository.flush();
	}

	private void handOut(ProgramAssignment assignment, Organization team) {
		AppUser owner = ownerOf(team);
		if (owner == null) {
			return;
		}
		ActionItem item = new ActionItem();
		item.setOrganization(team);
		item.setOwner(owner);
		item.setTitle(assignment.getTitle());
		item.setDescription(assignment.getDescription());
		item.setStatus(ActionItemStatus.OPEN);
		item.setDueDate(assignment.getDueDate());
		item.setProcessAssignment(true);
		item.setProgramPhaseId(assignment.getProgramPhaseId());
		item.setCreatedBy(assignment.getCreatedBy());
		item.setProgramAssignment(assignment);
		actionItemRepository.save(item);
		AppUser creator = assignment.getCreatedBy();
		if (creator == null || !creator.getId().equals(owner.getId())) {
			notificationService.createAndMail(
					owner,
					NotificationCategory.TASK_ASSIGNED,
					Texts.get(owner, "notification.newTask.title"),
					Texts.get(owner, "notification.programTask.body", assignment.getTitle()),
					"/startup/process");
		}
	}

	private List<ProgramAssignmentResponse> describe(List<ProgramAssignment> assignments, List<Organization> teams) {
		if (assignments.isEmpty()) {
			return List.of();
		}
		Set<UUID> current = teams.stream().map(Organization::getId).collect(Collectors.toSet());
		// A team that has since left the program keeps its copy, but no longer shows in the program's list.
		Map<UUID, List<ActionItem>> copies = actionItemRepository
				.findByProgramAssignmentIdIn(assignments.stream().map(ProgramAssignment::getId).toList())
				.stream()
				.filter(item -> current.contains(item.getOrganization().getId()))
				.collect(Collectors.groupingBy(item -> item.getProgramAssignment().getId()));
		Map<UUID, String> filenames = filenames(copies.values().stream().flatMap(List::stream).toList());
		LocalDate today = today();
		List<ProgramAssignmentResponse> rows = new ArrayList<>();
		for (ProgramAssignment assignment : assignments) {
			List<ProgramAssignmentResponse.TeamSubmission> standing = copies
					.getOrDefault(assignment.getId(), List.of())
					.stream()
					.map(item -> new ProgramAssignmentResponse.TeamSubmission(
							item.getOrganization().getId(),
							item.getOrganization().getName(),
							item.getId(),
							stateOf(item, assignment, today),
							handedIn(item, assignment) ? handedInAt(item) : null,
							item.getSubmissionDocumentId(),
							filenames.get(item.getSubmissionDocumentId())))
					.sorted(BY_TEAM_NAME)
					.toList();
			Map<ProgramAssignmentSubmissionState, Long> counts = standing.stream()
					.collect(Collectors.groupingBy(ProgramAssignmentResponse.TeamSubmission::state,
							Collectors.counting()));
			AppUser creator = assignment.getCreatedBy();
			rows.add(new ProgramAssignmentResponse(
					assignment.getId(),
					assignment.getProgramId(),
					assignment.getTitle(),
					assignment.getDescription(),
					assignment.getDueDate(),
					assignment.getProgramPhaseId(),
					assignment.isFileRequired(),
					creator == null ? null : creator.getFullName(),
					assignment.getCreatedAt(),
					count(counts, ProgramAssignmentSubmissionState.SUBMITTED),
					count(counts, ProgramAssignmentSubmissionState.LATE),
					count(counts, ProgramAssignmentSubmissionState.OVERDUE),
					count(counts, ProgramAssignmentSubmissionState.OPEN),
					standing));
		}
		return rows;
	}

	/** Handed in on time, late, not handed in past the due date, or still open. */
	static ProgramAssignmentSubmissionState stateOf(ActionItem item, ProgramAssignment assignment, LocalDate today) {
		LocalDate due = assignment.getDueDate();
		if (handedIn(item, assignment)) {
			Instant at = handedInAt(item);
			boolean late = due != null && at != null
					&& LocalDate.ofInstant(at, MeetingSlotTimes.APP_ZONE).isAfter(due);
			return late ? ProgramAssignmentSubmissionState.LATE : ProgramAssignmentSubmissionState.SUBMITTED;
		}
		return due != null && due.isBefore(today)
				? ProgramAssignmentSubmissionState.OVERDUE
				: ProgramAssignmentSubmissionState.OPEN;
	}

	/** With a file when the Ödev asks for one; otherwise a file or marking it done is enough. */
	private static boolean handedIn(ActionItem item, ProgramAssignment assignment) {
		if (item.getSubmissionDocumentId() != null) {
			return true;
		}
		return !assignment.isFileRequired() && item.getStatus() == ActionItemStatus.COMPLETED;
	}

	/** A copy marked done some other way than the Süreç hand-in has no hand-in time; its last change stands in. */
	private static @Nullable Instant handedInAt(ActionItem item) {
		return item.getSubmittedAt() != null ? item.getSubmittedAt() : item.getUpdatedAt();
	}

	private static int count(Map<ProgramAssignmentSubmissionState, Long> counts,
			ProgramAssignmentSubmissionState state) {
		return counts.getOrDefault(state, 0L).intValue();
	}

	/**
	 * The program's startups and its students' personal teams, as the program's reports count them, less any team
	 * still waiting for approval or archived.
	 */
	private List<Organization> teamsOf(UUID programId) {
		Set<UUID> ids = new LinkedHashSet<>();
		for (ProgramStartup startup : programStartupRepository.findByProgramIdOrderByCreatedAtAsc(programId)) {
			ids.add(startup.getOrganizationId());
		}
		for (AppUser student : appUserRepository.findPersonalTeamStudentsOfProgram(programId)) {
			ids.add(student.getOrganization().getId());
		}
		if (ids.isEmpty()) {
			return List.of();
		}
		return organizationRepository.findAllById(ids).stream()
				.filter(Organization::isApprovedForProgram)
				.toList();
	}

	/** Every program the team is listed in; a personal team belongs to its student's program. */
	private Set<UUID> programsOf(Organization team) {
		Set<UUID> programIds = new HashSet<>();
		if (team.isPersonal()) {
			AppUser student = team.getFounder();
			if (student != null && student.getProgramId() != null) {
				programIds.add(student.getProgramId());
			}
			return programIds;
		}
		programStartupRepository.findByOrganizationIdOrderByCreatedAtAsc(team.getId())
				.forEach(startup -> programIds.add(startup.getProgramId()));
		return programIds;
	}

	/** The founder holds the Ödev, as with any Süreç Ödev; a team without one gives it to a mentee. */
	private @Nullable AppUser ownerOf(Organization team) {
		if (team.getFounder() != null) {
			return team.getFounder();
		}
		return appUserRepository.findByOrganizationIdOrderByFullNameAsc(team.getId()).stream()
				.filter(user -> user.has(UserRole.MENTEE))
				.findFirst()
				.orElse(null);
	}

	private @Nullable UUID requirePhaseOf(UUID programId, @Nullable UUID phaseId) {
		if (phaseId == null) {
			return null;
		}
		return programPhaseRepository.findByProgramIdOrderByPositionAsc(programId).stream()
				.map(ProgramPhase::getId)
				.filter(phaseId::equals)
				.findFirst()
				.orElseThrow(() -> new ResourceNotFoundException("Phase not found: " + phaseId));
	}

	private Map<UUID, String> filenames(List<ActionItem> items) {
		Set<UUID> documentIds = items.stream()
				.map(ActionItem::getSubmissionDocumentId)
				.filter(Objects::nonNull)
				.collect(Collectors.toSet());
		Map<UUID, String> filenames = new HashMap<>();
		if (!documentIds.isEmpty()) {
			storedDocumentRepository.findAllById(documentIds)
					.forEach(document -> filenames.put(document.getId(), document.getOriginalFilename()));
		}
		return filenames;
	}

	private static String key(UUID assignmentId, UUID organizationId) {
		return assignmentId + ":" + organizationId;
	}

	private LocalDate today() {
		return LocalDate.now(clock.withZone(MeetingSlotTimes.APP_ZONE));
	}
}
