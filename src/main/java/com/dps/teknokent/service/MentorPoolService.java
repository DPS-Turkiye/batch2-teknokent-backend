package com.dps.teknokent.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.MentorPoolStatus;
import com.dps.teknokent.domain.MentorshipStatus;
import com.dps.teknokent.domain.ProgramMentorInvitedBy;
import com.dps.teknokent.domain.ProgramMentorStatus;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.responses.MentorPoolResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.MentorProfile;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramMentor;
import com.dps.teknokent.entity.ProgramMentorInvite;
import com.dps.teknokent.exception.ConflictException;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.MentorCount;
import com.dps.teknokent.repository.MentorProfileRepository;
import com.dps.teknokent.repository.MentorProgramName;
import com.dps.teknokent.repository.MentorshipRepository;
import com.dps.teknokent.repository.ProgramMentorInviteRepository;
import com.dps.teknokent.repository.ProgramMentorRepository;
import com.dps.teknokent.util.Texts;

import lombok.RequiredArgsConstructor;

/**
 * Flow 03.1: a program's Mentor Havuzu. Mentors with an account get the existing program invitation and accept
 * it themselves; anyone else gets a signup link, emailed to them and copied for the coordinator, and signing up with it leaves them
 * waiting for the coordinator, whose approval also approves the new account.
 */
@Service
@RequiredArgsConstructor
public class MentorPoolService {

	static final Duration INVITE_VALIDITY = Duration.ofDays(7);
	static final Duration EXPIRING_SOON = Duration.ofDays(2);
	private static final Set<MentorshipStatus> MATCHED = EnumSet.of(MentorshipStatus.ACTIVE, MentorshipStatus.PLANNED);

	private final CoordinatorProgramService coordinatorProgramService;
	private final ProgramService programService;
	private final ProgramMentorRepository programMentorRepository;
	private final ProgramMentorInviteRepository inviteRepository;
	private final AppUserRepository appUserRepository;
	private final MentorProfileRepository mentorProfileRepository;
	private final MentorshipRepository mentorshipRepository;
	private final MailService mailService;

	@Transactional(readOnly = true)
	public List<MentorPoolResponse.Row> pool(UUID actorId, UUID programId) {
		return rows(coordinatorProgramService.requireManagedProgram(actorId, programId));
	}

	@Transactional
	public List<MentorPoolResponse.Row> invite(UUID actorId, UUID programId, String rawEmail) {
		Program program = coordinatorProgramService.requireManagedProgram(actorId, programId);
		String email = rawEmail.trim().toLowerCase(Locale.ROOT);
		Optional<AppUser> existing = appUserRepository.findByEmailIgnoreCase(email);
		if (existing.isPresent()) {
			AppUser user = existing.get();
			if (user.getRole() != UserRole.MENTOR) {
				throw new InvalidRequestException("Bu e-posta mentor olmayan bir hesaba ait.");
			}
			programService.inviteMentor(actorId, programId, user.getId());
			return rows(program);
		}
		ProgramMentorInvite invite = inviteRepository.findByProgramIdAndEmailIgnoreCase(programId, email)
				.orElseGet(ProgramMentorInvite::new);
		if (invite.getId() == null) {
			invite.setId(UUID.randomUUID());
			invite.setProgramId(programId);
			invite.setEmail(email);
			invite.setCode(newCode());
			invite.setCreatedBy(actorId);
			invite.setCreatedAt(Instant.now());
		} else if (invite.getMentorId() != null) {
			throw new ConflictException(Texts.forRequest("error.mentorAlreadyJoinedByInvite"));
		}
		invite.setExpiresAt(Instant.now().plus(INVITE_VALIDITY));
		inviteRepository.save(invite);
		emailInviteLink(program, invite);
		return rows(program);
	}

	/** "Tekrar gönder": another 7 days on the same link, emailed again. */
	@Transactional
	public List<MentorPoolResponse.Row> renewInvite(UUID actorId, UUID programId, UUID inviteId) {
		Program program = coordinatorProgramService.requireManagedProgram(actorId, programId);
		ProgramMentorInvite invite = requireOpenInvite(programId, inviteId);
		invite.setExpiresAt(Instant.now().plus(INVITE_VALIDITY));
		inviteRepository.save(invite);
		emailInviteLink(program, invite);
		return rows(program);
	}

	private void emailInviteLink(Program program, ProgramMentorInvite invite) {
		mailService.sendAfterCommit(
				invite.getEmail(),
				Texts.inBoth(" / ", "mail.mentorPoolInvite.subject", program.getName()),
				Texts.inBoth(Texts.MAIL_LANGUAGE_BREAK, "mail.mentorPoolInvite.body", program.getName(),
						mailService.link("/signup?role=mentor&programInvite=" + invite.getCode())));
	}

