package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dps.teknokent.domain.CoordinatorCandidateStatus;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.AddCoordinatorRequest;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.MentorshipRepository;
import com.dps.teknokent.security.LoginProvisioner;

@ExtendWith(MockitoExtension.class)
class AdminCoordinatorServiceTest {

	@Mock
	private DomainLookupService domainLookupService;
	@Mock
	private AppUserRepository appUserRepository;
	@Mock
	private MentorshipRepository mentorshipRepository;
	@Mock
	private LoginProvisioner loginProvisioner;
	@Mock
	private NotificationService notificationService;
	@Mock
	private MailService mailService;
	@InjectMocks
	private AdminCoordinatorService service;

	private static final UUID ADMIN_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
	private static final String LOGIN = "https://menton.com.tr/login";
	private static final UUID MENTOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000006");

	@Test
	void aNewEmailGetsAFirebaseLoginAndAnApprovedCoordinatorAccount() {
		stubAdmin();
		when(appUserRepository.findByEmailIgnoreCase("elif@teknokent.org")).thenReturn(Optional.empty());
		when(loginProvisioner.loginFor("elif@teknokent.org", "Elif Demir")).thenReturn("uid-elif");
		when(mailService.link("/login")).thenReturn(LOGIN);
		when(loginProvisioner.passwordSetupLink("elif@teknokent.org", LOGIN))
				.thenReturn(Optional.of("https://auth.example/reset?oobCode=1"));

		var result = service.add(ADMIN_ID, new AddCoordinatorRequest(null, " Elif Demir ", "  Elif@Teknokent.org "));

		ArgumentCaptor<AppUser> saved = ArgumentCaptor.forClass(AppUser.class);
		verify(appUserRepository).save(saved.capture());
		assertThat(saved.getValue().getId()).as("the id is generated on insert").isNull();
		assertThat(saved.getValue().getEmail()).isEqualTo("elif@teknokent.org");
		assertThat(saved.getValue().getFullName()).isEqualTo("Elif Demir");
		assertThat(saved.getValue().getRole()).isEqualTo(UserRole.COORDINATOR);
		assertThat(saved.getValue().getStatus()).isEqualTo(UserAccountStatus.APPROVED);
		assertThat(saved.getValue().getFirebaseUid()).isEqualTo("uid-elif");
		assertThat(result.outcome()).isEqualTo(CoordinatorCandidateStatus.NEW);
		verify(notificationService, never()).create(any(), anyString(), anyString(), anyString());
		verify(mailService).sendAfterCommit(
				eq("elif@teknokent.org"),
				eq("menton koordinatör hesabınız hazır"),
				contains("https://auth.example/reset?oobCode=1"));
	}

	@Test
	void anAdminResendsThePasswordSetupEmailOnlyToAnActiveCoordinator() {
		stubAdmin();
		UUID coordinatorId = UUID.randomUUID();
		AppUser coordinator = user(coordinatorId, "elif@teknokent.org", UserRole.COORDINATOR);
		when(domainLookupService.requireUser(coordinatorId)).thenReturn(coordinator);
		when(mailService.link("/login")).thenReturn(LOGIN);
		when(loginProvisioner.passwordSetupLink("elif@teknokent.org", LOGIN))
				.thenReturn(Optional.of("https://auth.example/reset?oobCode=2"));

		service.resendPasswordSetup(ADMIN_ID, coordinatorId);

		verify(mailService).sendAfterCommit(
				eq("elif@teknokent.org"),
				anyString(),
				contains("https://auth.example/reset?oobCode=2"));

		when(loginProvisioner.passwordSetupLink("elif@teknokent.org", LOGIN)).thenReturn(Optional.empty());
		assertThatThrownBy(() -> service.resendPasswordSetup(ADMIN_ID, coordinatorId))
				.isInstanceOf(InvalidRequestException.class);

		UUID mentorId = UUID.randomUUID();
		when(domainLookupService.requireUser(mentorId)).thenReturn(user(mentorId, "m@x.co", UserRole.MENTOR));
		assertThatThrownBy(() -> service.resendPasswordSetup(ADMIN_ID, mentorId))
				.isInstanceOf(InvalidRequestException.class);
	}

