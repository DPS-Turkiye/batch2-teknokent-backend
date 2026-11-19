package com.dps.teknokent.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.MentorLabelLimits;
import com.dps.teknokent.domain.ProgramMentorStatus;
import com.dps.teknokent.domain.ProgramStatus;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.MentorLabelRequest;
import com.dps.teknokent.dto.responses.MentorLabelResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramMentor;
import com.dps.teknokent.entity.ProgramMentorLabel;
import com.dps.teknokent.entity.ProgramMentorLabelAssignment;
import com.dps.teknokent.exception.ConflictException;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.MentorProgramName;
import com.dps.teknokent.repository.ProgramMentorLabelAssignmentRepository;
import com.dps.teknokent.repository.ProgramMentorLabelRepository;
import com.dps.teknokent.repository.ProgramMentorRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.util.Texts;

import lombok.RequiredArgsConstructor;

/**
 * Mentor labels: each program keeps its own list, its coordinators (and admins, as on the rest of Mentor Havuzu) give
 * a pool mentor up to {@value MentorLabelLimits#PER_MENTOR} of them, and only people in that program see them. Nothing
 * shows, and nothing can be changed, while the program's "Mentor etiketleri" switch is off; the rows stay.
 */
@Service
@RequiredArgsConstructor
public class MentorLabelService {

	private static final Pattern INVISIBLE = Pattern.compile("[\\p{Cf}\\p{Cc}&&[^\\s]]");
	private static final Pattern SPACES = Pattern.compile("[\\p{Z}\\s]+");
	private static final Comparator<ProgramMentorLabel> BY_NAME = Comparator.comparing(ProgramMentorLabel::getName,
			String.CASE_INSENSITIVE_ORDER);

	private final CoordinatorProgramService coordinatorProgramService;
	private final MenteeProgramService menteeProgramService;
	private final ProgramRepository programRepository;
	private final ProgramMentorRepository programMentorRepository;
	private final ProgramMentorLabelRepository labelRepository;
	private final ProgramMentorLabelAssignmentRepository assignmentRepository;
	private final AppUserRepository appUserRepository;

	/** Mentor Havuzu's view: the program's list and who carries what. */
	@Transactional(readOnly = true)
	public MentorLabelResponse.Board board(UUID actorId, UUID programId) {
		return boardOf(coordinatorProgramService.requireManagedProgram(actorId, programId));
	}

	@Transactional
	public MentorLabelResponse.Board create(UUID actorId, UUID programId, MentorLabelRequest request) {
		Program program = requireLabelEditor(actorId, programId);
		List<ProgramMentorLabel> existing = labelRepository.findByProgramIdOrderByNameAsc(programId);
		if (existing.size() >= MentorLabelLimits.PER_PROGRAM) {
			throw new InvalidRequestException(
					Texts.forRequest("error.mentorLabelProgramFull", MentorLabelLimits.PER_PROGRAM));
		}
		String name = normalizedName(request.name());
		requireUniqueName(existing, name, null);
		ProgramMentorLabel label = new ProgramMentorLabel();
		label.setId(UUID.randomUUID());
		label.setProgramId(programId);
		label.setName(name);
		label.setColor(request.color());
		label.setCreatedAt(Instant.now());
		label.setCreatedBy(actorId);
		saveName(label);
		return boardOf(program);
	}

	/** Renames or recolors a label; every mentor carrying it shows the change. */
	@Transactional
	public MentorLabelResponse.Board update(UUID actorId, UUID programId, UUID labelId, MentorLabelRequest request) {
		Program program = requireLabelEditor(actorId, programId);
		ProgramMentorLabel label = requireLabel(programId, labelId);
		String name = normalizedName(request.name());
		requireUniqueName(labelRepository.findByProgramIdOrderByNameAsc(programId), name, labelId);
		label.setName(name);
		label.setColor(request.color());
		saveName(label);
		return boardOf(program);
	}

