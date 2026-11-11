package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

import com.dps.teknokent.domain.MeetingStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.OpenInterventionRequest;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.entity.Mentorship;
import com.dps.teknokent.entity.MentorshipIntervention;
import com.dps.teknokent.entity.Notification;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramCoordinator;
import com.dps.teknokent.entity.ProgramStartup;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.MeetingRepository;
import com.dps.teknokent.repository.MentorshipInterventionRepository;
import com.dps.teknokent.repository.MentorshipRepository;
import com.dps.teknokent.repository.NotificationRepository;
import com.dps.teknokent.repository.ProgramCoordinatorRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.repository.ProgramStartupRepository;

@ExtendWith(MockitoExtension.class)
class AdminInterventionServiceTest {

	private static final UUID ADMIN = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
	private static final UUID COORDINATOR = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
	private static final UUID MENTOR = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
	private static final UUID ORG = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
	private static final UUID PROGRAM = UUID.fromString("00000000-0000-0000-0000-000000000010");
	private static final UUID MENTORSHIP = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
	private static final UUID NOTIFICATION = UUID.fromString("00000000-0000-0000-0000-0000000000f1");

	@Mock
	private DomainLookupService domainLookupService;
	@Mock
	private MentorshipRepository mentorshipRepository;
	@Mock
	private MentorshipInterventionRepository interventionRepository;
	@Mock
	private ProgramRepository programRepository;
	@Mock
	private ProgramStartupRepository programStartupRepository;
	@Mock
	private MeetingRepository meetingRepository;
	@Mock
	private NotificationRepository notificationRepository;
	@Mock
	private NotificationService notificationService;
	@Mock
	private ProgramCoordinatorRepository programCoordinatorRepository;
	@Mock
	private AppUserRepository appUserRepository;
	@InjectMocks
	private AdminInterventionService service;

	@Test
	void openingTellsTheProgramCoordinatorAndKeepsTheNotification() {
		when(domainLookupService.requireUser(ADMIN)).thenReturn(user(ADMIN, "Demo Administrator", UserRole.ADMIN));
		when(domainLookupService.requireUser(COORDINATOR))
				.thenReturn(user(COORDINATOR, "Deniz Acar", UserRole.COORDINATOR));
		when(mentorshipRepository.findById(MENTORSHIP)).thenReturn(Optional.of(mentorship()));
		when(interventionRepository.findByMentorshipIdAndResolvedAtIsNull(MENTORSHIP)).thenReturn(Optional.empty());
		when(programRepository.findById(PROGRAM)).thenReturn(Optional.of(program()));
		Notification notification = new Notification();
		notification.setId(NOTIFICATION);
		when(notificationService.create(any(), anyString(), anyString(),
				eq("/coordinator/mentorships?mentorship=" + MENTORSHIP)))
				.thenReturn(notification);
		when(meetingRepository.findByMentorIdAndOrganizationIdOrderByScheduledAtDesc(MENTOR, ORG))
				.thenReturn(List.of());

		var result = service.open(ADMIN, new OpenInterventionRequest(
				ADMIN, MENTORSHIP, "48 saat içinde görüşme planlansın", " Not "));

		ArgumentCaptor<MentorshipIntervention> saved = ArgumentCaptor.forClass(MentorshipIntervention.class);
		verify(interventionRepository).save(saved.capture());
		assertThat(saved.getValue().getCoordinatorId()).isEqualTo(COORDINATOR);
		assertThat(saved.getValue().getNotificationId()).isEqualTo(NOTIFICATION);
		assertThat(saved.getValue().getNote()).isEqualTo("Not");
		assertThat(result.coordinatorName()).isEqualTo("Deniz Acar");
		assertThat(result.readAt()).isNull();
		verify(notificationService).create(
				any(),
				eq("Müdahale gerekiyor"),
				eq("ODTÜ Stars 2026 · Ayşe Demir ↔ Lumen. 48 saat içinde görüşme planlansın Not"),
				eq("/coordinator/mentorships?mentorship=" + MENTORSHIP));
	}