	@Test
	void aFreeMentorIsPromotedKeepsTheirNameAndIsNotified() {
		stubAdmin();
		AppUser mentor = user(MENTOR_ID, "ayse@mentor.com", UserRole.MENTOR);
		mentor.setFirebaseUid("uid-ayse");
		when(appUserRepository.findByEmailIgnoreCase("ayse@mentor.com")).thenReturn(Optional.of(mentor));
		when(mentorshipRepository.countByMentorIdAndStatusIn(eq(MENTOR_ID), any())).thenReturn(0L);

		var result = service.add(ADMIN_ID, new AddCoordinatorRequest(null, "Başka İsim", "ayse@mentor.com"));

		assertThat(mentor.getRole()).isEqualTo(UserRole.COORDINATOR);
		assertThat(mentor.getFullName()).isEqualTo("Ayşe Demir");
		assertThat(result.outcome()).isEqualTo(CoordinatorCandidateStatus.PROMOTABLE);
		verify(loginProvisioner, never()).loginFor(anyString(), anyString());
		verify(notificationService).create(eq(mentor), anyString(), anyString(), eq("/coordinator"));
	}

	@Test
	void aPromotedUserWithoutALinkedLoginIsLinkedByUid() {
		stubAdmin();
		AppUser mentee = user(UUID.randomUUID(), "can@solo.com", UserRole.MENTEE);
		when(appUserRepository.findByEmailIgnoreCase("can@solo.com")).thenReturn(Optional.of(mentee));
		when(loginProvisioner.loginFor("can@solo.com", "Ayşe Demir")).thenReturn("uid-can");

		service.add(ADMIN_ID, new AddCoordinatorRequest(null, "Can", "can@solo.com"));

		assertThat(mentee.getFirebaseUid()).isEqualTo("uid-can");
		assertThat(mentee.getRole()).isEqualTo(UserRole.COORDINATOR);
	}

	@Test
	void aDeactivatedCoordinatorIsLetBackIn() {
		stubAdmin();
		AppUser coordinator = user(UUID.randomUUID(), "old@teknokent.org", UserRole.COORDINATOR);
		coordinator.setStatus(UserAccountStatus.REJECTED);
		coordinator.setFirebaseUid("uid-old");
		when(appUserRepository.findByEmailIgnoreCase("old@teknokent.org")).thenReturn(Optional.of(coordinator));

		var candidate = service.candidate(ADMIN_ID, "old@teknokent.org");
		var result = service.add(ADMIN_ID, new AddCoordinatorRequest(null, "Old", "old@teknokent.org"));

		assertThat(candidate.status()).isEqualTo(CoordinatorCandidateStatus.REACTIVATABLE);
		assertThat(result.outcome()).isEqualTo(CoordinatorCandidateStatus.REACTIVATABLE);
		assertThat(coordinator.getStatus()).isEqualTo(UserAccountStatus.APPROVED);
	}