	/** Deletes a label and takes it off every mentor who carried it. */
	@Transactional
	public MentorLabelResponse.Board delete(UUID actorId, UUID programId, UUID labelId) {
		Program program = requireLabelEditor(actorId, programId);
		ProgramMentorLabel label = requireLabel(programId, labelId);
		assignmentRepository.deleteByLabelId(label.getId());
		labelRepository.delete(label);
		return boardOf(program);
	}

	/** Replaces the labels one pool mentor carries in the program. */
	@Transactional
	public MentorLabelResponse.Board setMentorLabels(UUID actorId, UUID programId, UUID mentorId, List<UUID> labelIds) {
		Program program = requireLabelEditor(actorId, programId);
		// The membership stays locked until the end, so two editors saving at once cannot add up past the limit.
		ProgramMentor membership = programMentorRepository.findByProgramIdAndMentorIdForUpdate(programId, mentorId)
				.filter(row -> row.getStatus() == ProgramMentorStatus.ACCEPTED)
				.orElseThrow(() -> new InvalidRequestException(Texts.forRequest("error.mentorLabelMentorNotInPool")));
		Set<UUID> wanted = new LinkedHashSet<>(labelIds);
		if (wanted.size() > MentorLabelLimits.PER_MENTOR) {
			throw new InvalidRequestException(
					Texts.forRequest("error.mentorLabelTooMany", MentorLabelLimits.PER_MENTOR));
		}
		Set<UUID> programLabels = labelRepository.findByProgramIdOrderByNameAsc(programId).stream()
				.map(ProgramMentorLabel::getId)
				.collect(Collectors.toSet());
		if (!programLabels.containsAll(wanted)) {
			throw new ResourceNotFoundException(Texts.forRequest("error.mentorLabelNotFound"));
		}
		assignmentRepository.deleteByProgramIdAndMentorId(programId, membership.getMentorId());
		Instant now = Instant.now();
		assignmentRepository.saveAll(wanted.stream().map(labelId -> {
			ProgramMentorLabelAssignment assignment = new ProgramMentorLabelAssignment();
			assignment.setId(UUID.randomUUID());
			assignment.setLabelId(labelId);
			assignment.setProgramId(programId);
			assignment.setMentorId(membership.getMentorId());
			assignment.setCreatedAt(now);
			return assignment;
		}).toList());
		return boardOf(program);
	}

	/**
	 * The labels on the mentors a mentee can request, in the mentee's own program only: the mentor list, the mentor
	 * profile and the recommendation cards all read this.
	 */
	@Transactional(readOnly = true)
	public MentorLabelResponse.Directory directory(@Nullable UUID actorId) {
		Optional<Program> program = approvedActor(actorId)
				.filter(actor -> actor.has(UserRole.MENTEE))
				.flatMap(menteeProgramService::programOf)
				.flatMap(programRepository::findById)
				.filter(Program::isMentorLabelsEnabled);
		if (program.isEmpty()) {
			return new MentorLabelResponse.Directory(null, List.of(), List.of());
		}
		UUID programId = program.get().getId();
		List<ProgramMentorLabel> labels = labelRepository.findByProgramIdOrderByNameAsc(programId);
		return new MentorLabelResponse.Directory(
				programId,
				labels.stream().map(MentorLabelService::toLabel).toList(),
				mentorLabels(programId, labels));
	}