	@Test
	void openingOnAnAdminCreatedProgramTellsEveryCoordinatorAndNamesTheFirst() {
		UUID second = UUID.fromString("00000000-0000-0000-0000-0000000000c2");
		AppUser admin = user(ADMIN, "Demo Administrator", UserRole.ADMIN);
		AppUser first = user(COORDINATOR, "Deniz Acar", UserRole.COORDINATOR);
		AppUser other = user(second, "Ece Kaya", UserRole.COORDINATOR);
		Program program = program();
		program.setCreatedBy(ADMIN);
		when(domainLookupService.requireUser(ADMIN)).thenReturn(admin);
		when(domainLookupService.requireUser(COORDINATOR)).thenReturn(first);
		when(mentorshipRepository.findById(MENTORSHIP)).thenReturn(Optional.of(mentorship()));
		when(interventionRepository.findByMentorshipIdAndResolvedAtIsNull(MENTORSHIP)).thenReturn(Optional.empty());
		when(programRepository.findById(PROGRAM)).thenReturn(Optional.of(program));
		when(programCoordinatorRepository.findByProgramIdIn(any()))
				.thenReturn(List.of(assigned(COORDINATOR, 1), assigned(second, 2)));
		when(appUserRepository.findAllById(any())).thenReturn(List.of(admin, first, other));
		Notification notification = new Notification();
		notification.setId(NOTIFICATION);
		when(notificationService.create(eq(first), anyString(), anyString(), anyString())).thenReturn(notification);
		when(meetingRepository.findByMentorIdAndOrganizationIdOrderByScheduledAtDesc(MENTOR, ORG))
				.thenReturn(List.of());

		service.open(ADMIN, new OpenInterventionRequest(ADMIN, MENTORSHIP, "Görüşme planlansın", null));

		ArgumentCaptor<MentorshipIntervention> saved = ArgumentCaptor.forClass(MentorshipIntervention.class);
		verify(interventionRepository).save(saved.capture());
		assertThat(saved.getValue().getCoordinatorId()).isEqualTo(COORDINATOR);
		assertThat(saved.getValue().getNotificationId()).isEqualTo(NOTIFICATION);
		verify(notificationService).create(eq(other), anyString(), anyString(), anyString());
		verify(notificationService, never()).create(eq(admin), anyString(), anyString(), anyString());
	}

	@Test
	void aSecondOpenWhileOneIsActiveIsRefused() {
		when(domainLookupService.requireUser(ADMIN)).thenReturn(user(ADMIN, "Demo Administrator", UserRole.ADMIN));
		when(mentorshipRepository.findById(MENTORSHIP)).thenReturn(Optional.of(mentorship()));
		when(interventionRepository.findByMentorshipIdAndResolvedAtIsNull(MENTORSHIP))
				.thenReturn(Optional.of(new MentorshipIntervention()));

		assertThatThrownBy(() -> service.open(ADMIN, new OpenInterventionRequest(ADMIN, MENTORSHIP, "Ara", null)))
				.isInstanceOf(InvalidRequestException.class);
	}

	@Test
	void listReadsWhetherTheCoordinatorOpenedItAndAMeetingBookedAfterward() {
		when(domainLookupService.requireUser(ADMIN)).thenReturn(user(ADMIN, "Demo Administrator", UserRole.ADMIN));
		when(domainLookupService.requireUser(COORDINATOR))
				.thenReturn(user(COORDINATOR, "Deniz Acar", UserRole.COORDINATOR));
		MentorshipIntervention row = new MentorshipIntervention();
		row.setId(UUID.randomUUID());
		row.setMentorshipId(MENTORSHIP);
		row.setCoordinatorId(COORDINATOR);
		row.setOpenedBy(ADMIN);
		row.setNotificationId(NOTIFICATION);
		row.setAction("Ara");
		row.setOpenedAt(Instant.parse("2026-09-15T07:12:00Z"));
		when(interventionRepository.findAllByOrderByOpenedAtDesc()).thenReturn(List.of(row));
		Notification notification = new Notification();
		notification.setId(NOTIFICATION);
		notification.setReadAt(Instant.parse("2026-09-15T08:40:00Z"));
		when(notificationRepository.findById(NOTIFICATION)).thenReturn(Optional.of(notification));
		when(mentorshipRepository.findById(MENTORSHIP)).thenReturn(Optional.of(mentorship()));
		Meeting meeting = new Meeting();
		meeting.setStatus(MeetingStatus.SCHEDULED);
		meeting.setScheduledAt(Instant.parse("2026-09-18T07:30:00Z"));
		when(meetingRepository.findByMentorIdAndOrganizationIdOrderByScheduledAtDesc(MENTOR, ORG))
				.thenReturn(List.of(meeting));

		var listed = service.list(ADMIN);

		assertThat(listed).hasSize(1);
		assertThat(listed.get(0).readAt()).isEqualTo(Instant.parse("2026-09-15T08:40:00Z"));
		assertThat(listed.get(0).plannedMeetingAt()).isEqualTo(Instant.parse("2026-09-18T07:30:00Z"));
	}

