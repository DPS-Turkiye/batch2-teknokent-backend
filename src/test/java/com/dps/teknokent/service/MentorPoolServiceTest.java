package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dps.teknokent.domain.MentorPoolStatus;
import com.dps.teknokent.domain.ProgramMentorInvitedBy;
import com.dps.teknokent.domain.ProgramMentorStatus;
import com.dps.teknokent.domain.ProgramStatus;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramMentor;
import com.dps.teknokent.entity.ProgramMentorInvite;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.MentorProfileRepository;
import com.dps.teknokent.repository.MentorshipRepository;
import com.dps.teknokent.repository.ProgramMentorInviteRepository;
import com.dps.teknokent.repository.ProgramMentorRepository;
import com.dps.teknokent.repository.ProgramRepository;

@ExtendWith(MockitoExtension.class)
class MentorPoolServiceTest {

	@Mock
	private CoordinatorProgramService coordinatorProgramService;
	@Mock
	private ProgramService programService;
	@Mock
	private ProgramRepository programRepository;
	@Mock
	private ProgramMentorRepository programMentorRepository;
	@Mock
	private ProgramMentorInviteRepository inviteRepository;
	@Mock
	private AppUserRepository appUserRepository;
	@Mock
	private MentorProfileRepository mentorProfileRepository;
	@Mock
	private MentorshipRepository mentorshipRepository;
	@Mock
	private MailService mailService;
	@InjectMocks
	private MentorPoolService service;

	private static final UUID ELIF = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
	private static final UUID PROGRAM_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

	@Test
	void anEmailWithoutAnAccountGetsASevenDayLinkAndAnExistingMentorTheUsualInvitation() {
		when(coordinatorProgramService.requireManagedProgram(ELIF, PROGRAM_ID)).thenReturn(program());
		when(mailService.link(org.mockito.ArgumentMatchers.anyString()))
				.thenAnswer(call -> "https://menton.com.tr" + call.getArgument(0));
		when(appUserRepository.findByEmailIgnoreCase("cagri@yilmazfinans.com")).thenReturn(Optional.empty());
		when(inviteRepository.findByProgramIdAndEmailIgnoreCase(PROGRAM_ID, "cagri@yilmazfinans.com"))
				.thenReturn(Optional.empty());

		service.invite(ELIF, PROGRAM_ID, " Cagri@YilmazFinans.com ");

		ArgumentCaptor<ProgramMentorInvite> invite = ArgumentCaptor.forClass(ProgramMentorInvite.class);
		verify(inviteRepository).save(invite.capture());
		assertThat(invite.getValue().getEmail()).isEqualTo("cagri@yilmazfinans.com");
		assertThat(invite.getValue().getCode()).hasSize(32);
		assertThat(invite.getValue().getExpiresAt())
				.isBetween(Instant.now().plus(Duration.ofDays(6)), Instant.now().plus(Duration.ofDays(8)));
		verify(mailService).sendAfterCommit(
				org.mockito.ArgumentMatchers.eq("cagri@yilmazfinans.com"),
				org.mockito.ArgumentMatchers.anyString(),
				org.mockito.ArgumentMatchers.contains(
						"https://menton.com.tr/signup?role=mentor&programInvite=" + invite.getValue().getCode()));

		AppUser mentor = user(UUID.randomUUID(), UserRole.MENTOR, UserAccountStatus.APPROVED);
		when(appUserRepository.findByEmailIgnoreCase("ayse@salesco.com")).thenReturn(Optional.of(mentor));
		service.invite(ELIF, PROGRAM_ID, "ayse@salesco.com");
		verify(programService).inviteMentor(ELIF, PROGRAM_ID, mentor.getId());

		when(appUserRepository.findByEmailIgnoreCase("founder@startup.co"))
				.thenReturn(Optional.of(user(UUID.randomUUID(), UserRole.MENTEE, UserAccountStatus.APPROVED)));
		assertThatThrownBy(() -> service.invite(ELIF, PROGRAM_ID, "founder@startup.co"))
				.isInstanceOf(InvalidRequestException.class);
	}

	@Test
	void approvingAMentorWhoSignedUpWithTheLinkAlsoApprovesTheirAccount() {
		when(coordinatorProgramService.requireManagedProgram(ELIF, PROGRAM_ID)).thenReturn(program());
		AppUser mentor = user(UUID.randomUUID(), UserRole.MENTOR, UserAccountStatus.PENDING);
		when(programMentorRepository.findByProgramIdAndMentorId(PROGRAM_ID, mentor.getId()))
				.thenReturn(Optional.of(membership(mentor.getId(), ProgramMentorInvitedBy.MENTOR)));
		when(appUserRepository.findById(mentor.getId())).thenReturn(Optional.of(mentor));

		service.decide(ELIF, PROGRAM_ID, mentor.getId(), true);

		assertThat(mentor.getStatus()).isEqualTo(UserAccountStatus.APPROVED);
		verify(programService).decideMentorRequest(ELIF, PROGRAM_ID, mentor.getId(), true);
	}

