package com.dps.teknokent.service;

import com.dps.teknokent.repository.ProgramMentorRepository;
import com.dps.teknokent.repository.MentorshipRepository;
import com.dps.teknokent.repository.MentorProgramName;
import com.dps.teknokent.repository.MentorCount;
import com.dps.teknokent.entity.MentorExperience;
import com.dps.teknokent.dto.responses.MentorProfileResponse;
import com.dps.teknokent.dto.requests.MentorExperienceRequest;
import com.dps.teknokent.domain.ProgramMentorStatus;
import com.dps.teknokent.domain.MentorshipStatus;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.time.DayOfWeek;
import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.time.LocalTime;
import org.mockito.ArgumentCaptor;
import com.dps.teknokent.dto.requests.CreateMentorAvailabilityRuleRequest;
import com.dps.teknokent.entity.MentorAvailabilityRule;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dps.teknokent.domain.MeetingStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.CreateMentorAvailabilitySlotRequest;
import com.dps.teknokent.dto.requests.UpdateMentorProfileRequest;
import com.dps.teknokent.dto.responses.MentorAvailabilitySlotResponse;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.MentorAvailabilitySlot;
import com.dps.teknokent.entity.MentorProfile;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.MeetingRepository;
import com.dps.teknokent.repository.MentorAvailabilitySlotRepository;
import com.dps.teknokent.repository.MentorAvailabilityRuleRepository;
import com.dps.teknokent.repository.MentorProfileRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MentorProfileServiceTest {

	@Mock
	private MentorProfileRepository mentorProfileRepository;
	@Mock
	private MentorAvailabilityRuleRepository mentorAvailabilityRuleRepository;
	@Mock
	private MentorAvailabilitySlotRepository mentorAvailabilitySlotRepository;
	@Mock
	private MeetingRepository meetingRepository;
	@Mock
	private DomainLookupService domainLookupService;
	@Mock
	private MentorshipRepository mentorshipRepository;
	@Mock
	private ProgramMentorRepository programMentorRepository;

	private MentorProfileService mentorProfileService;

	private final UUID mentorId = UUID.randomUUID();

	@BeforeEach
	void setUp() {
		mentorProfileService = new MentorProfileService(
				mentorProfileRepository, mentorAvailabilityRuleRepository, mentorAvailabilitySlotRepository,
				meetingRepository, domainLookupService, mentorshipRepository, programMentorRepository);
	}

	private AppUser mentor() {
		AppUser user = new AppUser();
		user.setId(mentorId);
		user.setRole(UserRole.MENTOR);
		return user;
	}

	@Test
	void createAvailabilitySlotRejectsPastStartTime() {
		lenient().when(domainLookupService.requireUser(mentorId)).thenReturn(mentor());
		CreateMentorAvailabilitySlotRequest request =
				new CreateMentorAvailabilitySlotRequest(Instant.now().minus(1, ChronoUnit.DAYS), 60);

		assertThatThrownBy(() -> mentorProfileService.createAvailabilitySlot(mentorId, request))
				.isInstanceOf(InvalidRequestException.class);
	}

	@Test
	void createAvailabilitySlotRejectsNonMentorUser() {
		AppUser mentee = new AppUser();
		mentee.setId(mentorId);
		mentee.setRole(UserRole.MENTEE);
		when(domainLookupService.requireUser(mentorId)).thenReturn(mentee);
		CreateMentorAvailabilitySlotRequest request =
				new CreateMentorAvailabilitySlotRequest(Instant.now().plus(1, ChronoUnit.DAYS), 60);

		assertThatThrownBy(() -> mentorProfileService.createAvailabilitySlot(mentorId, request))
				.isInstanceOf(InvalidRequestException.class);
	}

	@Test
	void createAvailabilitySlotRejectsDuplicateStartTime() {
		when(domainLookupService.requireUser(mentorId)).thenReturn(mentor());
		Instant startsAt = Instant.now().plus(1, ChronoUnit.DAYS);
		when(mentorAvailabilitySlotRepository.existsByMentorIdAndStartsAt(mentorId, startsAt)).thenReturn(true);
		CreateMentorAvailabilitySlotRequest request = new CreateMentorAvailabilitySlotRequest(startsAt, 60);

		assertThatThrownBy(() -> mentorProfileService.createAvailabilitySlot(mentorId, request))
				.isInstanceOf(InvalidRequestException.class);
	}

	@Test
	void createAvailabilitySlotDefaultsDurationTo60Minutes() {
		when(domainLookupService.requireUser(mentorId)).thenReturn(mentor());
		Instant startsAt = Instant.now().plus(1, ChronoUnit.DAYS);
		when(mentorAvailabilitySlotRepository.existsByMentorIdAndStartsAt(mentorId, startsAt)).thenReturn(false);
		when(mentorAvailabilitySlotRepository.saveAndFlush(any())).thenAnswer(invocation -> {
			MentorAvailabilitySlot slot = invocation.getArgument(0);
			slot.setId(UUID.randomUUID());
			return slot;
		});

		MentorAvailabilitySlotResponse response = mentorProfileService.createAvailabilitySlot(
				mentorId, new CreateMentorAvailabilitySlotRequest(startsAt, null));

		assertThat(response.durationMinutes()).isEqualTo(60);
		assertThat(response.booked()).isFalse();
	}

	@Test
	void deleteAvailabilitySlotRejectsSlotBelongingToAnotherMentor() {
		when(domainLookupService.requireUser(mentorId)).thenReturn(mentor());
		UUID slotId = UUID.randomUUID();
		MentorAvailabilitySlot slot = new MentorAvailabilitySlot();
		slot.setId(slotId);
		AppUser otherMentor = new AppUser();
		otherMentor.setId(UUID.randomUUID());
		slot.setMentor(otherMentor);
		when(mentorAvailabilitySlotRepository.findById(slotId)).thenReturn(Optional.of(slot));

		assertThatThrownBy(() -> mentorProfileService.deleteAvailabilitySlot(mentorId, slotId))
				.isInstanceOf(InvalidRequestException.class);
		verify(mentorAvailabilitySlotRepository, never()).delete(any());
	}

	@Test
	void deleteAvailabilitySlotRejectsAlreadyBookedSlot() {
		when(domainLookupService.requireUser(mentorId)).thenReturn(mentor());
		UUID slotId = UUID.randomUUID();
		Instant startsAt = Instant.now().plus(1, ChronoUnit.DAYS);
		MentorAvailabilitySlot slot = new MentorAvailabilitySlot();
		slot.setId(slotId);
		slot.setMentor(mentor());
		slot.setStartsAt(startsAt);
		when(mentorAvailabilitySlotRepository.findById(slotId)).thenReturn(Optional.of(slot));
		when(meetingRepository.existsByMentorIdAndScheduledAtAndStatus(mentorId, startsAt, MeetingStatus.SCHEDULED))
				.thenReturn(true);

		assertThatThrownBy(() -> mentorProfileService.deleteAvailabilitySlot(mentorId, slotId))
				.isInstanceOf(InvalidRequestException.class);
		verify(mentorAvailabilitySlotRepository, never()).delete(any());
	}

	@Test
	void deleteAvailabilitySlotRemovesUnbookedSlot() {
		when(domainLookupService.requireUser(mentorId)).thenReturn(mentor());
		UUID slotId = UUID.randomUUID();
		Instant startsAt = Instant.now().plus(1, ChronoUnit.DAYS);
		MentorAvailabilitySlot slot = new MentorAvailabilitySlot();
		slot.setId(slotId);
		slot.setMentor(mentor());
		slot.setStartsAt(startsAt);
		when(mentorAvailabilitySlotRepository.findById(slotId)).thenReturn(Optional.of(slot));
		when(meetingRepository.existsByMentorIdAndScheduledAtAndStatus(mentorId, startsAt, MeetingStatus.SCHEDULED))
				.thenReturn(false);

		mentorProfileService.deleteAvailabilitySlot(mentorId, slotId);

		verify(mentorAvailabilitySlotRepository).delete(slot);
	}

	@Test
	void deleteAvailabilitySlotRejectsUnknownSlot() {
		when(domainLookupService.requireUser(mentorId)).thenReturn(mentor());
		UUID slotId = UUID.randomUUID();
		when(mentorAvailabilitySlotRepository.findById(slotId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> mentorProfileService.deleteAvailabilitySlot(mentorId, slotId))
				.isInstanceOf(ResourceNotFoundException.class);
	}

	@Test
	void findAvailabilityMarksSlotsWithExistingMeetingsAsBooked() {
		when(domainLookupService.requireUser(mentorId)).thenReturn(mentor());
		Instant startsAt = Instant.now().plus(1, ChronoUnit.DAYS);
		MentorAvailabilitySlot slot = new MentorAvailabilitySlot();
		slot.setId(UUID.randomUUID());
		slot.setMentor(mentor());
		slot.setStartsAt(startsAt);
		slot.setDurationMinutes(60);
		when(mentorAvailabilitySlotRepository.findByMentorIdAndStartsAtAfterOrderByStartsAtAsc(eq(mentorId), any()))
				.thenReturn(List.of(slot));
		when(meetingRepository.existsByMentorIdAndScheduledAtAndStatus(mentorId, startsAt, MeetingStatus.SCHEDULED))
				.thenReturn(true);

		List<MentorAvailabilitySlotResponse> result = mentorProfileService.findAvailability(mentorId);

		assertThat(result).hasSize(1);
		assertThat(result.get(0).booked()).isTrue();
	}

	private UpdateMentorProfileRequest updateRequest() {
		return new UpdateMentorProfileRequest(
				"Ürün Danışmanı", "Acme", "Bu hafta müsait", "~24 saat", "Online",
				"Mesajlaşma açık", "Yapılandırılmış", "Yüksek", "Kıdemli", "SaaS", "Seed",
				List.of("Büyüme", "Ürün Stratejisi"), 4,
				List.of(new MentorExperienceRequest(" Head of Product ", "Looply", "2022–2026", " ")));
	}

	@Test
	void updateProfileAppliesAllFieldsAndReplacesSpecialties() {
		when(domainLookupService.requireUser(mentorId)).thenReturn(mentor());
		MentorProfile profile = new MentorProfile();
		profile.setId(mentorId);
		profile.setUser(mentor());
		profile.getSpecialties().add("Eski Uzmanlık");
		when(mentorProfileRepository.findOneById(mentorId)).thenReturn(Optional.of(profile));
		when(mentorProfileRepository.saveAndFlush(profile)).thenReturn(profile);

		mentorProfileService.updateProfile(mentorId, updateRequest());

		assertThat(profile.getProfessionalTitle()).isEqualTo("Ürün Danışmanı");
		assertThat(profile.getCompany()).isEqualTo("Acme");
		assertThat(profile.getSpecialties()).containsExactly("Büyüme", "Ürün Stratejisi");
		assertThat(profile.getMenteeCapacity()).isEqualTo(4);
		assertThat(profile.getExperiences()).singleElement().satisfies(experience -> {
			assertThat(experience.getTitle()).isEqualTo("Head of Product");
			assertThat(experience.getPeriod()).isEqualTo("2022–2026");
			assertThat(experience.getSummary()).isNull();
		});
	}

	@Test
	void updateProfileKeepsCapacityAndExperienceWhenTheyAreNotSent() {
		when(domainLookupService.requireUser(mentorId)).thenReturn(mentor());
		MentorProfile profile = new MentorProfile();
		profile.setId(mentorId);
		profile.setUser(mentor());
		profile.setMenteeCapacity(6);
		profile.getExperiences().add(new MentorExperience("METU Stars Mentoru", null, null, null));
		when(mentorProfileRepository.findOneById(mentorId)).thenReturn(Optional.of(profile));
		when(mentorProfileRepository.saveAndFlush(profile)).thenReturn(profile);

		UpdateMentorProfileRequest request = updateRequest();
		mentorProfileService.updateProfile(mentorId, new UpdateMentorProfileRequest(
				request.professionalTitle(), request.company(), request.availabilityLabel(),
				request.responseTimeLabel(), request.meetingMode(), request.communicationPreference(),
				request.mentoringStyle(), request.fitLabel(), request.experienceLevel(), request.sectorExperience(),
				request.startupStages(), request.specialties(), null, null));

		assertThat(profile.getMenteeCapacity()).isEqualTo(6);
		assertThat(profile.getExperiences()).extracting(MentorExperience::getTitle)
				.containsExactly("METU Stars Mentoru");
	}

	@Test
	void findAllAddsMenteeCountsAndProgramsToEachMentor() {
		MentorProfile profile = new MentorProfile();
		profile.setId(mentorId);
		profile.setUser(mentor());
		profile.setMenteeCapacity(6);
		when(mentorProfileRepository.findAllByUserStatusOrderBySortOrderAsc(UserAccountStatus.APPROVED))
				.thenReturn(List.of(profile));
		when(mentorshipRepository.countOrganizationsByMentor(List.of(mentorId),
				List.of(MentorshipStatus.PLANNED, MentorshipStatus.ACTIVE, MentorshipStatus.PAUSED)))
				.thenReturn(List.of(count(4)));
		when(mentorshipRepository.countMentorshipsByMentor(List.of(mentorId), MentorshipStatus.COMPLETED))
				.thenReturn(List.of(count(28)));
		when(programMentorRepository.findProgramNamesByMentorIds(List.of(mentorId), ProgramMentorStatus.ACCEPTED))
				.thenReturn(List.of(program("METU Stars")));

		MentorProfileResponse result = mentorProfileService.findAll().getFirst();

		assertThat(result.menteeCapacity()).isEqualTo(6);
		assertThat(result.activeMenteeCount()).isEqualTo(4);
		assertThat(result.completedMentorshipCount()).isEqualTo(28);
		assertThat(result.programNames()).containsExactly("METU Stars");
	}

	private MentorCount count(long total) {
		return new MentorCount() {
			@Override
			public UUID getMentorId() {
				return mentorId;
			}

			@Override
			public Long getTotal() {
				return total;
			}
		};
	}

	private MentorProgramName program(String name) {
		return new MentorProgramName() {
			@Override
			public UUID getMentorId() {
				return mentorId;
			}

			@Override
			public String getProgramName() {
				return name;
			}
		};
	}

	@Test
	void updateProfileRejectsNonMentorUser() {
		AppUser mentee = new AppUser();
		mentee.setId(mentorId);
		mentee.setRole(UserRole.MENTEE);
		when(domainLookupService.requireUser(mentorId)).thenReturn(mentee);

		assertThatThrownBy(() -> mentorProfileService.updateProfile(mentorId, updateRequest()))
				.isInstanceOf(InvalidRequestException.class);
	}

	@Test
	void updateProfileRejectsMissingProfile() {
		when(domainLookupService.requireUser(mentorId)).thenReturn(mentor());
		when(mentorProfileRepository.findOneById(mentorId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> mentorProfileService.updateProfile(mentorId, updateRequest()))
				.isInstanceOf(ResourceNotFoundException.class);
	}

	@Test
	void findAll_onlyAsksForApprovedMentors() {
		when(mentorProfileRepository.findAllByUserStatusOrderBySortOrderAsc(UserAccountStatus.APPROVED))
				.thenReturn(List.of());

		assertThat(mentorProfileService.findAll()).isEmpty();

		verify(mentorProfileRepository).findAllByUserStatusOrderBySortOrderAsc(UserAccountStatus.APPROVED);
	}

	@Test
	void findAllExcept_alsoStaysWithinApprovedMentors() {
		when(mentorProfileRepository.findAllByUserStatusOrderBySortOrderAsc(UserAccountStatus.APPROVED))
				.thenReturn(List.of());

		assertThat(mentorProfileService.findAllExcept(mentorId)).isEmpty();

		verify(mentorProfileRepository).findAllByUserStatusOrderBySortOrderAsc(UserAccountStatus.APPROVED);
	}

	@Test
	void createAvailabilityRule_materialisesWeeklySlotsAcrossTheHorizon() {
		AppUser mentorUser = mentor();
		when(domainLookupService.requireUser(mentorId)).thenReturn(mentorUser);
		when(mentorAvailabilityRuleRepository.existsByMentorIdAndDayOfWeekAndStartTime(
				eq(mentorId), anyShort(), any(LocalTime.class))).thenReturn(false);
		when(mentorAvailabilityRuleRepository.saveAndFlush(any(MentorAvailabilityRule.class)))
				.thenAnswer(invocation -> {
					MentorAvailabilityRule saved = invocation.getArgument(0);
					saved.setId(UUID.randomUUID());
					return saved;
				});
		when(mentorAvailabilitySlotRepository.existsByMentorIdAndStartsAt(eq(mentorId), any(Instant.class)))
				.thenReturn(false);

		mentorProfileService.createAvailabilityRule(mentorId,
				new CreateMentorAvailabilityRuleRequest(DayOfWeek.TUESDAY,
						LocalTime.of(14, 0), LocalTime.of(17, 0), 60));

		ArgumentCaptor<List<MentorAvailabilitySlot>> captor = ArgumentCaptor.forClass(List.class);
		verify(mentorAvailabilitySlotRepository).saveAll(captor.capture());
		List<MentorAvailabilitySlot> slots = captor.getValue();

		// Asserted by shape rather than by count: the generator skips slots that
		// have already passed, so running this on a Tuesday afternoon legitimately
		// produces fewer slots in the current week than in later ones.
		ZoneId zone = ZoneId.of("Europe/Istanbul");
		assertThat(slots).isNotEmpty();
		assertThat(slots).allSatisfy(slot -> {
			ZonedDateTime local = slot.getStartsAt().atZone(zone);
			assertThat(local.getDayOfWeek()).isEqualTo(DayOfWeek.TUESDAY);
			assertThat(local.getHour()).isBetween(14, 16);
			assertThat(local.getMinute()).isZero();
			assertThat(slot.getDurationMinutes()).isEqualTo(60);
			assertThat(slot.getRule()).isNotNull();
			assertThat(slot.getStartsAt()).isAfter(Instant.now());
		});
		// Every Tuesday inside the horizon is covered, the current one possibly
		// only partly.
		assertThat(slots.stream().map(slot -> slot.getStartsAt().atZone(zone).toLocalDate()).distinct())
				.hasSizeGreaterThanOrEqualTo(8);
	}

	@Test
	void createAvailabilityRule_rejectsAWindowThatEndsBeforeItStarts() {
		when(domainLookupService.requireUser(mentorId)).thenReturn(mentor());

		assertThatThrownBy(() -> mentorProfileService.createAvailabilityRule(mentorId,
				new CreateMentorAvailabilityRuleRequest(DayOfWeek.TUESDAY,
						LocalTime.of(17, 0), LocalTime.of(14, 0), 60)))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("end after");
	}

	@Test
	void deleteAvailabilityRule_keepsSlotsAMenteeAlreadyBooked() {
		UUID ruleId = UUID.randomUUID();
		AppUser mentorUser = mentor();
		when(domainLookupService.requireUser(mentorId)).thenReturn(mentorUser);

		MentorAvailabilityRule rule = new MentorAvailabilityRule();
		rule.setId(ruleId);
		rule.setMentor(mentorUser);
		when(mentorAvailabilityRuleRepository.findByIdAndMentorId(ruleId, mentorId))
				.thenReturn(Optional.of(rule));

		MentorAvailabilitySlot free = slotAt(mentorUser, Instant.now().plusSeconds(7200));
		MentorAvailabilitySlot booked = slotAt(mentorUser, Instant.now().plusSeconds(10800));
		when(mentorAvailabilitySlotRepository.findByRuleIdAndStartsAtAfter(eq(ruleId), any(Instant.class)))
				.thenReturn(List.of(free, booked));
		when(meetingRepository.existsByMentorIdAndScheduledAtAndStatus(
				mentorId, free.getStartsAt(), MeetingStatus.SCHEDULED)).thenReturn(false);
		when(meetingRepository.existsByMentorIdAndScheduledAtAndStatus(
				mentorId, booked.getStartsAt(), MeetingStatus.SCHEDULED)).thenReturn(true);

		mentorProfileService.deleteAvailabilityRule(mentorId, ruleId);

		ArgumentCaptor<List<MentorAvailabilitySlot>> captor = ArgumentCaptor.forClass(List.class);
		verify(mentorAvailabilitySlotRepository).deleteAll(captor.capture());
		assertThat(captor.getValue()).containsExactly(free);
		verify(mentorAvailabilityRuleRepository).delete(rule);
	}

	private MentorAvailabilitySlot slotAt(AppUser mentorUser, Instant startsAt) {
		MentorAvailabilitySlot slot = new MentorAvailabilitySlot();
		slot.setId(UUID.randomUUID());
		slot.setMentor(mentorUser);
		slot.setStartsAt(startsAt);
		slot.setDurationMinutes(60);
		return slot;
	}
}
