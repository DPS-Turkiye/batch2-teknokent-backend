package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dps.teknokent.domain.MeetingFrequency;
import com.dps.teknokent.domain.MeetingStatus;
import com.dps.teknokent.domain.MentorLanguage;
import com.dps.teknokent.domain.MentorshipStatus;
import com.dps.teknokent.domain.PeerExperienceKind;
import com.dps.teknokent.domain.PrepFileRequirement;
import com.dps.teknokent.domain.ProgramMentorStatus;
import com.dps.teknokent.domain.ProgramStatus;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.CreateMentorAvailabilityRuleRequest;
import com.dps.teknokent.dto.requests.CreateMentorAvailabilitySlotRequest;
import com.dps.teknokent.dto.requests.MentorAvailabilityExceptionRequest;
import com.dps.teknokent.dto.requests.MentorEducationRequest;
import com.dps.teknokent.dto.requests.MentorExperienceRequest;
import com.dps.teknokent.dto.requests.MentorProgramRoleRequest;
import com.dps.teknokent.dto.requests.StudentProfileRequest;
import com.dps.teknokent.dto.requests.UpdateMentorAvailabilitySettingsRequest;
import com.dps.teknokent.dto.requests.UpdateMentorProfileRequest;
import com.dps.teknokent.dto.responses.MentorAvailabilitySettingsResponse;
import com.dps.teknokent.dto.responses.MentorAvailabilitySlotResponse;
import com.dps.teknokent.dto.responses.MentorProfileResponse;
import com.dps.teknokent.dto.responses.MentorProgramResponse;
import com.dps.teknokent.dto.responses.StudentProfileResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.MentorAvailabilityException;
import com.dps.teknokent.entity.MentorAvailabilityRule;
import com.dps.teknokent.entity.MentorAvailabilitySlot;
import com.dps.teknokent.entity.MentorEducation;
import com.dps.teknokent.entity.MentorExperience;
import com.dps.teknokent.entity.MentorProfile;
import com.dps.teknokent.entity.ProgramMentor;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.MeetingRepository;
import com.dps.teknokent.repository.MentorAvailabilityExceptionRepository;
import com.dps.teknokent.repository.MentorAvailabilityRuleRepository;
import com.dps.teknokent.repository.MentorAvailabilitySlotRepository;
import com.dps.teknokent.repository.MentorCount;
import com.dps.teknokent.repository.MentorProfileRepository;
import com.dps.teknokent.repository.MentorProgramName;
import com.dps.teknokent.repository.MentorshipRepository;
import com.dps.teknokent.repository.ProgramMentorRepository;

@ExtendWith(MockitoExtension.class)
class MentorProfileServiceTest {

	@Mock
	private MentorProfileRepository mentorProfileRepository;
	@Mock
	private MentorAvailabilityRuleRepository mentorAvailabilityRuleRepository;
	@Mock
	private MentorAvailabilityExceptionRepository mentorAvailabilityExceptionRepository;
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
	@Mock
	private MenteeProgramService menteeProgramService;

	private MentorProfileService mentorProfileService;

	private final UUID mentorId = UUID.randomUUID();
	private final UUID programId = UUID.randomUUID();

	@BeforeEach
	void setUp() {
		mentorProfileService = new MentorProfileService(
				mentorProfileRepository, mentorAvailabilityRuleRepository, mentorAvailabilityExceptionRepository,
				mentorAvailabilitySlotRepository,
				meetingRepository, domainLookupService, mentorshipRepository, programMentorRepository,
				menteeProgramService);
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
		CreateMentorAvailabilitySlotRequest request = new CreateMentorAvailabilitySlotRequest(
				Instant.now().minus(1, ChronoUnit.DAYS), 60);

		assertThatThrownBy(() -> mentorProfileService.createAvailabilitySlot(mentorId, request))
				.isInstanceOf(InvalidRequestException.class);
	}

	@Test
	void createAvailabilitySlotRejectsNonMentorUser() {
		AppUser mentee = new AppUser();
		mentee.setId(mentorId);
		mentee.setRole(UserRole.MENTEE);
		when(domainLookupService.requireUser(mentorId)).thenReturn(mentee);
		CreateMentorAvailabilitySlotRequest request = new CreateMentorAvailabilitySlotRequest(
				Instant.now().plus(1, ChronoUnit.DAYS), 60);

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
	void aPeerMentorsCardSentBackThroughTheMentorFormKeepsItsKindAndMonths() {
		when(domainLookupService.requireUser(mentorId)).thenReturn(mentor());
		MentorProfile profile = new MentorProfile();
		profile.setId(mentorId);
		profile.setUser(mentor());
		profile.getExperiences().add(new MentorExperience("Head of Product", "Looply", null, null,
				PeerExperienceKind.JOB, LocalDate.of(2022, 1, 1), null));
		profile.getExperiences().add(new MentorExperience("Kulüp başkanı", null, null, null,
				PeerExperienceKind.CLUB, LocalDate.of(2023, 9, 1), LocalDate.of(2024, 6, 1)));
		when(mentorProfileRepository.findOneById(mentorId)).thenReturn(Optional.of(profile));
		when(mentorProfileRepository.saveAndFlush(profile)).thenReturn(profile);

		UpdateMentorProfileRequest request = updateRequest();
		mentorProfileService.updateProfile(mentorId, new UpdateMentorProfileRequest(
				request.professionalTitle(), request.company(), request.availabilityLabel(),
				request.responseTimeLabel(), request.meetingMode(), request.communicationPreference(),
				request.mentoringStyle(), request.fitLabel(), request.experienceLevel(), request.sectorExperience(),
				request.startupStages(), request.specialties(), null, List.of(
						new MentorExperienceRequest(" Head of Product ", "Looply", null, null),
						new MentorExperienceRequest("Gönüllü", null, null, null))));

		assertThat(profile.getExperiences()).satisfiesExactly(
				kept -> {
					assertThat(kept.getKind()).isEqualTo(PeerExperienceKind.JOB);
					assertThat(kept.getStartedOn()).isEqualTo(LocalDate.of(2022, 1, 1));
				},
				replaced -> {
					assertThat(replaced.getTitle()).isEqualTo("Gönüllü");
					assertThat(replaced.getKind()).isNull();
					assertThat(replaced.getStartedOn()).isNull();
				});
	}

	@Test
	void updateProfileReplacesOfferingsOnlyWhenSent() {
		when(domainLookupService.requireUser(mentorId)).thenReturn(mentor());
		MentorProfile profile = new MentorProfile();
		profile.setId(mentorId);
		profile.setUser(mentor());
		profile.getOfferings().add("Eski madde");
		when(mentorProfileRepository.findOneById(mentorId)).thenReturn(Optional.of(profile));
		when(mentorProfileRepository.saveAndFlush(profile)).thenReturn(profile);

		mentorProfileService.updateProfile(mentorId, updateRequest());
		assertThat(profile.getOfferings()).containsExactly("Eski madde");

		UpdateMentorProfileRequest request = updateRequest();
		var response = mentorProfileService.updateProfile(mentorId, new UpdateMentorProfileRequest(
				request.professionalTitle(), request.company(), request.availabilityLabel(),
				request.responseTimeLabel(), request.meetingMode(), request.communicationPreference(),
				request.mentoringStyle(), request.fitLabel(), request.experienceLevel(), request.sectorExperience(),
				request.startupStages(), request.specialties(), null, null, null, null, null, null, null,
				List.of(" Değer önerisini netleştirme ", "İlk kullanıcı akışını kurgulama")));

		assertThat(profile.getOfferings())
				.containsExactly("Değer önerisini netleştirme", "İlk kullanıcı akışını kurgulama");
		assertThat(response.offerings()).hasSize(2);
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
		assertThat(result.programs()).containsExactly(
				new MentorProgramResponse(programId, "METU Stars", ProgramStatus.COMPLETED, "Design mentor"));
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
			public UUID getProgramId() {
				return programId;
			}

			@Override
			public String getProgramName() {
				return name;
			}

			@Override
			public ProgramStatus getProgramStatus() {
				return ProgramStatus.COMPLETED;
			}

			@Override
			public String getRole() {
				return "Design mentor";
			}
		};
	}

