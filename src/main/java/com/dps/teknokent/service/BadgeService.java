package com.dps.teknokent.service;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.BadgeCode;
import com.dps.teknokent.domain.BadgeKind;
import com.dps.teknokent.domain.BadgeSource;
import com.dps.teknokent.domain.BadgeStatus;
import com.dps.teknokent.domain.GateBadgeState;
import com.dps.teknokent.domain.NotificationCategory;
import com.dps.teknokent.domain.PermissionSource;
import com.dps.teknokent.domain.ProgramMentorInvitedBy;
import com.dps.teknokent.domain.ProgramMentorStatus;
import com.dps.teknokent.domain.ProgramType;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.responses.BadgeAwardResponse;
import com.dps.teknokent.dto.responses.BadgeCatalogueResponse;
import com.dps.teknokent.dto.responses.GateBadgeRowResponse;
import com.dps.teknokent.dto.responses.PublicBadgeResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramMentor;
import com.dps.teknokent.entity.TrainingAttempt;
import com.dps.teknokent.entity.TrainingProgress;
import com.dps.teknokent.entity.UserBadge;
import com.dps.teknokent.exception.ConflictException;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.ProgramMentorRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.repository.TrainingAttemptRepository;
import com.dps.teknokent.repository.TrainingModuleRepository;
import com.dps.teknokent.repository.TrainingProgressRepository;
import com.dps.teknokent.repository.UserBadgeRepository;
import com.dps.teknokent.util.Texts;

import lombok.RequiredArgsConstructor;

/**
 * Menton's badges. The gate badge (Akran Mentoru) is awarded in a peer-mentoring program, by the program's
 * coordinator or an admin (or with the program's mentor approval, see {@link #awardAfterMentorApproval}); it
 * grants MENTOR through the account's permissions and makes the person one of the program's mentors.
 * <p>
 * Not here yet, by decision: the training that awards it automatically (D2a–D2f; it will call
 * {@link #award} with {@link BadgeSource#RULE}), expiry (D3; {@link BadgeStatus#EXPIRED} is reserved), and what a
 * revoke does to running mentorships (D4). A revoke today ends the badge and the MENTOR permission it gave, and
 * leaves requests, mentorships and the program membership as they are.
 */
@Service
@RequiredArgsConstructor
public class BadgeService {

	static final BadgeCode GATE = BadgeCode.PEER_MENTOR;
	private static final Set<BadgeCode> GATE_BADGES = Arrays.stream(BadgeCode.values())
			.filter(code -> code.grants() != null)
			.collect(Collectors.toCollection(() -> EnumSet.noneOf(BadgeCode.class)));

	private final UserBadgeRepository awards;
	private final AppUserRepository users;
	private final ProgramRepository programs;
	private final ProgramMentorRepository programMentors;
	private final CoordinatorProgramService coordinatorProgramService;
	private final DomainLookupService domainLookupService;
	private final NotificationService notificationService;
	private final TrainingModuleRepository trainingModules;
	private final TrainingProgressRepository trainingProgress;
	private final TrainingAttemptRepository trainingAttempts;

	public List<BadgeCatalogueResponse> catalogue() {
		return Arrays.stream(BadgeCode.values()).map(BadgeCatalogueResponse::of).toList();
	}

	/** Every award of the signed-in person, revoked ones and their reasons included. */
	@Transactional(readOnly = true)
	public List<BadgeAwardResponse> mine(UUID actorId) {
		List<UserBadge> own = awards.findByUserIdOrderByAwardedAtDesc(domainLookupService.requireUser(actorId).getId());
		Map<UUID, String> names = programNames(own.stream().map(UserBadge::getProgramId).toList());
		return own.stream().map(award -> BadgeAwardResponse.of(award, names.get(award.getProgramId()))).toList();
	}

	/** What anyone who can see the person sees (D5): active badges only. */
	@Transactional(readOnly = true)
	public List<PublicBadgeResponse> publicOf(UUID userId) {
		return awards.findByUserIdAndStatusOrderByAwardedAtDesc(userId, BadgeStatus.ACTIVE).stream()
				.map(PublicBadgeResponse::of)
				.toList();
	}

