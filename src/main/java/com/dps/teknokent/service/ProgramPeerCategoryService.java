package com.dps.teknokent.service;

import java.time.Instant;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.PeerTopicCategory;
import com.dps.teknokent.domain.ProgramType;
import com.dps.teknokent.dto.responses.ProgramPeerCategoriesResponse;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramPeerCategory;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.repository.ProgramPeerCategoryRepository;
import com.dps.teknokent.util.Texts;

import lombok.RequiredArgsConstructor;

/**
 * Which of the board's eight categories a peer-mentoring program uses. None is active until the program's
 * coordinator turns it on; students and peer mentors then only see the active ones.
 */
@Service
@RequiredArgsConstructor
public class ProgramPeerCategoryService {

	private final CoordinatorProgramService coordinatorProgramService;
	private final ProgramPeerCategoryRepository categories;

	/** The program's coordinators and the admin may read it. */
	@Transactional(readOnly = true)
	public ProgramPeerCategoriesResponse get(UUID actorId, UUID programId) {
		Program program = requirePeerProgram(coordinatorProgramService.requireManagedProgram(actorId, programId));
		return response(program.getId(), activeIn(program.getId()));
	}

	/** Only the program's coordinators change it, and not while the program is archived. */
	@Transactional
	public ProgramPeerCategoriesResponse replace(UUID actorId, UUID programId, List<PeerTopicCategory> wanted) {
		Program program = requirePeerProgram(coordinatorProgramService.requireSettingsEditor(actorId, programId));
		Set<PeerTopicCategory> next = wanted.isEmpty()
				? EnumSet.noneOf(PeerTopicCategory.class)
				: EnumSet.copyOf(wanted);
		List<ProgramPeerCategory> current = categories.findByProgramId(program.getId());
		categories.deleteAll(current.stream().filter(row -> !next.contains(row.getCategory())).toList());
		Set<PeerTopicCategory> kept = current.stream()
				.map(ProgramPeerCategory::getCategory)
				.filter(next::contains)
				.collect(Collectors.toCollection(() -> EnumSet.noneOf(PeerTopicCategory.class)));
		Instant now = Instant.now();
		categories.saveAll(next.stream()
				.filter(category -> !kept.contains(category))
				.map(category -> new ProgramPeerCategory(program.getId(), category, actorId, now))
				.toList());
		return response(program.getId(), next);
	}

	@Transactional(readOnly = true)
	public Set<PeerTopicCategory> activeIn(UUID programId) {
		return activeIn(List.of(programId));
	}

	/** The union over {@code programIds}; empty when none of them has an active category. */
	@Transactional(readOnly = true)
	public Set<PeerTopicCategory> activeIn(Collection<UUID> programIds) {
		Set<PeerTopicCategory> active = EnumSet.noneOf(PeerTopicCategory.class);
		if (programIds.isEmpty()) {
			return active;
		}
		categories.findByProgramIdIn(programIds).forEach(row -> active.add(row.getCategory()));
		return active;
	}

	private static Program requirePeerProgram(Program program) {
		if (program.getProgramType() != ProgramType.PEER_MENTORING) {
			throw new InvalidRequestException(Texts.forRequest("error.peerCategoriesPeerProgramsOnly"));
		}
		return program;
	}

	private static ProgramPeerCategoriesResponse response(UUID programId, Set<PeerTopicCategory> active) {
		return new ProgramPeerCategoriesResponse(programId,
				active.stream().sorted().toList());
	}
}
