package com.dps.teknokent.service;

import java.util.List;
import java.util.ArrayList;
import com.dps.teknokent.repository.OrganizationPendingMemberRepository;
import com.dps.teknokent.entity.OrganizationPendingMember;
import com.dps.teknokent.dto.requests.OnboardingTeamMemberRequest;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.StartupMaturity;
import com.dps.teknokent.domain.StartupProgramLevel;
import com.dps.teknokent.domain.StartupSector;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.CreateStartupRequest;
import com.dps.teknokent.dto.requests.RegisterFounderRequest;
import com.dps.teknokent.dto.requests.RegisterMemberRequest;
import com.dps.teknokent.dto.requests.RegisterMentorRequest;
import com.dps.teknokent.dto.requests.StartupProfileFields;
import com.dps.teknokent.dto.responses.UserResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.MentorProfile;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramStartup;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.MentorProfileRepository;
import com.dps.teknokent.repository.OnboardingDraftRepository;
import com.dps.teknokent.repository.OrganizationRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.repository.ProgramStartupRepository;
import com.dps.teknokent.security.FirebaseIdentity;
import com.dps.teknokent.util.InviteCodeGenerator;
import com.dps.teknokent.util.ProgramDrafts;
import com.dps.teknokent.util.TextUtils;

import lombok.RequiredArgsConstructor;
import com.dps.teknokent.entity.ProgramTeamInvite;
import com.dps.teknokent.repository.ProgramTeamInviteRepository;
import com.dps.teknokent.domain.ProgramMentorInvitedBy;
import com.dps.teknokent.domain.ProgramMentorStatus;
import com.dps.teknokent.domain.ProgramStatus;
import com.dps.teknokent.entity.ProgramMentor;
import com.dps.teknokent.entity.ProgramMentorInvite;
import com.dps.teknokent.repository.ProgramMentorInviteRepository;
import com.dps.teknokent.repository.ProgramMentorRepository;
import com.dps.teknokent.util.Texts;

@Service
@RequiredArgsConstructor
public class RegistrationService {

	/** Sign-up met an address that already has an account; the client sends the person to sign in. */
	public static final String EMAIL_ALREADY_REGISTERED = "EMAIL_ALREADY_REGISTERED";
	/** This Firebase login already belongs to an account. */
	public static final String ACCOUNT_ALREADY_REGISTERED = "ACCOUNT_ALREADY_REGISTERED";

	private final AppUserRepository appUserRepository;
	private final OrganizationRepository organizationRepository;
	private final MentorProfileRepository mentorProfileRepository;
	private final ProgramRepository programRepository;
	private final ProgramStartupRepository programStartupRepository;
	private final OnboardingDraftRepository onboardingDraftRepository;
	private final OrganizationPendingMemberRepository pendingMemberRepository;
	private final ProgramTeamInviteRepository programTeamInviteRepository;
	private final ProgramMentorInviteRepository programMentorInviteRepository;
	private final ProgramMentorRepository programMentorRepository;
	private final DomainLookupService domainLookupService;
	private final InviteCodeGenerator inviteCodeGenerator;
	private final TeamMembershipService teamMembershipService;
	private final NotificationService notificationService;
	private final ProgramEligibilityService programEligibilityService;

