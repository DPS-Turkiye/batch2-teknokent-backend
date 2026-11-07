package com.dps.teknokent.service;

import com.dps.teknokent.repository.OrganizationPendingMemberRepository;
import com.dps.teknokent.entity.OrganizationPendingMember;
import com.dps.teknokent.dto.responses.OrganizationPendingMemberResponse;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.dto.requests.CreateOrganizationMemberRequest;
import com.dps.teknokent.dto.requests.DecideAccountStatusRequest;
import com.dps.teknokent.dto.requests.UpdateOrganizationMemberRequest;
import com.dps.teknokent.dto.requests.UpdateUserProfileRequest;
import com.dps.teknokent.dto.responses.AccountDecisionResponse;
import com.dps.teknokent.dto.responses.OrganizationMemberResponse;
import com.dps.teknokent.dto.responses.UserProfileResponse;
import com.dps.teknokent.dto.responses.UserResponse;
import com.dps.teknokent.domain.AppLanguage;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.OrganizationRepository;
import com.dps.teknokent.security.LoginProvisioner;
import com.dps.teknokent.util.InviteCodeGenerator;
import com.dps.teknokent.util.TextUtils;
import com.dps.teknokent.util.Texts;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class AppUserService {

	private final AppUserRepository appUserRepository;
	private final OrganizationRepository organizationRepository;
	private final DomainLookupService domainLookupService;
	private final InviteCodeGenerator inviteCodeGenerator;
	private final NotificationService notificationService;
	private final OrganizationPendingMemberRepository pendingMemberRepository;
	private final LoginProvisioner loginProvisioner;
	private final TeamMembershipService teamMembershipService;
	private final ProgramEligibilityService programEligibilityService;

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

	@Transactional
	public void saveLanguage(UUID id, AppLanguage language) {
		domainLookupService.requireUser(id).setPreferredLanguage(language);
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
		if (request.contactPreferences() != null) {
			user.getContactPreferences().clear();
			user.getContactPreferences().addAll(request.contactPreferences().stream().distinct().toList());
		}

		return UserProfileResponse.fromEntity(appUserRepository.save(user));
	}

	@Transactional(readOnly = true)
	public List<OrganizationMemberResponse> findByOrganization(UUID organizationId) {
		Organization organization = domainLookupService.requireOrganization(organizationId);
		return appUserRepository.findByOrganizationIdOrderByFullNameAsc(organizationId).stream()
				.map(member -> OrganizationMemberResponse.fromEntity(member, organization))
				.toList();
	}

	// The founder manages the whole team; everyone else may only fix their own entry.
	@Transactional
	public OrganizationMemberResponse updateOrganizationMember(
			UUID organizationId, UUID memberId, UpdateOrganizationMemberRequest request) {
		Organization organization = domainLookupService.requireOrganization(organizationId);
		AppUser member = requireMember(organization, memberId);
		if (!isFounder(organization, request.actorId()) && !memberId.equals(request.actorId())) {
			throw new InvalidRequestException("Only the founder or the member themself can edit a team member");
		}

		member.setFullName(TextUtils.trim(request.fullName()));
		member.setJobTitle(TextUtils.normalizeOptional(request.jobTitle()));
		return OrganizationMemberResponse.fromEntity(appUserRepository.save(member), organization);
	}

	@Transactional(readOnly = true)
	public List<OrganizationPendingMemberResponse> findPendingMembers(UUID organizationId, UUID actorId) {
		Organization organization = domainLookupService.requireOrganization(organizationId);
		AppUser actor = domainLookupService.requireUser(actorId);
		boolean staff = actor.getRole() == UserRole.ADMIN || actor.getRole() == UserRole.COORDINATOR;
		boolean teammate = actor.getOrganization() != null
				&& actor.getOrganization().getId().equals(organization.getId());
		if (!staff && !teammate) {
			throw new InvalidRequestException("Only the team or program staff can see pending members");
		}
		return pendingMemberRepository.findByOrganizationIdOrderByCreatedAtAsc(organizationId).stream()
				.map(OrganizationPendingMemberResponse::fromEntity)
				.toList();
	}

	@Transactional
	public void removePendingMember(UUID organizationId, UUID pendingId, UUID actorId) {
		Organization organization = domainLookupService.requireOrganization(organizationId);
		if (!isFounder(organization, actorId)) {
			throw new InvalidRequestException("Only the organization founder can remove pending members");
		}
		OrganizationPendingMember pending = pendingMemberRepository.findByIdAndOrganizationId(pendingId, organizationId)
				.orElseThrow(() -> new ResourceNotFoundException("Pending member not found: " + pendingId));
		pendingMemberRepository.delete(pending);
	}

	@Transactional
	public OrganizationMemberResponse addOrganizationMember(
			UUID organizationId, CreateOrganizationMemberRequest request) {
		Organization organization = domainLookupService.requireOrganization(organizationId);
		String email = TextUtils.trim(request.email());
		if (appUserRepository.existsByEmailIgnoreCase(email)) {
			throw new InvalidRequestException("A user with this email already exists.");
		}
		programEligibilityService.requireTeamMember(organizationId, email);

		AppUser member = new AppUser();
		member.setFullName(TextUtils.trim(request.fullName()));
		member.setEmail(email);
		member.setRole(UserRole.MENTEE);
		member.setStatus(UserAccountStatus.APPROVED);
		member.setOrganization(organization);
		member.setJobTitle(TextUtils.normalizeOptional(request.jobTitle()));
		AppUser added = appUserRepository.save(member);
		teamMembershipService.joined(added, organization);
		return OrganizationMemberResponse.fromEntity(added, organization);
	}

	private AppUser requireMember(Organization organization, UUID memberId) {
		AppUser member = domainLookupService.requireUser(memberId);
		if (member.getOrganization() == null || !organization.getId().equals(member.getOrganization().getId())) {
			throw new ResourceNotFoundException("Team member not found: " + memberId);
		}
		return member;
	}

	private static boolean isFounder(Organization organization, UUID userId) {
		return organization.getFounder() != null && organization.getFounder().getId().equals(userId);
	}

	@Transactional
	public AccountDecisionResponse decideStatus(UUID userId, DecideAccountStatusRequest request) {
		AppUser actor = domainLookupService.requireUser(request.actorId());
		if (actor.getRole() != UserRole.ADMIN) {
			throw new InvalidRequestException("Only an admin can decide account status");
		}
		return decide(domainLookupService.requireUser(userId), request.status());
	}

	/** Shared by the admin's account list and a coordinator's "Onaylar" tab. */
	@Transactional
	public AccountDecisionResponse decide(AppUser target, UserAccountStatus status) {
		if (status != UserAccountStatus.APPROVED && status != UserAccountStatus.REJECTED) {
			throw new InvalidRequestException("Account status can only be set to APPROVED or REJECTED");
		}
		if (target.getStatus() != UserAccountStatus.PENDING) {
			throw new InvalidRequestException("Only pending accounts can be decided");
		}

		target.setStatus(status);
		appUserRepository.save(target);

		String inviteCode = null;
		if (status == UserAccountStatus.APPROVED) {
			inviteCode = organizationRepository.findByFounderId(target.getId())
					.map(this::ensureInviteCode)
					.orElse(null);
		}
		notificationService.create(
				target,
				status == UserAccountStatus.APPROVED
						? Texts.get(target, "notification.applicationApproved.title")
						: Texts.get(target, "notification.applicationRejected.title"),
				status == UserAccountStatus.APPROVED
						? Texts.get(target, "notification.applicationApproved.body")
						: Texts.get(target, "notification.applicationRejected.body"),
				"/application-status");
		return new AccountDecisionResponse(UserResponse.fromEntity(target), inviteCode);
	}

	/**
	 * Signs a user out everywhere: Firebase refuses their tokens, and the recorded time lets the web app end
	 * sessions that started before it. Firebase is asked first with no transaction open, so a slow Firebase holds
	 * no pool connection and a failure records nothing.
	 */
	public UserResponse revokeSignIns(UUID userId, UUID actorId) {
		AppUser actor = domainLookupService.requireUser(actorId);
		if (actor.getRole() != UserRole.ADMIN) {
			throw new InvalidRequestException("Only an admin can end someone's sign-ins");
		}
		AppUser target = domainLookupService.requireUser(userId);
		if (target.getFirebaseUid() != null) {
			loginProvisioner.revokeSignIns(target.getFirebaseUid());
		}
		Instant revokedAt = Instant.now();
		appUserRepository.recordSignInsRevokedAt(target.getId(), revokedAt);
		target.setSignInsRevokedAt(revokedAt);
		log.info("Admin {} ended all sign-ins of user {}", actor.getId(), target.getId());
		return UserResponse.fromEntity(target);
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
