package com.dps.teknokent.service;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.dto.requests.CreateOrganizationMemberRequest;
import com.dps.teknokent.dto.requests.DecideAccountStatusRequest;
import com.dps.teknokent.dto.requests.UpdateUserProfileRequest;
import com.dps.teknokent.dto.responses.AccountDecisionResponse;
import com.dps.teknokent.dto.responses.UserProfileResponse;
import com.dps.teknokent.dto.responses.UserResponse;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.OrganizationRepository;
import com.dps.teknokent.util.InviteCodeGenerator;
import com.dps.teknokent.util.TextUtils;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AppUserService {

	private final AppUserRepository appUserRepository;
	private final OrganizationRepository organizationRepository;
	private final DomainLookupService domainLookupService;
	private final InviteCodeGenerator inviteCodeGenerator;
	private final NotificationService notificationService;

	@Transactional(readOnly = true)
	public List<UserResponse> findAll(UserRole role) {
		return findAll(role, null);
	}

	@Transactional(readOnly = true)
	public List<UserResponse> findAll(UserRole role, UserAccountStatus status) {
		return appUserRepository.findAll().stream()
				.filter(user -> role == null || user.getRole() == role)
				.filter(user -> status == null || user.getStatus() == status)
				.sorted(Comparator.comparing(user -> user.getFullName().toLowerCase()))
				.map(UserResponse::fromEntity)
				.toList();
	}

	@Transactional(readOnly = true)
	public UserResponse findById(UUID id) {
		return appUserRepository.findById(id).map(UserResponse::fromEntity)
				.orElseThrow(() -> new ResourceNotFoundException("User not found: " + id));
	}

	@Transactional(readOnly = true)
	public UserProfileResponse findProfile(UUID id) {
		return UserProfileResponse.fromEntity(domainLookupService.requireUser(id));
	}

	// Self only, and not gated on APPROVED: a pending account waiting on review
	// still needs to be able to correct what they submitted.
	@Transactional
	public UserProfileResponse updateProfile(UUID id, UpdateUserProfileRequest request) {
		AppUser user = domainLookupService.requireUser(id);
		if (!id.equals(request.actorId())) {
			throw new InvalidRequestException("You can only edit your own profile");
		}

		user.setFullName(TextUtils.trim(request.fullName()));
		user.setJobTitle(TextUtils.normalizeOptional(request.jobTitle()));
		user.setPhone(TextUtils.normalizeOptional(request.phone()));
		user.setBio(TextUtils.normalizeOptional(request.bio()));
		user.setLinkedinUrl(TextUtils.normalizeOptional(request.linkedinUrl()));
		user.getExpertise().clear();
		user.getExpertise().addAll(TextUtils.normalizedList(request.expertise()));

		return UserProfileResponse.fromEntity(appUserRepository.save(user));
	}

	@Transactional(readOnly = true)
	public List<UserResponse> findByOrganization(UUID organizationId) {
		domainLookupService.requireOrganization(organizationId);
		return appUserRepository.findByOrganizationIdOrderByFullNameAsc(organizationId).stream()
				.map(UserResponse::fromEntity)
				.toList();
	}

	@Transactional
	public UserResponse addOrganizationMember(UUID organizationId, CreateOrganizationMemberRequest request) {
		Organization organization = domainLookupService.requireOrganization(organizationId);
		String email = TextUtils.trim(request.email());
		if (appUserRepository.existsByEmailIgnoreCase(email)) {
			throw new InvalidRequestException("A user with this email already exists.");
		}

		AppUser member = new AppUser();
		member.setFullName(TextUtils.trim(request.fullName()));
		member.setEmail(email);
		member.setRole(UserRole.MENTEE);
		member.setStatus(UserAccountStatus.APPROVED);
		member.setOrganization(organization);
		member.setJobTitle(TextUtils.normalizeOptional(request.jobTitle()));
		return UserResponse.fromEntity(appUserRepository.save(member));
	}

	@Transactional
	public AccountDecisionResponse decideStatus(UUID userId, DecideAccountStatusRequest request) {
		AppUser actor = domainLookupService.requireUser(request.actorId());
		if (actor.getRole() != UserRole.ADMIN) {
			throw new InvalidRequestException("Only an admin can decide account status");
		}
		if (request.status() != UserAccountStatus.APPROVED && request.status() != UserAccountStatus.REJECTED) {
			throw new InvalidRequestException("Account status can only be set to APPROVED or REJECTED");
		}

		AppUser target = domainLookupService.requireUser(userId);
		if (target.getStatus() != UserAccountStatus.PENDING) {
			throw new InvalidRequestException("Only pending accounts can be decided");
		}

		target.setStatus(request.status());
		appUserRepository.save(target);

		String inviteCode = null;
		if (request.status() == UserAccountStatus.APPROVED) {
			inviteCode = organizationRepository.findByFounderId(target.getId())
					.map(this::ensureInviteCode)
					.orElse(null);
		}
		notificationService.create(
				target,
				request.status() == UserAccountStatus.APPROVED
						? "Başvurunuz onaylandı"
						: "Başvurunuz sonuçlandı",
				request.status() == UserAccountStatus.APPROVED
						? "Hesabınız kullanıma açıldı. Platforma giriş yapabilirsiniz."
						: "Başvurunuz bu aşamada onaylanmadı.",
				"/application-status");
		return new AccountDecisionResponse(UserResponse.fromEntity(target), inviteCode);
	}

	private String ensureInviteCode(Organization organization) {
		if (organization.getInviteCode() != null) {
			return organization.getInviteCode();
		}
		String inviteCode = inviteCodeGenerator.nextUnique(organizationRepository::existsByInviteCode);
		organization.setInviteCode(inviteCode);
		organizationRepository.save(organization);
		return inviteCode;
	}
}
