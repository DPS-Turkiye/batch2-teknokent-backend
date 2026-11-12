package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.dps.teknokent.domain.PermissionSource;
import com.dps.teknokent.domain.PersonProgramRelation;
import com.dps.teknokent.domain.ProgramMentorInvitedBy;
import com.dps.teknokent.domain.ProgramMentorStatus;
import com.dps.teknokent.domain.ProgramStatus;
import com.dps.teknokent.domain.ProgramType;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.responses.MentorProfileResponse;
import com.dps.teknokent.dto.responses.PersonSummaryResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramMentor;
import com.dps.teknokent.entity.ProgramStartup;
import com.dps.teknokent.entity.TeamMembership;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.entity.MentorAvailabilityException;
import com.dps.teknokent.entity.MentorAvailabilityRule;
import com.dps.teknokent.entity.MentorAvailabilitySlot;
import com.dps.teknokent.repository.MentorAvailabilityExceptionRepository;
import com.dps.teknokent.repository.MentorAvailabilityRuleRepository;
import com.dps.teknokent.repository.MentorAvailabilitySlotRepository;
import com.dps.teknokent.repository.MentorProfileRepository;
import com.dps.teknokent.repository.ProgramCoordinatorRepository;
import com.dps.teknokent.repository.ProgramMentorRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.repository.ProgramStartupRepository;
import com.dps.teknokent.repository.TeamMembershipRepository;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PersonSummaryServiceTest {

	private static final UUID OWN_PROGRAM = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
	private static final UUID OTHER_PROGRAM = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
	private static final UUID PEER_PROGRAM = UUID.fromString("00000000-0000-0000-0000-0000000000a3");
	private static final UUID PAST_PROGRAM = UUID.fromString("00000000-0000-0000-0000-0000000000a4");
	private static final UUID COORDINATOR_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
	private static final UUID OTHER_COORDINATOR_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c2");
	private static final Instant NOW = Instant.parse("2026-11-12T22:30:00Z");
	private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
	private static final UUID ADMIN_ID = UUID.fromString("00000000-0000-0000-0000-0000000000d1");

	@Mock
	private DomainLookupService domainLookupService;
	@Mock
	private MentorProfileService mentorProfileService;
	@Mock
	private MentorProfileRepository mentorProfileRepository;
	@Mock
	private ProgramMentorRepository programMentorRepository;
	@Mock
	private ProgramStartupRepository programStartupRepository;
	@Mock
	private TeamMembershipRepository teamMembershipRepository;
	@Mock
	private MentorAvailabilityRuleRepository mentorAvailabilityRuleRepository;
	@Mock
	private MentorAvailabilityExceptionRepository mentorAvailabilityExceptionRepository;
	@Mock
	private MentorAvailabilitySlotRepository mentorAvailabilitySlotRepository;
	@Mock
	private ProgramRepository programRepository;
	@Mock
	private ProgramCoordinatorRepository programCoordinatorRepository;

	private PersonSummaryService service;

	private final List<ProgramStartup> links = new ArrayList<>();

	private final List<Program> allPrograms = List.of(
			program(OWN_PROGRAM, "Yeşil Program", ProgramType.STARTUP_MENTORING, COORDINATOR_ID),
			program(OTHER_PROGRAM, "Mavi Program", ProgramType.STARTUP_MENTORING, OTHER_COORDINATOR_ID),
			program(PEER_PROGRAM, "Akran Programı", ProgramType.PEER_MENTORING, COORDINATOR_ID),
			program(PAST_PROGRAM, "Eski Program", ProgramType.STARTUP_MENTORING, COORDINATOR_ID));

	@BeforeEach
	void setUp() {
		TeamMembershipService teamMembershipService = new TeamMembershipService(teamMembershipRepository,
				programStartupRepository, programRepository, programCoordinatorRepository, domainLookupService);
		service = new PersonSummaryService(domainLookupService, mentorProfileService, mentorProfileRepository,
				programMentorRepository, programStartupRepository, teamMembershipRepository, teamMembershipService,
				new MentorAvailabilitySummaryService(mentorAvailabilityRuleRepository,
						mentorAvailabilityExceptionRepository, mentorAvailabilitySlotRepository, CLOCK),
				programRepository, programCoordinatorRepository);
		when(programStartupRepository.findByOrganizationIdIn(anyCollection())).thenAnswer(invocation -> {
			Collection<UUID> ids = invocation.getArgument(0);
			return links.stream().filter(link -> ids.contains(link.getOrganizationId())).toList();
		});
		when(programStartupRepository.findByOrganizationIdOrderByCreatedAtAsc(any())).thenAnswer(invocation ->
				links.stream().filter(link -> link.getOrganizationId().equals(invocation.getArgument(0))).toList());
		register(user(ADMIN_ID, UserRole.ADMIN));
		register(user(COORDINATOR_ID, UserRole.COORDINATOR));
		register(user(OTHER_COORDINATOR_ID, UserRole.COORDINATOR));
		when(programRepository.findAllById(anyCollection())).thenAnswer(invocation -> {
			Collection<UUID> ids = invocation.getArgument(0);
			return allPrograms.stream().filter(program -> ids.contains(program.getId())).toList();
		});
	}

	@Test
	void anAdminSeesAMentorWithTheirProfileAndEveryLiveMentorPool() {
		AppUser mentor = register(user(UUID.randomUUID(), UserRole.MENTOR));
		mentor.setJobTitle("Ürün danışmanı");
		mentor.setPhone("+90 000 000 00 00");
		pools(mentor, pool(OWN_PROGRAM, ProgramMentorStatus.ACCEPTED), pool(OTHER_PROGRAM,
				ProgramMentorStatus.PENDING, ProgramMentorInvitedBy.MENTOR),
				pool(PAST_PROGRAM, ProgramMentorStatus.REJECTED));
		when(mentorProfileRepository.existsById(mentor.getId())).thenReturn(true);
		when(mentorProfileService.findById(mentor.getId())).thenReturn(mentorProfile(mentor));

		PersonSummaryResponse summary = service.summary(ADMIN_ID, mentor.getId());

		assertThat(summary.jobTitle()).isEqualTo("Ürün danışmanı");
		assertThat(summary.mentor().company()).isEqualTo("Örnek Şirket");
		assertThat(summary.mentor().activeMenteeCount()).isEqualTo(2);
		assertThat(summary.startup()).isNull();
		assertThat(summary.programs()).extracting(PersonSummaryResponse.Program::name)
				.containsExactly("Mavi Program", "Yeşil Program");
		assertThat(summary.programs()).extracting(PersonSummaryResponse.Program::mentorStatus)
				.containsExactly(ProgramMentorStatus.PENDING, ProgramMentorStatus.ACCEPTED);
	}

	@Test
	void aMentorWithoutAProfileComesBackWithoutTheMentorBlock() {
		AppUser mentor = register(user(UUID.randomUUID(), UserRole.MENTOR));
		pools(mentor);

		assertThat(service.summary(ADMIN_ID, mentor.getId()).mentor()).isNull();
		verify(mentorProfileService, never()).findById(any());
	}

	@Test
	void aCoordinatorSeesAMentorWhoAskedToJoinButOnlyTheirOwnPrograms() {
		AppUser mentor = register(user(UUID.randomUUID(), UserRole.MENTOR));
		pools(mentor, pool(OWN_PROGRAM, ProgramMentorStatus.PENDING, ProgramMentorInvitedBy.MENTOR),
				pool(OTHER_PROGRAM, ProgramMentorStatus.ACCEPTED));

		PersonSummaryResponse summary = service.summary(COORDINATOR_ID, mentor.getId());

		assertThat(summary.programs()).extracting(PersonSummaryResponse.Program::id).containsExactly(OWN_PROGRAM);
	}

	@Test
	void aCoordinatorAddedToAProgramCountsLikeItsCreator() {
		AppUser mentor = register(user(UUID.randomUUID(), UserRole.MENTOR));
		pools(mentor, pool(OTHER_PROGRAM, ProgramMentorStatus.ACCEPTED));
		when(programCoordinatorRepository.existsByProgramIdAndUserId(OTHER_PROGRAM, COORDINATOR_ID))
				.thenReturn(true);

		assertThat(service.summary(COORDINATOR_ID, mentor.getId()).programs()).hasSize(1);
	}

	@Test
	void aMentorIsHiddenFromCoordinatorsOfOtherProgramsOfPeerProgramsAndWhoTurnedThemDown() {
		AppUser mentor = register(user(UUID.randomUUID(), UserRole.MENTOR));
		pools(mentor, pool(OTHER_PROGRAM, ProgramMentorStatus.ACCEPTED),
				pool(PEER_PROGRAM, ProgramMentorStatus.ACCEPTED), pool(OWN_PROGRAM, ProgramMentorStatus.REJECTED));

		assertThatThrownBy(() -> service.summary(COORDINATOR_ID, mentor.getId()))
				.isInstanceOf(ResourceNotFoundException.class);
	}

	@Test
	void aCoordinatorSeesAMenteeThroughTheirStartupWithTheStartupSummary() {
		Organization startup = startup("Pazar Lab");
		AppUser mentee = register(user(UUID.randomUUID(), UserRole.MENTEE));
		mentee.setOrganization(startup);
		startupIn(startup, OWN_PROGRAM);
		when(teamMembershipRepository.findByUserIdOrderByJoinedAtDesc(mentee.getId())).thenReturn(List.of());

		PersonSummaryResponse summary = service.summary(COORDINATOR_ID, mentee.getId());

		assertThat(summary.startup().name()).isEqualTo("Pazar Lab");
		assertThat(summary.startup().description()).isEqualTo("Yerel üreticiler için pazar yeri");
		assertThat(summary.mentor()).isNull();
		assertThat(summary.programs()).singleElement().satisfies(row -> {
			assertThat(row.relation()).isEqualTo(PersonProgramRelation.MENTEE);
			assertThat(row.mentorStatus()).isNull();
		});
	}

	@Test
	void aCoordinatorSeesAnIndividualMenteeInTheirProgram() {
		AppUser mentee = register(user(UUID.randomUUID(), UserRole.MENTEE));
		mentee.setProgramId(OWN_PROGRAM);
		when(teamMembershipRepository.findByUserIdOrderByJoinedAtDesc(mentee.getId())).thenReturn(List.of());

		assertThat(service.summary(COORDINATOR_ID, mentee.getId()).programs())
				.extracting(PersonSummaryResponse.Program::id).containsExactly(OWN_PROGRAM);
	}

	@Test
	void anEarlierStartupGivesAccessLikeTeamHistoryButIsNotListedAsAProgram() {
		Organization earlier = startup("Eski Takım");
		AppUser mentee = register(user(UUID.randomUUID(), UserRole.MENTEE));
		startupIn(earlier, PAST_PROGRAM);
		TeamMembership stay = new TeamMembership();
		stay.setOrganization(earlier);
		stay.setJoinedAt(Instant.parse("2026-09-01T09:00:00Z"));
		stay.setLeftAt(Instant.parse("2026-10-01T09:00:00Z"));
		when(teamMembershipRepository.findByUserIdOrderByJoinedAtDesc(mentee.getId())).thenReturn(List.of(stay));

		PersonSummaryResponse summary = service.summary(COORDINATOR_ID, mentee.getId());

		assertThat(summary.startup()).isNull();
		assertThat(summary.programs()).isEmpty();
	}

	@Test
	void aMenteeIsHiddenFromAnotherProgramsCoordinator() {
		AppUser mentee = register(user(UUID.randomUUID(), UserRole.MENTEE));
		mentee.setProgramId(OWN_PROGRAM);
		when(teamMembershipRepository.findByUserIdOrderByJoinedAtDesc(mentee.getId())).thenReturn(List.of());

		assertThatThrownBy(() -> service.summary(OTHER_COORDINATOR_ID, mentee.getId()))
				.isInstanceOf(ResourceNotFoundException.class);
	}

	@Test
	void aPersonalWorkspaceIsNotShownAsAStartup() {
		Organization personal = startup("Kişisel");
		personal.setPersonal(true);
		AppUser mentee = register(user(UUID.randomUUID(), UserRole.MENTEE));
		mentee.setOrganization(personal);
		when(teamMembershipRepository.findByUserIdOrderByJoinedAtDesc(mentee.getId())).thenReturn(List.of());

		assertThat(service.summary(ADMIN_ID, mentee.getId()).startup()).isNull();
	}

	@Test
	void menteesMentorsAndAcademiciansCannotOpenAnyone() {
		AppUser mentee = register(user(UUID.randomUUID(), UserRole.MENTEE));
		mentee.setProgramId(OWN_PROGRAM);
		AppUser otherMentee = register(user(UUID.randomUUID(), UserRole.MENTEE));
		AppUser mentor = register(user(UUID.randomUUID(), UserRole.MENTOR));
		AppUser academician = register(user(UUID.randomUUID(), UserRole.ACADEMICIAN));

		for (AppUser actor : List.of(otherMentee, mentor, academician, mentee)) {
			assertThatThrownBy(() -> service.summary(actor.getId(), mentee.getId()))
					.isInstanceOf(ResourceNotFoundException.class);
		}
	}

	@Test
	void adminsCoordinatorsAndAcademiciansHaveNoProfilePage() {
		AppUser academician = register(user(UUID.randomUUID(), UserRole.ACADEMICIAN));

		for (UUID target : List.of(COORDINATOR_ID, ADMIN_ID, academician.getId())) {
			assertThatThrownBy(() -> service.summary(ADMIN_ID, target))
					.isInstanceOf(ResourceNotFoundException.class);
		}
	}

	@Test
	void aCoordinatorsOwnOpenInviteDoesNotOpenTheMentorsProfile() {
		AppUser mentor = register(user(UUID.randomUUID(), UserRole.MENTOR));
		pools(mentor, pool(OWN_PROGRAM, ProgramMentorStatus.PENDING, ProgramMentorInvitedBy.COORDINATOR));

		assertThatThrownBy(() -> service.summary(COORDINATOR_ID, mentor.getId()))
				.isInstanceOf(ResourceNotFoundException.class);
	}

	@Test
	void aCoordinatorWhoseAccountIsNoLongerApprovedSeesNobody() {
		AppUser mentor = register(user(UUID.randomUUID(), UserRole.MENTOR));
		pools(mentor, pool(OWN_PROGRAM, ProgramMentorStatus.ACCEPTED));
		AppUser coordinator = register(user(UUID.randomUUID(), UserRole.COORDINATOR));
		coordinator.setStatus(UserAccountStatus.REJECTED);
		allPrograms.getFirst().setCreatedBy(coordinator.getId());

		assertThatThrownBy(() -> service.summary(coordinator.getId(), mentor.getId()))
				.isInstanceOf(ResourceNotFoundException.class);
	}

	@Test
	void aMenteeWhoAlsoRunsAProgramIsOpenToAdminsOnly() {
		AppUser mentee = register(user(UUID.randomUUID(), UserRole.MENTEE));
		mentee.grant(UserRole.COORDINATOR, PermissionSource.ADMIN, Instant.parse("2026-11-01T09:00:00Z"));
		mentee.setProgramId(OWN_PROGRAM);

		assertThatThrownBy(() -> service.summary(COORDINATOR_ID, mentee.getId()))
				.isInstanceOf(ResourceNotFoundException.class);
		assertThat(service.summary(ADMIN_ID, mentee.getId()).programs()).hasSize(1);
	}

	@Test
	void aPeerProgramDoesNotTieAMenteeToItsCoordinator() {
		Organization startup = startup("Akran Takımı");
		AppUser mentee = register(user(UUID.randomUUID(), UserRole.MENTEE));
		mentee.setOrganization(startup);
		startupIn(startup, PEER_PROGRAM);

		assertThatThrownBy(() -> service.summary(COORDINATOR_ID, mentee.getId()))
				.isInstanceOf(ResourceNotFoundException.class);
	}

	@Test
	void eachCoordinatorSeesOnlyTheirSideOfAMenteeInTwoPrograms() {
		Organization startup = startup("Pazar Lab");
		AppUser mentee = register(user(UUID.randomUUID(), UserRole.MENTEE));
		mentee.setProgramId(OWN_PROGRAM);
		mentee.setOrganization(startup);
		startupIn(startup, OTHER_PROGRAM);

		assertThat(service.summary(COORDINATOR_ID, mentee.getId()).programs())
				.extracting(PersonSummaryResponse.Program::id).containsExactly(OWN_PROGRAM);
		assertThat(service.summary(OTHER_COORDINATOR_ID, mentee.getId()).programs())
				.extracting(PersonSummaryResponse.Program::id).containsExactly(OTHER_PROGRAM);
	}

	@Test
	void aMentorWhoIsAlsoAMenteeGetsBothRowsForTheSameProgram() {
		Organization startup = startup("Pazar Lab");
		AppUser person = register(user(UUID.randomUUID(), UserRole.MENTOR));
		person.grant(UserRole.MENTEE, PermissionSource.ADMIN, Instant.parse("2026-11-01T09:00:00Z"));
		person.setOrganization(startup);
		startupIn(startup, OWN_PROGRAM);
		pools(person, pool(OWN_PROGRAM, ProgramMentorStatus.ACCEPTED));

		assertThat(service.summary(COORDINATOR_ID, person.getId()).programs())
				.extracting(PersonSummaryResponse.Program::relation)
				.containsExactlyInAnyOrder(PersonProgramRelation.MENTOR, PersonProgramRelation.MENTEE);
	}

	@Test
	void theSummaryHasNoPhoneOrContactPreferences() {
		assertThat(Arrays.stream(PersonSummaryResponse.class.getRecordComponents()).map(c -> c.getName()))
				.doesNotContain("phone", "contactPreferences");
	}

	@Test
	void aMentorsSummaryCarriesTheirWeeklyWindowsUpcomingDaysOffAndExtraSlots() {
		AppUser mentor = register(user(UUID.randomUUID(), UserRole.MENTOR));
		pools(mentor, pool(OWN_PROGRAM, ProgramMentorStatus.ACCEPTED));
		when(mentorProfileRepository.existsById(mentor.getId())).thenReturn(true);
		when(mentorProfileService.findById(mentor.getId())).thenReturn(mentorProfile(mentor));
		MentorAvailabilityRule tuesday = new MentorAvailabilityRule();
		tuesday.setDayOfWeek((short) 2);
		tuesday.setStartTime(LocalTime.of(10, 0));
		tuesday.setEndTime(LocalTime.of(12, 0));
		when(mentorAvailabilityRuleRepository.findByMentorIdOrderByDayOfWeekAscStartTimeAsc(mentor.getId()))
				.thenReturn(List.of(tuesday));
		MentorAvailabilityException holiday = new MentorAvailabilityException();
		holiday.setStartsOn(LocalDate.of(2026, 11, 20));
		holiday.setEndsOn(LocalDate.of(2026, 11, 22));
		holiday.setReason("Özel bir sebep");
		when(mentorAvailabilityExceptionRepository.findByMentorIdAndEndsOnGreaterThanEqualOrderByStartsOnAsc(
				mentor.getId(), LocalDate.of(2026, 11, 13))).thenReturn(List.of(holiday));
		MentorAvailabilitySlot extra = new MentorAvailabilitySlot();
		extra.setStartsAt(Instant.parse("2026-11-14T09:00:00Z"));
		extra.setDurationMinutes(45);
		when(mentorAvailabilitySlotRepository.findTop10ByMentorIdAndRuleIsNullAndStartsAtAfterOrderByStartsAtAsc(
				mentor.getId(), NOW)).thenReturn(List.of(extra));

		PersonSummaryResponse.Availability availability =
				service.summary(COORDINATOR_ID, mentor.getId()).mentor().availability();

		assertThat(availability.weekly()).containsExactly(new PersonSummaryResponse.WeeklyWindow(
				DayOfWeek.TUESDAY, LocalTime.of(10, 0), LocalTime.of(12, 0)));
		assertThat(availability.daysOff()).containsExactly(new PersonSummaryResponse.DayOff(
				LocalDate.of(2026, 11, 20), LocalDate.of(2026, 11, 22)));
		assertThat(availability.extraSlots()).containsExactly(new PersonSummaryResponse.ExtraSlot(
				Instant.parse("2026-11-14T09:00:00Z"), 45));
	}

	@Test
	void aMentorWithNothingSetGetsEmptyAvailabilityLists() {
		AppUser mentor = register(user(UUID.randomUUID(), UserRole.MENTOR));
		pools(mentor);
		when(mentorProfileRepository.existsById(mentor.getId())).thenReturn(true);
		when(mentorProfileService.findById(mentor.getId())).thenReturn(mentorProfile(mentor));

		PersonSummaryResponse.Availability availability =
				service.summary(ADMIN_ID, mentor.getId()).mentor().availability();

		assertThat(availability.weekly()).isEmpty();
		assertThat(availability.daysOff()).isEmpty();
		assertThat(availability.extraSlots()).isEmpty();
	}

	@Test
	void aDayOffNeverCarriesTheMentorsReason() {
		assertThat(Arrays.stream(PersonSummaryResponse.DayOff.class.getRecordComponents()).map(c -> c.getName()))
				.containsExactly("startsOn", "endsOn");
	}

	private AppUser register(AppUser user) {
		when(domainLookupService.requireUser(user.getId())).thenReturn(user);
		return user;
	}

	private void pools(AppUser mentor, ProgramMentor... rows) {
		for (ProgramMentor row : rows) {
			row.setMentorId(mentor.getId());
		}
		when(programMentorRepository.findByMentorIdOrderByCreatedAtAsc(mentor.getId())).thenReturn(List.of(rows));
	}

	private void startupIn(Organization startup, UUID programId) {
		ProgramStartup link = new ProgramStartup();
		link.setProgramId(programId);
		link.setOrganizationId(startup.getId());
		links.add(link);
	}

	private static ProgramMentor pool(UUID programId, ProgramMentorStatus status) {
		return pool(programId, status, ProgramMentorInvitedBy.COORDINATOR);
	}

	private static ProgramMentor pool(UUID programId, ProgramMentorStatus status, ProgramMentorInvitedBy invitedBy) {
		ProgramMentor row = new ProgramMentor();
		row.setProgramId(programId);
		row.setStatus(status);
		row.setInvitedBy(invitedBy);
		return row;
	}

	private static Program program(UUID id, String name, ProgramType type, UUID createdBy) {
		Program program = new Program();
		program.setId(id);
		program.setName(name);
		program.setProgramType(type);
		program.setStatus(ProgramStatus.ACTIVE);
		program.setCreatedBy(createdBy);
		return program;
	}

	private static Organization startup(String name) {
		Organization startup = new Organization();
		startup.setId(UUID.randomUUID());
		startup.setName(name);
		startup.setDescription("Yerel üreticiler için pazar yeri");
		startup.setApprovalStatus(UserAccountStatus.APPROVED);
		return startup;
	}

	private static AppUser user(UUID id, UserRole role) {
		AppUser user = new AppUser();
		user.setId(id);
		user.setFullName("Kişi " + role);
		user.setEmail(role.name().toLowerCase() + "@example.test");
		user.setRole(role);
		return user;
	}

	private static MentorProfileResponse mentorProfile(AppUser mentor) {
		return new MentorProfileResponse(mentor.getId(), mentor.getFullName(), mentor.getEmail(), "KM",
				"Ürün lideri", "Örnek Şirket", null, null, "ONLINE", null, null, null, "SENIOR", "Fintech",
				"MVP", List.of("Ürün"), List.of(), List.of(), 3, "Ürün ekipleriyle çalışıyor.", 45, 10, null,
				null, List.of(), 2, 5, List.of(), List.of(), List.of(), List.of("Ürün stratejisi"), null,
				List.of());
	}
}