	/**
	 * Katılımcılar's roster for a peer program: its students, its accepted mentors and anyone who ever held its
	 * gate badge, each with where they stand. {@code state} narrows it (Akran Mentorları = ACTIVE).
	 */
	@Transactional(readOnly = true)
	public List<GateBadgeRowResponse> roster(UUID actorId, UUID programId, GateBadgeState state) {
		Program program = requirePeerProgram(actorId, programId);
		Map<UUID, UserBadge> latest = latestByUser(awards.findByProgramIdAndBadgeCodeOrderByAwardedAtDesc(
				program.getId(), GATE));
		Map<UUID, AppUser> people = new LinkedHashMap<>();
		users.findByProgramIdAndPermissionAndStatusOrderByCreatedAtAsc(program.getId(), UserRole.MENTEE,
				UserAccountStatus.APPROVED).forEach(user -> people.put(user.getId(), user));
		Set<UUID> others = new LinkedHashSet<>(latest.keySet());
		programMentors.findByProgramIdOrderByCreatedAtAsc(program.getId()).stream()
				.filter(membership -> membership.getStatus() == ProgramMentorStatus.ACCEPTED)
				.forEach(membership -> others.add(membership.getMentorId()));
		users.findAllById(others).forEach(user -> people.putIfAbsent(user.getId(), user));
		Map<UUID, GateBadgeRowResponse.Training> training = trainingOf(program, people.keySet());
		return people.values().stream()
				.map(person -> row(person, program, latest.get(person.getId()),
						training.getOrDefault(person.getId(), GateBadgeRowResponse.Training.NONE)))
				.filter(row -> state == null || row.state() == state)
				.sorted((left, right) -> left.fullName().compareToIgnoreCase(right.fullName()))
				.toList();
	}

	/** The admin's Öğrenciler list: everyone in a peer program, with their gate badge state there. */
	@Transactional(readOnly = true)
	public List<GateBadgeRowResponse> students(UUID actorId, GateBadgeState state) {
		if (!domainLookupService.requireUser(actorId).has(UserRole.ADMIN)) {
			throw new InvalidRequestException(Texts.forRequest("error.badgeAdminOnly"));
		}
		Map<UUID, Program> peerPrograms = programs.findByProgramType(ProgramType.PEER_MENTORING).stream()
				.collect(Collectors.toMap(Program::getId, Function.identity()));
		List<UserBadge> gateAwards = awards.findByBadgeCodeOrderByAwardedAtDesc(GATE);
		Map<String, UserBadge> latest = new LinkedHashMap<>();
		gateAwards.forEach(award -> latest.putIfAbsent(award.getUserId() + "|" + award.getProgramId(), award));
		Map<String, GateBadgeRowResponse> rows = new LinkedHashMap<>();
		for (Program program : peerPrograms.values()) {
			for (AppUser student : users.findByProgramIdAndPermissionAndStatusOrderByCreatedAtAsc(program.getId(),
					UserRole.MENTEE, UserAccountStatus.APPROVED)) {
				String key = student.getId() + "|" + program.getId();
				rows.put(key, row(student, program, latest.get(key)));
			}
		}
		Map<UUID, AppUser> holders = users.findAllById(gateAwards.stream().map(UserBadge::getUserId)
				.collect(Collectors.toSet())).stream().collect(Collectors.toMap(AppUser::getId, Function.identity()));
		latest.forEach((key, award) -> {
			Program program = peerPrograms.get(award.getProgramId());
			AppUser holder = holders.get(award.getUserId());
			if (program != null && holder != null) {
				rows.putIfAbsent(key, row(holder, program, award));
			}
		});
		Map<UUID, List<GateBadgeRowResponse>> byProgram = rows.values().stream()
				.collect(Collectors.groupingBy(GateBadgeRowResponse::programId));
		return byProgram.entrySet().stream()
				.flatMap(entry -> {
					Map<UUID, GateBadgeRowResponse.Training> training = trainingOf(peerPrograms.get(entry.getKey()),
							entry.getValue().stream().map(GateBadgeRowResponse::userId).toList());
					return entry.getValue().stream().map(row -> withTraining(row,
							training.getOrDefault(row.userId(), GateBadgeRowResponse.Training.NONE)));
				})
				.filter(row -> state == null || row.state() == state)
				.sorted((left, right) -> left.fullName().compareToIgnoreCase(right.fullName()))
				.toList();
	}