	private UpdateMentorProfileRequest requestWithPreferences(List<MentorProgramRoleRequest> programRoles) {
		UpdateMentorProfileRequest request = updateRequest();
		return new UpdateMentorProfileRequest(
				request.professionalTitle(), request.company(), request.availabilityLabel(),
				request.responseTimeLabel(), request.meetingMode(), request.communicationPreference(),
				request.mentoringStyle(), request.fitLabel(), request.experienceLevel(), request.sectorExperience(),
				request.startupStages(), request.specialties(), null, null, null,
				MeetingFrequency.BIWEEKLY, PrepFileRequirement.REQUIRED,
				List.of(MentorLanguage.TURKISH, MentorLanguage.ENGLISH, MentorLanguage.TURKISH), programRoles, null);
	}

	private MentorProfile editableProfile() {
		when(domainLookupService.requireUser(mentorId)).thenReturn(mentor());
		MentorProfile profile = new MentorProfile();
		profile.setId(mentorId);
		profile.setUser(mentor());
		profile.getLanguages().add(MentorLanguage.GERMAN);
		when(mentorProfileRepository.findOneById(mentorId)).thenReturn(Optional.of(profile));
		return profile;
	}

	private UpdateMentorProfileRequest requestWithStudent(StudentProfileRequest student) {
		UpdateMentorProfileRequest request = updateRequest();
		return new UpdateMentorProfileRequest(
				request.professionalTitle(), request.company(), request.availabilityLabel(),
				request.responseTimeLabel(), request.meetingMode(), request.communicationPreference(),
				request.mentoringStyle(), request.fitLabel(), request.experienceLevel(), request.sectorExperience(),
				request.startupStages(), request.specialties(), null, null, null, null, null, null, null, null,
				student);
	}

	private UpdateMentorProfileRequest requestWithEducation(List<MentorEducationRequest> educations) {
		UpdateMentorProfileRequest request = updateRequest();
		return new UpdateMentorProfileRequest(
				request.professionalTitle(), request.company(), request.availabilityLabel(),
				request.responseTimeLabel(), request.meetingMode(), request.communicationPreference(),
				request.mentoringStyle(), request.fitLabel(), request.experienceLevel(), request.sectorExperience(),
				request.startupStages(), request.specialties(), null, null, null, null, null, null, null, null,
				null, educations);
	}

	@Test
	void updateProfileStoresTheSchoolsInOrderAndReturnsThem() {
		MentorProfile profile = editableProfile();
		when(mentorProfileRepository.saveAndFlush(profile)).thenReturn(profile);

		var response = mentorProfileService.updateProfile(mentorId, requestWithEducation(List.of(
				new MentorEducationRequest(" ODTÜ ", " Lisans, Bilgisayar Mühendisliği ", "2022 – 2026"),
				new MentorEducationRequest("TU Delft", "Erasmus", "  "))));

		assertThat(profile.getEducations()).extracting("school", "degree", "period").containsExactly(
				tuple("ODTÜ", "Lisans, Bilgisayar Mühendisliği", "2022 – 2026"), tuple("TU Delft", "Erasmus", null));
		assertThat(response.educations()).extracting("school").containsExactly("ODTÜ", "TU Delft");
	}

	@Test
	void updateProfileWithoutSchoolsKeepsTheStoredOnes() {
		MentorProfile profile = editableProfile();
		profile.getEducations().add(new MentorEducation("ODTÜ", "Lisans", null));
		when(mentorProfileRepository.saveAndFlush(profile)).thenReturn(profile);

		mentorProfileService.updateProfile(mentorId, requestWithEducation(null));

		assertThat(profile.getEducations()).extracting("school").containsExactly("ODTÜ");
	}

	@Test
	void updateProfileStoresAStudentMentorsStudies() {
		MentorProfile profile = editableProfile();
		when(mentorProfileRepository.saveAndFlush(profile)).thenReturn(profile);

		var response = mentorProfileService.updateProfile(mentorId, requestWithStudent(new StudentProfileRequest(
				" Bilgisayar Mühendisliği ", "4. sınıf", List.of(" CENG 140 ", "MATH 119"),
				List.of("Kampüs etkinlik uygulaması"), "  TU Delft, 2025 güz ")));

		assertThat(profile.getDepartment()).isEqualTo("Bilgisayar Mühendisliği");
		assertThat(profile.getStudyYear()).isEqualTo("4. sınıf");
		assertThat(profile.getStrongCourses()).containsExactly("CENG 140", "MATH 119");
		assertThat(profile.getProjects()).containsExactly("Kampüs etkinlik uygulaması");
		assertThat(profile.getErasmus()).isEqualTo("TU Delft, 2025 güz");
		assertThat(response.studentProfile().department()).isEqualTo("Bilgisayar Mühendisliği");
		assertThat(response.studentProfile().strongCourses()).containsExactly("CENG 140", "MATH 119");
	}