	@Transactional
	public UserResponse registerFounder(RegisterFounderRequest request) {
		String email = requireUniqueEmail(request.email());
		programEligibilityService.requirePerson(request.programId(), email);
		boolean individual = isIndividualProgram(request.programId());
		if (!individual && Boolean.TRUE.equals(request.startupLater())) {
			return registerWithoutStartup(request, email);
		}
		// In an individual-mentee program (İŞKUR) each student is a team of one, named after them, so requests,
		// matching and meetings work unchanged without the student creating a startup.
		String organizationName = individual ? request.fullName() : request.organizationName();
		if (TextUtils.normalizeOptional(organizationName) == null) {
			throw new InvalidRequestException("The startup name is required.");
		}

		Organization organization = new Organization();
		organization.setName(TextUtils.trim(organizationName));
		organization.setDescription(TextUtils.normalizeOptional(request.description()));
		applyStartupFields(organization, request);
		organizationRepository.saveAndFlush(organization);

		AppUser founder = newUser(email, request.fullName(), request.jobTitle(), request.phone(), UserRole.MENTEE,
				UserAccountStatus.PENDING, organization);
		applyProfileFields(founder, request);
		founder.setProgramId(request.programId());
		applyFirebaseIdentity(founder, email);
		appUserRepository.saveAndFlush(founder);

		organization.setFounder(founder);
		attachDeclaredProgram(organization, request.programId());
		organizationRepository.saveAndFlush(organization);
		teamMembershipService.joined(founder, organization);
		if (!individual) {
			savePendingMembers(organization, request.teamMembers());
		}
		discardOnboardingDraft();

		return UserResponse.fromEntity(founder, individual);
	}

	private boolean isIndividualProgram(UUID programId) {
		return programId != null
				&& programRepository.findById(programId).map(Program::isIndividualMentees).orElse(false);
	}

	/**
	 * "Startup'ı daha sonra oluşturacağım": the person waits for the program's coordinator like a founder does, then
	 * joins or creates a startup from /startup.
	 */
	private UserResponse registerWithoutStartup(RegisterFounderRequest request, String email) {
		requireProgram(request.programId());
		AppUser person = newUser(email, request.fullName(), request.jobTitle(), request.phone(), UserRole.MENTEE,
				UserAccountStatus.PENDING, null);
		applyProfileFields(person, request);
		person.setProgramId(request.programId());
		applyFirebaseIdentity(person, email);
		appUserRepository.saveAndFlush(person);
		discardOnboardingDraft();
		return UserResponse.fromEntity(person);
	}

	/** "Kodum var" on /startup: joins at once, but only a startup of the person's own program. */
	@Transactional
	public UserResponse joinStartup(UUID actorId, String rawCode) {
		AppUser person = requireWithoutStartup(actorId);
		Organization organization = organizationRepository.findByInviteCode(TextUtils.normalizeInviteCode(rawCode))
				.orElseThrow(() -> new InvalidRequestException(Texts.forRequest("error.startupCodeInvalid")));
		if (person.getProgramId() == null || programStartupRepository
				.findByProgramIdAndOrganizationId(person.getProgramId(), organization.getId()).isEmpty()) {
			throw new InvalidRequestException(Texts.forRequest("error.startupCodeOtherProgram"));
		}
		programEligibilityService.requireTeamMember(organization.getId(), person.getEmail());
		person.setOrganization(organization);
		pendingMemberRepository.deleteByOrganizationIdAndEmailIgnoreCase(organization.getId(), person.getEmail());
		AppUser joined = appUserRepository.save(person);
		teamMembershipService.joined(joined, organization);
		notifyLeaderOfNewMember(organization, joined);
		return UserResponse.fromEntity(joined);
	}

	/**
	 * "Startup'ımı oluştur" on /startup: the startup joins the creator's program and waits for its coordinator. The
	 * code exists from the start so the creator can already bring the team in.
	 */
	@Transactional
	public UserResponse createStartup(UUID actorId, CreateStartupRequest request) {
		AppUser person = requireWithoutStartup(actorId);
		Organization organization = new Organization();
		organization.setName(TextUtils.trim(request.name()));
		organization.setDescription(TextUtils.normalizeOptional(request.description()));
		applyStartupFields(organization, request);
		organization.setApprovalStatus(UserAccountStatus.PENDING);
		organization.setInviteCode(inviteCodeGenerator.nextUnique(organizationRepository::existsByInviteCode));
		organization.setFounder(person);
		organizationRepository.saveAndFlush(organization);
		attachDeclaredProgram(organization, person.getProgramId());
		organizationRepository.save(organization);
		savePendingMembers(organization, request.teamMembers());

		person.setOrganization(organization);
		AppUser founder = appUserRepository.save(person);
		teamMembershipService.joined(founder, organization);
		return UserResponse.fromEntity(founder);
	}

