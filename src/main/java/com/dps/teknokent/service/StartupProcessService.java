package com.dps.teknokent.service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.ActionItemStatus;
import com.dps.teknokent.domain.MentorshipRequestStatus;
import com.dps.teknokent.domain.NotificationCategory;
import com.dps.teknokent.domain.StartupStage;
import com.dps.teknokent.domain.StartupStageState;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.CreateProcessAssignmentRequest;
import com.dps.teknokent.dto.requests.CreateStartupRoundRequest;
import com.dps.teknokent.dto.requests.SubmitProcessAssignmentRequest;
import com.dps.teknokent.dto.requests.UpdateStartupStageRequest;
import com.dps.teknokent.dto.responses.ProcessAssignmentResponse;
import com.dps.teknokent.dto.responses.ProcessPhaseResponse;
import com.dps.teknokent.dto.responses.StartupProcessResponse;
import com.dps.teknokent.dto.responses.StartupRoundResponse;
import com.dps.teknokent.dto.responses.StartupStageResponse;
import com.dps.teknokent.entity.ActionItem;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.ProgramPhase;
import com.dps.teknokent.entity.ProgramStartup;
import com.dps.teknokent.entity.StartupRound;
import com.dps.teknokent.entity.StoredDocument;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.ActionItemRepository;
import com.dps.teknokent.repository.MentorshipRequestRepository;
import com.dps.teknokent.repository.OrganizationRepository;
import com.dps.teknokent.repository.ProgramPhaseRepository;
import com.dps.teknokent.repository.ProgramCoordinatorRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.repository.ProgramStartupRepository;
import com.dps.teknokent.repository.StartupRoundRepository;
import com.dps.teknokent.repository.StoredDocumentRepository;
import com.dps.teknokent.util.ProgramCoordination;
import com.dps.teknokent.util.TextUtils;
import com.dps.teknokent.util.Texts;

import lombok.RequiredArgsConstructor;

/**
 * The Süreç tab: the phases of the team's program and where today falls in them, the round it is
 * focused on, and the Ödevler it has been given. Ödevler are action items
 * marked as process assignments, so they also surface wherever action items do.
 */
@Service
@RequiredArgsConstructor
public class StartupProcessService {

	private static final ZoneId PROGRAM_ZONE = ZoneId.of("Europe/Istanbul");

	private final DomainLookupService domainLookupService;
	private final OrganizationRepository organizationRepository;
	private final ProgramRepository programRepository;
	private final ProgramCoordinatorRepository programCoordinatorRepository;
	private final ProgramStartupRepository programStartupRepository;
	private final StartupRoundRepository startupRoundRepository;
	private final ActionItemRepository actionItemRepository;
	private final StoredDocumentRepository storedDocumentRepository;
	private final MentorshipRequestRepository mentorshipRequestRepository;
	private final NotificationService notificationService;
	private final MenteeProgramService menteeProgramService;
	private final ProgramPhaseRepository programPhaseRepository;

	@Transactional(readOnly = true)
	public StartupProcessResponse get(UUID organizationId, UUID actorId) {
		Organization organization = domainLookupService.requireOrganization(organizationId);
		requireTeamOrEditor(organization, actorId);
		return describe(organization, today());
	}

	@Transactional
	public StartupProcessResponse updateStage(UUID organizationId, UpdateStartupStageRequest request) {
		Organization organization = domainLookupService.requireOrganization(organizationId);
		requireStageEditor(organization, request.actorId());

		organization.setCurrentStage(request.stage());
		return describe(organizationRepository.save(organization), today());
	}

