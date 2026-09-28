package com.dps.teknokent.service;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.CoordinatorCandidateStatus;
import com.dps.teknokent.domain.MentorshipStatus;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.AddCoordinatorRequest;
import com.dps.teknokent.dto.responses.CoordinatorAccountResponse;
import com.dps.teknokent.dto.responses.CoordinatorCandidateResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.MentorshipRepository;
import com.dps.teknokent.security.LoginProvisioner;
import com.dps.teknokent.security.LoginProvisioner.LoginProvisioning;

import lombok.RequiredArgsConstructor;

/**
 * Admins choose the coordinators: a new email gets a fresh account, an existing mentor or mentee is promoted.
 * Every coordinator row is linked to its Firebase login by uid, because coordinator rows are never claimed
 * by email at sign-in.
 */
@Service
@RequiredArgsConstructor
public class AdminCoordinatorService {

	private static final Set<MentorshipStatus> ONGOING = EnumSet.of(MentorshipStatus.PLANNED, MentorshipStatus.ACTIVE);
	private static final Set<CoordinatorCandidateStatus> ASSIGNABLE = EnumSet.of(
			CoordinatorCandidateStatus.NEW,
			CoordinatorCandidateStatus.PROMOTABLE,
			CoordinatorCandidateStatus.REACTIVATABLE);

	private final DomainLookupService domainLookupService;
	private final AppUserRepository appUserRepository;
	private final MentorshipRepository mentorshipRepository;
	private final LoginProvisioner loginProvisioner;
	private final NotificationService notificationService;
	private final MailService mailService;

	@Transactional(readOnly = true)
	public CoordinatorCandidateResponse candidate(UUID actorId, String email) {
		requireAdmin(actorId);
		String normalized = normalize(email);
		Optional<AppUser> existing = appUserRepository.findByEmailIgnoreCase(normalized);
		return new CoordinatorCandidateResponse(
				normalized,
				classify(existing),
				existing.map(AppUser::getId).orElse(null),
				existing.map(AppUser::getFullName).orElse(null),
				existing.map(AppUser::getRole).orElse(null));
	}

	@Transactional
	public CoordinatorAccountResponse add(UUID actorId, AddCoordinatorRequest request) {
		requireAdmin(actorId);
		String email = normalize(request.email());
		Optional<AppUser> existing = appUserRepository.findByEmailIgnoreCase(email);
		CoordinatorCandidateStatus outcome = classify(existing);
		if (!ASSIGNABLE.contains(outcome)) {
			throw new InvalidRequestException(refusal(outcome));
		}
		AppUser coordinator = existing.orElseGet(() -> newCoordinator(email, request.fullName().trim()));
		LoginProvisioning provisionedLogin = null;
		if (coordinator.getFirebaseUid() == null) {
			provisionedLogin = loginProvisioner.loginFor(email, coordinator.getFullName());
			coordinator.setFirebaseUid(provisionedLogin.uid());
		}
		coordinator.setRole(UserRole.COORDINATOR);
		coordinator.setStatus(UserAccountStatus.APPROVED);
		appUserRepository.save(coordinator);
		boolean needsPasswordSetup = provisionedLogin != null
				&& (provisionedLogin.created() || outcome == CoordinatorCandidateStatus.NEW);
		if (provisionedLogin != null && !sendAccountSetup(coordinator, needsPasswordSetup)) {
			throw new InvalidRequestException("The coordinator account setup email could not be prepared.");
		}
		if (provisionedLogin == null && outcome != CoordinatorCandidateStatus.NEW
				&& !sendAccountSetup(coordinator, false)) {
			throw new InvalidRequestException("The coordinator email verification could not be prepared.");
		}
		if (outcome != CoordinatorCandidateStatus.NEW) {
			notificationService.create(
					coordinator,
					"Koordinatör olarak atandınız",
					"Artık programları koordinatör olarak yönetebilirsiniz.",
					"/coordinator");
			mailService.sendAfterCommit(
					coordinator.getEmail(),
					"menton'da koordinatör olarak atandınız",
					"Merhaba " + coordinator.getFullName() + ",\n\n"
							+ "menton'da (ODTÜ Teknokent) artık programları koordinatör olarak yönetebilirsiniz. "
							+ "Mevcut şifrenizle giriş yapabilirsiniz: " + mailService.link("/login")
							+ "\n\nmenton");
		}
		return toResponse(coordinator, outcome);
	}

	/** Resend the setup links for a coordinator who lost or never received them. */
	@Transactional(readOnly = true)
	public CoordinatorAccountResponse resendPasswordSetup(UUID actorId, UUID coordinatorId) {
		requireAdmin(actorId);
		AppUser coordinator = domainLookupService.requireUser(coordinatorId);
		if (coordinator.getRole() != UserRole.COORDINATOR || coordinator.getStatus() != UserAccountStatus.APPROVED) {
			throw new InvalidRequestException("Only an active coordinator can be sent a password setup email.");
		}
		if (!sendAccountSetup(coordinator, true)) {
			throw new InvalidRequestException("The coordinator account setup email could not be prepared.");
		}
		return toResponse(coordinator, null);
	}