	@Transactional
	public List<MentorPoolResponse.Row> cancelInvite(UUID actorId, UUID programId, UUID inviteId) {
		Program program = coordinatorProgramService.requireManagedProgram(actorId, programId);
		inviteRepository.delete(requireOpenInvite(programId, inviteId));
		return rows(program);
	}

	/** Takes back an invitation an existing mentor has not answered yet. */
	@Transactional
	public List<MentorPoolResponse.Row> withdraw(UUID actorId, UUID programId, UUID mentorId) {
		Program program = coordinatorProgramService.requireManagedProgram(actorId, programId);
		ProgramMentor membership = requireMembership(programId, mentorId);
		if (membership.getStatus() != ProgramMentorStatus.PENDING
				|| membership.getInvitedBy() != ProgramMentorInvitedBy.COORDINATOR) {
			throw new InvalidRequestException(Texts.forRequest("error.onlyOpenInviteWithdrawn"));
		}
		programMentorRepository.delete(membership);
		return rows(program);
	}

	@Transactional(readOnly = true)
	public MentorPoolResponse.Review review(UUID actorId, UUID programId, UUID mentorId) {
		Program program = coordinatorProgramService.requireManagedProgram(actorId, programId);
		ProgramMentor membership = requireAwaitingApproval(programId, mentorId);
		AppUser mentor = appUserRepository.findById(mentorId)
				.orElseThrow(() -> new ResourceNotFoundException(Texts.forRequest("error.mentorNotFound", mentorId)));
		Optional<ProgramMentorInvite> invite = inviteRepository.findByProgramIdAndMentorId(programId, mentorId);
		Optional<MentorProfile> profile = mentorProfileRepository.findById(mentorId);
		String invitedBy = invite.flatMap(value -> appUserRepository.findById(value.getCreatedBy()))
				.map(AppUser::getFullName)
				.orElse(null);
		return new MentorPoolResponse.Review(
				mentor.getId(),
				mentor.getFullName(),
				mentor.getEmail(),
				mentor.getJobTitle(),
				membership.getCreatedAt(),
				invite.isPresent(),
				invite.isPresent() || mentor.getFirebaseUid() != null,
				mentor.getLinkedinUrl(),
				invitedBy,
				profile.map(MentorProfile::getSpecialties).map(List::copyOf).orElse(List.of()),
				profile.map(MentorProfile::getAvailabilityLabel).filter(label -> !label.isBlank()).orElse(null),
				capacityOf(profile.orElse(null), program));
	}

	/**
	 * "Onayla ve havuza ekle" / "Reddet". A mentor who signed up with this program's link is still waiting for
	 * their account to be approved; the coordinator's decision settles both.
	 */
	@Transactional
	public List<MentorPoolResponse.Row> decide(UUID actorId, UUID programId, UUID mentorId, boolean accepted) {
		Program program = coordinatorProgramService.requireManagedProgram(actorId, programId);
		requireAwaitingApproval(programId, mentorId);
		AppUser mentor = appUserRepository.findById(mentorId)
				.orElseThrow(() -> new ResourceNotFoundException(Texts.forRequest("error.mentorNotFound", mentorId)));
		if (mentor.getStatus() == UserAccountStatus.PENDING) {
			if (inviteRepository.findByProgramIdAndMentorId(programId, mentorId).isEmpty()) {
				throw new InvalidRequestException(Texts.forRequest("error.mentorNeedsAdminApproval"));
			}
			mentor.setStatus(accepted ? UserAccountStatus.APPROVED : UserAccountStatus.REJECTED);
			appUserRepository.save(mentor);
		}
		programService.decideMentorRequest(actorId, programId, mentorId, accepted);
		return rows(program);
	}