	@Transactional
	public StartupProcessResponse createRound(UUID organizationId, CreateStartupRoundRequest request) {
		Organization organization = domainLookupService.requireOrganization(organizationId);
		AppUser actor = requireEditor(organization, request.actorId());
		if (request.endsOn().isBefore(request.startsOn())) {
			throw new InvalidRequestException("A round cannot end before it starts");
		}

		StartupRound round = new StartupRound();
		round.setOrganization(organization);
		round.setTitle(TextUtils.trim(request.title()));
		round.setName(TextUtils.trim(request.name()));
		round.setStage(request.stage());
		round.setProgramPhaseId(requirePhaseOf(organization, request.phaseId()));
		round.setStartsOn(request.startsOn());
		round.setEndsOn(request.endsOn());
		round.setCreatedBy(actor);
		startupRoundRepository.saveAndFlush(round);
		return describe(organization, today());
	}

	@Transactional
	public StartupProcessResponse createAssignment(UUID organizationId, CreateProcessAssignmentRequest request) {
		Organization organization = domainLookupService.requireOrganization(organizationId);
		AppUser actor = requireEditor(organization, request.actorId());
		StartupRound round = request.roundId() == null
				? null
				: startupRoundRepository.findByIdAndOrganizationId(request.roundId(), organizationId)
						.orElseThrow(() -> new ResourceNotFoundException("Round not found: " + request.roundId()));
		StartupStage stage = request.stage() != null ? request.stage() : round == null ? null : round.getStage();
		// An Ödev made inside a round belongs where the round does, whether the round names its phase or is
		// placed by its start.
		UUID phaseId = request.phaseId() != null ? requirePhaseOf(organization, request.phaseId())
				: round == null ? null
						: linkedOrCovering(round.getProgramPhaseId(), round.getStartsOn(), phasesOf(organization));

		ActionItem item = new ActionItem();
		item.setOrganization(organization);
		item.setOwner(organization.getFounder() != null
				? organization.getFounder()
				: domainLookupService.requireOrganizationMentee(organizationId));
		item.setTitle(TextUtils.trim(request.title()));
		item.setDescription(TextUtils.normalizeOptional(request.description()));
		item.setStatus(ActionItemStatus.OPEN);
		item.setDueDate(request.dueDate());
		item.setProcessAssignment(true);
		item.setStage(stage);
		item.setProgramPhaseId(phaseId);
		item.setRound(round);
		item.setCreatedBy(actor);
		actionItemRepository.saveAndFlush(item);
		if (!actor.getId().equals(item.getOwner().getId())) {
			notificationService.create(
					item.getOwner(),
					NotificationCategory.TASK_ASSIGNED,
					Texts.get(item.getOwner(), "notification.newTask.title"),
					Texts.get(item.getOwner(), "notification.newTask.body", actor.getFullName(), item.getTitle()),
					"/startup/process");
		}
		return describe(organization, today());
	}

	@Transactional
	public StartupProcessResponse submitAssignment(
			UUID organizationId, UUID assignmentId, SubmitProcessAssignmentRequest request) {
		Organization organization = domainLookupService.requireOrganization(organizationId);
		requireTeamOrEditor(organization, request.actorId());
		ActionItem item = actionItemRepository.findByIdAndOrganizationId(assignmentId, organizationId)
				.filter(StartupProcessService::isAssignment)
				.orElseThrow(() -> new ResourceNotFoundException("Assignment not found: " + assignmentId));
		StoredDocument document = storedDocumentRepository.findById(request.documentId())
				.orElseThrow(() -> new ResourceNotFoundException("Document not found: " + request.documentId()));
		if (!organizationId.equals(document.getOrganizationId())) {
			throw new InvalidRequestException("The submitted document belongs to another startup");
		}

		item.setSubmissionDocumentId(document.getId());
		item.setStatus(ActionItemStatus.COMPLETED);
		actionItemRepository.saveAndFlush(item);
		return describe(organization, today());
	}