	@Test
	void aMeetingFromBeforeTheNoticeDoesNotCountAsPlanned() {
		when(domainLookupService.requireUser(ADMIN)).thenReturn(user(ADMIN, "Demo Administrator", UserRole.ADMIN));
		when(domainLookupService.requireUser(COORDINATOR))
				.thenReturn(user(COORDINATOR, "Deniz Acar", UserRole.COORDINATOR));
		MentorshipIntervention row = openRow();
		when(interventionRepository.findAllByOrderByOpenedAtDesc()).thenReturn(List.of(row));
		when(notificationRepository.findById(NOTIFICATION)).thenReturn(Optional.empty());
		when(mentorshipRepository.findById(MENTORSHIP)).thenReturn(Optional.of(mentorship()));
		Meeting earlier = new Meeting();
		earlier.setStatus(MeetingStatus.SCHEDULED);
		earlier.setScheduledAt(Instant.parse("2026-09-01T07:30:00Z"));
		Meeting cancelled = new Meeting();
		cancelled.setStatus(MeetingStatus.CANCELLED);
		cancelled.setScheduledAt(Instant.parse("2026-09-20T07:30:00Z"));
		when(meetingRepository.findByMentorIdAndOrganizationIdOrderByScheduledAtDesc(MENTOR, ORG))
				.thenReturn(List.of(cancelled, earlier));

		assertThat(service.list(ADMIN).get(0).plannedMeetingAt()).isNull();
	}

	@Test
	void resolveClosesTheFollowUp() {
		when(domainLookupService.requireUser(ADMIN)).thenReturn(user(ADMIN, "Demo Administrator", UserRole.ADMIN));
		when(domainLookupService.requireUser(COORDINATOR))
				.thenReturn(user(COORDINATOR, "Deniz Acar", UserRole.COORDINATOR));
		MentorshipIntervention row = openRow();
		when(interventionRepository.findById(row.getId())).thenReturn(Optional.of(row));
		when(mentorshipRepository.findById(MENTORSHIP)).thenReturn(Optional.of(mentorship()));
		when(notificationRepository.findById(NOTIFICATION)).thenReturn(Optional.empty());
		when(meetingRepository.findByMentorIdAndOrganizationIdOrderByScheduledAtDesc(MENTOR, ORG))
				.thenReturn(List.of());

		var result = service.resolve(ADMIN, row.getId());

		assertThat(row.getResolvedAt()).isNotNull();
		assertThat(result.resolvedAt()).isEqualTo(row.getResolvedAt());
		verify(interventionRepository).save(row);
	}

	@Test
	void resolvingAClosedFollowUpIsRefused() {
		when(domainLookupService.requireUser(ADMIN)).thenReturn(user(ADMIN, "Demo Administrator", UserRole.ADMIN));
		MentorshipIntervention row = openRow();
		row.setResolvedAt(Instant.parse("2026-09-20T00:00:00Z"));
		when(interventionRepository.findById(row.getId())).thenReturn(Optional.of(row));

		assertThatThrownBy(() -> service.resolve(ADMIN, row.getId())).isInstanceOf(InvalidRequestException.class);
		verify(interventionRepository, never()).save(any());
	}

	@Test
	void aCoordinatorCannotOpenListOrResolve() {
		when(domainLookupService.requireUser(COORDINATOR))
				.thenReturn(user(COORDINATOR, "Deniz Acar", UserRole.COORDINATOR));

		assertThatThrownBy(() -> service.list(COORDINATOR)).isInstanceOf(InvalidRequestException.class);
		assertThatThrownBy(
				() -> service.open(COORDINATOR, new OpenInterventionRequest(COORDINATOR, MENTORSHIP, "Ara", null)))
				.isInstanceOf(InvalidRequestException.class);
		assertThatThrownBy(() -> service.resolve(COORDINATOR, UUID.randomUUID()))
				.isInstanceOf(InvalidRequestException.class);
		verify(notificationService, never()).create(any(), anyString(), anyString(), anyString());
		verify(interventionRepository, never()).save(any());
	}

	@Test
	void openingGoesToTheProgramTheMentorshipWasMadeIn() {
		Mentorship mentorship = mentorship();
		mentorship.getOrganization().setActiveProgramId(UUID.randomUUID());
		mentorship.setProgram(program());
		when(domainLookupService.requireUser(ADMIN)).thenReturn(user(ADMIN, "Demo Administrator", UserRole.ADMIN));
		when(domainLookupService.requireUser(COORDINATOR))
				.thenReturn(user(COORDINATOR, "Deniz Acar", UserRole.COORDINATOR));
		when(mentorshipRepository.findById(MENTORSHIP)).thenReturn(Optional.of(mentorship));
		when(interventionRepository.findByMentorshipIdAndResolvedAtIsNull(MENTORSHIP)).thenReturn(Optional.empty());
		Notification notification = new Notification();
		notification.setId(NOTIFICATION);
		when(notificationService.create(any(), anyString(), anyString(), anyString())).thenReturn(notification);
		when(meetingRepository.findByMentorIdAndOrganizationIdOrderByScheduledAtDesc(MENTOR, ORG))
				.thenReturn(List.of());

		var result = service.open(ADMIN, new OpenInterventionRequest(ADMIN, MENTORSHIP, "Ara", null));

		assertThat(result.coordinatorId()).isEqualTo(COORDINATOR);
		verify(programRepository, never()).findById(any());
	}