	private List<MentorPoolResponse.Row> rows(Program program) {
		List<ProgramMentor> memberships = programMentorRepository.findByProgramIdOrderByCreatedAtAsc(program.getId())
				.stream()
				.filter(membership -> membership.getStatus() != ProgramMentorStatus.REJECTED)
				.toList();
		List<UUID> mentorIds = memberships.stream().map(ProgramMentor::getMentorId).toList();
		Map<UUID, AppUser> users = appUserRepository.findAllById(mentorIds).stream()
				.collect(Collectors.toMap(AppUser::getId, Function.identity()));
		Map<UUID, MentorProfile> profiles = mentorProfileRepository.findAllById(mentorIds).stream()
				.collect(Collectors.toMap(MentorProfile::getId, Function.identity()));
		Map<UUID, Long> load = mentorIds.isEmpty() ? Map.of()
				: mentorshipRepository.countOrganizationsByMentor(mentorIds, MATCHED).stream()
						.collect(Collectors.toMap(MentorCount::getMentorId, MentorCount::getTotal));
		Map<UUID, List<String>> programs = mentorIds.isEmpty() ? Map.of()
				: programMentorRepository.findProgramNamesByMentorIds(mentorIds, ProgramMentorStatus.ACCEPTED).stream()
						.collect(Collectors.groupingBy(MentorProgramName::getMentorId,
								Collectors.mapping(MentorProgramName::getProgramName, Collectors.toList())));

		Instant now = Instant.now();
		List<MentorPoolResponse.Row> rows = new ArrayList<>();
		for (ProgramMentor membership : memberships) {
			AppUser user = users.get(membership.getMentorId());
			if (user == null) {
				continue;
			}
			MentorProfile profile = profiles.get(user.getId());
			int activeTeams = load.getOrDefault(user.getId(), 0L).intValue();
			int capacity = capacityOf(profile, program);
			rows.add(new MentorPoolResponse.Row(
					user.getId(),
					null,
					user.getFullName(),
					user.getEmail(),
					statusOf(membership, activeTeams, capacity),
					profile == null ? List.of() : List.copyOf(profile.getSpecialties()),
					programs.getOrDefault(user.getId(), List.of()),
					activeTeams,
					capacity,
					membership.getStatus() == ProgramMentorStatus.ACCEPTED && membership.getDecidedAt() != null
							? membership.getDecidedAt()
							: membership.getCreatedAt(),
					null,
					null));
		}
		for (ProgramMentorInvite invite : inviteRepository.findByProgramIdOrderByCreatedAtAsc(program.getId())) {
			if (invite.getMentorId() != null) {
				continue;
			}
			rows.add(new MentorPoolResponse.Row(
					null,
					invite.getId(),
					null,
					invite.getEmail(),
					inviteStatus(invite.getExpiresAt(), now),
					List.of(),
					List.of(),
					0,
					program.getDefaultMentorCapacity(),
					invite.getCreatedAt(),
					invite.getExpiresAt(),
					invite.getCode()));
		}
		return rows;
	}

	private static MentorPoolStatus statusOf(ProgramMentor membership, int activeTeams, int capacity) {
		if (membership.getStatus() == ProgramMentorStatus.PENDING) {
			return membership.getInvitedBy() == ProgramMentorInvitedBy.MENTOR
					? MentorPoolStatus.AWAITING_APPROVAL
					: MentorPoolStatus.INVITED;
		}
		return activeTeams >= capacity ? MentorPoolStatus.FULL : MentorPoolStatus.ACTIVE;
	}

	static MentorPoolStatus inviteStatus(Instant expiresAt, Instant now) {
		if (!expiresAt.isAfter(now)) {
			return MentorPoolStatus.INVITE_EXPIRED;
		}
		return expiresAt.isBefore(now.plus(EXPIRING_SOON)) ? MentorPoolStatus.INVITE_EXPIRING : MentorPoolStatus.INVITED;
	}

	/** The mentor's own limit when they set one, otherwise the program's default (flow 03.3). */
	private static int capacityOf(MentorProfile profile, Program program) {
		return profile != null && profile.getMenteeCapacity() != null
				? profile.getMenteeCapacity()
				: program.getDefaultMentorCapacity();
	}

	private ProgramMentor requireMembership(UUID programId, UUID mentorId) {
		return programMentorRepository.findByProgramIdAndMentorId(programId, mentorId)
				.orElseThrow(() -> new ResourceNotFoundException(Texts.forRequest("error.mentorNotInProgram", mentorId)));
	}

	private ProgramMentor requireAwaitingApproval(UUID programId, UUID mentorId) {
		ProgramMentor membership = requireMembership(programId, mentorId);
		if (membership.getStatus() != ProgramMentorStatus.PENDING
				|| membership.getInvitedBy() != ProgramMentorInvitedBy.MENTOR) {
			throw new InvalidRequestException("Bu mentor bir karar beklemiyor.");
		}
		return membership;
	}

	private ProgramMentorInvite requireOpenInvite(UUID programId, UUID inviteId) {
		ProgramMentorInvite invite = inviteRepository.findByIdAndProgramId(inviteId, programId)
				.orElseThrow(() -> new ResourceNotFoundException(Texts.forRequest("error.mentorInviteNotFound", inviteId)));
		if (invite.getMentorId() != null) {
			throw new InvalidRequestException(Texts.forRequest("error.mentorAlreadySignedUp"));
		}
		return invite;
	}

	private static String newCode() {
		return UUID.randomUUID().toString().replace("-", "");
	}
}