	private AppUser requireWithoutStartup(UUID actorId) {
		AppUser person = domainLookupService.requireUser(actorId);
		if (!person.has(UserRole.MENTEE) || person.getStatus() != UserAccountStatus.APPROVED
				|| person.getOrganization() != null) {
			throw new InvalidRequestException(Texts.forRequest("error.withoutStartupOnly"));
		}
		return person;
	}

	/**
	 * A coordinator put this team into a program and handed its lead the code (flow 03.3). The lead's signup
	 * creates the team, already approved and in the program; the code then serves the members as the team's
	 * invite code. Only the lead's own address can claim it, so a forwarded code alone takes nothing.
	 */
	private UserResponse claimTeam(ProgramTeamInvite invite, RegisterMemberRequest request, String email) {
		if (!invite.getLeadEmail().equalsIgnoreCase(email)) {
			throw new InvalidRequestException(
					"This code is for the team's lead; members can use it once the lead has joined.");
		}
		requireLaunched(invite.getProgramId());
		programEligibilityService.requirePerson(invite.getProgramId(), email);
		Organization organization = new Organization();
		organization.setName(invite.getTeamName());
		organization.setInviteCode(invite.getCode());
		organization.setActiveProgramId(invite.getProgramId());
		organizationRepository.saveAndFlush(organization);

		AppUser lead = newUser(email, request.fullName(), request.jobTitle(), request.phone(), UserRole.MENTEE,
				UserAccountStatus.APPROVED, organization);
		applyFirebaseIdentity(lead, email);
		appUserRepository.saveAndFlush(lead);
		organization.setFounder(lead);
		organizationRepository.saveAndFlush(organization);
		teamMembershipService.joined(lead, organization);

		ProgramStartup membership = new ProgramStartup();
		membership.setId(UUID.randomUUID());
		membership.setProgramId(invite.getProgramId());
		membership.setOrganizationId(organization.getId());
		membership.setCreatedAt(Instant.now());
		programStartupRepository.save(membership);

		invite.setOrganizationId(organization.getId());
		invite.setJoinedAt(Instant.now());
		programTeamInviteRepository.save(invite);
		return UserResponse.fromEntity(lead);
	}

	private void applyProfileFields(AppUser founder, RegisterFounderRequest request) {
		founder.setBio(TextUtils.normalizeOptional(request.bio()));
		founder.setLinkedinUrl(TextUtils.normalizeOptional(request.linkedinUrl()));
		if (request.expertise() != null) {
			founder.setExpertise(TextUtils.normalizedList(request.expertise()));
		}
		if (request.contactPreferences() != null) {
			founder.setContactPreferences(new ArrayList<>(request.contactPreferences().stream().distinct().toList()));
		}
	}

	private static void applyStartupFields(Organization organization, StartupProfileFields request) {
		organization.setWebsite(TextUtils.normalizeOptional(request.website()));
		organization.setMvpUrl(TextUtils.normalizeOptional(request.mvpUrl()));
		organization.setFoundedYear(request.foundedYear());
		organization.setProblem(TextUtils.normalizeOptional(request.problem()));
		organization.setSolution(TextUtils.normalizeOptional(request.solution()));
		organization.setCurrentSolution(TextUtils.normalizeOptional(request.currentSolution()));
		organization.setTargetUser(TextUtils.normalizeOptional(request.targetUser()));
		organization.setSector(StartupSector.requireValidOrNull(request.sector()));
		organization.setMaturity(StartupMaturity.requireValidOrNull(request.maturity()));
		organization.setProgramLevel(StartupProgramLevel.requireValidOrNull(request.programLevel()));
		if (request.supportAreas() != null) {
			organization.setSupportAreas(TextUtils.normalizedList(request.supportAreas()));
		}
	}

