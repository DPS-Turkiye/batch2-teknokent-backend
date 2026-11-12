package com.dps.teknokent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.IdentityKind;
import com.dps.teknokent.domain.InstitutionDataSource;
import com.dps.teknokent.domain.OfficeHoursRequestStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.InstitutionPerson;
import com.dps.teknokent.entity.OfficeHoursRequest;
import com.dps.teknokent.entity.OfficeHoursUser;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.InstitutionPersonRepository;
import com.dps.teknokent.repository.OfficeHoursRequestRepository;
import com.dps.teknokent.repository.OfficeHoursUserRepository;
import com.dps.teknokent.util.MeetingSlotTimes;

/** The academician side of Menton office hours through the real beans: no Moodle launch anywhere. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class OfficeHoursMeIntegrationTest {

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private AppUserRepository appUsers;
	@Autowired
	private InstitutionPersonRepository institutionPeople;
	@Autowired
	private OfficeHoursUserRepository officeHoursUsers;
	@Autowired
	private OfficeHoursRequestRepository requests;

	private AppUser academician;
	private AppUser student;

	@BeforeEach
	void setUp() {
		academician = account(UserRole.ACADEMICIAN, "Elif Şahin", IdentityKind.ACADEMIC, null);
		student = account(UserRole.MENTEE, "Ali Kaya", IdentityKind.STUDENT, 3);
	}

	private AppUser account(UserRole role, String name, IdentityKind kind, Integer classYear) {
		AppUser account = new AppUser();
		account.setFullName(name);
		account.setEmail(UUID.randomUUID() + "@office-hours.test");
		account.setRole(role);
		account = appUsers.saveAndFlush(account);
		InstitutionPerson record = new InstitutionPerson("odtu", UUID.randomUUID().toString());
		record.setUser(account);
		record.setKinds(Set.of(kind));
		record.setFullName(name);
		record.setDepartmentName("Bilgisayar Mühendisliği");
		record.setClassYear(classYear);
		record.setSource(InstitutionDataSource.DEV_STUB);
		record.setSyncedAt(Instant.now());
		institutionPeople.saveAndFlush(record);
		return account;
	}

	private static RequestPostProcessor frontendServer() {
		return user("frontend").roles("SERVICE");
	}

	private String as(AppUser account) {
		return account.getId().toString();
	}

	@Test
	void anAcademicianSetsHoursAndProfileAsOnePerson() throws Exception {
		String hours = """
				{"rules":[{"dayOfWeek":"TUESDAY","startTime":"10:00","endTime":"12:00","slotMinutes":30}],
				 "exceptions":[],"studentCapacity":null,"defaultMeetingDurationMinutes":30,"meetingBufferMinutes":0}
				""";
		mockMvc.perform(put("/api/office-hours/me/availability").param("actorId", as(academician))
						.with(frontendServer()).contentType(MediaType.APPLICATION_JSON).content(hours))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.rules.length()").value(1));
		mockMvc.perform(put("/api/office-hours/me/profile").param("actorId", as(academician))
						.with(frontendServer()).contentType(MediaType.APPLICATION_JSON)
						.content("{\"listed\":true,\"defaultPlace\":\"A-204\",\"onlineLink\":\"https://meet.example.com/x\"}"))
				.andExpect(status().isOk());

		mockMvc.perform(get("/api/office-hours/me/profile").param("actorId", as(academician)).with(frontendServer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.listed").value(true))
				.andExpect(jsonPath("$.defaultPlace").value("A-204"));
		mockMvc.perform(get("/api/office-hours/me/availability").param("actorId", as(academician))
						.with(frontendServer()))
				.andExpect(jsonPath("$.defaultMeetingDurationMinutes").value(30))
				.andExpect(jsonPath("$.rules[0].dayOfWeek").value("TUESDAY"));
		OfficeHoursUser person = officeHoursUsers.findByAppUserId(academician.getId()).orElseThrow();
		assertThat(person.getIssuer()).isNull();
		assertThat(person.getFullName()).isEqualTo("Elif Şahin");
	}

	@Test
	void anAcademicianAnswersAMentonRequestAndSeesWhoAsked() throws Exception {
		mockMvc.perform(put("/api/office-hours/me/profile").param("actorId", as(academician))
						.with(frontendServer()).contentType(MediaType.APPLICATION_JSON).content("{\"listed\":true}"))
				.andExpect(status().isOk());
		UUID instructorId = officeHoursUsers.findByAppUserId(academician.getId()).orElseThrow().getId();
		OfficeHoursUser asker = new OfficeHoursUser();
		asker.setId(UUID.randomUUID());
		asker.setAppUserId(student.getId());
		asker.setFullName("Ali Kaya");
		asker.setCreatedAt(Instant.now());
		asker.setLastLaunchAt(Instant.now());
		officeHoursUsers.saveAndFlush(asker);
		OfficeHoursRequest request = new OfficeHoursRequest();
		request.setId(UUID.randomUUID());
		request.setStudentId(asker.getId());
		request.setInstructorId(instructorId);
		request.setStatus(OfficeHoursRequestStatus.PENDING);
		request.setTopic("Staj");
		request.setPreferredSlots(
				MeetingSlotTimes.formatMachineSlot(Instant.now().plus(10, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS)));
		request.setCreatedAt(Instant.now());
		request.setUpdatedAt(Instant.now());
		requests.saveAndFlush(request);

		mockMvc.perform(get("/api/office-hours/me/inbox").param("actorId", as(academician)).with(frontendServer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].student.fullName").value("Ali Kaya"))
				.andExpect(jsonPath("$[0].student.department").value("Bilgisayar Mühendisliği"))
				.andExpect(jsonPath("$[0].student.classYear").value(3))
				.andExpect(jsonPath("$[0].courseName").doesNotExist())
				.andExpect(jsonPath("$[0].allowedActions.length()").value(3));
		mockMvc.perform(post("/api/office-hours/me/requests/{id}/decision", request.getId())
						.param("actorId", as(academician)).with(frontendServer())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"action\":\"ASK_QUESTION\",\"message\":\"Hangi ders?\"}"))
				.andExpect(status().isBadRequest());
		mockMvc.perform(post("/api/office-hours/me/requests/{id}/decision", request.getId())
						.param("actorId", as(academician)).with(frontendServer())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"action\":\"DECLINE\",\"message\":\"Bu hafta dolu\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("DECLINED"))
				.andExpect(jsonPath("$.rejectionReason").value("Bu hafta dolu"))
				.andExpect(jsonPath("$.allowedActions.length()").value(0));
	}

	@Test
	void aStudentHasNoAcademicianOfficeHours() throws Exception {
		mockMvc.perform(get("/api/office-hours/me/inbox").param("actorId", as(student)).with(frontendServer()))
				.andExpect(status().isNotFound());
		mockMvc.perform(put("/api/office-hours/me/profile").param("actorId", as(student)).with(frontendServer())
						.contentType(MediaType.APPLICATION_JSON).content("{\"listed\":true}"))
				.andExpect(status().isNotFound());
		assertThat(officeHoursUsers.findByAppUserId(student.getId())).isEmpty();
	}
}
