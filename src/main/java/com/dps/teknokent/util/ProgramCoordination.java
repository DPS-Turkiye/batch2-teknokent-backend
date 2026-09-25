package com.dps.teknokent.util;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramCoordinator;
import com.dps.teknokent.repository.ProgramCoordinatorRepository;
import com.dps.teknokent.repository.ProgramRepository;

/** A program is run by its creator plus any coordinators added to it; these answer "who runs what". */
public final class ProgramCoordination {

	private ProgramCoordination() {
	}

	public static boolean coordinates(Program program, UUID userId, ProgramCoordinatorRepository coordinators) {
		return program.getCreatedBy().equals(userId)
				|| coordinators.existsByProgramIdAndUserId(program.getId(), userId);
	}

	/** Programs the user created or was added to, newest first. */
	public static List<Program> programsOf(
			UUID userId,
			ProgramRepository programs,
			ProgramCoordinatorRepository coordinators) {
		Map<UUID, Program> byId = new LinkedHashMap<>();
		for (Program program : programs.findByCreatedByOrderByCreatedAtDesc(userId)) {
			byId.put(program.getId(), program);
		}
		List<UUID> added = coordinators.findByUserId(userId).stream()
				.map(ProgramCoordinator::getProgramId)
				.filter(id -> !byId.containsKey(id))
				.toList();
		if (added.isEmpty()) {
			return new ArrayList<>(byId.values());
		}
		programs.findAllById(added).forEach(program -> byId.put(program.getId(), program));
		return byId.values().stream()
				.sorted(Comparator.comparing(Program::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
				.toList();
	}

	/** The creator first, then the added coordinators in the order they were added. */
	public static List<UUID> coordinatorIds(Program program, ProgramCoordinatorRepository coordinators) {
		List<UUID> ids = new ArrayList<>();
		ids.add(program.getCreatedBy());
		coordinators.findByProgramIdOrderByCreatedAtAsc(program.getId()).stream()
				.map(ProgramCoordinator::getUserId)
				.filter(id -> !ids.contains(id))
				.forEach(ids::add);
		return ids;
	}
}