	@Test
	void approvingAMentorWhoPickedTheProgramAtOnboardingApprovesTheirAccountWithoutTheAdmin() {
		AppUser mentor = awaitingDecision();

		service.decide(ELIF, PROGRAM_ID, mentor.getId(), true);

		assertThat(mentor.getStatus()).isEqualTo(UserAccountStatus.APPROVED);
		verify(programService).decideMentorRequest(ELIF, PROGRAM_ID, mentor.getId(), true);
	}

	@Test
	void aRejectionLeavesTheAccountWaitingWhileAnotherChosenProgramHasNotDecided() {
		AppUser mentor = awaitingDecision();
		when(programMentorRepository.existsByMentorIdAndProgramIdNotAndStatusIn(
				org.mockito.ArgumentMatchers.eq(mentor.getId()), org.mockito.ArgumentMatchers.eq(PROGRAM_ID), any()))
				.thenReturn(true);

		service.decide(ELIF, PROGRAM_ID, mentor.getId(), false);

		assertThat(mentor.getStatus()).isEqualTo(UserAccountStatus.PENDING);
		verify(programService).decideMentorRequest(ELIF, PROGRAM_ID, mentor.getId(), false);
	}

	@Test
	void theLastChosenProgramRejectingAlsoRejectsTheAccount() {
		AppUser mentor = awaitingDecision();

		service.decide(ELIF, PROGRAM_ID, mentor.getId(), false);

		assertThat(mentor.getStatus()).isEqualTo(UserAccountStatus.REJECTED);
	}

	@Test
	void onboardingAddsNewlyTickedProgramsAndDropsAnUntickedRequestNobodyAnswered() {
		AppUser mentor = user(UUID.randomUUID(), UserRole.MENTOR, UserAccountStatus.PENDING);
		when(appUserRepository.findByFirebaseUid("uid-1")).thenReturn(Optional.of(mentor));
		ProgramMentor unticked = membership(mentor.getId(), ProgramMentorInvitedBy.MENTOR);
		ProgramMentor accepted = membership(mentor.getId(), ProgramMentorInvitedBy.COORDINATOR);
		accepted.setProgramId(UUID.randomUUID());
		accepted.setStatus(ProgramMentorStatus.ACCEPTED);
		when(programMentorRepository.findByMentorIdOrderByCreatedAtAsc(mentor.getId()))
				.thenReturn(List.of(unticked, accepted));
		when(inviteRepository.findByProgramIdAndMentorId(PROGRAM_ID, mentor.getId())).thenReturn(Optional.empty());
		Program open = program();
		open.setId(UUID.randomUUID());
		when(programRepository.findAllById(List.of(open.getId()))).thenReturn(List.of(open));

		service.choosePrograms("uid-1", List.of(open.getId()));

		verify(programMentorRepository).delete(unticked);
		verify(programMentorRepository, never()).delete(accepted);
		ArgumentCaptor<ProgramMentor> saved = ArgumentCaptor.forClass(ProgramMentor.class);
		verify(programMentorRepository).save(saved.capture());
		assertThat(saved.getValue().getProgramId()).isEqualTo(open.getId());
		assertThat(saved.getValue().getStatus()).isEqualTo(ProgramMentorStatus.PENDING);
		assertThat(saved.getValue().getInvitedBy()).isEqualTo(ProgramMentorInvitedBy.MENTOR);
	}

	@Test
	void onboardingRefusesAProgramThatIsNoLongerOpen() {
		AppUser mentor = user(UUID.randomUUID(), UserRole.MENTOR, UserAccountStatus.PENDING);
		when(appUserRepository.findByFirebaseUid("uid-1")).thenReturn(Optional.of(mentor));
		Program finished = program();
		finished.setStatus(ProgramStatus.COMPLETED);
		when(programRepository.findAllById(List.of(PROGRAM_ID))).thenReturn(List.of(finished));

		assertThatThrownBy(() -> service.choosePrograms("uid-1", List.of(PROGRAM_ID)))
				.isInstanceOf(InvalidRequestException.class);
		verify(programMentorRepository, never()).save(any());
	}