	@Test
	void teamMembersBusyMentorsAdminsAndCoordinatorsAreRefused() {
		stubAdmin();
		AppUser teamMember = user(UUID.randomUUID(), "team@startup.com", UserRole.MENTEE);
		teamMember.setOrganization(new Organization());
		AppUser busyMentor = user(MENTOR_ID, "busy@mentor.com", UserRole.MENTOR);
		AppUser admin = user(UUID.randomUUID(), "boss@teknokent.org", UserRole.ADMIN);
		AppUser coordinator = user(UUID.randomUUID(), "coord@teknokent.org", UserRole.COORDINATOR);
		when(appUserRepository.findByEmailIgnoreCase("team@startup.com")).thenReturn(Optional.of(teamMember));
		when(appUserRepository.findByEmailIgnoreCase("busy@mentor.com")).thenReturn(Optional.of(busyMentor));
		when(appUserRepository.findByEmailIgnoreCase("boss@teknokent.org")).thenReturn(Optional.of(admin));
		when(appUserRepository.findByEmailIgnoreCase("coord@teknokent.org")).thenReturn(Optional.of(coordinator));
		when(mentorshipRepository.countByMentorIdAndStatusIn(eq(MENTOR_ID), any())).thenReturn(1L);

		assertThat(service.candidate(ADMIN_ID, "team@startup.com").status())
				.isEqualTo(CoordinatorCandidateStatus.TEAM_MEMBER);
		assertThat(service.candidate(ADMIN_ID, "busy@mentor.com").status())
				.isEqualTo(CoordinatorCandidateStatus.MENTOR_IN_MENTORSHIP);
		assertThat(service.candidate(ADMIN_ID, "boss@teknokent.org").status())
				.isEqualTo(CoordinatorCandidateStatus.ADMIN);
		assertThat(service.candidate(ADMIN_ID, "coord@teknokent.org").status())
				.isEqualTo(CoordinatorCandidateStatus.ALREADY_COORDINATOR);
		for (String email : new String[] { "team@startup.com", "busy@mentor.com", "boss@teknokent.org", "coord@teknokent.org" }) {
			assertThatThrownBy(() -> service.add(ADMIN_ID, new AddCoordinatorRequest(null, "X", email)))
					.isInstanceOf(InvalidRequestException.class);
		}
		verify(appUserRepository, never()).save(any());
		verify(loginProvisioner, never()).loginFor(anyString(), anyString());
	}

	@Test
	void onlyAnAdminCanManageCoordinators() {
		when(domainLookupService.requireUser(MENTOR_ID)).thenReturn(user(MENTOR_ID, "ayse@mentor.com", UserRole.COORDINATOR));

		assertThatThrownBy(() -> service.candidate(MENTOR_ID, "x@y.com")).isInstanceOf(InvalidRequestException.class);
		assertThatThrownBy(() -> service.add(MENTOR_ID, new AddCoordinatorRequest(null, "X", "x@y.com")))
				.isInstanceOf(InvalidRequestException.class);
		verify(appUserRepository, never()).findByEmailIgnoreCase(anyString());
	}

	@Test
	void anAdminDeactivatesACoordinatorButNotSomeoneElse() {
		stubAdmin();
		AppUser coordinator = user(UUID.randomUUID(), "coord@teknokent.org", UserRole.COORDINATOR);
		AppUser mentor = user(MENTOR_ID, "ayse@mentor.com", UserRole.MENTOR);
		when(domainLookupService.requireUser(coordinator.getId())).thenReturn(coordinator);
		when(domainLookupService.requireUser(MENTOR_ID)).thenReturn(mentor);

		var result = service.setAccess(ADMIN_ID, coordinator.getId(), false);

		assertThat(result.status()).isEqualTo(UserAccountStatus.REJECTED);
		assertThat(coordinator.getStatus()).isEqualTo(UserAccountStatus.REJECTED);
		assertThatThrownBy(() -> service.setAccess(ADMIN_ID, MENTOR_ID, false))
				.isInstanceOf(InvalidRequestException.class);
		assertThat(mentor.getStatus()).isEqualTo(UserAccountStatus.APPROVED);
	}

	private void stubAdmin() {
		when(domainLookupService.requireUser(ADMIN_ID)).thenReturn(user(ADMIN_ID, "arya@admin.tnt", UserRole.ADMIN));
	}

	private static AppUser user(UUID id, String email, UserRole role) {
		AppUser user = new AppUser();
		user.setId(id);
		user.setEmail(email);
		user.setFullName("Ayşe Demir");
		user.setRole(role);
		user.setStatus(UserAccountStatus.APPROVED);
		return user;
	}
}