	/**
	 * One mentor's labels, per program, as the viewer may see them: the mentor sees their own programs, a coordinator
	 * the programs they manage (an admin every program), a mentee only their own program. Programs whose switch is off,
	 * and programs where the mentor carries no label, are left out.
	 */
	@Transactional(readOnly = true)
	public List<MentorLabelResponse.ProgramLabels> ofMentor(UUID mentorId, @Nullable UUID actorId) {
		Optional<AppUser> viewer = approvedActor(actorId);
		if (viewer.isEmpty()) {
			return List.of();
		}
		Map<UUID, String> memberships = programMentorRepository
				.findProgramNamesByMentorIds(List.of(mentorId), ProgramMentorStatus.ACCEPTED).stream()
				.collect(Collectors.toMap(MentorProgramName::getProgramId, MentorProgramName::getProgramName,
						(first, second) -> first));
		Set<UUID> visible = visiblePrograms(viewer.get(), mentorId);
		List<UUID> programIds = programRepository.findAllById(memberships.keySet()).stream()
				.filter(Program::isMentorLabelsEnabled)
				.map(Program::getId)
				.filter(visible::contains)
				.toList();
		if (programIds.isEmpty()) {
			return List.of();
		}
		Map<UUID, ProgramMentorLabel> labels = labelRepository.findByProgramIdInOrderByNameAsc(programIds).stream()
				.collect(Collectors.toMap(ProgramMentorLabel::getId, Function.identity()));
		Map<UUID, List<ProgramMentorLabel>> carried = new HashMap<>();
		for (ProgramMentorLabelAssignment assignment : assignmentRepository.findByMentorIdAndProgramIdIn(mentorId,
				programIds)) {
			ProgramMentorLabel label = labels.get(assignment.getLabelId());
			if (label != null) {
				carried.computeIfAbsent(assignment.getProgramId(), id -> new ArrayList<>()).add(label);
			}
		}
		return carried.entrySet().stream()
				.map(entry -> new MentorLabelResponse.ProgramLabels(
						entry.getKey(),
						memberships.getOrDefault(entry.getKey(), ""),
						entry.getValue().stream().sorted(BY_NAME).map(MentorLabelService::toLabel).toList()))
				.sorted(Comparator.comparing(MentorLabelResponse.ProgramLabels::programName,
						String.CASE_INSENSITIVE_ORDER))
				.toList();
	}

	private Set<UUID> visiblePrograms(AppUser viewer, UUID mentorId) {
		if (viewer.getId().equals(mentorId)) {
			return programMentorRepository.findByMentorIdOrderByCreatedAtAsc(mentorId).stream()
					.map(ProgramMentor::getProgramId)
					.collect(Collectors.toSet());
		}
		if (viewer.has(UserRole.COORDINATOR) || viewer.has(UserRole.ADMIN)) {
			return coordinatorProgramService.managedPrograms(viewer).stream()
					.map(Program::getId)
					.collect(Collectors.toSet());
		}
		if (viewer.has(UserRole.MENTEE)) {
			return menteeProgramService.programOf(viewer).map(Set::of).orElse(Set.of());
		}
		return Set.of();
	}

	private Optional<AppUser> approvedActor(@Nullable UUID actorId) {
		if (actorId == null) {
			return Optional.empty();
		}
		return appUserRepository.findById(actorId).filter(user -> user.getStatus() == UserAccountStatus.APPROVED);
	}

	private MentorLabelResponse.Board boardOf(Program program) {
		if (!program.isMentorLabelsEnabled()) {
			return new MentorLabelResponse.Board(false, false, List.of(), List.of());
		}
		List<ProgramMentorLabel> labels = labelRepository.findByProgramIdOrderByNameAsc(program.getId());
		List<MentorLabelResponse.MentorLabels> mentors = mentorLabels(program.getId(), labels);
		Map<UUID, Integer> carriers = new HashMap<>();
		mentors.forEach(mentor -> mentor.labelIds().forEach(id -> carriers.merge(id, 1, Integer::sum)));
		return new MentorLabelResponse.Board(
				true,
				program.getStatus() != ProgramStatus.COMPLETED,
				labels.stream()
						.map(label -> new MentorLabelResponse.BoardLabel(label.getId(), label.getName(),
								label.getColor(), carriers.getOrDefault(label.getId(), 0)))
						.toList(),
				mentors);
	}

