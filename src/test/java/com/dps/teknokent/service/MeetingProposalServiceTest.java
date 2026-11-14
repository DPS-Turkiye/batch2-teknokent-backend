package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dps.teknokent.domain.AppLanguage;
import com.dps.teknokent.domain.MeetingProposalStatus;
import com.dps.teknokent.domain.MeetingStatus;
import com.dps.teknokent.domain.MentorshipStatus;
import com.dps.teknokent.domain.NotificationCategory;
import com.dps.teknokent.domain.PermissionSource;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.CreateMeetingRequest;
import com.dps.teknokent.dto.requests.DeclineMeetingProposalRequest;
import com.dps.teknokent.dto.requests.ProposeMeetingRequest;
import com.dps.teknokent.dto.requests.RescheduleMeetingRequest;
import com.dps.teknokent.dto.responses.MeetingProposalResponse;
import com.dps.teknokent.dto.responses.MeetingResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.entity.MeetingProposal;
import com.dps.teknokent.entity.Mentorship;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.exception.ConflictException;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.MeetingProposalRepository;
import com.dps.teknokent.repository.MeetingRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.service.CoordinatorMentorshipService.Scoped;
import com.dps.teknokent.util.MeetingSlotTimes;

@ExtendWith(MockitoExtension.class)
class MeetingProposalServiceTest {

	@Mock
	private CoordinatorMentorshipService coordinatorMentorshipService;
	@Mock
	private CoordinatorProgramService programService;
	@Mock
	private MeetingProposalRepository proposalRepository;
	@Mock
	private MeetingRepository meetingRepository;
	@Mock
	private MeetingService meetingService;
	@Mock
	private AppUserRepository appUserRepository;
	@Mock
	private ProgramRepository programRepository;
	@Mock
	private NotificationService notificationService;

	@InjectMocks
	private MeetingProposalService service;

	private final AppUser coordinator = user("Elif Demir", UserRole.COORDINATOR);
	private final AppUser mentor = user("Ayşe Demir", UserRole.MENTOR);
	private final AppUser lead = user("Deniz Ak", UserRole.MENTEE);
	private final AppUser member = user("Can Er", UserRole.MENTEE);
	private final Organization lumen = organization();
	private final Mentorship mentorship = mentorship();
	private final Program program = program();
	private final Instant nextWeek = Instant.now().plus(7, ChronoUnit.DAYS);