	@Test
	void updateProfileWithoutAStudentProfileKeepsTheStoredOne() {
		MentorProfile profile = editableProfile();
		profile.setDepartment("Endüstri Mühendisliği");
		profile.getStrongCourses().add("IE 251");
		when(mentorProfileRepository.saveAndFlush(profile)).thenReturn(profile);

		mentorProfileService.updateProfile(mentorId, requestWithStudent(null));

		assertThat(profile.getDepartment()).isEqualTo("Endüstri Mühendisliği");
		assertThat(profile.getStrongCourses()).containsExactly("IE 251");
	}

	@Test
	void aMentorWhoIsNotAStudentHasNoStudentProfile() {
		MentorProfile profile = new MentorProfile();
		profile.setId(mentorId);
		profile.setUser(mentor());

		assertThat(StudentProfileResponse.fromEntity(profile)).isNull();
		assertThat(MentorProfileResponse.fromEntity(profile).studentProfile()).isNull();
	}

	@Test
	void updateProfileStoresMeetingPreferencesLanguagesAndProgramRoles() {
		MentorProfile profile = editableProfile();
		when(mentorProfileRepository.saveAndFlush(profile)).thenReturn(profile);
		ProgramMentor membership = new ProgramMentor();
		membership.setStatus(ProgramMentorStatus.ACCEPTED);
		when(programMentorRepository.findByProgramIdAndMentorId(programId, mentorId))
				.thenReturn(Optional.of(membership));

		mentorProfileService.updateProfile(mentorId,
				requestWithPreferences(List.of(new MentorProgramRoleRequest(programId, "  Design mentor "))));

		assertThat(profile.getMeetingFrequency()).isEqualTo(MeetingFrequency.BIWEEKLY);
		assertThat(profile.getPrepFileRequirement()).isEqualTo(PrepFileRequirement.REQUIRED);
		assertThat(profile.getLanguages()).containsExactly(MentorLanguage.TURKISH, MentorLanguage.ENGLISH);
		assertThat(membership.getRole()).isEqualTo("Design mentor");
		verify(programMentorRepository).save(membership);
	}

	@Test
	void updateProfileClearsABlankProgramRole() {
		MentorProfile profile = editableProfile();
		when(mentorProfileRepository.saveAndFlush(profile)).thenReturn(profile);
		ProgramMentor membership = new ProgramMentor();
		membership.setStatus(ProgramMentorStatus.ACCEPTED);
		membership.setRole("Eski rol");
		when(programMentorRepository.findByProgramIdAndMentorId(programId, mentorId))
				.thenReturn(Optional.of(membership));

		mentorProfileService.updateProfile(mentorId,
				requestWithPreferences(List.of(new MentorProgramRoleRequest(programId, "   "))));

		assertThat(membership.getRole()).isNull();
	}

	@Test
	void updateProfileRefusesARoleInAProgramTheMentorHasNotJoined() {
		editableProfile();
		ProgramMentor pending = new ProgramMentor();
		pending.setStatus(ProgramMentorStatus.PENDING);
		when(programMentorRepository.findByProgramIdAndMentorId(programId, mentorId))
				.thenReturn(Optional.of(pending));

		assertThatThrownBy(() -> mentorProfileService.updateProfile(mentorId,
				requestWithPreferences(List.of(new MentorProgramRoleRequest(programId, "Design mentor")))))
				.isInstanceOf(InvalidRequestException.class);
		verify(programMentorRepository, never()).save(any());
	}

	@Test
	void updateProfileKeepsPreferencesAndLanguagesWhenTheyAreNotSent() {
		MentorProfile profile = editableProfile();
		profile.setMeetingFrequency(MeetingFrequency.MONTHLY);
		profile.setPrepFileRequirement(PrepFileRequirement.OPTIONAL);
		when(mentorProfileRepository.saveAndFlush(profile)).thenReturn(profile);

		mentorProfileService.updateProfile(mentorId, updateRequest());

		assertThat(profile.getMeetingFrequency()).isEqualTo(MeetingFrequency.MONTHLY);
		assertThat(profile.getPrepFileRequirement()).isEqualTo(PrepFileRequirement.OPTIONAL);
		assertThat(profile.getLanguages()).containsExactly(MentorLanguage.GERMAN);
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

		ArgumentCaptor<List<MentorAvailabilitySlot>> captor = ArgumentCaptor.captor();
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

		ArgumentCaptor<List<MentorAvailabilitySlot>> captor = ArgumentCaptor.captor();
		verify(mentorAvailabilitySlotRepository).deleteAll(captor.capture());
		assertThat(captor.getValue()).containsExactly(free);
		verify(mentorAvailabilityRuleRepository).delete(rule);
	}

	@Test
	void findAvailabilitySettings_leavesAnUnsetCapacityEmpty() {
		when(mentorProfileRepository.findOneById(mentorId)).thenReturn(Optional.of(profile()));
		when(mentorAvailabilityRuleRepository.findByMentorIdOrderByDayOfWeekAscStartTimeAsc(mentorId))
				.thenReturn(List.of());
		when(mentorAvailabilityExceptionRepository.findByMentorIdOrderByStartsOnAsc(mentorId)).thenReturn(List.of());

		MentorAvailabilitySettingsResponse settings = mentorProfileService.findAvailabilitySettings(mentorId);

		assertThat(settings.menteeCapacity()).isNull();
		assertThat(settings.defaultMeetingDurationMinutes()).isEqualTo(45);
	}

	@Test
	void updateAvailabilitySettings_savesTheDatesAndDefaultsAndKeepsAnOmittedCapacity() {
		MentorProfile profile = profile();
		profile.setMenteeCapacity(4);
		when(domainLookupService.requireUser(mentorId)).thenReturn(mentor());
		when(mentorProfileRepository.findOneById(mentorId)).thenReturn(Optional.of(profile));
		when(mentorAvailabilityRuleRepository.findByMentorIdOrderByDayOfWeekAscStartTimeAsc(mentorId))
				.thenReturn(List.of());
		when(mentorAvailabilityExceptionRepository.findByMentorId(mentorId)).thenReturn(List.of());
		when(mentorAvailabilityExceptionRepository.findByMentorIdOrderByStartsOnAsc(mentorId)).thenReturn(List.of());
		when(mentorAvailabilitySlotRepository.findByMentorIdAndStartsAtAfterOrderByStartsAtAsc(eq(mentorId),
				any(Instant.class))).thenReturn(List.of());

		mentorProfileService.updateAvailabilitySettings(mentorId, new UpdateMentorAvailabilitySettingsRequest(
				List.of(),
				List.of(new MentorAvailabilityExceptionRequest(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 3),
						"  İzin ")),
				null, 60, 10));