	/** Deactivating keeps the row, since programs and messages still point at the coordinator. */
	@Transactional
	public CoordinatorAccountResponse setAccess(UUID actorId, UUID coordinatorId, boolean active) {
		AppUser admin = requireAdmin(actorId);
		AppUser coordinator = domainLookupService.requireUser(coordinatorId);
		if (coordinator.getRole() != UserRole.COORDINATOR) {
			throw new InvalidRequestException("Only a coordinator's access can be changed here.");
		}
		if (coordinator.getId().equals(admin.getId())) {
			throw new InvalidRequestException("You cannot change your own access.");
		}
		coordinator.setStatus(active ? UserAccountStatus.APPROVED : UserAccountStatus.REJECTED);
		appUserRepository.save(coordinator);
		return toResponse(coordinator, null);
	}

	/**
	 * A new coordinator needs both a password and a verified email. Firebase password setup does not verify the
	 * address, so send its verification action link as part of the same email.
	 */
	private boolean sendAccountSetup(AppUser coordinator, boolean includePasswordSetup) {
		String login = mailService.link("/login");
		Optional<String> verificationLink = loginProvisioner.emailVerificationLink(coordinator.getEmail(), login);
		Optional<String> passwordLink = includePasswordSetup
				? loginProvisioner.passwordSetupLink(coordinator.getEmail(), login)
				: Optional.empty();
		if ((includePasswordSetup && passwordLink.isEmpty()) || verificationLink.isEmpty()) {
			return false;
		}
		return verificationLink.map(verification -> {
					mailService.sendAfterCommit(
							coordinator.getEmail(),
							"menton koordinatör hesabınız hazır",
							"Merhaba " + coordinator.getFullName() + ",\n\n"
									+ "menton'da (ODTÜ Teknokent) koordinatör erişiminiz etkinleştirildi. "
									+ "E-posta adresinizi doğrulamak için bu bağlantıyı açın:\n\n" + verification
									+ (includePasswordSetup
											? "\n\nŞifrenizi belirlemek için bu bağlantıyı açın:\n\n"
													+ passwordLink.orElseThrow()
											: "")
									+ "\n\nE-postanızı doğrulayıp"
									+ (includePasswordSetup ? " şifrenizi belirledikten" : "")
									+ " sonra " + login + " adresinden giriş yapabilirsiniz. "
									+ "Bağlantının süresi dolarsa giriş sayfasındaki \"Şifremi unuttum\" ile yenisini "
									+ "isteyebilir ya da yöneticinizden e-postayı tekrar göndermesini isteyebilirsiniz."
									+ "\n\nmenton");
					return true;
				}).orElse(false);
	}

	private CoordinatorCandidateStatus classify(Optional<AppUser> existing) {
		if (existing.isEmpty()) {
			return CoordinatorCandidateStatus.NEW;
		}
		AppUser user = existing.get();
		return switch (user.getRole()) {
			case ADMIN -> CoordinatorCandidateStatus.ADMIN;
			case COORDINATOR -> user.getStatus() == UserAccountStatus.APPROVED
					? CoordinatorCandidateStatus.ALREADY_COORDINATOR
					: CoordinatorCandidateStatus.REACTIVATABLE;
			case MENTEE -> user.getOrganization() != null
					? CoordinatorCandidateStatus.TEAM_MEMBER
					: CoordinatorCandidateStatus.PROMOTABLE;
			case MENTOR -> mentorshipRepository.countByMentorIdAndStatusIn(user.getId(), ONGOING) > 0
					? CoordinatorCandidateStatus.MENTOR_IN_MENTORSHIP
					: CoordinatorCandidateStatus.PROMOTABLE;
		};
	}

	private static String refusal(CoordinatorCandidateStatus status) {
		return switch (status) {
			case ALREADY_COORDINATOR -> "This person is already a coordinator.";
			case ADMIN -> "This email belongs to an admin.";
			case TEAM_MEMBER -> "A startup team member cannot become a coordinator.";
			case MENTOR_IN_MENTORSHIP -> "This mentor still has a planned or active mentorship.";
			default -> "This email cannot become a coordinator.";
		};
	}

	private static AppUser newCoordinator(String email, String fullName) {
		AppUser user = new AppUser();
		user.setEmail(email);
		user.setFullName(fullName);
		return user;
	}

	private AppUser requireAdmin(UUID actorId) {
		AppUser actor = domainLookupService.requireUser(actorId);
		if (actor.getRole() != UserRole.ADMIN) {
			throw new InvalidRequestException("Only an admin can manage coordinators.");
		}
		return actor;
	}

	private static String normalize(String email) {
		return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
	}

	private static CoordinatorAccountResponse toResponse(AppUser user, CoordinatorCandidateStatus outcome) {
		return new CoordinatorAccountResponse(user.getId(), user.getEmail(), user.getFullName(), user.getStatus(), outcome);
	}
}