	/**
	 * Teammates listed during signup. They are names only until each person joins
	 * with the organization's invite code, which removes the matching entry.
	 */
	private void savePendingMembers(Organization organization, List<OnboardingTeamMemberRequest> members) {
		if (members == null) {
			return;
		}
		Instant now = Instant.now();
		for (OnboardingTeamMemberRequest member : members) {
			OrganizationPendingMember pending = new OrganizationPendingMember();
			pending.setId(UUID.randomUUID());
			pending.setOrganizationId(organization.getId());
			pending.setFullName(TextUtils.trim(member.fullName()));
			pending.setJobTitle(TextUtils.normalizeOptional(member.jobTitle()));
			pending.setEmail(TextUtils.normalizeOptional(member.email()));
			pending.setCreatedAt(now);
			pendingMemberRepository.save(pending);
		}
	}

	/**
	 * The program the founder chose during signup. This is a declaration made
	 * with the application, not a self-assignment behind the coordinator's back:
	 * the account (or a startup created later) stays PENDING until a coordinator
	 * approves it, and the coordinator can remove the startup from the program at
	 * any point.
	 */
	private void attachDeclaredProgram(Organization organization, UUID programId) {
		requireProgram(programId);
		ProgramStartup membership = new ProgramStartup();
		membership.setId(UUID.randomUUID());
		membership.setProgramId(programId);
		membership.setOrganizationId(organization.getId());
		membership.setCreatedAt(Instant.now());
		programStartupRepository.save(membership);
		organization.setActiveProgramId(programId);
	}

	private void requireLaunched(UUID programId) {
		programRepository.findById(programId).ifPresent(ProgramDrafts::requireLaunched);
	}

	private void requireProgram(UUID programId) {
		if (programId == null) {
			throw new InvalidRequestException("A program is required.");
		}
		boolean open = programRepository.findById(programId)
				.filter(program -> program.getStatus() != ProgramStatus.DRAFT)
				.isPresent();
		if (!open) {
			throw new InvalidRequestException("The selected program does not exist.");
		}
	}

	@Transactional
	public UserResponse registerMentor(RegisterMentorRequest request) {
		String email = requireUniqueEmail(request.email());
		Optional<ProgramMentorInvite> programInvite = programMentorInvite(request.programInvite(), email);
		AppUser mentor = newUser(email, request.fullName(), request.jobTitle(), request.phone(), UserRole.MENTOR,
				UserAccountStatus.PENDING, null);
		applyFirebaseIdentity(mentor, email);
		appUserRepository.saveAndFlush(mentor);
		mentorProfileRepository.save(MentorProfile.blankFor(mentor, mentorProfileRepository.findMaxSortOrder() + 1));
		programInvite.ifPresent(invite -> joinProgramPool(invite, mentor));
		return UserResponse.fromEntity(mentor);
	}

	/** A mentor signing up with a program's link (flow 03.1) must use the address the coordinator invited. */
	private Optional<ProgramMentorInvite> programMentorInvite(String code, String email) {
		if (code == null || code.isBlank()) {
			return Optional.empty();
		}
		ProgramMentorInvite invite = programMentorInviteRepository.findByCode(code.trim())
				.filter(value -> value.getMentorId() == null)
				.orElseThrow(() -> new InvalidRequestException(Texts.forRequest("error.programInviteInvalid")));
		if (!invite.getExpiresAt().isAfter(Instant.now())) {
			throw new InvalidRequestException(Texts.forRequest("error.programInviteExpired"));
		}
		if (!invite.getEmail().equalsIgnoreCase(email)) {
			throw new InvalidRequestException(Texts.forRequest("error.programInviteWrongEmail"));
		}
		requireLaunched(invite.getProgramId());
		programEligibilityService.requirePerson(invite.getProgramId(), email);
		return Optional.of(invite);
	}

	/** Leaves the mentor waiting for the coordinator's expertise check, like a mentor asking to join. */
	private void joinProgramPool(ProgramMentorInvite invite, AppUser mentor) {
		ProgramMentor membership = new ProgramMentor();
		membership.setId(UUID.randomUUID());
		membership.setProgramId(invite.getProgramId());
		membership.setMentorId(mentor.getId());
		membership.setStatus(ProgramMentorStatus.PENDING);
		membership.setInvitedBy(ProgramMentorInvitedBy.MENTOR);
		membership.setCreatedAt(Instant.now());
		programMentorRepository.save(membership);
		invite.setMentorId(mentor.getId());
		invite.setJoinedAt(Instant.now());
		programMentorInviteRepository.save(invite);
	}