	@BeforeEach
	void setUp() {
		Map<UUID, AppUser> users = Map.of(
				coordinator.getId(), coordinator, mentor.getId(), mentor, lead.getId(), lead, member.getId(), member);
		lenient().when(appUserRepository.findById(any()))
				.thenAnswer(invocation -> Optional.ofNullable(users.get(invocation.<UUID>getArgument(0))));
		lenient().when(appUserRepository.findByOrganizationIdOrderByFullNameAsc(lumen.getId()))
				.thenReturn(List.of(lead, member));
		lenient().when(proposalRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
		lenient().when(programRepository.findById(program.getId())).thenReturn(Optional.of(program));
	}

	@Test
	void proposesAPendingMeetingAndAsksTheMentorAndTheTeamLead() {
		when(coordinatorMentorshipService.requireManagedMentorship(coordinator.getId(), mentorship.getId()))
				.thenReturn(new Scoped(mentorship, program));

		MeetingProposalResponse response = service.propose(coordinator.getId(), mentorship.getId(),
				new ProposeMeetingRequest(null, nextWeek, 45, "Tanışma", null));

		assertThat(response.status()).isEqualTo(MeetingProposalStatus.PENDING);
		assertThat(response.programName()).isEqualTo("METU Stars 2026");
		assertThat(response.proposedByName()).isEqualTo("Elif Demir");
		verify(notificationService).create(eq(mentor), eq(NotificationCategory.MEETING_CHANGE), anyString(),
				anyString(),
				eq("/mentor-dashboard"));
		verify(notificationService).create(eq(lead), eq(NotificationCategory.MEETING_CHANGE), anyString(), anyString(),
				eq("/dashboard"));
		verify(notificationService, never()).create(eq(member), any(), anyString(), anyString(), anyString());
	}

	@Test
	void refusesASecondOpenProposalAndAPastTime() {
		when(coordinatorMentorshipService.requireManagedMentorship(coordinator.getId(), mentorship.getId()))
				.thenReturn(new Scoped(mentorship, program));

		assertThatThrownBy(() -> service.propose(coordinator.getId(), mentorship.getId(),
				new ProposeMeetingRequest(null, Instant.now().minusSeconds(60), 45, null, null)))
				.isInstanceOf(InvalidRequestException.class);

		when(proposalRepository.existsByMentorshipIdAndStatus(mentorship.getId(), MeetingProposalStatus.PENDING))
				.thenReturn(true);
		assertThatThrownBy(() -> service.propose(coordinator.getId(), mentorship.getId(),
				new ProposeMeetingRequest(null, nextWeek, 45, null, null)))
				.isInstanceOf(ConflictException.class);
	}

	@Test
	void staysPendingUntilBothSidesAcceptThenBooksTheMeeting() {
		MeetingProposal proposal = pending(null);
		when(proposalRepository.findById(proposal.getId())).thenReturn(Optional.of(proposal));
		MeetingResponse created = meetingResponse(UUID.randomUUID());
		when(meetingService.create(any())).thenReturn(created);

		MeetingProposalResponse afterMentor = service.accept(mentor.getId(), proposal.getId());
		assertThat(afterMentor.status()).isEqualTo(MeetingProposalStatus.PENDING);
		assertThat(afterMentor.canRespond()).isFalse();
		verify(meetingService, never()).create(any());

		MeetingProposalResponse afterLead = service.accept(lead.getId(), proposal.getId());

		assertThat(afterLead.status()).isEqualTo(MeetingProposalStatus.CONFIRMED);
		assertThat(afterLead.teamResponderName()).isEqualTo("Deniz Ak");
		assertThat(afterLead.meetingId()).isNotNull();
		ArgumentCaptor<CreateMeetingRequest> booked = ArgumentCaptor.forClass(CreateMeetingRequest.class);
		verify(meetingService).create(booked.capture());
		assertThat(booked.getValue().scheduledAt()).isEqualTo(nextWeek);
		assertThat(booked.getValue().mentorId()).isEqualTo(mentor.getId());
		assertThat(booked.getValue().status()).isEqualTo(MeetingStatus.SCHEDULED);
		verify(notificationService).create(eq(coordinator), eq(NotificationCategory.MEETING_CHANGE),
				eq("Görüşme onaylandı"), anyString(), eq("/coordinator/mentorships?mentorship=" + mentorship.getId()));
	}

	@Test
	void onlyTheTeamLeadAnswersForATeamThatHasOne() {
		MeetingProposal proposal = pending(null);
		when(proposalRepository.findById(proposal.getId())).thenReturn(Optional.of(proposal));

		assertThatThrownBy(() -> service.accept(member.getId(), proposal.getId()))
				.isInstanceOf(InvalidRequestException.class);
	}

	@Test
	void aConfirmedRescheduleMovesTheExistingMeeting() {
		Meeting existing = new Meeting();
		existing.setId(UUID.randomUUID());
		existing.setOrganization(lumen);
		existing.setMentor(mentor);
		existing.setStatus(MeetingStatus.SCHEDULED);
		existing.setScheduledAt(Instant.now().plus(1, ChronoUnit.DAYS));
		existing.setReminderSentAt(Instant.now());
		MeetingProposal proposal = pending(existing.getId());
		proposal.setMentorAcceptedAt(Instant.now());
		when(proposalRepository.findById(proposal.getId())).thenReturn(Optional.of(proposal));
		when(meetingRepository.findById(existing.getId())).thenReturn(Optional.of(existing));

		MeetingProposalResponse response = service.accept(lead.getId(), proposal.getId());

		assertThat(response.status()).isEqualTo(MeetingProposalStatus.CONFIRMED);
		assertThat(response.meetingId()).isEqualTo(existing.getId());
		assertThat(existing.getScheduledAt()).isEqualTo(nextWeek);
		assertThat(existing.getReminderSentAt()).isNull();
		verify(meetingService, never()).create(any());
	}

	@Test
	void aNewTimeWithEveryRecordingBotBusyIsRefusedBeforeAnyoneIsAsked() {
		Meeting meeting = scheduledMeeting();
		when(appUserRepository.findById(mentor.getId())).thenReturn(Optional.of(mentor));
		when(meetingRepository.findById(meeting.getId())).thenReturn(Optional.of(meeting));
		doThrow(new ConflictException("busy")).when(meetingService)
				.requireTranscriptBotSlot(meeting, nextWeek, meeting.getDurationMinutes());

		assertThatThrownBy(() -> service.proposeReschedule(mentor.getId(), meeting.getId(),
				new RescheduleMeetingRequest(null, nextWeek, null))).isInstanceOf(ConflictException.class);
		verify(proposalRepository, never()).save(any());
		verify(notificationService, never()).create(any(), any(), anyString(), anyString(), anyString());
	}

	@Test
	void aRescheduleWhoseSlotFilledUpWhileWaitingIsNotConfirmed() {
		Instant original = Instant.now().plus(1, ChronoUnit.DAYS);
		Meeting existing = new Meeting();
		existing.setId(UUID.randomUUID());
		existing.setOrganization(lumen);
		existing.setMentor(mentor);
		existing.setStatus(MeetingStatus.SCHEDULED);
		existing.setScheduledAt(original);
		MeetingProposal proposal = pending(existing.getId());
		proposal.setMentorAcceptedAt(Instant.now());
		when(proposalRepository.findById(proposal.getId())).thenReturn(Optional.of(proposal));
		when(meetingRepository.findById(existing.getId())).thenReturn(Optional.of(existing));
		doThrow(new ConflictException("busy")).when(meetingService)
				.requireTranscriptBotSlot(eq(existing), eq(nextWeek), any());

		assertThatThrownBy(() -> service.accept(lead.getId(), proposal.getId())).isInstanceOf(ConflictException.class);
		assertThat(existing.getScheduledAt()).isEqualTo(original);
		assertThat(proposal.getStatus()).isEqualTo(MeetingProposalStatus.PENDING);
	}

	@Test
	void aDeclineClosesTheProposalAndTellsTheCoordinatorWhy() {
		MeetingProposal proposal = pending(null);
		when(proposalRepository.findById(proposal.getId())).thenReturn(Optional.of(proposal));

		MeetingProposalResponse response = service.decline(mentor.getId(), proposal.getId(),
				new DeclineMeetingProposalRequest(null, "  O gün seyahatteyim  "));

		assertThat(response.status()).isEqualTo(MeetingProposalStatus.DECLINED);
		assertThat(response.declinedByName()).isEqualTo("Ayşe Demir");
		assertThat(response.declineReason()).isEqualTo("O gün seyahatteyim");
		ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
		verify(notificationService).create(eq(coordinator), eq(NotificationCategory.MEETING_CHANGE),
				eq("Görüşme önerisi reddedildi"), body.capture(), anyString());
		assertThat(body.getValue()).endsWith(": O gün seyahatteyim");
		assertThatThrownBy(() -> service.accept(lead.getId(), proposal.getId()))
				.isInstanceOf(ConflictException.class);
	}

	@Test
	void listsOpenProposalsForTheMentorWithTheirOwnAnswerState() {
		MeetingProposal proposal = pending(null);
		when(proposalRepository.findByStatusAndMentorIdOrderByScheduledAtAsc(
				MeetingProposalStatus.PENDING, mentor.getId())).thenReturn(List.of(proposal));
		when(programRepository.findAllById(List.of(program.getId()))).thenReturn(List.of(program));

		List<MeetingProposalResponse> mine = service.mine(mentor.getId());

		assertThat(mine).singleElement().satisfies(item -> {
			assertThat(item.canRespond()).isTrue();
			assertThat(item.organizationName()).isEqualTo("Lumen");
		});
	}

	// A student who mentors and is mentored answers proposals on both sides, soonest first.
	@Test
	void listsOpenProposalsFromBothSidesForAnAccountThatMentorsAndIsMentored() {
		lead.setOrganization(lumen);
		lead.grant(UserRole.MENTOR, PermissionSource.BADGE, Instant.now());
		MeetingProposal asMentor = pending(null);
		asMentor.setMentor(lead);
		asMentor.setOrganization(organization());
		asMentor.setScheduledAt(nextWeek.plusSeconds(3600));
		MeetingProposal asTeam = pending(null);
		when(proposalRepository.findByStatusAndMentorIdOrderByScheduledAtAsc(
				MeetingProposalStatus.PENDING, lead.getId())).thenReturn(List.of(asMentor));
		when(proposalRepository.findByStatusAndOrganizationIdOrderByScheduledAtAsc(
				MeetingProposalStatus.PENDING, lumen.getId())).thenReturn(List.of(asTeam));
		when(programRepository.findAllById(List.of(program.getId()))).thenReturn(List.of(program));

		List<MeetingProposalResponse> mine = service.mine(lead.getId());

		assertThat(mine).extracting(MeetingProposalResponse::id).containsExactly(asTeam.getId(), asMentor.getId());
	}

	@Test
	void remindsOnlyTheSideThatHasNotAnswered() {
		MeetingProposal proposal = pending(null);
		proposal.setMentorAcceptedAt(Instant.now());
		when(proposalRepository.findById(proposal.getId())).thenReturn(Optional.of(proposal));

		service.remind(coordinator.getId(), proposal.getId());

		assertThat(proposal.getRemindedAt()).isNotNull();
		verify(notificationService, never()).create(eq(mentor), any(), anyString(), anyString(), anyString());
		verify(notificationService).create(eq(lead), eq(NotificationCategory.MEETING_CHANGE), anyString(), anyString(),
				eq("/dashboard"));
	}

	@Test
	void theMentorProposesANewTimeAndTheTeamLeadConfirmsIt() {
		Meeting meeting = scheduledMeeting();
		when(appUserRepository.findById(mentor.getId())).thenReturn(Optional.of(mentor));
		when(meetingRepository.findById(meeting.getId())).thenReturn(Optional.of(meeting));

		MeetingProposalResponse proposed = service.proposeReschedule(mentor.getId(), meeting.getId(),
				new RescheduleMeetingRequest(null, nextWeek, null));

		assertThat(proposed.mentorAcceptedAt()).isNotNull();
		assertThat(proposed.teamAcceptedAt()).isNull();
		assertThat(proposed.durationMinutes()).isEqualTo(60);
		assertThat(proposed.mentorshipId()).isNull();
		verify(notificationService).create(eq(lead), eq(NotificationCategory.MEETING_CHANGE), anyString(), anyString(),
				eq("/dashboard"));
		verify(notificationService, never()).create(eq(mentor), any(), anyString(), anyString(), anyString());

		ArgumentCaptor<MeetingProposal> saved = ArgumentCaptor.forClass(MeetingProposal.class);
		verify(proposalRepository).save(saved.capture());
		MeetingProposal proposal = saved.getValue();
		when(proposalRepository.findById(proposal.getId())).thenReturn(Optional.of(proposal));

		MeetingProposalResponse confirmed = service.accept(lead.getId(), proposal.getId());

		assertThat(confirmed.status()).isEqualTo(MeetingProposalStatus.CONFIRMED);
		assertThat(meeting.getScheduledAt()).isEqualTo(nextWeek);
		verify(notificationService).create(eq(mentor), eq(NotificationCategory.MEETING_CHANGE), eq("Görüşme onaylandı"),
				anyString(), eq("/mentor-dashboard/calendar"));
	}

	@Test
	void aTeamMemberCanProposeButSomeoneElseCannotAndOnlyOneAtATime() {
		Meeting meeting = scheduledMeeting();
		member.setOrganization(lumen);
		AppUser outsider = user("Başka Biri", UserRole.MENTEE);
		when(appUserRepository.findById(member.getId())).thenReturn(Optional.of(member));
		when(appUserRepository.findById(outsider.getId())).thenReturn(Optional.of(outsider));
		when(meetingRepository.findById(meeting.getId())).thenReturn(Optional.of(meeting));

		assertThatThrownBy(() -> service.proposeReschedule(outsider.getId(), meeting.getId(),
				new RescheduleMeetingRequest(null, nextWeek, 30))).isInstanceOf(InvalidRequestException.class);

		MeetingProposalResponse proposed = service.proposeReschedule(member.getId(), meeting.getId(),
				new RescheduleMeetingRequest(null, nextWeek, 30));
		assertThat(proposed.teamAcceptedAt()).isNotNull();
		assertThat(proposed.mentorAcceptedAt()).isNull();
		verify(notificationService).create(eq(mentor), eq(NotificationCategory.MEETING_CHANGE), anyString(),
				anyString(),
				eq("/mentor-dashboard"));

		when(proposalRepository.existsByReplacesMeetingIdAndStatus(meeting.getId(), MeetingProposalStatus.PENDING))
				.thenReturn(true);
		assertThatThrownBy(() -> service.proposeReschedule(member.getId(), meeting.getId(),
				new RescheduleMeetingRequest(null, nextWeek, 30))).isInstanceOf(ConflictException.class);
	}

	@Test
	void refusesAProposalForAMentorshipThatIsNoLongerOpen() {
		mentorship.setStatus(MentorshipStatus.COMPLETED);
		when(coordinatorMentorshipService.requireManagedMentorship(coordinator.getId(), mentorship.getId()))
				.thenReturn(new Scoped(mentorship, program));

		assertThatThrownBy(() -> service.propose(coordinator.getId(), mentorship.getId(),
				new ProposeMeetingRequest(null, nextWeek, 45, null, null)))
				.isInstanceOf(InvalidRequestException.class);
		verify(proposalRepository, never()).save(any());
	}

	@Test
	void aCoordinatorsNewTimeForAMeetingChecksTheBotSlotAndSaysItIsANewTime() {
		Meeting meeting = scheduledMeeting();
		when(coordinatorMentorshipService.requireManagedMentorship(coordinator.getId(), mentorship.getId()))
				.thenReturn(new Scoped(mentorship, program));
		when(meetingRepository.findById(meeting.getId())).thenReturn(Optional.of(meeting));

		MeetingProposalResponse response = service.propose(coordinator.getId(), mentorship.getId(),
				new ProposeMeetingRequest(null, nextWeek, 45, "  Yeni saat  ", meeting.getId()));

		assertThat(response.replacesMeetingId()).isEqualTo(meeting.getId());
		assertThat(response.agenda()).isEqualTo("Yeni saat");
		assertThat(response.mentorshipId()).isEqualTo(mentorship.getId());
		verify(meetingService).requireTranscriptBotSlot(meeting, nextWeek, 45);
		verify(notificationService).create(eq(mentor), eq(NotificationCategory.MEETING_CHANGE),
				eq("Koordinatörden yeni saat önerisi"), anyString(), eq("/mentor-dashboard"));
	}

	@Test
	void aCoordinatorCanOnlyMoveAPlannedMeetingOfTheSameMentorAndTeam() {
		when(coordinatorMentorshipService.requireManagedMentorship(coordinator.getId(), mentorship.getId()))
				.thenReturn(new Scoped(mentorship, program));
		Meeting anotherMentors = scheduledMeeting();
		anotherMentors.setMentor(user("Başka Mentor", UserRole.MENTOR));
		Meeting anotherTeams = scheduledMeeting();
		anotherTeams.setOrganization(organization());
		Meeting withoutMentor = scheduledMeeting();
		withoutMentor.setMentor(null);
		Meeting cancelled = scheduledMeeting();
		cancelled.setStatus(MeetingStatus.CANCELLED);

		for (Meeting refused : List.of(anotherMentors, anotherTeams, withoutMentor, cancelled)) {
			when(meetingRepository.findById(refused.getId())).thenReturn(Optional.of(refused));
			assertThatThrownBy(() -> service.propose(coordinator.getId(), mentorship.getId(),
					new ProposeMeetingRequest(null, nextWeek, 45, null, refused.getId())))
					.isInstanceOf(InvalidRequestException.class);
		}
		assertThatThrownBy(() -> service.propose(coordinator.getId(), mentorship.getId(),
				new ProposeMeetingRequest(null, nextWeek, 45, null, UUID.randomUUID())))
				.isInstanceOf(ResourceNotFoundException.class);
		verify(proposalRepository, never()).save(any());
		verify(meetingService, never()).requireTranscriptBotSlot(any(), any(), any());
	}

	@Test
	void aNewTimeIsOnlyForAPlannedMeetingThatHasAMentor() {
		Meeting cancelled = scheduledMeeting();
		cancelled.setStatus(MeetingStatus.CANCELLED);
		Meeting withoutMentor = scheduledMeeting();
		withoutMentor.setMentor(null);
		when(meetingRepository.findById(cancelled.getId())).thenReturn(Optional.of(cancelled));
		when(meetingRepository.findById(withoutMentor.getId())).thenReturn(Optional.of(withoutMentor));

		assertThatThrownBy(() -> service.proposeReschedule(mentor.getId(), cancelled.getId(),
				new RescheduleMeetingRequest(null, nextWeek, null))).isInstanceOf(InvalidRequestException.class);
		assertThatThrownBy(() -> service.proposeReschedule(lead.getId(), withoutMentor.getId(),
				new RescheduleMeetingRequest(null, nextWeek, null))).isInstanceOf(InvalidRequestException.class);
		assertThatThrownBy(() -> service.proposeReschedule(mentor.getId(), UUID.randomUUID(),
				new RescheduleMeetingRequest(null, nextWeek, null))).isInstanceOf(ResourceNotFoundException.class);
		verify(proposalRepository, never()).save(any());
	}

	@Test
	void theCoordinatorSeesOneMentorshipsProposalsWithoutAnAnswerButton() {
		MeetingProposal proposal = pending(null);
		when(coordinatorMentorshipService.requireManagedMentorship(coordinator.getId(), mentorship.getId()))
				.thenReturn(new Scoped(mentorship, program));
		when(proposalRepository.findByMentorshipIdOrderByCreatedAtDesc(mentorship.getId()))
				.thenReturn(List.of(proposal));

		List<MeetingProposalResponse> listed = service.coordinatorList(coordinator.getId(), null, mentorship.getId());

		assertThat(listed).singleElement().satisfies(item -> {
			assertThat(item.id()).isEqualTo(proposal.getId());
			assertThat(item.programName()).isEqualTo("METU Stars 2026");
			assertThat(item.canRespond()).isFalse();
		});
		verify(programService, never()).requireCoordinatorOrAdmin(any());
	}

	@Test
	void theCoordinatorSeesEveryProposalInTheProgramsTheyManage() {
		MeetingProposal proposal = pending(null);
		when(programService.requireCoordinatorOrAdmin(coordinator.getId())).thenReturn(coordinator);
		when(programService.managedPrograms(coordinator)).thenReturn(List.of(program));
		when(programService.scopeOf(coordinator.getId(), List.of(program), program.getId()))
				.thenReturn(List.of(program));
		when(proposalRepository.findByProgramIdInOrderByCreatedAtDesc(Set.of(program.getId())))
				.thenReturn(List.of(proposal));

		List<MeetingProposalResponse> listed = service.coordinatorList(coordinator.getId(), program.getId(), null);

		assertThat(listed).singleElement().satisfies(item -> {
			assertThat(item.programName()).isEqualTo("METU Stars 2026");
			assertThat(item.mentorName()).isEqualTo("Ayşe Demir");
			assertThat(item.organizationName()).isEqualTo("Lumen");
		});
	}

	@Test
	void aCoordinatorWithNoProgramInScopeSeesNoProposals() {
		when(programService.requireCoordinatorOrAdmin(coordinator.getId())).thenReturn(coordinator);
		when(programService.managedPrograms(coordinator)).thenReturn(List.of());
		when(programService.scopeOf(coordinator.getId(), List.of(), null)).thenReturn(List.of());

		assertThat(service.coordinatorList(coordinator.getId(), null, null)).isEmpty();
		verify(proposalRepository, never()).findByProgramIdInOrderByCreatedAtDesc(any());
	}

	@Test
	void theTeamLeadSeesTheTeamsOpenProposalsAndAReschedulesHasNoProgram() {
		lead.setOrganization(lumen);
		MeetingProposal reschedule = pending(UUID.randomUUID());
		reschedule.setMentorship(null);
		reschedule.setProgramId(null);
		reschedule.setProposedBy(mentor.getId());
		reschedule.setMentorAcceptedAt(Instant.now());
		when(proposalRepository.findByStatusAndOrganizationIdOrderByScheduledAtAsc(
				MeetingProposalStatus.PENDING, lumen.getId())).thenReturn(List.of(reschedule));

		List<MeetingProposalResponse> mine = service.mine(lead.getId());

		assertThat(mine).singleElement().satisfies(item -> {
			assertThat(item.canRespond()).isTrue();
			assertThat(item.programName()).isNull();
			assertThat(item.mentorshipId()).isNull();
			assertThat(item.proposedByName()).isEqualTo("Ayşe Demir");
		});
		verify(proposalRepository, never()).findByStatusAndMentorIdOrderByScheduledAtAsc(any(), any());
	}

	@Test
	void anAccountThatNeitherMentorsNorIsInATeamHasNoProposals() {
		assertThat(service.mine(coordinator.getId())).isEmpty();

		verify(proposalRepository, never()).findByStatusAndMentorIdOrderByScheduledAtAsc(any(), any());
		verify(proposalRepository, never()).findByStatusAndOrganizationIdOrderByScheduledAtAsc(any(), any());
	}

	@Test
	void theTeamLeadCanAnswerFirstAndASecondYesFromThemChangesNothing() {
		MeetingProposal proposal = pending(null);
		when(proposalRepository.findById(proposal.getId())).thenReturn(Optional.of(proposal));

		MeetingProposalResponse first = service.accept(lead.getId(), proposal.getId());
		Instant firstYes = proposal.getTeamAcceptedAt();
		MeetingProposalResponse second = service.accept(lead.getId(), proposal.getId());

		assertThat(first.status()).isEqualTo(MeetingProposalStatus.PENDING);
		assertThat(first.canRespond()).isFalse();
		assertThat(first.teamResponderName()).isEqualTo("Deniz Ak");
		assertThat(firstYes).isNotNull();
		assertThat(second.teamAcceptedAt()).isEqualTo(firstYes);
		assertThat(second.status()).isEqualTo(MeetingProposalStatus.PENDING);
		verify(meetingService, never()).create(any());
	}

	@Test
	void anyMemberAnswersForATeamWithoutALead() {
		lumen.setFounder(null);
		MeetingProposal proposal = pending(null);
		when(proposalRepository.findById(proposal.getId())).thenReturn(Optional.of(proposal));

		MeetingProposalResponse response = service.accept(member.getId(), proposal.getId());

		assertThat(response.teamAcceptedAt()).isNotNull();
		assertThat(response.teamResponderName()).isEqualTo("Can Er");
	}

	@Test
	void aProposalForATeamWithoutALeadAsksEveryMember() {
		lumen.setFounder(null);
		when(coordinatorMentorshipService.requireManagedMentorship(coordinator.getId(), mentorship.getId()))
				.thenReturn(new Scoped(mentorship, program));

		service.propose(coordinator.getId(), mentorship.getId(),
				new ProposeMeetingRequest(null, nextWeek, 45, "Tanışma", null));

		verify(notificationService).create(eq(lead), eq(NotificationCategory.MEETING_CHANGE),
				eq("Koordinatörden görüşme önerisi"), anyString(), eq("/dashboard"));
		verify(notificationService).create(eq(member), eq(NotificationCategory.MEETING_CHANGE),
				eq("Koordinatörden görüşme önerisi"), anyString(), eq("/dashboard"));
	}

	@Test
	void aDeclineWithABlankReasonTellsTheProposerWithoutOne() {
		MeetingProposal proposal = pending(null);
		when(proposalRepository.findById(proposal.getId())).thenReturn(Optional.of(proposal));

		MeetingProposalResponse response = service.decline(lead.getId(), proposal.getId(),
				new DeclineMeetingProposalRequest(null, "   "));

		assertThat(response.status()).isEqualTo(MeetingProposalStatus.DECLINED);
		assertThat(response.declineReason()).isNull();
		assertThat(response.declinedByName()).isEqualTo("Deniz Ak");
		verify(notificationService).create(coordinator, NotificationCategory.MEETING_CHANGE,
				"Görüşme önerisi reddedildi",
				"Deniz Ak · Ayşe Demir ↔ Lumen · " + MeetingSlotTimes.formatForPeople(nextWeek, AppLanguage.TR),
				"/coordinator/mentorships?mentorship=" + mentorship.getId());
	}

	@Test
	void aTeamMemberWhoseNewTimeIsDeclinedIsSentBackToTheTeamDashboard() {
		MeetingProposal proposal = pending(UUID.randomUUID());
		proposal.setMentorship(null);
		proposal.setProgramId(null);
		proposal.setProposedBy(member.getId());
		proposal.setTeamAcceptedAt(Instant.now());
		when(proposalRepository.findById(proposal.getId())).thenReturn(Optional.of(proposal));

		service.decline(mentor.getId(), proposal.getId(), new DeclineMeetingProposalRequest(null, "Uygun değilim"));

		verify(notificationService).create(eq(member), eq(NotificationCategory.MEETING_CHANGE),
				eq("Görüşme önerisi reddedildi"), anyString(), eq("/dashboard"));
	}

	@Test
	void anAdminWhoProposedIsSentToTheMentorshipWhenItIsDeclined() {
		AppUser admin = user("Sistem Yöneticisi", UserRole.ADMIN);
		MeetingProposal proposal = pending(null);
		proposal.setProposedBy(admin.getId());
		when(proposalRepository.findById(proposal.getId())).thenReturn(Optional.of(proposal));
		when(appUserRepository.findById(admin.getId())).thenReturn(Optional.of(admin));

		service.decline(mentor.getId(), proposal.getId(), new DeclineMeetingProposalRequest(null, null));

		verify(notificationService).create(eq(admin), eq(NotificationCategory.MEETING_CHANGE),
				eq("Görüşme önerisi reddedildi"), anyString(),
				eq("/coordinator/mentorships?mentorship=" + mentorship.getId()));
	}

	@Test
	void theCoordinatorWithdrawsAPendingProposal() {
		MeetingProposal proposal = pending(null);
		when(proposalRepository.findById(proposal.getId())).thenReturn(Optional.of(proposal));

		service.withdraw(coordinator.getId(), proposal.getId());

		assertThat(proposal.getStatus()).isEqualTo(MeetingProposalStatus.WITHDRAWN);
		verify(coordinatorMentorshipService).requireManagedMentorship(coordinator.getId(), mentorship.getId());
		verify(proposalRepository).save(proposal);
		verify(notificationService, never()).create(any(), any(), anyString(), anyString(), anyString());
	}

	@Test
	void aCoordinatorWhoDoesNotManageTheMentorshipCannotWithdrawItsProposal() {
		AppUser stranger = user("Başka Koordinatör", UserRole.COORDINATOR);
		MeetingProposal proposal = pending(null);
		when(proposalRepository.findById(proposal.getId())).thenReturn(Optional.of(proposal));
		when(coordinatorMentorshipService.requireManagedMentorship(stranger.getId(), mentorship.getId()))
				.thenThrow(new InvalidRequestException("not yours"));

		assertThatThrownBy(() -> service.withdraw(stranger.getId(), proposal.getId()))
				.isInstanceOf(InvalidRequestException.class);
		assertThat(proposal.getStatus()).isEqualTo(MeetingProposalStatus.PENDING);
		verify(proposalRepository, never()).save(any());
	}

	@Test
	void aNewTimeBetweenTheTwoSidesIsNotTheCoordinatorsToWithdrawOrRemind() {
		MeetingProposal proposal = pending(UUID.randomUUID());
		proposal.setMentorship(null);
		proposal.setProposedBy(mentor.getId());
		when(proposalRepository.findById(proposal.getId())).thenReturn(Optional.of(proposal));

		assertThatThrownBy(() -> service.withdraw(coordinator.getId(), proposal.getId()))
				.isInstanceOf(InvalidRequestException.class);
		assertThatThrownBy(() -> service.remind(coordinator.getId(), proposal.getId()))
				.isInstanceOf(InvalidRequestException.class);
		assertThat(proposal.getStatus()).isEqualTo(MeetingProposalStatus.PENDING);
		assertThat(proposal.getRemindedAt()).isNull();
		verify(proposalRepository, never()).save(any());
	}

	@Test
	void aProposalThatIsAlreadyClosedCannotBeWithdrawn() {
		MeetingProposal proposal = pending(null);
		proposal.setStatus(MeetingProposalStatus.CONFIRMED);
		when(proposalRepository.findById(proposal.getId())).thenReturn(Optional.of(proposal));

		assertThatThrownBy(() -> service.withdraw(coordinator.getId(), proposal.getId()))
				.isInstanceOf(ConflictException.class);
		assertThat(proposal.getStatus()).isEqualTo(MeetingProposalStatus.CONFIRMED);
	}

	@Test
	void aRescheduleOfAMeetingCancelledMeanwhileBooksANewMeetingWithTheProposalsAgenda() {
		Meeting cancelled = scheduledMeeting();
		Instant original = cancelled.getScheduledAt();
		cancelled.setStatus(MeetingStatus.CANCELLED);
		MeetingProposal proposal = pending(cancelled.getId());
		proposal.setAgenda("Finansal model");
		proposal.setMentorAcceptedAt(Instant.now());
		when(proposalRepository.findById(proposal.getId())).thenReturn(Optional.of(proposal));
		when(meetingRepository.findById(cancelled.getId())).thenReturn(Optional.of(cancelled));
		UUID bookedId = UUID.randomUUID();
		MeetingResponse created = meetingResponse(bookedId);
		when(meetingService.create(any())).thenReturn(created);

		MeetingProposalResponse response = service.accept(lead.getId(), proposal.getId());

		assertThat(response.status()).isEqualTo(MeetingProposalStatus.CONFIRMED);
		assertThat(response.meetingId()).isEqualTo(bookedId);
		assertThat(cancelled.getScheduledAt()).isEqualTo(original);
		ArgumentCaptor<CreateMeetingRequest> booked = ArgumentCaptor.forClass(CreateMeetingRequest.class);
		verify(meetingService).create(booked.capture());
		assertThat(booked.getValue().agenda()).isEqualTo("Finansal model");
		assertThat(booked.getValue().organizationId()).isEqualTo(lumen.getId());
		assertThat(booked.getValue().durationMinutes()).isEqualTo(45);
		verify(meetingRepository, never()).save(any());
		verify(notificationService, never()).create(any(), any(), eq("Görüşme yeni saate taşındı"), anyString(),
				anyString());
	}

	@Test
	void aConfirmedRescheduleTellsEveryTeamMemberTheMeetingMoved() {
		Meeting existing = scheduledMeeting();
		MeetingProposal proposal = pending(existing.getId());
		proposal.setMentorAcceptedAt(Instant.now());
		when(proposalRepository.findById(proposal.getId())).thenReturn(Optional.of(proposal));
		when(meetingRepository.findById(existing.getId())).thenReturn(Optional.of(existing));

		service.accept(lead.getId(), proposal.getId());

		assertThat(existing.getDurationMinutes()).isEqualTo(45);
		verify(meetingRepository).save(existing);
		verify(notificationService).create(eq(lead), eq(NotificationCategory.MEETING_CHANGE),
				eq("Görüşme yeni saate taşındı"), anyString(), eq("/mentorships?tab=active"));
		verify(notificationService).create(eq(member), eq(NotificationCategory.MEETING_CHANGE),
				eq("Görüşme yeni saate taşındı"), anyString(), eq("/mentorships?tab=active"));
		verify(notificationService).create(eq(mentor), eq(NotificationCategory.MEETING_CHANGE),
				eq("Görüşme onaylandı"), anyString(), eq("/mentor-dashboard/calendar"));
	}

	private Meeting scheduledMeeting() {
		Meeting meeting = new Meeting();
		meeting.setId(UUID.randomUUID());
		meeting.setOrganization(lumen);
		meeting.setMentor(mentor);
		meeting.setStatus(MeetingStatus.SCHEDULED);
		meeting.setScheduledAt(Instant.now().plus(2, ChronoUnit.DAYS));
		meeting.setDurationMinutes(60);
		meeting.setAgenda("Finansal model");
		return meeting;
	}

	private MeetingProposal pending(UUID replacesMeetingId) {
		MeetingProposal proposal = new MeetingProposal();
		proposal.setId(UUID.randomUUID());
		proposal.setMentorship(mentorship);
		proposal.setProgramId(program.getId());
		proposal.setOrganization(lumen);
		proposal.setMentor(mentor);
		proposal.setProposedBy(coordinator.getId());
		proposal.setScheduledAt(nextWeek);
		proposal.setDurationMinutes(45);
		proposal.setReplacesMeetingId(replacesMeetingId);
		proposal.setStatus(MeetingProposalStatus.PENDING);
		return proposal;
	}

	private static MeetingResponse meetingResponse(UUID id) {
		MeetingResponse response = org.mockito.Mockito.mock(MeetingResponse.class);
		when(response.id()).thenReturn(id);
		return response;
	}

	private Organization organization() {
		Organization organization = new Organization();
		organization.setId(UUID.randomUUID());
		organization.setName("Lumen");
		organization.setFounder(lead);
		return organization;
	}

	private Mentorship mentorship() {
		Mentorship value = new Mentorship();
		value.setId(UUID.randomUUID());
		value.setMentor(mentor);
		value.setOrganization(lumen);
		value.setStatus(MentorshipStatus.ACTIVE);
		return value;
	}

	private static Program program() {
		Program program = new Program();
		program.setId(UUID.randomUUID());
		program.setName("METU Stars 2026");
		return program;
	}

	private static AppUser user(String name, UserRole role) {
		AppUser user = new AppUser();
		user.setId(UUID.randomUUID());
		user.setFullName(name);
		user.setRole(role);
		user.setEmail(user.getId() + "@tekno.test");
		return user;
	}
}