		ArgumentCaptor<MentorAvailabilityException> saved = ArgumentCaptor.forClass(MentorAvailabilityException.class);
		verify(mentorAvailabilityExceptionRepository).save(saved.capture());
		assertThat(saved.getValue().getReason()).isEqualTo("İzin");
		assertThat(profile.getMenteeCapacity()).isEqualTo(4);
		assertThat(profile.getDefaultMeetingDurationMinutes()).isEqualTo(60);
		assertThat(profile.getMeetingBufferMinutes()).isEqualTo(10);
	}

	@Test
	void updateAvailabilitySettings_rejectsTheSameUnavailableDatesTwice() {
		when(domainLookupService.requireUser(mentorId)).thenReturn(mentor());
		when(mentorProfileRepository.findOneById(mentorId)).thenReturn(Optional.of(profile()));
		MentorAvailabilityExceptionRequest dates = new MentorAvailabilityExceptionRequest(LocalDate.of(2026, 10, 1),
				LocalDate.of(2026, 10, 3), null);

		assertThatThrownBy(() -> mentorProfileService.updateAvailabilitySettings(mentorId,
				new UpdateMentorAvailabilitySettingsRequest(List.of(), List.of(dates, dates), 5, 45, 15)))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("Duplicate");
		verify(mentorAvailabilityExceptionRepository, never()).deleteAll(any());
	}

	@Test
	void updateAvailabilitySettings_rejectsOverlappingRangesOnOneDay() {
		when(domainLookupService.requireUser(mentorId)).thenReturn(mentor());
		when(mentorProfileRepository.findOneById(mentorId)).thenReturn(Optional.of(profile()));

		assertThatThrownBy(() -> mentorProfileService.updateAvailabilitySettings(mentorId,
				new UpdateMentorAvailabilitySettingsRequest(List.of(
						new CreateMentorAvailabilityRuleRequest(DayOfWeek.TUESDAY, LocalTime.of(10, 0),
								LocalTime.of(13, 0), 45),
						new CreateMentorAvailabilityRuleRequest(DayOfWeek.TUESDAY, LocalTime.of(12, 0),
								LocalTime.of(14, 0), 45)),
						List.of(), 5, 45, 15)))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("overlap");
		verify(mentorAvailabilityRuleRepository, never()).delete(any());
	}

	// --- Directory lookups ---

	private MentorProfile approved(UUID id, String fullName) {
		AppUser user = new AppUser();
		user.setId(id);
		user.setRole(UserRole.MENTOR);
		user.setFullName(fullName);
		MentorProfile profile = new MentorProfile();
		profile.setId(id);
		profile.setUser(user);
		return profile;
	}

	@Test
	void findAmongExcept_keepsOnlyTheGivenMentorsAndLeavesOutTheExcludedOne() {
		UUID inPool = UUID.randomUUID();
		UUID outsidePool = UUID.randomUUID();
		when(mentorProfileRepository.findAllByUserStatusOrderBySortOrderAsc(UserAccountStatus.APPROVED))
				.thenReturn(List.of(approved(mentorId, "Kemal"), approved(inPool, "Selin"),
						approved(outsidePool, "Mert")));

		assertThat(mentorProfileService.findAmongExcept(Set.of(mentorId, inPool), mentorId))
				.extracting(MentorProfileResponse::id)
				.containsExactly(inPool);
	}

	@Test
	void findAllExcept_leavesOutOnlyTheExcludedMentor() {
		UUID other = UUID.randomUUID();
		when(mentorProfileRepository.findAllByUserStatusOrderBySortOrderAsc(UserAccountStatus.APPROVED))
				.thenReturn(List.of(approved(mentorId, "Kemal"), approved(other, "Selin")));

		assertThat(mentorProfileService.findAllExcept(mentorId)).extracting(MentorProfileResponse::fullName)
				.containsExactly("Selin");
	}

	@Test
	void findById_returnsTheMentorWithTheirMenteeCounts() {
		when(mentorProfileRepository.findOneById(mentorId)).thenReturn(Optional.of(approved(mentorId, "Kemal")));
		when(mentorshipRepository.countOrganizationsByMentor(List.of(mentorId),
				List.of(MentorshipStatus.PLANNED, MentorshipStatus.ACTIVE, MentorshipStatus.PAUSED)))
				.thenReturn(List.of(count(2)));

		MentorProfileResponse result = mentorProfileService.findById(mentorId);

		assertThat(result.fullName()).isEqualTo("Kemal");
		assertThat(result.activeMenteeCount()).isEqualTo(2);
		assertThat(result.completedMentorshipCount()).isZero();
		assertThat(result.programs()).isEmpty();
	}

	@Test
	void findById_reportsAMentorWithoutAProfile() {
		when(mentorProfileRepository.findOneById(mentorId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> mentorProfileService.findById(mentorId))
				.isInstanceOf(ResourceNotFoundException.class)
				.hasMessageContaining(mentorId.toString());
	}

	// --- Weekly availability: single rules ---

	private static CreateMentorAvailabilityRuleRequest range(DayOfWeek day, int fromHour, int toHour) {
		return new CreateMentorAvailabilityRuleRequest(day, LocalTime.of(fromHour, 0), LocalTime.of(toHour, 0), 60);
	}

	@Test
	void findAvailabilityRules_listsAMentorsWeeklyRanges() {
		when(domainLookupService.requireUser(mentorId)).thenReturn(mentor());
		MentorAvailabilityRule rule = new MentorAvailabilityRule();
		rule.setId(UUID.randomUUID());
		rule.setDay(DayOfWeek.THURSDAY);
		rule.setStartTime(LocalTime.of(9, 30));
		rule.setEndTime(LocalTime.of(11, 0));
		rule.setSlotMinutes(45);
		when(mentorAvailabilityRuleRepository.findByMentorIdOrderByDayOfWeekAscStartTimeAsc(mentorId))
				.thenReturn(List.of(rule));

		assertThat(mentorProfileService.findAvailabilityRules(mentorId)).singleElement().satisfies(found -> {
			assertThat(found.id()).isEqualTo(rule.getId());
			assertThat(found.dayOfWeek()).isEqualTo(DayOfWeek.THURSDAY);
			assertThat(found.startTime()).isEqualTo(LocalTime.of(9, 30));
			assertThat(found.endTime()).isEqualTo(LocalTime.of(11, 0));
			assertThat(found.slotMinutes()).isEqualTo(45);
		});
	}

	@Test
	void availabilityRulesBelongOnlyToMentors() {
		AppUser mentee = new AppUser();
		mentee.setId(mentorId);
		mentee.setRole(UserRole.MENTEE);
		when(domainLookupService.requireUser(mentorId)).thenReturn(mentee);

		assertThatThrownBy(() -> mentorProfileService.findAvailabilityRules(mentorId))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("not a mentor");
		assertThatThrownBy(() -> mentorProfileService.createAvailabilityRule(mentorId,
				range(DayOfWeek.TUESDAY, 14, 17)))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("not a mentor");
		assertThatThrownBy(() -> mentorProfileService.deleteAvailabilityRule(mentorId, UUID.randomUUID()))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("not a mentor");
		verify(mentorAvailabilityRuleRepository, never()).saveAndFlush(any());
		verify(mentorAvailabilityRuleRepository, never()).delete(any());
	}

	@Test
	void createAvailabilityRule_rejectsAWindowThatEndsWhenItStarts() {
		when(domainLookupService.requireUser(mentorId)).thenReturn(mentor());

		assertThatThrownBy(() -> mentorProfileService.createAvailabilityRule(mentorId,
				range(DayOfWeek.TUESDAY, 14, 14)))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("end after");
		verify(mentorAvailabilityRuleRepository, never()).saveAndFlush(any());
	}

	@Test
	void createAvailabilityRule_rejectsASecondRuleStartingAtTheSameTimeThatDay() {
		when(domainLookupService.requireUser(mentorId)).thenReturn(mentor());
		when(mentorAvailabilityRuleRepository.existsByMentorIdAndDayOfWeekAndStartTime(mentorId, (short) 2,
				LocalTime.of(14, 0))).thenReturn(true);

		assertThatThrownBy(() -> mentorProfileService.createAvailabilityRule(mentorId,
				range(DayOfWeek.TUESDAY, 14, 17)))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("already starts at this time");
		verify(mentorAvailabilityRuleRepository, never()).saveAndFlush(any());
		verify(mentorAvailabilitySlotRepository, never()).saveAll(any());
	}

	@Test
	void createAvailabilityRule_makesHourSlotsByDefaultAndSkipsUnavailableDaysAndTimesAlreadyOffered() {
		ZoneId zone = ZoneId.of("Europe/Istanbul");
		// Three days ahead, so this weekday's first date is the same even if midnight passes during the test.
		LocalDate firstWeek = LocalDate.now(zone).plusDays(3);
		LocalDate secondWeek = firstWeek.plusWeeks(1);
		LocalDate thirdWeek = firstWeek.plusWeeks(2);
		Instant alreadyOffered = secondWeek.atTime(10, 0).atZone(zone).toInstant();
		when(domainLookupService.requireUser(mentorId)).thenReturn(mentor());
		when(mentorAvailabilityRuleRepository.saveAndFlush(any(MentorAvailabilityRule.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));
		when(mentorAvailabilityExceptionRepository.existsByMentorIdAndStartsOnLessThanEqualAndEndsOnGreaterThanEqual(
				eq(mentorId), any(LocalDate.class), any(LocalDate.class)))
				.thenAnswer(invocation -> firstWeek.equals(invocation.getArgument(1)));
		when(mentorAvailabilitySlotRepository.existsByMentorIdAndStartsAt(eq(mentorId), any(Instant.class)))
				.thenAnswer(invocation -> alreadyOffered.equals(invocation.getArgument(1)));

		var rule = mentorProfileService.createAvailabilityRule(mentorId, new CreateMentorAvailabilityRuleRequest(
				firstWeek.getDayOfWeek(), LocalTime.of(10, 0), LocalTime.of(12, 0), null));

		assertThat(rule.slotMinutes()).isEqualTo(60);
		ArgumentCaptor<List<MentorAvailabilitySlot>> captor = ArgumentCaptor.captor();
		verify(mentorAvailabilitySlotRepository).saveAll(captor.capture());
		List<ZonedDateTime> starts = captor.getValue().stream()
				.map(slot -> slot.getStartsAt().atZone(zone))
				.toList();
		assertThat(starts).noneMatch(start -> start.toLocalDate().equals(firstWeek));
		assertThat(starts).filteredOn(start -> start.toLocalDate().equals(secondWeek))
				.extracting(ZonedDateTime::toLocalTime)
				.containsExactly(LocalTime.of(11, 0));
		assertThat(starts).filteredOn(start -> start.toLocalDate().equals(thirdWeek))
				.extracting(ZonedDateTime::toLocalTime)
				.containsExactly(LocalTime.of(10, 0), LocalTime.of(11, 0));
		assertThat(captor.getValue()).allSatisfy(slot -> assertThat(slot.getDurationMinutes()).isEqualTo(60));
	}

	@Test
	void deleteAvailabilityRule_reportsARuleThatIsNotThisMentors() {
		UUID ruleId = UUID.randomUUID();
		when(domainLookupService.requireUser(mentorId)).thenReturn(mentor());
		when(mentorAvailabilityRuleRepository.findByIdAndMentorId(ruleId, mentorId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> mentorProfileService.deleteAvailabilityRule(mentorId, ruleId))
				.isInstanceOf(ResourceNotFoundException.class)
				.hasMessageContaining(ruleId.toString());
		verify(mentorAvailabilityRuleRepository, never()).delete(any());
	}

	@Test
	void createAvailabilitySlotKeepsTheDurationThatWasSent() {
		when(domainLookupService.requireUser(mentorId)).thenReturn(mentor());
		Instant startsAt = Instant.now().plus(1, ChronoUnit.DAYS);
		when(mentorAvailabilitySlotRepository.saveAndFlush(any()))
				.thenAnswer(invocation -> invocation.getArgument(0));

		MentorAvailabilitySlotResponse response = mentorProfileService.createAvailabilitySlot(
				mentorId, new CreateMentorAvailabilitySlotRequest(startsAt, 30));

		assertThat(response.startsAt()).isEqualTo(startsAt);
		assertThat(response.durationMinutes()).isEqualTo(30);
	}

	// --- Weekly availability: the whole settings form ---

	private static UpdateMentorAvailabilitySettingsRequest settingsWith(
			List<CreateMentorAvailabilityRuleRequest> rules, List<MentorAvailabilityExceptionRequest> exceptions) {
		return new UpdateMentorAvailabilitySettingsRequest(rules, exceptions, null, 45, 15);
	}

	private void assertSettingsAreRefused(UpdateMentorAvailabilitySettingsRequest request, String reason) {
		assertThatThrownBy(() -> mentorProfileService.updateAvailabilitySettings(mentorId, request))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining(reason);
		// Refused before anything stored is touched.
		verify(mentorAvailabilityRuleRepository, never()).findByMentorIdOrderByDayOfWeekAscStartTimeAsc(any());
		verify(mentorAvailabilityExceptionRepository, never()).deleteAll(any());
		verify(mentorProfileRepository, never()).save(any());
	}

	@Test
	void updateAvailabilitySettings_rejectsARangeThatDoesNotEndAfterItStarts() {
		when(domainLookupService.requireUser(mentorId)).thenReturn(mentor());
		when(mentorProfileRepository.findOneById(mentorId)).thenReturn(Optional.of(profile()));

		assertSettingsAreRefused(settingsWith(List.of(range(DayOfWeek.MONDAY, 9, 11),
				range(DayOfWeek.TUESDAY, 14, 14)), List.of()), "must end after it starts");
		assertSettingsAreRefused(settingsWith(List.of(range(DayOfWeek.TUESDAY, 17, 14)), List.of()),
				"must end after it starts");
	}

	@Test
	void updateAvailabilitySettings_rejectsTwoRangesStartingAtTheSameTimeOnOneDay() {
		when(domainLookupService.requireUser(mentorId)).thenReturn(mentor());
		when(mentorProfileRepository.findOneById(mentorId)).thenReturn(Optional.of(profile()));

		assertSettingsAreRefused(settingsWith(List.of(range(DayOfWeek.TUESDAY, 10, 12),
				range(DayOfWeek.WEDNESDAY, 10, 12), range(DayOfWeek.TUESDAY, 10, 11)), List.of()),
				"Duplicate weekly availability rule");
	}

	@Test
	void updateAvailabilitySettings_rejectsOverlapWhicheverRangeComesFirstOrContainsTheOther() {
		when(domainLookupService.requireUser(mentorId)).thenReturn(mentor());
		when(mentorProfileRepository.findOneById(mentorId)).thenReturn(Optional.of(profile()));

		assertSettingsAreRefused(settingsWith(List.of(range(DayOfWeek.TUESDAY, 12, 14),
				range(DayOfWeek.TUESDAY, 10, 13)), List.of()), "cannot overlap");
		assertSettingsAreRefused(settingsWith(List.of(range(DayOfWeek.TUESDAY, 9, 17),
				range(DayOfWeek.TUESDAY, 11, 12)), List.of()), "cannot overlap");
		assertSettingsAreRefused(settingsWith(List.of(range(DayOfWeek.MONDAY, 9, 10),
				range(DayOfWeek.TUESDAY, 11, 12), range(DayOfWeek.TUESDAY, 9, 17)), List.of()), "cannot overlap");
	}

	@Test
	void updateAvailabilitySettings_acceptsBackToBackRangesAndTheSameHoursOnAnotherDay() {
		when(domainLookupService.requireUser(mentorId)).thenReturn(mentor());
		when(mentorProfileRepository.findOneById(mentorId)).thenReturn(Optional.of(profile()));
		when(mentorAvailabilityRuleRepository.saveAndFlush(any(MentorAvailabilityRule.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));

		mentorProfileService.updateAvailabilitySettings(mentorId, settingsWith(List.of(
				range(DayOfWeek.TUESDAY, 12, 14), range(DayOfWeek.TUESDAY, 10, 12),
				range(DayOfWeek.WEDNESDAY, 10, 13), range(DayOfWeek.WEDNESDAY, 13, 15)), List.of()));

		ArgumentCaptor<MentorAvailabilityRule> saved = ArgumentCaptor.forClass(MentorAvailabilityRule.class);
		verify(mentorAvailabilityRuleRepository, times(4)).saveAndFlush(saved.capture());
		assertThat(saved.getAllValues())
				.extracting(MentorAvailabilityRule::day, MentorAvailabilityRule::getStartTime,
						MentorAvailabilityRule::getEndTime)
				.containsExactly(
						tuple(DayOfWeek.TUESDAY, LocalTime.of(12, 0), LocalTime.of(14, 0)),
						tuple(DayOfWeek.TUESDAY, LocalTime.of(10, 0), LocalTime.of(12, 0)),
						tuple(DayOfWeek.WEDNESDAY, LocalTime.of(10, 0), LocalTime.of(13, 0)),
						tuple(DayOfWeek.WEDNESDAY, LocalTime.of(13, 0), LocalTime.of(15, 0)));
	}

	@Test
	void updateAvailabilitySettings_rejectsUnavailableDatesThatEndBeforeTheyStart() {
		when(domainLookupService.requireUser(mentorId)).thenReturn(mentor());
		when(mentorProfileRepository.findOneById(mentorId)).thenReturn(Optional.of(profile()));

		assertSettingsAreRefused(settingsWith(List.of(), List.of(new MentorAvailabilityExceptionRequest(
				LocalDate.of(2026, 10, 3), LocalDate.of(2026, 10, 1), "İzin"))),
				"must end on or after it starts");
	}

	@Test
	void updateAvailabilitySettings_needsAMentorWithAProfile() {
		when(domainLookupService.requireUser(mentorId)).thenReturn(mentor());
		when(mentorProfileRepository.findOneById(mentorId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> mentorProfileService.updateAvailabilitySettings(mentorId,
				settingsWith(List.of(), List.of())))
				.isInstanceOf(ResourceNotFoundException.class)
				.hasMessageContaining("Mentor profile not found");
		assertThatThrownBy(() -> mentorProfileService.findAvailabilitySettings(mentorId))
				.isInstanceOf(ResourceNotFoundException.class)
				.hasMessageContaining("Mentor profile not found");
		verify(mentorAvailabilityExceptionRepository, never()).deleteAll(any());
	}

	@Test
	void updateAvailabilitySettings_replacesTheStoredRulesAndDatesWithTheOnesSent() {
		AppUser mentorUser = mentor();
		MentorProfile profile = profile();
		profile.setMenteeCapacity(4);
		UUID oldRuleId = UUID.randomUUID();
		MentorAvailabilityRule oldRule = new MentorAvailabilityRule();
		oldRule.setId(oldRuleId);
		oldRule.setMentor(mentorUser);
		oldRule.setDay(DayOfWeek.MONDAY);
		oldRule.setStartTime(LocalTime.of(9, 0));
		oldRule.setEndTime(LocalTime.of(11, 0));
		oldRule.setSlotMinutes(60);
		MentorAvailabilitySlot oldFreeSlot = slotAt(mentorUser, Instant.now().plus(2, ChronoUnit.DAYS));
		MentorAvailabilityException oldDates = new MentorAvailabilityException();
		when(domainLookupService.requireUser(mentorId)).thenReturn(mentorUser);
		when(mentorProfileRepository.findOneById(mentorId)).thenReturn(Optional.of(profile));
		when(mentorAvailabilityRuleRepository.findByMentorIdOrderByDayOfWeekAscStartTimeAsc(mentorId))
				.thenReturn(List.of(oldRule));
		when(mentorAvailabilityRuleRepository.findByIdAndMentorId(oldRuleId, mentorId))
				.thenReturn(Optional.of(oldRule));
		when(mentorAvailabilitySlotRepository.findByRuleIdAndStartsAtAfter(eq(oldRuleId), any(Instant.class)))
				.thenReturn(List.of(oldFreeSlot));
		when(mentorAvailabilityExceptionRepository.findByMentorId(mentorId)).thenReturn(List.of(oldDates));
		when(mentorAvailabilityRuleRepository.saveAndFlush(any(MentorAvailabilityRule.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));

		MentorAvailabilitySettingsResponse settings = mentorProfileService.updateAvailabilitySettings(mentorId,
				new UpdateMentorAvailabilitySettingsRequest(
						List.of(new CreateMentorAvailabilityRuleRequest(DayOfWeek.WEDNESDAY, LocalTime.of(9, 0),
								LocalTime.of(10, 30), 45)),
						List.of(new MentorAvailabilityExceptionRequest(LocalDate.of(2099, 10, 1),
								LocalDate.of(2099, 10, 1), "   ")),
						7, 30, 5));

		verify(mentorAvailabilitySlotRepository).deleteAll(List.of(oldFreeSlot));
		verify(mentorAvailabilityRuleRepository).delete(oldRule);
		ArgumentCaptor<MentorAvailabilityException> newDates = ArgumentCaptor
				.forClass(MentorAvailabilityException.class);
		// The old dates must be gone from the database before the new ones go in: they are unique per mentor.
		InOrder dates = inOrder(mentorAvailabilityExceptionRepository);
		dates.verify(mentorAvailabilityExceptionRepository).deleteAll(List.of(oldDates));
		dates.verify(mentorAvailabilityExceptionRepository).flush();
		dates.verify(mentorAvailabilityExceptionRepository).save(newDates.capture());
		assertThat(newDates.getValue().getMentor()).isSameAs(mentorUser);
		assertThat(newDates.getValue().getStartsOn()).isEqualTo(LocalDate.of(2099, 10, 1));
		assertThat(newDates.getValue().getEndsOn()).isEqualTo(LocalDate.of(2099, 10, 1));
		assertThat(newDates.getValue().getReason()).isNull();
		ArgumentCaptor<MentorAvailabilityRule> newRule = ArgumentCaptor.forClass(MentorAvailabilityRule.class);
		verify(mentorAvailabilityRuleRepository).saveAndFlush(newRule.capture());
		assertThat(newRule.getValue().day()).isEqualTo(DayOfWeek.WEDNESDAY);
		assertThat(newRule.getValue().getSlotMinutes()).isEqualTo(45);
		assertThat(profile.getMenteeCapacity()).isEqualTo(7);
		assertThat(settings.menteeCapacity()).isEqualTo(7);
		assertThat(settings.defaultMeetingDurationMinutes()).isEqualTo(30);
		assertThat(settings.meetingBufferMinutes()).isEqualTo(5);
		verify(mentorProfileRepository).save(profile);
	}

	@Test
	void updateAvailabilitySettings_dropsFreeSlotsOnTheUnavailableDatesButKeepsBookedOnes() {
		ZoneId zone = ZoneId.of("Europe/Istanbul");
		AppUser mentorUser = mentor();
		LocalDate away = LocalDate.now(zone).plusDays(10);
		// 00:30 in Turkey is still the day before in UTC; the unavailable date is a Turkish one.
		MentorAvailabilitySlot justAfterMidnight = slotAt(mentorUser, away.atTime(0, 30).atZone(zone).toInstant());
		MentorAvailabilitySlot free = slotAt(mentorUser, away.atTime(10, 0).atZone(zone).toInstant());
		MentorAvailabilitySlot booked = slotAt(mentorUser, away.atTime(14, 0).atZone(zone).toInstant());
		MentorAvailabilitySlot nextDay = slotAt(mentorUser, away.plusDays(1).atTime(10, 0).atZone(zone).toInstant());
		when(domainLookupService.requireUser(mentorId)).thenReturn(mentorUser);
		when(mentorProfileRepository.findOneById(mentorId)).thenReturn(Optional.of(profile()));
		when(mentorAvailabilitySlotRepository.findByMentorIdAndStartsAtAfterOrderByStartsAtAsc(eq(mentorId),
				any(Instant.class))).thenReturn(List.of(justAfterMidnight, free, booked, nextDay));
		when(mentorAvailabilityExceptionRepository.existsByMentorIdAndStartsOnLessThanEqualAndEndsOnGreaterThanEqual(
				eq(mentorId), any(LocalDate.class), any(LocalDate.class)))
				.thenAnswer(invocation -> away.equals(invocation.getArgument(1)));
		when(meetingRepository.existsByMentorIdAndScheduledAtAndStatus(eq(mentorId), any(Instant.class),
				eq(MeetingStatus.SCHEDULED)))
				.thenAnswer(invocation -> booked.getStartsAt().equals(invocation.getArgument(1)));

		mentorProfileService.updateAvailabilitySettings(mentorId, settingsWith(List.of(),
				List.of(new MentorAvailabilityExceptionRequest(away, away, null))));

		verify(mentorAvailabilityExceptionRepository)
				.save(argThat((MentorAvailabilityException saved) -> saved.getReason() == null));
		verify(mentorAvailabilitySlotRepository).delete(justAfterMidnight);
		verify(mentorAvailabilitySlotRepository).delete(free);
		verify(mentorAvailabilitySlotRepository, never()).delete(booked);
		verify(mentorAvailabilitySlotRepository, never()).delete(nextDay);
	}

	// --- Profile form: the optional parts ---

	private UpdateMentorProfileRequest requestWith(List<MentorExperienceRequest> experiences, String about,
			List<MentorProgramRoleRequest> programRoles) {
		UpdateMentorProfileRequest request = updateRequest();
		return new UpdateMentorProfileRequest(
				request.professionalTitle(), request.company(), request.availabilityLabel(),
				request.responseTimeLabel(), request.meetingMode(), request.communicationPreference(),
				request.mentoringStyle(), request.fitLabel(), request.experienceLevel(), request.sectorExperience(),
				request.startupStages(), request.specialties(), null, experiences, about, null, null, null,
				programRoles, null);
	}

	@Test
	void updateProfileTrimsTheAboutTextAndKeepsTheStoredOneWhenNoneIsSent() {
		MentorProfile profile = editableProfile();
		when(mentorProfileRepository.saveAndFlush(profile)).thenReturn(profile);

		mentorProfileService.updateProfile(mentorId, requestWith(null, "  15 yıldır ürün yönetiyorum.  ", null));
		assertThat(profile.getAbout()).isEqualTo("15 yıldır ürün yönetiyorum.");

		mentorProfileService.updateProfile(mentorId, requestWith(null, null, null));
		assertThat(profile.getAbout()).isEqualTo("15 yıldır ürün yönetiyorum.");
	}

	@Test
	void updateProfileReplacesAnOrdinaryExperienceLineAndAppendsANewOne() {
		MentorProfile profile = editableProfile();
		profile.getExperiences().add(new MentorExperience("Ürün Müdürü", "Looply", "2019–2022", null));
		when(mentorProfileRepository.saveAndFlush(profile)).thenReturn(profile);

		mentorProfileService.updateProfile(mentorId, requestWith(List.of(
				new MentorExperienceRequest("Ürün Müdürü", "Looply", "2019–2023", null),
				new MentorExperienceRequest("Danışman", null, null, null)), null, null));

		assertThat(profile.getExperiences()).extracting(MentorExperience::getTitle, MentorExperience::getPeriod,
				MentorExperience::getKind)
				.containsExactly(tuple("Ürün Müdürü", "2019–2023", null), tuple("Danışman", null, null));
	}

	@Test
	void updateProfileClearsAProgramRoleSentWithoutText() {
		MentorProfile profile = editableProfile();
		when(mentorProfileRepository.saveAndFlush(profile)).thenReturn(profile);
		ProgramMentor membership = new ProgramMentor();
		membership.setStatus(ProgramMentorStatus.ACCEPTED);
		membership.setRole("Eski rol");
		when(programMentorRepository.findByProgramIdAndMentorId(programId, mentorId))
				.thenReturn(Optional.of(membership));

		mentorProfileService.updateProfile(mentorId,
				requestWith(null, null, List.of(new MentorProgramRoleRequest(programId, null))));

		assertThat(membership.getRole()).isNull();
		verify(programMentorRepository).save(membership);
	}

	@Test
	void updateProfileEmptiesAStudentsCoursesAndProjectsWhenTheListsAreLeftOut() {
		MentorProfile profile = editableProfile();
		profile.setErasmus("TU Delft");
		profile.getStrongCourses().add("IE 251");
		profile.getProjects().add("Kampüs uygulaması");
		when(mentorProfileRepository.saveAndFlush(profile)).thenReturn(profile);

		mentorProfileService.updateProfile(mentorId, requestWithStudent(
				new StudentProfileRequest("Endüstri Mühendisliği", " ", null, null, null)));

		assertThat(profile.getDepartment()).isEqualTo("Endüstri Mühendisliği");
		assertThat(profile.getStudyYear()).isNull();
		assertThat(profile.getErasmus()).isNull();
		assertThat(profile.getStrongCourses()).isEmpty();
		assertThat(profile.getProjects()).isEmpty();
	}

	private MentorProfile profile() {
		MentorProfile profile = new MentorProfile();
		profile.setId(mentorId);
		return profile;
	}

	private MentorAvailabilitySlot slotAt(AppUser mentorUser, Instant startsAt) {
		MentorAvailabilitySlot slot = new MentorAvailabilitySlot();
		slot.setId(UUID.randomUUID());
		slot.setMentor(mentorUser);
		slot.setStartsAt(startsAt);
		slot.setDurationMinutes(60);
		return slot;
	}

	@Test
	void findReachable_keepsOnlyTheMentorsTheMenteesProgramAccepted() {
		UUID mentee = UUID.randomUUID();
		MentorProfile inProgram = new MentorProfile();
		inProgram.setId(mentorId);
		inProgram.setUser(mentor());
		MentorProfile outside = new MentorProfile();
		outside.setId(UUID.randomUUID());
		outside.setUser(mentor());
		when(mentorProfileRepository.findAllByUserStatusOrderBySortOrderAsc(UserAccountStatus.APPROVED))
				.thenReturn(List.of(inProgram, outside));
		when(menteeProgramService.reachableMentorIds(mentee)).thenReturn(Optional.of(Set.of(mentorId)));

		assertThat(mentorProfileService.findReachable(mentee)).extracting(MentorProfileResponse::id)
				.containsExactly(mentorId);
	}

	@Test
	void findReachable_listsEveryApprovedMentorWhenNothingLimitsTheCaller() {
		UUID admin = UUID.randomUUID();
		MentorProfile only = new MentorProfile();
		only.setId(mentorId);
		only.setUser(mentor());
		when(mentorProfileRepository.findAllByUserStatusOrderBySortOrderAsc(UserAccountStatus.APPROVED))
				.thenReturn(List.of(only));
		when(menteeProgramService.reachableMentorIds(admin)).thenReturn(Optional.empty());

		assertThat(mentorProfileService.findReachable(admin)).hasSize(1);
	}
}
