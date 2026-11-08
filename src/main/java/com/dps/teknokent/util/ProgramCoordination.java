package com.dps.teknokent.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramCoordinator;
import com.dps.teknokent.repository.AppUserRepository;
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

	/**
	 * The people who run each program, in {@link #coordinatorIds} order: its coordinators, so an admin who
	 * created the program is skipped; the creator only while nobody else runs it. Their notifications go
	 * here, and the first one is the coordinator a program names.
	 */
	public static Map<UUID, List<AppUser>> runners(
			Collection<Program> programs,
			ProgramCoordinatorRepository coordinators,
			AppUserRepository users) {
		if (programs.isEmpty()) {
			return Map.of();
		}
		Map<UUID, List<UUID>> idsByProgram = new HashMap<>();
		for (Program program : programs) {
			List<UUID> ids = new ArrayList<>();
			ids.add(program.getCreatedBy());
			idsByProgram.put(program.getId(), ids);
		}
		coordinators.findByProgramIdIn(idsByProgram.keySet()).stream()
				.sorted(Comparator.comparing(ProgramCoordinator::getCreatedAt,
						Comparator.nullsLast(Comparator.naturalOrder())))
				.forEach(row -> {
					List<UUID> ids = idsByProgram.get(row.getProgramId());
					if (!ids.contains(row.getUserId())) {
						ids.add(row.getUserId());
					}
				});
		Set<UUID> allIds = new HashSet<>();
		idsByProgram.values().forEach(allIds::addAll);
		Map<UUID, AppUser> byId = users.findAllById(allIds).stream()
				.collect(Collectors.toMap(AppUser::getId, Function.identity()));
		Map<UUID, List<AppUser>> runners = new HashMap<>();
		for (Program program : programs) {
			List<AppUser> known = idsByProgram.get(program.getId()).stream()
					.map(byId::get)
					.filter(user -> user != null)
					.toList();
			List<AppUser> running = known.stream().filter(user -> user.getRole() == UserRole.COORDINATOR).toList();
			runners.put(program.getId(), running.isEmpty()
					? known.stream().filter(user -> user.getId().equals(program.getCreatedBy())).toList()
					: running);
		}
		return runners;
	}

	public static List<AppUser> runners(
			Program program,
			ProgramCoordinatorRepository coordinators,
			AppUserRepository users) {
		return runners(List.of(program), coordinators, users).getOrDefault(program.getId(), List.of());
	}
}
