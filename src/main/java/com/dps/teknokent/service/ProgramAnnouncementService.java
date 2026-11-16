package com.dps.teknokent.service;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.AnnouncementAudience;
import com.dps.teknokent.domain.NotificationCategory;
import com.dps.teknokent.domain.ProgramMentorStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.PostProgramAnnouncementRequest;
import com.dps.teknokent.dto.responses.ProgramAnnouncementResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramAnnouncement;
import com.dps.teknokent.entity.ProgramMentor;
import com.dps.teknokent.entity.ProgramStartup;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.ProgramAnnouncementRepository;
import com.dps.teknokent.repository.ProgramMentorRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.repository.ProgramStartupRepository;
import com.dps.teknokent.util.Texts;

import lombok.RequiredArgsConstructor;

/**
 * "Duyurular": a coordinator posts to a program, for everyone in it, only its accepted mentors, or only its teams'
 * members. Exactly that group gets a notification and reads the announcement in a read-only thread in their Mesajlar.
 * Newest first.
 */
@Service
@RequiredArgsConstructor
public class ProgramAnnouncementService {

	private static final int PREVIEW_LENGTH = 140;

	private final CoordinatorProgramService programService;
	private final ProgramAnnouncementRepository announcementRepository;
	private final ProgramRepository programRepository;
	private final ProgramStartupRepository programStartupRepository;
	private final ProgramMentorRepository programMentorRepository;
	private final AppUserRepository appUserRepository;
	private final NotificationService notificationService;

	@Transactional
	public ProgramAnnouncementResponse post(UUID actorId, UUID programId, PostProgramAnnouncementRequest request) {
		Program program = programService.requireManagedProgram(actorId, programId);
		ProgramAnnouncement announcement = new ProgramAnnouncement();
		announcement.setId(UUID.randomUUID());
		announcement.setProgramId(programId);
		announcement.setAuthorId(actorId);
		announcement.setBody(request.body().strip());
		announcement.setAudience(request.audienceOrDefault());
		ProgramAnnouncement saved = announcementRepository.saveAndFlush(announcement);

		Audience audience = audience(programId).reachedBy(saved.getAudience());
		String preview = preview(saved.getBody());
		for (AppUser member : audience.members()) {
			notificationService.create(member, NotificationCategory.GENERAL,
					Texts.get(member, "notification.announcement.title", program.getName()), preview, "/messages");
		}
		for (AppUser mentor : audience.mentors()) {
			notificationService.create(mentor, NotificationCategory.GENERAL,
					Texts.get(mentor, "notification.announcement.title", program.getName()), preview,
					"/mentor-dashboard/messages");
		}
		return response(saved, program, nameOf(actorId), audience);
	}

	@Transactional(readOnly = true)
	public List<ProgramAnnouncementResponse> coordinatorList(UUID actorId, UUID programId) {
		Program program = programService.requireManagedProgram(actorId, programId);
		return responses(announcementRepository.findByProgramIdOrderByCreatedAtDesc(programId),
				Map.of(programId, program));
	}

	/**
	 * The announcements meant for the viewer: in programs they mentor in, those for everyone or for mentors; in
	 * programs their team is in, those for everyone or for participants. A team counts only for a mentee, the same
	 * people {@link #post} notifies.
	 */
	@Transactional(readOnly = true)
	public List<ProgramAnnouncementResponse> mine(UUID actorId) {
		AppUser actor = appUserRepository.findById(actorId)
				.orElseThrow(() -> new ResourceNotFoundException("User not found: " + actorId));
		Set<UUID> mentorProgramIds = new HashSet<>();
		if (actor.has(UserRole.MENTOR)) {
			programMentorRepository.findByMentorIdAndStatusOrderByCreatedAtDesc(actorId, ProgramMentorStatus.ACCEPTED)
					.forEach(row -> mentorProgramIds.add(row.getProgramId()));
		}
		Set<UUID> teamProgramIds = new HashSet<>();
		if (actor.has(UserRole.MENTEE) && actor.getOrganization() != null) {
			programStartupRepository.findByOrganizationIdOrderByCreatedAtAsc(actor.getOrganization().getId())
					.forEach(row -> teamProgramIds.add(row.getProgramId()));
		}
		Map<UUID, ProgramAnnouncement> visible = new HashMap<>();
		readable(mentorProgramIds, AnnouncementAudience.FOR_MENTORS).forEach(a -> visible.put(a.getId(), a));
		readable(teamProgramIds, AnnouncementAudience.FOR_PARTICIPANTS).forEach(a -> visible.put(a.getId(), a));
		if (visible.isEmpty()) {
			return List.of();
		}
		List<ProgramAnnouncement> newestFirst = visible.values().stream()
				.sorted(Comparator.comparing(ProgramAnnouncement::getCreatedAt).reversed()
						.thenComparing(ProgramAnnouncement::getId))
				.toList();
		Map<UUID, Program> programs = programRepository
				.findAllById(newestFirst.stream().map(ProgramAnnouncement::getProgramId).distinct().toList()).stream()
				.collect(Collectors.toMap(Program::getId, Function.identity()));
		return responses(newestFirst, programs);
	}