	/** Watched, tries and best score on the program's current training version, for those who started it. */
	private Map<UUID, GateBadgeRowResponse.Training> trainingOf(Program program, Collection<UUID> userIds) {
		if (userIds.isEmpty()) {
			return Map.of();
		}
		return trainingModules.findByProgramId(program.getId()).map(module -> {
			int version = module.getCurrentVersion();
			Set<UUID> watched = trainingProgress.findByModuleIdAndVersionAndUserIdIn(module.getId(), version, userIds)
					.stream().map(TrainingProgress::getUserId).collect(Collectors.toSet());
			Map<UUID, List<TrainingAttempt>> tries = trainingAttempts
					.findByModuleIdAndVersionAndUserIdIn(module.getId(), version, userIds).stream()
					.collect(Collectors.groupingBy(TrainingAttempt::getUserId));
			Map<UUID, GateBadgeRowResponse.Training> result = new LinkedHashMap<>();
			for (UUID userId : userIds) {
				List<TrainingAttempt> own = tries.getOrDefault(userId, List.of());
				Integer best = own.stream().map(TrainingAttempt::getScore).max(Integer::compare).orElse(null);
				result.put(userId, new GateBadgeRowResponse.Training(watched.contains(userId), own.size(), best));
			}
			return result;
		}).orElse(Map.of());
	}

	private static GateBadgeRowResponse withTraining(GateBadgeRowResponse row, GateBadgeRowResponse.Training training) {
		return new GateBadgeRowResponse(row.userId(), row.fullName(), row.email(), row.programId(),
				row.programName(), row.state(), row.awardId(), row.source(), row.awardedAt(), row.revokedAt(),
				row.reason(), training);
	}

	/**
	 * "Rozet ver" in Katılımcılar: the program's coordinator or an admin awards the gate badge to someone of the
	 * program, never to themselves: an approved student of it, or a mentor already accepted into it (one who
	 * joined by the coordinator's invitation never passed through "Kabul Et").
	 */
	@Transactional
	public GateBadgeRowResponse awardGate(UUID actorId, UUID programId, UUID userId) {
		Program program = requirePeerProgram(actorId, programId);
		AppUser actor = domainLookupService.requireUser(actorId);
		AppUser student = domainLookupService.requireUser(userId);
		requireNotSelf(actor, student);
		if (student.getStatus() != UserAccountStatus.APPROVED
				|| !(isStudentOf(student, program) || isAcceptedMentorOf(student, program))) {
			throw new InvalidRequestException(Texts.forRequest("error.badgeNotStudentOfProgram"));
		}
		if (activeGate(student.getId(), program.getId()) != null) {
			throw new ConflictException(Texts.forRequest("error.badgeAlreadyActive"));
		}
		UserBadge award = award(student, GATE, program, sourceOf(actor), actor.getId());
		return row(student, program, award);
	}

	/**
	 * Accepting a mentor's request to join a peer program ("Kabul Et" in Onaylar or Mentor Havuzu) is the
	 * coordinator's decision that they mentor there, so it awards the program's gate badge too. Does nothing
	 * outside peer programs, or when the badge is already active.
	 */
	@Transactional
	public void awardAfterMentorApproval(UUID actorId, Program program, AppUser mentor) {
		if (program.getProgramType() != ProgramType.PEER_MENTORING
				|| activeGate(mentor.getId(), program.getId()) != null) {
			return;
		}
		AppUser actor = domainLookupService.requireUser(actorId);
		award(mentor, GATE, program, sourceOf(actor), actor.getId());
	}

	/**
	 * The one way a badge is awarded, by hand now and by rules later: the award row, the permission a gate badge
	 * gives, the program membership that makes a peer mentor discoverable there, and the notification.
	 */
	UserBadge award(AppUser holder, BadgeCode code, Program program, BadgeSource source, UUID awardedBy) {
		return award(holder, code, program, source, awardedBy, true);
	}

