package com.dps.teknokent.service;

import java.time.Instant;
import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

	@Transactional
	public UserResponse registerFounder(RegisterFounderRequest request) {
		String email = requireUniqueEmail(request.email());

		Organization organization = new Organization();
		organization.setName(TextUtils.trim(request.organizationName()));
		organization.setDescription(TextUtils.normalizeOptional(request.description()));
		organizationRepository.saveAndFlush(organization);

		AppUser founder = newUser(email, request.fullName(), request.jobTitle(), request.phone(), UserRole.MENTEE,
				UserAccountStatus.PENDING, organization);
		applyProfileFields(founder, request);
		applyFirebaseIdentity(founder, email);
		appUserRepository.saveAndFlush(founder);

		organization.setFounder(founder);
		attachDeclaredProgram(organization, request.programId());
		organizationRepository.saveAndFlush(organization);

		return UserResponse.fromEntity(founder);
	}

	/**
	 * The bio, LinkedIn and expertise collected on the wizard's first step, which
	 * the CV parser also fills. Until V33 there was nowhere to put them and they
	 * were dropped on submit.
	 *
	 * <p>Blank entries are discarded rather than stored: a free-form chip list can
	 * arrive with empties, and an empty expertise row helps nobody.
	 */
	private void applyProfileFields(AppUser founder, RegisterFounderRequest request) {
		founder.setBio(TextUtils.normalizeOptional(request.bio()));
		founder.setLinkedinUrl(TextUtils.normalizeOptional(request.linkedinUrl()));
		if (request.expertise() != null) {
			founder.setExpertise(request.expertise().stream()
					.map(TextUtils::normalizeOptional)
					.filter(java.util.Objects::nonNull)
					.distinct()
					.toList());
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
		String inviteCode = TextUtils.trim(request.inviteCode());
		Organization organization = organizationRepository.findByInviteCode(inviteCode)
				.orElseThrow(() -> new InvalidRequestException("Invalid invite code."));

		AppUser member = newUser(email, request.fullName(), request.jobTitle(), request.phone(), UserRole.MENTEE,
				UserAccountStatus.APPROVED, organization);
		applyFirebaseIdentity(member, email);
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