	/** Who carries which label, for the program's accepted mentors, with each mentor's labels in the list's order. */
	private List<MentorLabelResponse.MentorLabels> mentorLabels(UUID programId, List<ProgramMentorLabel> labels) {
		Set<UUID> accepted = menteeProgramService.acceptedMentorIds(programId);
		Map<UUID, Integer> order = new HashMap<>();
		for (int index = 0; index < labels.size(); index++) {
			order.put(labels.get(index).getId(), index);
		}
		Map<UUID, List<UUID>> byMentor = new HashMap<>();
		for (ProgramMentorLabelAssignment assignment : assignmentRepository.findByProgramId(programId)) {
			if (accepted.contains(assignment.getMentorId()) && order.containsKey(assignment.getLabelId())) {
				byMentor.computeIfAbsent(assignment.getMentorId(), id -> new ArrayList<>())
						.add(assignment.getLabelId());
			}
		}
		return byMentor.entrySet().stream()
				.map(entry -> new MentorLabelResponse.MentorLabels(entry.getKey(), entry.getValue().stream()
						.sorted(Comparator.comparing(id -> order.getOrDefault(id, Integer.MAX_VALUE)))
						.toList()))
				.sorted(Comparator.comparing(MentorLabelResponse.MentorLabels::mentorId))
				.toList();
	}

	private Program requireLabelEditor(UUID actorId, UUID programId) {
		coordinatorProgramService.requireManagedProgram(actorId, programId);
		// Locked for the rest of the change: two editors creating labels at once cannot pass the 50-label check
		// together, and a label cannot be deleted while it is being given to a mentor.
		Program program = programRepository.findByIdForUpdate(programId)
				.orElseThrow(() -> new ResourceNotFoundException("Program not found: " + programId));
		if (!program.isMentorLabelsEnabled()) {
			throw new InvalidRequestException(Texts.forRequest("error.mentorLabelsOff"));
		}
		if (program.getStatus() == ProgramStatus.COMPLETED) {
			throw new InvalidRequestException(Texts.forRequest("error.mentorLabelsArchived"));
		}
		return program;
	}

	/**
	 * Flushed here so a clash with the database's case-insensitive name index is answered as "name taken": its
	 * lower() can fold a letter (Turkish İ) that the check above, in Locale.ROOT, keeps apart.
	 */
	private void saveName(ProgramMentorLabel label) {
		try {
			labelRepository.saveAndFlush(label);
		} catch (DataIntegrityViolationException clash) {
			throw new ConflictException(Texts.forRequest("error.mentorLabelNameTaken"));
		}
	}

	private ProgramMentorLabel requireLabel(UUID programId, UUID labelId) {
		return labelRepository.findByIdAndProgramId(labelId, programId)
				.orElseThrow(() -> new ResourceNotFoundException(Texts.forRequest("error.mentorLabelNotFound")));
	}

	/**
	 * Without invisible characters (zero-width spaces, direction marks), trimmed, and with inner runs of any kind of
	 * space made one, so look-alike spellings are one name.
	 */
	static String normalizedName(String raw) {
		String name = SPACES.matcher(INVISIBLE.matcher(raw).replaceAll("")).replaceAll(" ").strip();
		if (name.isEmpty()) {
			throw new InvalidRequestException(Texts.forRequest("error.mentorLabelNameRequired"));
		}
		if (name.codePointCount(0, name.length()) > MentorLabelLimits.NAME_MAX_LENGTH) {
			throw new InvalidRequestException(
					Texts.forRequest("error.mentorLabelNameTooLong", MentorLabelLimits.NAME_MAX_LENGTH));
		}
		return name;
	}

	private static void requireUniqueName(List<ProgramMentorLabel> existing, String name, @Nullable UUID except) {
		String key = name.toLowerCase(Locale.ROOT);
		boolean taken = existing.stream()
				.anyMatch(label -> !label.getId().equals(except)
						&& label.getName().toLowerCase(Locale.ROOT).equals(key));
		if (taken) {
			throw new ConflictException(Texts.forRequest("error.mentorLabelNameTaken"));
		}
	}

	private static MentorLabelResponse.Label toLabel(ProgramMentorLabel label) {
		return new MentorLabelResponse.Label(label.getId(), label.getName(), label.getColor());
	}
}