	@Transactional
	public UserResponse registerMember(RegisterMemberRequest request) {
		String email = requireUniqueEmail(request.email());
		String inviteCode = TextUtils.normalizeInviteCode(request.inviteCode());
		Optional<ProgramTeamInvite> teamInvite = programTeamInviteRepository.findByCode(inviteCode)
				.filter(invite -> invite.getOrganizationId() == null);
		if (teamInvite.isPresent()) {
			return claimTeam(teamInvite.get(), request, email);
		}
		Organization organization = organizationRepository.findByInviteCode(inviteCode)
				.orElseThrow(() -> new InvalidRequestException("Invalid invite code."));
		programEligibilityService.requireTeamMember(organization.getId(), email);

		AppUser member = newUser(email, request.fullName(), request.jobTitle(), request.phone(), UserRole.MENTEE,
				UserAccountStatus.APPROVED, organization);
		applyFirebaseIdentity(member, email);
		pendingMemberRepository.deleteByOrganizationIdAndEmailIgnoreCase(organization.getId(), email);
		AppUser joined = appUserRepository.save(member);
		teamMembershipService.joined(joined, organization);
		notifyLeaderOfNewMember(organization, joined);
		return UserResponse.fromEntity(joined);
	}

	/** Whoever joins with the startup's code, at signup or later, is announced to the team's current leader. */
	private void notifyLeaderOfNewMember(Organization organization, AppUser member) {
		AppUser leader = organization.getFounder();
		if (leader == null || leader.getId().equals(member.getId())) {
			return;
		}
		notificationService.create(leader, Texts.get(leader, "notification.teamMemberJoined.title",
				member.getFullName()),
				Texts.get(leader, "notification.teamMemberJoined.body", member.getFullName(), organization.getName()),
				"/startup/team");
	}

	private void applyFirebaseIdentity(AppUser user, String email) {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || !(authentication.getPrincipal() instanceof FirebaseIdentity identity)) {
			return;
		}
		if (!email.equalsIgnoreCase(identity.email())) {
			throw new InvalidRequestException("Email does not match the signed-in account.");
		}
		// Team claims, program invites and startup codes trust the address as proof of who is joining.
		if (!identity.emailVerified()) {
			throw new InvalidRequestException("Verify your email address before registering.");
		}
		if (appUserRepository.findByFirebaseUid(identity.uid()).isPresent()) {
			throw new InvalidRequestException(Texts.forRequest("error.accountAlreadyRegistered"),
					ACCOUNT_ALREADY_REGISTERED);
		}
		user.setFirebaseUid(identity.uid());
	}

	private void discardOnboardingDraft() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || !(authentication.getPrincipal() instanceof FirebaseIdentity identity)) {
			return;
		}
		onboardingDraftRepository.deleteById(identity.uid());
	}

	private String requireUniqueEmail(String rawEmail) {
		String email = TextUtils.trim(rawEmail);
		if (appUserRepository.existsByEmailIgnoreCase(email)) {
			throw new InvalidRequestException(Texts.forRequest("error.emailAlreadyRegistered"),
					EMAIL_ALREADY_REGISTERED);
		}
		return email;
	}

	private static AppUser newUser(
			String email,
			String fullName,
			String jobTitle,
			String phone,
			UserRole role,
			UserAccountStatus status,
			Organization organization) {
		AppUser user = new AppUser();
		user.setEmail(email);
		user.setFullName(TextUtils.trim(fullName));
		user.setJobTitle(TextUtils.normalizeOptional(jobTitle));
		user.setPhone(TextUtils.normalizeOptional(phone));
		user.setRole(role);
		user.setStatus(status);
		user.setOrganization(organization);
		return user;
	}
}