	/** As above; {@code announce = false} awards without a notification (the achievement back-fill). */
	UserBadge award(AppUser holder, BadgeCode code, Program program, BadgeSource source, UUID awardedBy,
			boolean announce) {
		Instant now = Instant.now();
		UserBadge award = new UserBadge();
		award.setId(UUID.randomUUID());
		award.setUserId(holder.getId());
		award.setBadgeCode(code);
		award.setProgramId(program == null ? null : program.getId());
		award.setStatus(BadgeStatus.ACTIVE);
		award.setSource(source);
		award.setAwardedAt(now);
		award.setAwardedBy(awardedBy);
		awards.save(award);
		if (code.grants() != null) {
			holder.grant(code.grants(), PermissionSource.BADGE, award.getId(), now);
			users.save(holder);
		}
		if (code == GATE && program != null) {
			acceptMembership(program, holder.getId(), now);
		}
		if (announce && code.kind() == BadgeKind.ACHIEVEMENT) {
			String badge = Texts.get(holder, "badge.name." + code.name());
			notificationService.create(holder, NotificationCategory.BADGE,
					Texts.get(holder, "notification.achievementEarned.title", badge),
					Texts.get(holder, "notification.achievementEarned.body", badge), badgesPageOf(holder));
		} else if (announce) {
			notify(holder, "notification.badgeAwarded", code, program, null);
		}
		return award;
	}

	/**
	 * "Rozeti geri al": ends the award and, when no other active gate badge still backs it, the permission it
	 * gave. A permission the person holds for another reason (their own mentor account) stays.
	 */
	@Transactional
	public GateBadgeRowResponse revoke(UUID actorId, UUID programId, UUID awardId, String reason) {
		Program program = requirePeerProgram(actorId, programId);
		AppUser actor = domainLookupService.requireUser(actorId);
		UserBadge award = awards.findById(awardId)
				.filter(found -> program.getId().equals(found.getProgramId()))
				.orElseThrow(() -> new ResourceNotFoundException(Texts.forRequest("error.badgeAwardNotFound")));
		if (award.getStatus() != BadgeStatus.ACTIVE) {
			throw new ConflictException(Texts.forRequest("error.badgeNotActive"));
		}
		String why = reason == null ? "" : reason.trim();
		if (why.isEmpty()) {
			throw new InvalidRequestException(Texts.forRequest("error.badgeRevokeReasonRequired"));
		}
		AppUser holder = domainLookupService.requireUser(award.getUserId());
		requireNotSelf(actor, holder);
		Instant now = Instant.now();
		award.setStatus(BadgeStatus.REVOKED);
		award.setRevokedAt(now);
		award.setRevokedBy(actor.getId());
		award.setReason(why);
		awards.save(award);
		if (award.getBadgeCode().grants() != null) {
			holder.withdraw(PermissionSource.BADGE, permissionsStillGranted(holder.getId()), now);
			users.save(holder);
		}
		notify(holder, "notification.badgeRevoked", award.getBadgeCode(), program, why);
		return row(holder, program, award);
	}

	private Set<UserRole> permissionsStillGranted(UUID userId) {
		return awards.findByUserIdAndStatusOrderByAwardedAtDesc(userId, BadgeStatus.ACTIVE).stream()
				.map(UserBadge::getBadgeCode)
				.filter(GATE_BADGES::contains)
				.map(BadgeCode::grants)
				.collect(Collectors.toCollection(() -> EnumSet.noneOf(UserRole.class)));
	}

	private void acceptMembership(Program program, UUID mentorId, Instant now) {
		ProgramMentor membership = programMentors.findByProgramIdAndMentorId(program.getId(), mentorId)
				.orElseGet(() -> {
					ProgramMentor created = new ProgramMentor();
					created.setId(UUID.randomUUID());
					created.setProgramId(program.getId());
					created.setMentorId(mentorId);
					created.setInvitedBy(ProgramMentorInvitedBy.COORDINATOR);
					created.setCreatedAt(now);
					return created;
				});
		if (membership.getStatus() != ProgramMentorStatus.ACCEPTED) {
			membership.setStatus(ProgramMentorStatus.ACCEPTED);
			membership.setDecidedAt(now);
			programMentors.save(membership);
		}
	}