	private List<ProgramAnnouncement> readable(Set<UUID> programIds, Collection<AnnouncementAudience> audiences) {
		return programIds.isEmpty()
				? List.of()
				: announcementRepository.findByProgramIdInAndAudienceIn(programIds, audiences);
	}

	private List<ProgramAnnouncementResponse> responses(List<ProgramAnnouncement> announcements,
			Map<UUID, Program> programs) {
		Map<UUID, Audience> audiences = new HashMap<>();
		Map<UUID, String> authors = appUserRepository
				.findAllById(announcements.stream().map(ProgramAnnouncement::getAuthorId).distinct().toList()).stream()
				.collect(Collectors.toMap(AppUser::getId, AppUser::getFullName));
		return announcements.stream()
				.map(announcement -> response(
						announcement,
						programs.get(announcement.getProgramId()),
						authors.get(announcement.getAuthorId()),
						audiences.computeIfAbsent(announcement.getProgramId(), this::audience)
								.reachedBy(announcement.getAudience())))
				.toList();
	}

	private Audience audience(UUID programId) {
		Set<UUID> organizationIds = programStartupRepository.findByProgramIdOrderByCreatedAtAsc(programId).stream()
				.map(ProgramStartup::getOrganizationId)
				.collect(Collectors.toSet());
		List<AppUser> members = organizationIds.stream()
				.flatMap(id -> appUserRepository.findByOrganizationIdOrderByFullNameAsc(id).stream())
				.filter(user -> user.has(UserRole.MENTEE))
				.toList();
		List<UUID> mentorIds = programMentorRepository.findByProgramIdOrderByCreatedAtAsc(programId).stream()
				.filter(row -> row.getStatus() == ProgramMentorStatus.ACCEPTED)
				.map(ProgramMentor::getMentorId)
				.toList();
		return new Audience(organizationIds.size(), members, appUserRepository.findAllById(mentorIds));
	}

	private @Nullable String nameOf(UUID userId) {
		return appUserRepository.findById(userId).map(AppUser::getFullName).orElse(null);
	}

	private static String preview(String body) {
		return body.length() <= PREVIEW_LENGTH ? body : body.substring(0, PREVIEW_LENGTH - 1) + "…";
	}

	private static ProgramAnnouncementResponse response(
			ProgramAnnouncement announcement, @Nullable Program program, @Nullable String authorName,
			Audience audience) {
		return new ProgramAnnouncementResponse(
				announcement.getId(),
				announcement.getProgramId(),
				program == null ? null : program.getName(),
				authorName,
				announcement.getBody(),
				announcement.getCreatedAt(),
				announcement.getAudience(),
				program != null && program.isIndividualMentees(),
				audience.teamCount(),
				audience.mentors().size());
	}

	/** Everyone in a program today; {@link #reachedBy} narrows it to one announcement's audience. */
	private record Audience(int teamCount, List<AppUser> members, List<AppUser> mentors) {

		Audience reachedBy(AnnouncementAudience audience) {
			return new Audience(
					audience.reachesParticipants() ? teamCount : 0,
					audience.reachesParticipants() ? members : List.of(),
					audience.reachesMentors() ? mentors : List.of());
		}
	}
}