	@Test
	void openingWithoutAnActiveProgramUsesTheLatestMembership() {
		Mentorship mentorship = mentorship();
		mentorship.getOrganization().setActiveProgramId(null);
		when(domainLookupService.requireUser(ADMIN)).thenReturn(user(ADMIN, "Demo Administrator", UserRole.ADMIN));
		when(domainLookupService.requireUser(COORDINATOR))
				.thenReturn(user(COORDINATOR, "Deniz Acar", UserRole.COORDINATOR));
		when(mentorshipRepository.findById(MENTORSHIP)).thenReturn(Optional.of(mentorship));
		when(interventionRepository.findByMentorshipIdAndResolvedAtIsNull(MENTORSHIP)).thenReturn(Optional.empty());
		ProgramStartup older = new ProgramStartup();
		older.setProgramId(UUID.randomUUID());
		ProgramStartup newer = new ProgramStartup();
		newer.setProgramId(PROGRAM);
		when(programStartupRepository.findByOrganizationIdOrderByCreatedAtAsc(ORG)).thenReturn(List.of(older, newer));
		when(programRepository.findById(PROGRAM)).thenReturn(Optional.of(program()));
		Notification notification = new Notification();
		notification.setId(NOTIFICATION);
		when(notificationService.create(any(), anyString(), anyString(), anyString())).thenReturn(notification);
		when(meetingRepository.findByMentorIdAndOrganizationIdOrderByScheduledAtDesc(MENTOR, ORG))
				.thenReturn(List.of());

		var result = service.open(ADMIN, new OpenInterventionRequest(ADMIN, MENTORSHIP, "Ara", null));

		assertThat(result.coordinatorId()).isEqualTo(COORDINATOR);
	}

	@Test
	void aTeamWithNoProgramCannotBeFollowed() {
		Mentorship mentorship = mentorship();
		mentorship.getOrganization().setActiveProgramId(null);
		when(domainLookupService.requireUser(ADMIN)).thenReturn(user(ADMIN, "Demo Administrator", UserRole.ADMIN));
		when(mentorshipRepository.findById(MENTORSHIP)).thenReturn(Optional.of(mentorship));
		when(interventionRepository.findByMentorshipIdAndResolvedAtIsNull(MENTORSHIP)).thenReturn(Optional.empty());
		when(programStartupRepository.findByOrganizationIdOrderByCreatedAtAsc(ORG)).thenReturn(List.of());

		assertThatThrownBy(() -> service.open(ADMIN, new OpenInterventionRequest(ADMIN, MENTORSHIP, "Ara", null)))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessage("Bu takım bir programa bağlı değil.");
	}

	private static MentorshipIntervention openRow() {
		MentorshipIntervention row = new MentorshipIntervention();
		row.setId(UUID.randomUUID());
		row.setMentorshipId(MENTORSHIP);
		row.setCoordinatorId(COORDINATOR);
		row.setOpenedBy(ADMIN);
		row.setNotificationId(NOTIFICATION);
		row.setAction("Ara");
		row.setOpenedAt(Instant.parse("2026-09-15T07:12:00Z"));
		return row;
	}

	private static Mentorship mentorship() {
		Organization organization = new Organization();
		organization.setId(ORG);
		organization.setName("Lumen");
		organization.setActiveProgramId(PROGRAM);
		AppUser mentor = user(MENTOR, "Ayşe Demir", UserRole.MENTOR);
		Mentorship mentorship = new Mentorship();
		mentorship.setId(MENTORSHIP);
		mentorship.setMentor(mentor);
		mentorship.setOrganization(organization);
		return mentorship;
	}

	private static Program program() {
		Program program = new Program();
		program.setId(PROGRAM);
		program.setName("ODTÜ Stars 2026");
		program.setCreatedBy(COORDINATOR);
		return program;
	}

	private static ProgramCoordinator assigned(UUID userId, long order) {
		ProgramCoordinator row = new ProgramCoordinator();
		row.setId(UUID.randomUUID());
		row.setProgramId(PROGRAM);
		row.setUserId(userId);
		row.setCreatedAt(Instant.parse("2026-11-01T10:00:00Z").plusSeconds(order));
		return row;
	}

	private static AppUser user(UUID id, String name, UserRole role) {
		AppUser user = new AppUser();
		user.setId(id);
		user.setFullName(name);
		user.setRole(role);
		return user;
	}
}