	StartupProcessResponse describe(Organization organization, LocalDate today) {
		List<StartupRound> rounds = startupRoundRepository.findByOrganizationIdOrderByStartsOnDesc(organization.getId());
		List<ActionItem> assignments = actionItemRepository
				.findByOrganizationIdOrderByCreatedAtDesc(organization.getId()).stream()
				.filter(StartupProcessService::isAssignment)
				.toList()
				.reversed();
		Map<UUID, String> filenames = submissionFilenames(assignments);
		StartupRound currentRound = pickCurrentRound(rounds, today);
		List<ProgramPhase> phases = phasesOf(organization);
		List<ProcessPhaseResponse> cards = phaseCards(phases, today);

		return new StartupProcessResponse(
				organization.getId(),
				organization.getCurrentStage(),
				stages(organization.getCurrentStage()),
				currentRound == null ? null : roundOf(currentRound, phases),
				rounds.stream().map(round -> roundOf(round, phases)).toList(),
				assignments.stream()
						.map(item -> ProcessAssignmentResponse.fromEntity(item,
								filenames.get(item.getSubmissionDocumentId()), phaseOf(item, phases)))
						.toList(),
				cards.stream()
						.filter(card -> card.state() == StartupStageState.CURRENT)
						.map(ProcessPhaseResponse::id)
						.findFirst()
						.orElse(null),
				cards);
	}

	/**
	 * A Süreç Ödev. The stage check covers rows an older backend wrote while V103 was being rolled out; it goes
	 * with the stage column.
	 */
	static boolean isAssignment(ActionItem item) {
		return item.isProcessAssignment() || item.getStage() != null;
	}

	/** The phases of the team's program, in order; none without a program. */
	private List<ProgramPhase> phasesOf(Organization organization) {
		return menteeProgramService.programOf(organization)
				.map(programPhaseRepository::findByProgramIdOrderByPositionAsc)
				.orElse(List.of());
	}

	/** A phase of the team's program, or a 404 for any other id. */
	private UUID requirePhaseOf(Organization organization, UUID phaseId) {
		if (phaseId == null) {
			return null;
		}
		return phasesOf(organization).stream()
				.map(ProgramPhase::getId)
				.filter(phaseId::equals)
				.findFirst()
				.orElseThrow(() -> new ResourceNotFoundException("Phase not found: " + phaseId));
	}

	static List<ProcessPhaseResponse> phaseCards(List<ProgramPhase> phases, LocalDate today) {
		return phases.stream()
				.map(phase -> new ProcessPhaseResponse(phase.getId(), phase.getName(), phase.getPosition(),
						phase.getStartDate(), phase.getEndDate(), phase.getExpectedOutput(), stateOf(phase, today)))
				.toList();
	}

	private static StartupStageState stateOf(ProgramPhase phase, LocalDate today) {
		if (phase.getEndDate() != null && phase.getEndDate().isBefore(today)) {
			return StartupStageState.COMPLETED;
		}
		return covers(phase, today) ? StartupStageState.CURRENT : StartupStageState.UPCOMING;
	}

	/**
	 * The phase an Ödev belongs to: the one it names while that is still a phase of the program, else the one its
	 * due date (or the day it was made) falls in; null when none does.
	 */
	static UUID phaseOf(ActionItem item, List<ProgramPhase> phases) {
		LocalDate day = item.getDueDate() != null ? item.getDueDate()
				: item.getCreatedAt() == null ? null : LocalDate.ofInstant(item.getCreatedAt(), PROGRAM_ZONE);
		return linkedOrCovering(item.getProgramPhaseId(), day, phases);
	}

	private static StartupRoundResponse roundOf(StartupRound round, List<ProgramPhase> phases) {
		return StartupRoundResponse.fromEntity(round,
				linkedOrCovering(round.getProgramPhaseId(), round.getStartsOn(), phases));
	}

	private static UUID linkedOrCovering(UUID linked, LocalDate day, List<ProgramPhase> phases) {
		if (linked != null && phases.stream().anyMatch(phase -> linked.equals(phase.getId()))) {
			return linked;
		}
		if (day == null) {
			return null;
		}
		return phases.stream().filter(phase -> covers(phase, day)).map(ProgramPhase::getId).findFirst().orElse(null);
	}