	private void notify(AppUser holder, String key, BadgeCode code, Program program, String reason) {
		String badge = Texts.get(holder, "badge.name." + code.name());
		String programName = program == null ? "" : program.getName();
		notificationService.create(holder, NotificationCategory.BADGE, Texts.get(holder, key + ".title"),
				Texts.get(holder, key + ".body", badge, programName, reason), homeOf(holder));
	}

	/** Where a person sees their badges: a student's Profilim (Rozetlerim); a mentor account's own dashboard. */
	private static String badgesPageOf(AppUser holder) {
		return holder.getRole() == UserRole.MENTOR ? "/mentor-dashboard" : "/student/profile";
	}

	/** A student's peer mentoring page; a mentor account's own dashboard. */
	private static String homeOf(AppUser holder) {
		return holder.getRole() == UserRole.MENTOR ? "/mentor-dashboard" : "/student/peer-mentoring";
	}

	/** The program, when it is a peer program the actor coordinates (or the actor is an admin). */
	Program requirePeerProgram(UUID actorId, UUID programId) {
		Program program = coordinatorProgramService.requireManagedProgram(actorId, programId);
		if (program.getProgramType() != ProgramType.PEER_MENTORING) {
			throw new InvalidRequestException(Texts.forRequest("error.badgeNotPeerProgram"));
		}
		return program;
	}

	private static boolean isStudentOf(AppUser person, Program program) {
		return person.has(UserRole.MENTEE) && program.getId().equals(person.getProgramId());
	}

	private boolean isAcceptedMentorOf(AppUser person, Program program) {
		return programMentors.findByProgramIdAndMentorId(program.getId(), person.getId())
				.filter(membership -> membership.getStatus() == ProgramMentorStatus.ACCEPTED)
				.isPresent();
	}

	private static void requireNotSelf(AppUser actor, AppUser holder) {
		if (actor.getId().equals(holder.getId())) {
			throw new InvalidRequestException(Texts.forRequest("error.badgeOwnAccount"));
		}
	}

	private static BadgeSource sourceOf(AppUser actor) {
		return actor.has(UserRole.ADMIN) ? BadgeSource.ADMIN : BadgeSource.COORDINATOR;
	}

	private UserBadge activeGate(UUID userId, UUID programId) {
		return awards.findFirstByUserIdAndBadgeCodeAndProgramIdAndStatus(userId, GATE, programId, BadgeStatus.ACTIVE)
				.orElse(null);
	}

	private static Map<UUID, UserBadge> latestByUser(List<UserBadge> newestFirst) {
		Map<UUID, UserBadge> latest = new LinkedHashMap<>();
		newestFirst.forEach(award -> latest.putIfAbsent(award.getUserId(), award));
		return latest;
	}

	private Map<UUID, String> programNames(Collection<UUID> programIds) {
		List<UUID> ids = programIds.stream().filter(Objects::nonNull).distinct().toList();
		return ids.isEmpty() ? Map.of()
				: programs.findAllById(ids).stream().collect(Collectors.toMap(Program::getId, Program::getName));
	}

	private static GateBadgeRowResponse row(AppUser person, Program program, UserBadge award) {
		return row(person, program, award, GateBadgeRowResponse.Training.NONE);
	}

	private static GateBadgeRowResponse row(AppUser person, Program program, UserBadge award,
			GateBadgeRowResponse.Training training) {
		return new GateBadgeRowResponse(person.getId(), person.getFullName(), person.getEmail(), program.getId(),
				program.getName(), GateBadgeState.of(award == null ? null : award.getStatus()),
				award == null ? null : award.getId(), award == null ? null : award.getSource(),
				award == null ? null : award.getAwardedAt(), award == null ? null : award.getRevokedAt(),
				award == null ? null : award.getReason(), training);
	}
}