	@Test
	void onlyAMentorCanChoosePrograms() {
		AppUser mentee = user(UUID.randomUUID(), UserRole.MENTEE, UserAccountStatus.PENDING);
		when(appUserRepository.findByFirebaseUid("uid-2")).thenReturn(Optional.of(mentee));

		assertThatThrownBy(() -> service.choosePrograms("uid-2", List.of(PROGRAM_ID)))
				.isInstanceOf(InvalidRequestException.class);
	}

	private AppUser awaitingDecision() {
		when(coordinatorProgramService.requireManagedProgram(ELIF, PROGRAM_ID)).thenReturn(program());
		AppUser mentor = user(UUID.randomUUID(), UserRole.MENTOR, UserAccountStatus.PENDING);
		when(programMentorRepository.findByProgramIdAndMentorId(PROGRAM_ID, mentor.getId()))
				.thenReturn(Optional.of(membership(mentor.getId(), ProgramMentorInvitedBy.MENTOR)));
		when(appUserRepository.findById(mentor.getId())).thenReturn(Optional.of(mentor));
		return mentor;
	}

	@Test
	void onlyAnUnansweredCoordinatorInvitationCanBeTakenBack() {
		when(coordinatorProgramService.requireManagedProgram(ELIF, PROGRAM_ID)).thenReturn(program());
		UUID mentorId = UUID.randomUUID();
		ProgramMentor accepted = membership(mentorId, ProgramMentorInvitedBy.COORDINATOR);
		accepted.setStatus(ProgramMentorStatus.ACCEPTED);
		when(programMentorRepository.findByProgramIdAndMentorId(PROGRAM_ID, mentorId)).thenReturn(Optional.of(accepted));

		assertThatThrownBy(() -> service.withdraw(ELIF, PROGRAM_ID, mentorId))
				.isInstanceOf(InvalidRequestException.class);
		verify(programMentorRepository, never()).delete(any());
	}

	@Test
	void poolRowsShowLoadAgainstCapacityAndTheInviteDeadline() {
		Program program = program();
		when(coordinatorProgramService.requireManagedProgram(ELIF, PROGRAM_ID)).thenReturn(program);
		AppUser mentor = user(UUID.randomUUID(), UserRole.MENTOR, UserAccountStatus.APPROVED);
		ProgramMentor active = membership(mentor.getId(), ProgramMentorInvitedBy.COORDINATOR);
		active.setStatus(ProgramMentorStatus.ACCEPTED);
		when(programMentorRepository.findByProgramIdOrderByCreatedAtAsc(PROGRAM_ID)).thenReturn(List.of(active));
		when(appUserRepository.findAllById(List.of(mentor.getId()))).thenReturn(List.of(mentor));
		ProgramMentorInvite invite = new ProgramMentorInvite();
		invite.setId(UUID.randomUUID());
		invite.setEmail("murat.koc@mail.com");
		invite.setCode("code");
		invite.setCreatedAt(Instant.now().minus(Duration.ofDays(6)));
		invite.setExpiresAt(Instant.now().plus(Duration.ofDays(1)));
		when(inviteRepository.findByProgramIdOrderByCreatedAtAsc(PROGRAM_ID)).thenReturn(List.of(invite));

		var rows = service.pool(ELIF, PROGRAM_ID);

		assertThat(rows).hasSize(2);
		assertThat(rows.get(0).status()).isEqualTo(MentorPoolStatus.ACTIVE);
		assertThat(rows.get(0).capacity()).isEqualTo(4);
		assertThat(rows.get(1).status()).isEqualTo(MentorPoolStatus.INVITE_EXPIRING);
		assertThat(rows.get(1).inviteCode()).isEqualTo("code");
		assertThat(MentorPoolService.inviteStatus(Instant.now().minusSeconds(1), Instant.now()))
				.isEqualTo(MentorPoolStatus.INVITE_EXPIRED);
	}

	private static Program program() {
		Program program = new Program();
		program.setId(PROGRAM_ID);
		program.setName("METU Stars 2026");
		program.setDefaultMentorCapacity(4);
		return program;
	}

	private static ProgramMentor membership(UUID mentorId, ProgramMentorInvitedBy invitedBy) {
		ProgramMentor membership = new ProgramMentor();
		membership.setId(UUID.randomUUID());
		membership.setProgramId(PROGRAM_ID);
		membership.setMentorId(mentorId);
		membership.setStatus(ProgramMentorStatus.PENDING);
		membership.setInvitedBy(invitedBy);
		membership.setCreatedAt(Instant.now());
		return membership;
	}

	private static AppUser user(UUID id, UserRole role, UserAccountStatus status) {
		AppUser user = new AppUser();
		user.setId(id);
		user.setRole(role);
		user.setStatus(status);
		user.setFullName("Kişi");
		user.setEmail("kisi@test.co");
		return user;
	}
}
