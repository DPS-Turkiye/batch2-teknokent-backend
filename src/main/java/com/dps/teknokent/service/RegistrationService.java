package com.dps.teknokent.service;

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
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.OrganizationRepository;
import com.dps.teknokent.security.FirebaseIdentity;
import com.dps.teknokent.util.TextUtils;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class RegistrationService {

	private final AppUserRepository appUserRepository;
	private final OrganizationRepository organizationRepository;

	@Transactional
	public UserResponse registerFounder(RegisterFounderRequest request) {
		String email = requireUniqueEmail(request.email());

		Organization organization = new Organization();
		organization.setName(TextUtils.trim(request.organizationName()));
		organization.setDescription(TextUtils.normalizeOptional(request.description()));
		organizationRepository.saveAndFlush(organization);

		AppUser founder = newUser(email, request.fullName(), request.jobTitle(), request.phone(), UserRole.MENTEE,
				UserAccountStatus.PENDING, organization);
		applyFirebaseIdentity(founder, email);
		appUserRepository.saveAndFlush(founder);

		organization.setFounder(founder);
		organizationRepository.saveAndFlush(organization);

		return UserResponse.fromEntity(founder);
	}

	@Transactional
	public UserResponse registerMentor(RegisterMentorRequest request) {
		String email = requireUniqueEmail(request.email());
		AppUser mentor = newUser(email, request.fullName(), request.jobTitle(), request.phone(), UserRole.MENTOR,
				UserAccountStatus.PENDING, null);
		applyFirebaseIdentity(mentor, email);
		return UserResponse.fromEntity(appUserRepository.save(mentor));
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
