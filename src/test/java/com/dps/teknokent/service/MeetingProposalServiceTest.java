package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dps.teknokent.domain.MeetingProposalStatus;
import com.dps.teknokent.domain.MeetingStatus;
import com.dps.teknokent.domain.MentorshipStatus;
import com.dps.teknokent.domain.NotificationCategory;
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
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.MeetingProposalRepository;
import com.dps.teknokent.repository.MeetingRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.service.CoordinatorMentorshipService.Scoped;

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
		verify(notificationService).create(eq(mentor), eq(NotificationCategory.MEETING_CHANGE), anyString(), anyString(),
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
		verify(notificationService).create(eq(mentor), eq(NotificationCategory.MEETING_CHANGE), anyString(), anyString(),
				eq("/mentor-dashboard"));

		when(proposalRepository.existsByReplacesMeetingIdAndStatus(meeting.getId(), MeetingProposalStatus.PENDING))
				.thenReturn(true);
		assertThatThrownBy(() -> service.proposeReschedule(member.getId(), meeting.getId(),
				new RescheduleMeetingRequest(null, nextWeek, 30))).isInstanceOf(ConflictException.class);
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
