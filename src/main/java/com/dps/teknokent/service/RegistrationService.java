package com.dps.teknokent.service;

import java.util.List;
import java.util.ArrayList;
import com.dps.teknokent.repository.OrganizationPendingMemberRepository;
import com.dps.teknokent.entity.OrganizationPendingMember;
import com.dps.teknokent.dto.requests.OnboardingTeamMemberRequest;
import java.time.Instant;
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
import com.dps.teknokent.dto.requests.RegisterFounderRequest;
import com.dps.teknokent.dto.requests.RegisterMemberRequest;
import com.dps.teknokent.dto.requests.RegisterMentorRequest;
import com.dps.teknokent.dto.responses.UserResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.MentorProfile;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.ProgramStartup;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.MentorProfileRepository;
import com.dps.teknokent.repository.OnboardingDraftRepository;
import com.dps.teknokent.repository.OrganizationRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.repository.ProgramStartupRepository;
import com.dps.teknokent.security.FirebaseIdentity;
import com.dps.teknokent.util.TextUtils;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class RegistrationService {

	private final AppUserRepository appUserRepository;
	private final OrganizationRepository organizationRepository;
	private final MentorProfileRepository mentorProfileRepository;
	private final ProgramRepository programRepository;
	private final ProgramStartupRepository programStartupRepository;
	private final OnboardingDraftRepository onboardingDraftRepository;
	private final OrganizationPendingMemberRepository pendingMemberRepository;

	@Transactional
	public UserResponse registerFounder(RegisterFounderRequest request) {
		String email = requireUniqueEmail(request.email());

		Organization organization = new Organization();
		organization.setName(TextUtils.trim(request.organizationName()));
		organization.setDescription(TextUtils.normalizeOptional(request.description()));
		applyStartupFields(organization, request);
		organizationRepository.saveAndFlush(organization);

		AppUser founder = newUser(email, request.fullName(), request.jobTitle(), request.phone(), UserRole.MENTEE,
				UserAccountStatus.PENDING, organization);
		applyProfileFields(founder, request);
		applyFirebaseIdentity(founder, email);
		appUserRepository.saveAndFlush(founder);

		organization.setFounder(founder);
		attachDeclaredProgram(organization, request.programId());
		organizationRepository.saveAndFlush(organization);
		savePendingMembers(organization, request.teamMembers());
		discardOnboardingDraft();

		return UserResponse.fromEntity(founder);
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

	private void applyStartupFields(Organization organization, RegisterFounderRequest request) {
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
	 * the account stays PENDING until a coordinator approves it, and the
	 * coordinator can remove the startup from the program at any point.
	 *
	 * <p>A null programId is a real answer — "no program" — and leaves both the
	 * membership and the active choice unset.
	 */
	private void attachDeclaredProgram(Organization organization, UUID programId) {
		if (programId == null) {
			return;
		}
		if (!programRepository.existsById(programId)) {
			throw new InvalidRequestException("The selected program does not exist.");
		}
		ProgramStartup membership = new ProgramStartup();
		membership.setId(UUID.randomUUID());
		membership.setProgramId(programId);
		membership.setOrganizationId(organization.getId());
		membership.setCreatedAt(Instant.now());
		programStartupRepository.save(membership);
		organization.setActiveProgramId(programId);
	}

	@Transactional
	public UserResponse registerMentor(RegisterMentorRequest request) {
		String email = requireUniqueEmail(request.email());
		AppUser mentor = newUser(email, request.fullName(), request.jobTitle(), request.phone(), UserRole.MENTOR,
				UserAccountStatus.PENDING, null);
		applyFirebaseIdentity(mentor, email);
		appUserRepository.saveAndFlush(mentor);
		mentorProfileRepository.save(newMentorProfile(mentor));
		return UserResponse.fromEntity(mentor);
	}

	/**
	 * Onboarding updates a profile rather than creating one, so the row has to exist
	 * from registration. The presentation labels it does not collect start empty and
	 * are filled in later from the mentor profile screen.
	 */
	private MentorProfile newMentorProfile(AppUser mentor) {
		MentorProfile profile = new MentorProfile();
		profile.setUser(mentor);
		profile.setInitials(TextUtils.initials(mentor.getFullName()));
		profile.setProfessionalTitle(TextUtils.nullToEmpty(mentor.getJobTitle()));
		profile.setCompany("");
		profile.setAvailabilityLabel("");
		profile.setResponseTimeLabel("");
		profile.setMeetingMode("");
		profile.setCommunicationPreference("");
		profile.setMentoringStyle("");
		profile.setFitLabel("");
		profile.setExperienceLevel("");
		profile.setSectorExperience("");
		profile.setStartupStages("");
		profile.setSortOrder(mentorProfileRepository.findMaxSortOrder() + 1);
		return profile;
	}

	@Transactional
	public UserResponse registerMember(RegisterMemberRequest request) {
		String email = requireUniqueEmail(request.email());
		String inviteCode = TextUtils.normalizeInviteCode(request.inviteCode());
		Organization organization = organizationRepository.findByInviteCode(inviteCode)
				.orElseThrow(() -> new InvalidRequestException("Invalid invite code."));

		AppUser member = newUser(email, request.fullName(), request.jobTitle(), request.phone(), UserRole.MENTEE,
				UserAccountStatus.APPROVED, organization);
		applyFirebaseIdentity(member, email);
		pendingMemberRepository.deleteByOrganizationIdAndEmailIgnoreCase(organization.getId(), email);
		return UserResponse.fromEntity(appUserRepository.save(member));
	}

	private void applyFirebaseIdentity(AppUser user, String email) {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || !(authentication.getPrincipal() instanceof FirebaseIdentity identity)) {
			return;
		}
		if (!email.equalsIgnoreCase(identity.email())) {
			throw new InvalidRequestException("Email does not match the signed-in account.");
		}
		if (appUserRepository.findByFirebaseUid(identity.uid()).isPresent()) {
			throw new InvalidRequestException("This account is already registered.");
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
			throw new InvalidRequestException("A user with this email already exists.");
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