	private static boolean covers(ProgramPhase phase, LocalDate day) {
		return phase.getStartDate() != null && !phase.getStartDate().isAfter(day)
				&& (phase.getEndDate() == null || !phase.getEndDate().isBefore(day));
	}

	/** The round running today; failing that the next one planned; failing that the last one held. */
	static StartupRound pickCurrentRound(List<StartupRound> rounds, LocalDate today) {
		Comparator<StartupRound> byStart = Comparator.comparing(StartupRound::getStartsOn);
		return rounds.stream().filter(round -> round.covers(today)).max(byStart)
				.or(() -> rounds.stream().filter(round -> round.getStartsOn().isAfter(today)).min(byStart))
				.or(() -> rounds.stream().max(byStart))
				.orElse(null);
	}

	static List<StartupStageResponse> stages(StartupStage current) {
		return Arrays.stream(StartupStage.values())
				.map(stage -> new StartupStageResponse(stage, stateOf(stage, current)))
				.toList();
	}

	private static StartupStageState stateOf(StartupStage stage, StartupStage current) {
		if (current == null) {
			return StartupStageState.UPCOMING;
		}
		int order = stage.compareTo(current);
		if (order < 0) {
			return StartupStageState.COMPLETED;
		}
		return order == 0 ? StartupStageState.CURRENT : StartupStageState.UPCOMING;
	}

	private Map<UUID, String> submissionFilenames(List<ActionItem> assignments) {
		Set<UUID> documentIds = assignments.stream()
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

	// Founders and mentors see the stage on Süreç but do not set it.
	private void requireStageEditor(Organization organization, UUID actorId) {
		AppUser actor = domainLookupService.requireUser(actorId);
		if (actor.has(UserRole.ADMIN)) {
			return;
		}
		boolean coordinatesIt = actor.has(UserRole.COORDINATOR)
				&& programStartupRepository.findByOrganizationIdOrderByCreatedAtAsc(organization.getId()).stream()
						.map(ProgramStartup::getProgramId)
						.map(programRepository::findById)
						.flatMap(Optional::stream)
						.anyMatch(program -> ProgramCoordination.coordinates(
								program, actor.getId(), programCoordinatorRepository));
		if (!coordinatesIt) {
			throw new InvalidRequestException(
					"Only an admin or a coordinator of this startup's program can change its stage");
		}
	}

	// Founder, admin, coordinator, or a mentor this startup has an accepted mentorship with.
	private AppUser requireEditor(Organization organization, UUID actorId) {
		AppUser actor = domainLookupService.requireUser(actorId);
		if (!isEditor(organization, actor)) {
			throw new InvalidRequestException(
					"Only the founder, a mentor of this startup, an admin or a coordinator can change its process");
		}
		return actor;
	}

	private void requireTeamOrEditor(Organization organization, UUID actorId) {
		AppUser actor = domainLookupService.requireUser(actorId);
		boolean teamMember = actor.getOrganization() != null
				&& organization.getId().equals(actor.getOrganization().getId());
		if (!teamMember && !isEditor(organization, actor)) {
			throw new InvalidRequestException("Only the startup's team, its mentors, an admin or a coordinator can do this");
		}
	}

	private boolean isEditor(Organization organization, AppUser actor) {
		if (actor.has(UserRole.ADMIN) || actor.has(UserRole.COORDINATOR)) {
			return true;
		}
		if (organization.getFounder() != null && organization.getFounder().getId().equals(actor.getId())) {
			return true;
		}
		return actor.has(UserRole.MENTOR) && mentorshipRequestRepository
				.findFirstByMentorIdAndOrganizationIdAndStatusOrderByCreatedAtDesc(
						actor.getId(), organization.getId(), MentorshipRequestStatus.ACCEPTED)
				.isPresent();
	}

	private static LocalDate today() {
		return LocalDate.now(PROGRAM_ZONE);
	}
}
