package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dps.teknokent.domain.MentorPoolStatus;
import com.dps.teknokent.domain.ProgramMentorInvitedBy;
import com.dps.teknokent.domain.ProgramMentorStatus;
import com.dps.teknokent.domain.ProgramStatus;
import com.dps.teknokent.domain.ProgramType;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.MentorProfile;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramMentor;
import com.dps.teknokent.entity.ProgramMentorInvite;
import com.dps.teknokent.exception.ConflictException;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.MentorCount;
import com.dps.teknokent.repository.MentorProfileRepository;
import com.dps.teknokent.repository.MentorProgramName;
import com.dps.teknokent.repository.MentorshipRepository;
import com.dps.teknokent.repository.ProgramMentorInviteRepository;
import com.dps.teknokent.repository.ProgramMentorRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.util.Texts;

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
	@Mock
	private ProgramEligibilityService programEligibilityService;
	@Mock
	private BadgeService badgeService;
	@Spy
	private EditionService editionService = new EditionService("TEKNOKENT");

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
		verify(programService).settleMentorRequest(any(), org.mockito.ArgumentMatchers.eq(mentor.getId()),
				org.mockito.ArgumentMatchers.eq(true));
	}

	// In a peer program, "Kabul Et" also awards the gate badge (the badge service decides when it applies).
	@Test
	void acceptingAMentorHandsTheDecisionToTheBadgesAndRejectingDoesNot() {
		AppUser accepted = awaitingDecision();
		service.decide(ELIF, PROGRAM_ID, accepted.getId(), true);
		verify(badgeService).awardAfterMentorApproval(org.mockito.ArgumentMatchers.eq(ELIF), any(),
				org.mockito.ArgumentMatchers.eq(accepted));

		AppUser rejected = awaitingDecision();
		service.decide(ELIF, PROGRAM_ID, rejected.getId(), false);
		verify(badgeService, org.mockito.Mockito.never()).awardAfterMentorApproval(any(), any(),
				org.mockito.ArgumentMatchers.eq(rejected));
	}

	@Test
	void approvingAMentorWhoPickedTheProgramAtOnboardingApprovesTheirAccountWithoutTheAdmin() {
		AppUser mentor = awaitingDecision();

		service.decide(ELIF, PROGRAM_ID, mentor.getId(), true);

		assertThat(mentor.getStatus()).isEqualTo(UserAccountStatus.APPROVED);
		verify(programService).settleMentorRequest(any(), org.mockito.ArgumentMatchers.eq(mentor.getId()),
				org.mockito.ArgumentMatchers.eq(true));
	}

	@Test
	void aRejectionLeavesTheAccountWaitingWhileAnotherChosenProgramHasNotDecided() {
		AppUser mentor = awaitingDecision();
		when(programMentorRepository.existsByMentorIdAndProgramIdNotAndStatusIn(
				org.mockito.ArgumentMatchers.eq(mentor.getId()), org.mockito.ArgumentMatchers.eq(PROGRAM_ID), any()))
				.thenReturn(true);

		service.decide(ELIF, PROGRAM_ID, mentor.getId(), false);

		assertThat(mentor.getStatus()).isEqualTo(UserAccountStatus.PENDING);
		verify(programService).settleMentorRequest(any(), org.mockito.ArgumentMatchers.eq(mentor.getId()),
				org.mockito.ArgumentMatchers.eq(false));
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
	void onboardingRefusesAProgramOfTheOtherEdition() {
		AppUser mentor = user(UUID.randomUUID(), UserRole.MENTOR, UserAccountStatus.PENDING);
		when(appUserRepository.findByFirebaseUid("uid-1")).thenReturn(Optional.of(mentor));
		Program peer = program();
		peer.setStatus(ProgramStatus.ACTIVE);
		peer.setProgramType(ProgramType.PEER_MENTORING);
		when(programRepository.findAllById(List.of(PROGRAM_ID))).thenReturn(List.of(peer));

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
		when(programMentorRepository.findByProgramIdAndMentorId(PROGRAM_ID, mentorId))
				.thenReturn(Optional.of(accepted));

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

	// --- Invite links: before launch, a second invitation, resending and cancelling ---

	@Test
	void nobodyIsInvitedBeforeTheProgramIsLaunched() {
		Program draft = program();
		draft.setStatus(ProgramStatus.DRAFT);
		when(coordinatorProgramService.requireManagedProgram(ELIF, PROGRAM_ID)).thenReturn(draft);

		assertThatThrownBy(() -> service.invite(ELIF, PROGRAM_ID, "cagri@yilmazfinans.com"))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessage(Texts.forRequest("error.programNotLaunched"));
		assertThatThrownBy(() -> service.renewInvite(ELIF, PROGRAM_ID, UUID.randomUUID()))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessage(Texts.forRequest("error.programNotLaunched"));
		verifyNoInteractions(inviteRepository, mailService, programService, appUserRepository);
	}

	@Test
	void invitingTheSameEmailAgainExtendsItsLinkInsteadOfIssuingAnother() {
		when(coordinatorProgramService.requireManagedProgram(ELIF, PROGRAM_ID)).thenReturn(program());
		when(mailService.link(anyString())).thenAnswer(call -> "https://menton.com.tr" + call.getArgument(0));
		UUID firstInviter = UUID.randomUUID();
		ProgramMentorInvite lapsed = invite("cagri@yilmazfinans.com", Instant.now().minus(Duration.ofDays(3)));
		lapsed.setCreatedBy(firstInviter);
		when(appUserRepository.findByEmailIgnoreCase("cagri@yilmazfinans.com")).thenReturn(Optional.empty());
		when(inviteRepository.findByProgramIdAndEmailIgnoreCase(PROGRAM_ID, "cagri@yilmazfinans.com"))
				.thenReturn(Optional.of(lapsed));

		service.invite(ELIF, PROGRAM_ID, "cagri@yilmazfinans.com");

		verify(inviteRepository).save(lapsed);
		assertThat(lapsed.getCode()).isEqualTo("code-1");
		assertThat(lapsed.getCreatedBy()).isEqualTo(firstInviter);
		assertThat(lapsed.getExpiresAt())
				.isBetween(Instant.now().plus(Duration.ofDays(6)), Instant.now().plus(Duration.ofDays(8)));
		verify(mailService).sendAfterCommit(eq("cagri@yilmazfinans.com"), anyString(),
				contains("https://menton.com.tr/signup?role=mentor&programInvite=code-1"));
	}

	@Test
	void anEmailWhoseLinkWasAlreadyUsedToSignUpIsNotInvitedAgain() {
		when(coordinatorProgramService.requireManagedProgram(ELIF, PROGRAM_ID)).thenReturn(program());
		ProgramMentorInvite used = invite("cagri@yilmazfinans.com", Instant.now().plus(Duration.ofDays(3)));
		used.setMentorId(UUID.randomUUID());
		when(appUserRepository.findByEmailIgnoreCase("cagri@yilmazfinans.com")).thenReturn(Optional.empty());
		when(inviteRepository.findByProgramIdAndEmailIgnoreCase(PROGRAM_ID, "cagri@yilmazfinans.com"))
				.thenReturn(Optional.of(used));

		assertThatThrownBy(() -> service.invite(ELIF, PROGRAM_ID, "cagri@yilmazfinans.com"))
				.isInstanceOf(ConflictException.class)
				.hasMessage(Texts.forRequest("error.mentorAlreadyJoinedByInvite"));
		verify(inviteRepository, never()).save(any());
		verifyNoInteractions(mailService);
	}

	@Test
	void anEmailTheProgramDoesNotAdmitGetsNoLink() {
		Program program = program();
		when(coordinatorProgramService.requireManagedProgram(ELIF, PROGRAM_ID)).thenReturn(program);
		when(appUserRepository.findByEmailIgnoreCase("cagri@gmail.com")).thenReturn(Optional.empty());
		doThrow(new InvalidRequestException("Bu program yalnızca ODTÜ e-postalarına açık."))
				.when(programEligibilityService).requirePerson(program, "cagri@gmail.com");

		assertThatThrownBy(() -> service.invite(ELIF, PROGRAM_ID, "cagri@gmail.com"))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessage("Bu program yalnızca ODTÜ e-postalarına açık.");
		verifyNoInteractions(inviteRepository, mailService);
	}

	@Test
	void resendingGivesTheSameLinkAnotherSevenDaysAndMailsItAgain() {
		when(coordinatorProgramService.requireManagedProgram(ELIF, PROGRAM_ID)).thenReturn(program());
		when(mailService.link(anyString())).thenAnswer(call -> "https://menton.com.tr" + call.getArgument(0));
		ProgramMentorInvite lapsed = invite("cagri@yilmazfinans.com", Instant.now().minus(Duration.ofDays(3)));
		when(inviteRepository.findByIdAndProgramId(lapsed.getId(), PROGRAM_ID)).thenReturn(Optional.of(lapsed));
		when(inviteRepository.findByProgramIdOrderByCreatedAtAsc(PROGRAM_ID)).thenReturn(List.of(lapsed));

		var rows = service.renewInvite(ELIF, PROGRAM_ID, lapsed.getId());

		verify(inviteRepository).save(lapsed);
		assertThat(lapsed.getExpiresAt())
				.isBetween(Instant.now().plus(Duration.ofDays(6)), Instant.now().plus(Duration.ofDays(8)));
		verify(mailService).sendAfterCommit(eq("cagri@yilmazfinans.com"), contains("METU Stars 2026"),
				contains("https://menton.com.tr/signup?role=mentor&programInvite=code-1"));
		assertThat(rows).singleElement().satisfies(row -> {
			assertThat(row.inviteId()).isEqualTo(lapsed.getId());
			assertThat(row.status()).isEqualTo(MentorPoolStatus.INVITED);
			assertThat(row.expiresAt()).isEqualTo(lapsed.getExpiresAt());
		});
	}

	@Test
	void aLinkSomeoneAlreadySignedUpWithCanBeNeitherResentNorCancelled() {
		when(coordinatorProgramService.requireManagedProgram(ELIF, PROGRAM_ID)).thenReturn(program());
		ProgramMentorInvite used = invite("cagri@yilmazfinans.com", Instant.now().plus(Duration.ofDays(3)));
		used.setMentorId(UUID.randomUUID());
		Instant deadline = used.getExpiresAt();
		when(inviteRepository.findByIdAndProgramId(used.getId(), PROGRAM_ID)).thenReturn(Optional.of(used));

		assertThatThrownBy(() -> service.renewInvite(ELIF, PROGRAM_ID, used.getId()))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessage(Texts.forRequest("error.mentorAlreadySignedUp"));
		assertThatThrownBy(() -> service.cancelInvite(ELIF, PROGRAM_ID, used.getId()))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessage(Texts.forRequest("error.mentorAlreadySignedUp"));
		assertThat(used.getExpiresAt()).isEqualTo(deadline);
		verify(inviteRepository, never()).save(any());
		verify(inviteRepository, never()).delete(any());
		verifyNoInteractions(mailService);
	}

	// The lookup is by invite and program together, so another program's invite id finds nothing.
	@Test
	void anInviteThatIsNotThisProgramsIsNotFound() {
		when(coordinatorProgramService.requireManagedProgram(ELIF, PROGRAM_ID)).thenReturn(program());
		UUID foreignInvite = UUID.randomUUID();
		when(inviteRepository.findByIdAndProgramId(foreignInvite, PROGRAM_ID)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.renewInvite(ELIF, PROGRAM_ID, foreignInvite))
				.isInstanceOf(ResourceNotFoundException.class)
				.hasMessage(Texts.forRequest("error.mentorInviteNotFound", foreignInvite));
		assertThatThrownBy(() -> service.cancelInvite(ELIF, PROGRAM_ID, foreignInvite))
				.isInstanceOf(ResourceNotFoundException.class);
		verify(inviteRepository, never()).delete(any());
	}

	@Test
	void cancellingAnOpenLinkDeletesIt() {
		when(coordinatorProgramService.requireManagedProgram(ELIF, PROGRAM_ID)).thenReturn(program());
		ProgramMentorInvite open = invite("cagri@yilmazfinans.com", Instant.now().plus(Duration.ofDays(3)));
		when(inviteRepository.findByIdAndProgramId(open.getId(), PROGRAM_ID)).thenReturn(Optional.of(open));

		var rows = service.cancelInvite(ELIF, PROGRAM_ID, open.getId());

		verify(inviteRepository).delete(open);
		assertThat(rows).isEmpty();
	}

	// --- Taking back an invitation sent to a mentor who already has an account ---

	@Test
	void anUnansweredCoordinatorInvitationIsTakenBack() {
		when(coordinatorProgramService.requireManagedProgram(ELIF, PROGRAM_ID)).thenReturn(program());
		UUID mentorId = UUID.randomUUID();
		ProgramMentor invited = membership(mentorId, ProgramMentorInvitedBy.COORDINATOR);
		when(programMentorRepository.findByProgramIdAndMentorId(PROGRAM_ID, mentorId))
				.thenReturn(Optional.of(invited));

		service.withdraw(ELIF, PROGRAM_ID, mentorId);

		verify(programMentorRepository).delete(invited);
	}

	@Test
	void aRequestTheMentorMadeThemselvesIsNotTheCoordinatorsToTakeBack() {
		when(coordinatorProgramService.requireManagedProgram(ELIF, PROGRAM_ID)).thenReturn(program());
		UUID mentorId = UUID.randomUUID();
		when(programMentorRepository.findByProgramIdAndMentorId(PROGRAM_ID, mentorId))
				.thenReturn(Optional.of(membership(mentorId, ProgramMentorInvitedBy.MENTOR)));

		assertThatThrownBy(() -> service.withdraw(ELIF, PROGRAM_ID, mentorId))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessage(Texts.forRequest("error.onlyOpenInviteWithdrawn"));
		verify(programMentorRepository, never()).delete(any());
	}

	@Test
	void takingBackAnInvitationOfSomeoneOutsideTheProgramIsNotFound() {
		when(coordinatorProgramService.requireManagedProgram(ELIF, PROGRAM_ID)).thenReturn(program());
		UUID stranger = UUID.randomUUID();
		when(programMentorRepository.findByProgramIdAndMentorId(PROGRAM_ID, stranger)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.withdraw(ELIF, PROGRAM_ID, stranger))
				.isInstanceOf(ResourceNotFoundException.class)
				.hasMessage(Texts.forRequest("error.mentorNotInProgram", stranger));
		verify(programMentorRepository, never()).delete(any());
	}

	// --- The review pop-up and the decision ---

	@Test
	void reviewShowsAMentorWhoCameByLinkWithTheirProfileAndWhoInvitedThem() {
		when(coordinatorProgramService.requireManagedProgram(ELIF, PROGRAM_ID)).thenReturn(program());
		AppUser mentor = user(UUID.randomUUID(), UserRole.MENTOR, UserAccountStatus.PENDING);
		mentor.setJobTitle("CFO");
		mentor.setLinkedinUrl("https://linkedin.com/in/cagri");
		ProgramMentor request = membership(mentor.getId(), ProgramMentorInvitedBy.MENTOR);
		ProgramMentorInvite link = invite(mentor.getEmail(), Instant.now().plus(Duration.ofDays(3)));
		link.setMentorId(mentor.getId());
		link.setCreatedBy(ELIF);
		AppUser elif = user(ELIF, UserRole.COORDINATOR, UserAccountStatus.APPROVED);
		elif.setFullName("Elif Kaya");
		MentorProfile profile = new MentorProfile();
		profile.setSpecialties(new ArrayList<>(List.of("Finans", "Satış")));
		profile.setAvailabilityLabel("Haftada 2 saat");
		profile.setMenteeCapacity(2);
		when(programMentorRepository.findByProgramIdAndMentorId(PROGRAM_ID, mentor.getId()))
				.thenReturn(Optional.of(request));
		when(appUserRepository.findById(mentor.getId())).thenReturn(Optional.of(mentor));
		when(appUserRepository.findById(ELIF)).thenReturn(Optional.of(elif));
		when(inviteRepository.findByProgramIdAndMentorId(PROGRAM_ID, mentor.getId())).thenReturn(Optional.of(link));
		when(mentorProfileRepository.findById(mentor.getId())).thenReturn(Optional.of(profile));

		var review = service.review(ELIF, PROGRAM_ID, mentor.getId());

		assertThat(review.mentorId()).isEqualTo(mentor.getId());
		assertThat(review.professionalTitle()).isEqualTo("CFO");
		assertThat(review.linkedinUrl()).isEqualTo("https://linkedin.com/in/cagri");
		assertThat(review.requestedAt()).isEqualTo(request.getCreatedAt());
		assertThat(review.viaInvite()).isTrue();
		assertThat(review.emailVerified()).isTrue();
		assertThat(review.invitedBy()).isEqualTo("Elif Kaya");
		assertThat(review.expertise()).containsExactly("Finans", "Satış");
		assertThat(review.availability()).isEqualTo("Haftada 2 saat");
		assertThat(review.capacity()).isEqualTo(2);
	}

	@Test
	void reviewOfAMentorWhoAskedAtOnboardingFallsBackToTheProgramsCapacity() {
		when(coordinatorProgramService.requireManagedProgram(ELIF, PROGRAM_ID)).thenReturn(program());
		AppUser mentor = user(UUID.randomUUID(), UserRole.MENTOR, UserAccountStatus.PENDING);
		mentor.setFirebaseUid("uid-7");
		MentorProfile unfinished = new MentorProfile();
		unfinished.setAvailabilityLabel("  ");
		when(programMentorRepository.findByProgramIdAndMentorId(PROGRAM_ID, mentor.getId()))
				.thenReturn(Optional.of(membership(mentor.getId(), ProgramMentorInvitedBy.MENTOR)));
		when(appUserRepository.findById(mentor.getId())).thenReturn(Optional.of(mentor));
		when(inviteRepository.findByProgramIdAndMentorId(PROGRAM_ID, mentor.getId())).thenReturn(Optional.empty());
		when(mentorProfileRepository.findById(mentor.getId())).thenReturn(Optional.of(unfinished));

		var review = service.review(ELIF, PROGRAM_ID, mentor.getId());

		assertThat(review.viaInvite()).isFalse();
		assertThat(review.emailVerified()).isTrue();
		assertThat(review.invitedBy()).isNull();
		assertThat(review.expertise()).isEmpty();
		assertThat(review.availability()).isNull();
		assertThat(review.capacity()).isEqualTo(4);
	}

	@Test
	void reviewOfAMentorWithNoProfileAndNoSignInYetShowsTheEmailAsUnverified() {
		when(coordinatorProgramService.requireManagedProgram(ELIF, PROGRAM_ID)).thenReturn(program());
		AppUser mentor = user(UUID.randomUUID(), UserRole.MENTOR, UserAccountStatus.PENDING);
		when(programMentorRepository.findByProgramIdAndMentorId(PROGRAM_ID, mentor.getId()))
				.thenReturn(Optional.of(membership(mentor.getId(), ProgramMentorInvitedBy.MENTOR)));
		when(appUserRepository.findById(mentor.getId())).thenReturn(Optional.of(mentor));
		when(inviteRepository.findByProgramIdAndMentorId(PROGRAM_ID, mentor.getId())).thenReturn(Optional.empty());
		when(mentorProfileRepository.findById(mentor.getId())).thenReturn(Optional.empty());

		var review = service.review(ELIF, PROGRAM_ID, mentor.getId());

		assertThat(review.emailVerified()).isFalse();
		assertThat(review.expertise()).isEmpty();
		assertThat(review.availability()).isNull();
		assertThat(review.capacity()).isEqualTo(4);
	}

	@Test
	void onlyAMentorWaitingForTheCoordinatorCanBeReviewedOrDecided() {
		when(coordinatorProgramService.requireManagedProgram(ELIF, PROGRAM_ID)).thenReturn(program());
		UUID mentorId = UUID.randomUUID();
		ProgramMentor invitedByCoordinator = membership(mentorId, ProgramMentorInvitedBy.COORDINATOR);
		ProgramMentor alreadyAccepted = membership(mentorId, ProgramMentorInvitedBy.MENTOR);
		alreadyAccepted.setStatus(ProgramMentorStatus.ACCEPTED);

		for (ProgramMentor notWaiting : List.of(invitedByCoordinator, alreadyAccepted)) {
			when(programMentorRepository.findByProgramIdAndMentorId(PROGRAM_ID, mentorId))
					.thenReturn(Optional.of(notWaiting));

			assertThatThrownBy(() -> service.review(ELIF, PROGRAM_ID, mentorId))
					.isInstanceOf(InvalidRequestException.class)
					.hasMessage("Bu mentor bir karar beklemiyor.");
			assertThatThrownBy(() -> service.decide(ELIF, PROGRAM_ID, mentorId, true))
					.isInstanceOf(InvalidRequestException.class)
					.hasMessage("Bu mentor bir karar beklemiyor.");
		}
		verifyNoInteractions(programService, badgeService, appUserRepository);
	}

	@Test
	void aMentorWhoseAccountIsGoneCanBeNeitherReviewedNorDecided() {
		when(coordinatorProgramService.requireManagedProgram(ELIF, PROGRAM_ID)).thenReturn(program());
		UUID mentorId = UUID.randomUUID();
		when(programMentorRepository.findByProgramIdAndMentorId(PROGRAM_ID, mentorId))
				.thenReturn(Optional.of(membership(mentorId, ProgramMentorInvitedBy.MENTOR)));
		when(appUserRepository.findById(mentorId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.review(ELIF, PROGRAM_ID, mentorId))
				.isInstanceOf(ResourceNotFoundException.class)
				.hasMessage(Texts.forRequest("error.mentorNotFound", mentorId));
		assertThatThrownBy(() -> service.decide(ELIF, PROGRAM_ID, mentorId, true))
				.isInstanceOf(ResourceNotFoundException.class)
				.hasMessage(Texts.forRequest("error.mentorNotFound", mentorId));
		verifyNoInteractions(programService, badgeService);
	}

	@Test
	void rejectingAMentorWhoseAccountIsAlreadyApprovedLeavesTheAccountAlone() {
		Program program = program();
		when(coordinatorProgramService.requireManagedProgram(ELIF, PROGRAM_ID)).thenReturn(program);
		AppUser mentor = user(UUID.randomUUID(), UserRole.MENTOR, UserAccountStatus.APPROVED);
		when(programMentorRepository.findByProgramIdAndMentorId(PROGRAM_ID, mentor.getId()))
				.thenReturn(Optional.of(membership(mentor.getId(), ProgramMentorInvitedBy.MENTOR)));
		when(appUserRepository.findById(mentor.getId())).thenReturn(Optional.of(mentor));

		service.decide(ELIF, PROGRAM_ID, mentor.getId(), false);

		assertThat(mentor.getStatus()).isEqualTo(UserAccountStatus.APPROVED);
		verify(appUserRepository, never()).save(any());
		verify(programService).settleMentorRequest(program, mentor.getId(), false);
	}

	// --- Onboarding's program step ---

	@Test
	void onboardingShowsTheProgramsAMentorAskedForOrBelongsToButNotARejection() {
		AppUser mentor = user(UUID.randomUUID(), UserRole.MENTOR, UserAccountStatus.APPROVED);
		when(appUserRepository.findByFirebaseUid("uid-1")).thenReturn(Optional.of(mentor));
		ProgramMentor asked = membershipIn(UUID.randomUUID(), mentor.getId(), ProgramMentorStatus.PENDING);
		ProgramMentor member = membershipIn(UUID.randomUUID(), mentor.getId(), ProgramMentorStatus.ACCEPTED);
		ProgramMentor refused = membershipIn(UUID.randomUUID(), mentor.getId(), ProgramMentorStatus.REJECTED);
		when(programMentorRepository.findByMentorIdOrderByCreatedAtAsc(mentor.getId()))
				.thenReturn(List.of(asked, member, refused));

		assertThat(service.chosenPrograms("uid-1")).containsExactly(asked.getProgramId(), member.getProgramId());
	}

	@Test
	void onlyAMentorSeesChosenPrograms() {
		when(appUserRepository.findByFirebaseUid("uid-2"))
				.thenReturn(Optional.of(user(UUID.randomUUID(), UserRole.MENTEE, UserAccountStatus.APPROVED)));
		when(appUserRepository.findByFirebaseUid("uid-unknown")).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.chosenPrograms("uid-2")).isInstanceOf(InvalidRequestException.class);
		assertThatThrownBy(() -> service.chosenPrograms("uid-unknown")).isInstanceOf(InvalidRequestException.class);
		verifyNoInteractions(programMentorRepository);
	}

	@Test
	void untickingKeepsARequestThatCameWithAnInviteLinkAndACoordinatorsInvitation() {
		AppUser mentor = user(UUID.randomUUID(), UserRole.MENTOR, UserAccountStatus.PENDING);
		when(appUserRepository.findByFirebaseUid("uid-1")).thenReturn(Optional.of(mentor));
		ProgramMentor cameByLink = membershipIn(UUID.randomUUID(), mentor.getId(), ProgramMentorStatus.PENDING);
		ProgramMentor invited = membershipIn(UUID.randomUUID(), mentor.getId(), ProgramMentorStatus.PENDING);
		invited.setInvitedBy(ProgramMentorInvitedBy.COORDINATOR);
		when(programMentorRepository.findByMentorIdOrderByCreatedAtAsc(mentor.getId()))
				.thenReturn(List.of(cameByLink, invited));
		when(inviteRepository.findByProgramIdAndMentorId(cameByLink.getProgramId(), mentor.getId()))
				.thenReturn(Optional.of(invite(mentor.getEmail(), Instant.now().plus(Duration.ofDays(3)))));

		var chosen = service.choosePrograms("uid-1", List.of());

		verify(programMentorRepository, never()).delete(any());
		verifyNoInteractions(programRepository);
		assertThat(chosen).containsExactly(cameByLink.getProgramId(), invited.getProgramId());
	}

	@Test
	void tickingAProgramTheMentorIsAlreadyInAsksForNothingNew() {
		AppUser mentor = user(UUID.randomUUID(), UserRole.MENTOR, UserAccountStatus.APPROVED);
		when(appUserRepository.findByFirebaseUid("uid-1")).thenReturn(Optional.of(mentor));
		ProgramMentor member = membershipIn(PROGRAM_ID, mentor.getId(), ProgramMentorStatus.ACCEPTED);
		when(programMentorRepository.findByMentorIdOrderByCreatedAtAsc(mentor.getId())).thenReturn(List.of(member));

		var chosen = service.choosePrograms("uid-1", List.of(PROGRAM_ID, PROGRAM_ID));

		verify(programMentorRepository, never()).save(any());
		verify(programMentorRepository, never()).delete(any());
		verifyNoInteractions(programRepository, programEligibilityService);
		assertThat(chosen).containsExactly(PROGRAM_ID);
	}

	@Test
	void onboardingRefusesAProgramThatDoesNotExist() {
		AppUser mentor = user(UUID.randomUUID(), UserRole.MENTOR, UserAccountStatus.PENDING);
		when(appUserRepository.findByFirebaseUid("uid-1")).thenReturn(Optional.of(mentor));
		UUID unknown = UUID.randomUUID();
		when(programRepository.findAllById(List.of(PROGRAM_ID, unknown))).thenReturn(List.of(program()));

		assertThatThrownBy(() -> service.choosePrograms("uid-1", List.of(PROGRAM_ID, unknown)))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessage(Texts.forRequest("error.programNotOpen"));
		verify(programMentorRepository, never()).save(any());
	}

	@Test
	void onboardingRefusesAProgramTheMentorsEmailIsNotAdmittedTo() {
		AppUser mentor = user(UUID.randomUUID(), UserRole.MENTOR, UserAccountStatus.PENDING);
		when(appUserRepository.findByFirebaseUid("uid-1")).thenReturn(Optional.of(mentor));
		Program open = program();
		when(programRepository.findAllById(List.of(PROGRAM_ID))).thenReturn(List.of(open));
		doThrow(new InvalidRequestException("Bu program yalnızca ODTÜ e-postalarına açık."))
				.when(programEligibilityService).requirePerson(open, mentor.getEmail());

		assertThatThrownBy(() -> service.choosePrograms("uid-1", List.of(PROGRAM_ID)))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessage("Bu program yalnızca ODTÜ e-postalarına açık.");
		verify(programMentorRepository, never()).save(any());
	}

	// --- The pool list ---

	@Test
	void poolRowsTellInvitedWaitingAndFullMentorsApartAndHideRejectedOnes() {
		when(coordinatorProgramService.requireManagedProgram(ELIF, PROGRAM_ID)).thenReturn(program());
		AppUser invited = user(UUID.randomUUID(), UserRole.MENTOR, UserAccountStatus.APPROVED);
		AppUser waiting = user(UUID.randomUUID(), UserRole.MENTOR, UserAccountStatus.PENDING);
		AppUser full = user(UUID.randomUUID(), UserRole.MENTOR, UserAccountStatus.APPROVED);
		UUID deletedAccount = UUID.randomUUID();
		ProgramMentor fullMembership = membershipIn(PROGRAM_ID, full.getId(), ProgramMentorStatus.ACCEPTED);
		Instant joined = Instant.parse("2026-03-01T09:00:00Z");
		fullMembership.setDecidedAt(joined);
		when(programMentorRepository.findByProgramIdOrderByCreatedAtAsc(PROGRAM_ID)).thenReturn(List.of(
				membership(invited.getId(), ProgramMentorInvitedBy.COORDINATOR),
				membership(waiting.getId(), ProgramMentorInvitedBy.MENTOR),
				fullMembership,
				membershipIn(PROGRAM_ID, UUID.randomUUID(), ProgramMentorStatus.REJECTED),
				membershipIn(PROGRAM_ID, deletedAccount, ProgramMentorStatus.ACCEPTED)));
		List<UUID> shown = List.of(invited.getId(), waiting.getId(), full.getId(), deletedAccount);
		when(appUserRepository.findAllById(shown)).thenReturn(List.of(invited, waiting, full));
		MentorProfile profile = new MentorProfile();
		profile.setId(full.getId());
		profile.setSpecialties(new ArrayList<>(List.of("Finans")));
		profile.setMenteeCapacity(2);
		when(mentorProfileRepository.findAllById(shown)).thenReturn(List.of(profile));
		when(mentorshipRepository.countOrganizationsByMentor(eq(shown), any()))
				.thenReturn(List.of(load(full.getId(), 2)));
		when(programMentorRepository.findProgramNamesByMentorIds(shown, ProgramMentorStatus.ACCEPTED))
				.thenReturn(List.of(programName(full.getId(), "METU Stars 2026"), programName(full.getId(), "BİGG")));

		var rows = service.pool(ELIF, PROGRAM_ID);

		assertThat(rows).extracting(row -> row.mentorId())
				.containsExactly(invited.getId(), waiting.getId(), full.getId());
		assertThat(rows).extracting(row -> row.status()).containsExactly(MentorPoolStatus.INVITED,
				MentorPoolStatus.AWAITING_APPROVAL, MentorPoolStatus.FULL);
		assertThat(rows.get(0).capacity()).isEqualTo(4);
		assertThat(rows.get(0).expertise()).isEmpty();
		assertThat(rows.get(0).programs()).isEmpty();
		assertThat(rows.get(2).activeTeams()).isEqualTo(2);
		assertThat(rows.get(2).capacity()).isEqualTo(2);
		assertThat(rows.get(2).expertise()).containsExactly("Finans");
		assertThat(rows.get(2).programs()).containsExactly("METU Stars 2026", "BİGG");
		assertThat(rows.get(2).since()).isEqualTo(joined);
	}

	@Test
	void poolListsOnlyTheInviteLinksNobodyHasSignedUpWithYet() {
		when(coordinatorProgramService.requireManagedProgram(ELIF, PROGRAM_ID)).thenReturn(program());
		ProgramMentorInvite used = invite("joined@mail.com", Instant.now().plus(Duration.ofDays(5)));
		used.setMentorId(UUID.randomUUID());
		ProgramMentorInvite fresh = invite("fresh@mail.com", Instant.now().plus(Duration.ofDays(5)));
		ProgramMentorInvite lapsed = invite("lapsed@mail.com", Instant.now().minus(Duration.ofDays(1)));
		when(inviteRepository.findByProgramIdOrderByCreatedAtAsc(PROGRAM_ID))
				.thenReturn(List.of(used, fresh, lapsed));

		var rows = service.pool(ELIF, PROGRAM_ID);

		assertThat(rows).extracting(row -> row.email()).containsExactly("fresh@mail.com", "lapsed@mail.com");
		assertThat(rows).extracting(row -> row.status()).containsExactly(MentorPoolStatus.INVITED,
				MentorPoolStatus.INVITE_EXPIRED);
		assertThat(rows.getFirst().mentorId()).isNull();
		assertThat(rows.getFirst().inviteId()).isEqualTo(fresh.getId());
		assertThat(rows.getFirst().capacity()).isEqualTo(4);
		verifyNoInteractions(mentorshipRepository);
	}

	@Test
	void anInviteLinkCountsAsExpiringInItsLastTwoDays() {
		Instant now = Instant.parse("2026-05-10T12:00:00Z");

		assertThat(MentorPoolService.inviteStatus(now, now)).isEqualTo(MentorPoolStatus.INVITE_EXPIRED);
		assertThat(MentorPoolService.inviteStatus(now.plusSeconds(1), now))
				.isEqualTo(MentorPoolStatus.INVITE_EXPIRING);
		assertThat(MentorPoolService.inviteStatus(now.plus(Duration.ofDays(2)).minusSeconds(1), now))
				.isEqualTo(MentorPoolStatus.INVITE_EXPIRING);
		assertThat(MentorPoolService.inviteStatus(now.plus(Duration.ofDays(2)), now))
				.isEqualTo(MentorPoolStatus.INVITED);
	}

	private static ProgramMentorInvite invite(String email, Instant expiresAt) {
		ProgramMentorInvite invite = new ProgramMentorInvite();
		invite.setId(UUID.randomUUID());
		invite.setProgramId(PROGRAM_ID);
		invite.setEmail(email);
		invite.setCode("code-1");
		invite.setCreatedBy(ELIF);
		invite.setCreatedAt(Instant.now().minus(Duration.ofDays(10)));
		invite.setExpiresAt(expiresAt);
		return invite;
	}

	private static ProgramMentor membershipIn(UUID programId, UUID mentorId, ProgramMentorStatus status) {
		ProgramMentor membership = membership(mentorId, ProgramMentorInvitedBy.MENTOR);
		membership.setProgramId(programId);
		membership.setStatus(status);
		return membership;
	}

	private static MentorCount load(UUID mentorId, long teams) {
		return new MentorCount() {
			@Override
			public UUID getMentorId() {
				return mentorId;
			}

			@Override
			public Long getTotal() {
				return teams;
			}
		};
	}

	private static MentorProgramName programName(UUID mentorId, String name) {
		return new MentorProgramName() {
			@Override
			public UUID getMentorId() {
				return mentorId;
			}

			@Override
			public UUID getProgramId() {
				return PROGRAM_ID;
			}

			@Override
			public String getProgramName() {
				return name;
			}

			@Override
			public ProgramStatus getProgramStatus() {
				return ProgramStatus.ACTIVE;
			}

			@Override
			public String getRole() {
				return null;
			}
		};
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
