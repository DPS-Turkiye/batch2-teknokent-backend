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
import com.dps.teknokent.util.Texts;

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
		if (coordinator.getFirebaseUid() == null) {
			coordinator.setFirebaseUid(loginProvisioner.loginFor(email, coordinator.getFullName()));
		}
		coordinator.setRole(UserRole.COORDINATOR);
		coordinator.setStatus(UserAccountStatus.APPROVED);
		appUserRepository.save(coordinator);
		if (outcome == CoordinatorCandidateStatus.NEW) {
			sendPasswordSetup(coordinator);
		} else {
			notificationService.create(
					coordinator,
					Texts.get(coordinator, "notification.coordinatorAssigned.title"),
					Texts.get(coordinator, "notification.coordinatorAssigned.body"),
					"/coordinator");
			mailService.sendAfterCommit(
					coordinator.getEmail(),
					Texts.get(coordinator, "mail.coordinatorAssigned.subject"),
					Texts.get(coordinator, "mail.coordinatorAssigned.body", coordinator.getFullName(),
							mailService.link("/login")));
		}
		return toResponse(coordinator, outcome);
	}

	/** "Şifre belirleme e-postasını tekrar gönder": a fresh link for a coordinator who lost or never got it. */
	@Transactional(readOnly = true)
	public CoordinatorAccountResponse resendPasswordSetup(UUID actorId, UUID coordinatorId) {
		requireAdmin(actorId);
		AppUser coordinator = domainLookupService.requireUser(coordinatorId);
		if (coordinator.getRole() != UserRole.COORDINATOR || coordinator.getStatus() != UserAccountStatus.APPROVED) {
			throw new InvalidRequestException("Only an active coordinator can be sent a password setup email.");
		}
		if (!sendPasswordSetup(coordinator)) {
			throw new InvalidRequestException("The password setup email could not be prepared.");
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
	 * A new coordinator's login has no password, so they get the same reset link Firebase's "Şifremi unuttum"
	 * sends, but from our own domain: university mail filters tend to drop Firebase's sender.
	 */
	private boolean sendPasswordSetup(AppUser coordinator) {
		String login = mailService.link("/login");
		return loginProvisioner.passwordSetupLink(coordinator.getEmail(), login)
				.map(link -> {
					mailService.sendAfterCommit(
							coordinator.getEmail(),
							Texts.get(coordinator, "mail.coordinatorAccount.subject"),
							Texts.get(coordinator, "mail.coordinatorAccount.body", coordinator.getFullName(), link, login));
					return true;
				})
				.orElse(false);
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
