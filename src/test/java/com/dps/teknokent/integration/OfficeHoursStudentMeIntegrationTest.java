package com.dps.teknokent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.IdentityKind;
import com.dps.teknokent.domain.InstitutionDataSource;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.InstitutionPerson;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.InstitutionPersonRepository;
import com.dps.teknokent.repository.OfficeHoursMeetingRepository;
import com.dps.teknokent.repository.OfficeHoursUserRepository;
import com.jayway.jsonpath.JsonPath;

/** A Menton student books a listed academician through the real beans, with no Moodle launch on either side. */
@SpringBootTest(properties = "app.edition=ODTU")
@AutoConfigureMockMvc
@Transactional
class OfficeHoursStudentMeIntegrationTest {

	private static final String EVERY_DAY = """
			{"rules":[%s],"exceptions":[],"studentCapacity":null,"defaultMeetingDurationMinutes":30,
			 "meetingBufferMinutes":0}
			""".formatted(String.join(",", List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY",
			"SATURDAY", "SUNDAY").stream()
			.map(day -> "{\"dayOfWeek\":\"" + day + "\",\"startTime\":\"09:00\",\"endTime\":\"17:00\",\"slotMinutes\":30}")
			.toList()));

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private AppUserRepository appUsers;
	@Autowired
	private InstitutionPersonRepository institutionPeople;
	@Autowired
	private OfficeHoursUserRepository officeHoursUsers;
	@Autowired
	private OfficeHoursMeetingRepository meetings;

	private AppUser academician;
	private AppUser hidden;
	private AppUser student;

	@BeforeEach
	void setUp() throws Exception {
		academician = account(UserRole.ACADEMICIAN, "Elif Şahin", IdentityKind.ACADEMIC);
		hidden = account(UserRole.ACADEMICIAN, "Can Yılmaz", IdentityKind.ACADEMIC);
		student = account(UserRole.MENTEE, "Ali Kaya", IdentityKind.STUDENT);
		putAs(academician, "/api/office-hours/me/availability", EVERY_DAY);
		putAs(academician, "/api/office-hours/me/profile", "{\"listed\":true,\"defaultPlace\":\"A-204\"}");
		putAs(hidden, "/api/office-hours/me/availability", EVERY_DAY);
	}

	private AppUser account(UserRole role, String name, IdentityKind kind) {
		AppUser account = new AppUser();
		account.setFullName(name);
		account.setEmail(UUID.randomUUID() + "@office-hours.test");
		account.setRole(role);
		account = appUsers.saveAndFlush(account);
		InstitutionPerson record = new InstitutionPerson("odtu", UUID.randomUUID().toString());
		record.setUser(account);
		record.setKinds(Set.of(kind));
		record.setFullName(name);
		record.setAcademicTitle(kind == IdentityKind.ACADEMIC ? "Doç. Dr." : null);
		record.setDepartmentName("Bilgisayar Mühendisliği");
		record.setSource(InstitutionDataSource.DEV_STUB);
		record.setSyncedAt(Instant.now());
		institutionPeople.saveAndFlush(record);
		return account;
	}

	private static RequestPostProcessor frontendServer() {
		return user("frontend").roles("SERVICE");
	}

	private void putAs(AppUser account, String path, String body) throws Exception {
		mockMvc.perform(put(path).param("actorId", account.getId().toString()).with(frontendServer())
				.contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
	}

	private String getAs(AppUser account, String path) throws Exception {
		return mockMvc.perform(get(path).param("actorId", account.getId().toString()).with(frontendServer()))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
	}

	private ResultActions postAs(AppUser account, String path, String body) throws Exception {
		return mockMvc.perform(post(path).param("actorId", account.getId().toString()).with(frontendServer())
				.contentType(MediaType.APPLICATION_JSON).content(body));
	}

	@Test
	void aStudentBooksAListedAcademicianAndTakesAnOfferedTime() throws Exception {
		String listedId = officeHoursUsers.findByAppUserId(academician.getId()).orElseThrow().getId().toString();
		mockMvc.perform(get("/api/office-hours/me/academicians").param("actorId", student.getId().toString())
						.with(frontendServer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].id").value(listedId))
				.andExpect(jsonPath("$[0].fullName").value("Elif Şahin"))
				.andExpect(jsonPath("$[0].academicTitle").value("Doç. Dr."))
				.andExpect(jsonPath("$[0].place").value("A-204"))
				.andExpect(jsonPath("$[0].meetingMinutes").value(30));

		String slots = getAs(student, "/api/office-hours/me/academicians/" + listedId + "/slots");
		String first = JsonPath.read(slots, "$[0].startsAt");
		String second = JsonPath.read(slots, "$[1].startsAt");
		String ask = "{\"academicianId\":\"" + listedId + "\",\"startsAt\":\"" + first
				+ "\",\"title\":\"Staj\",\"description\":\"CV'ye bakalım\"}";
		String created = postAs(student, "/api/office-hours/me/requests", ask)
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("PENDING"))
				.andExpect(jsonPath("$.academician.fullName").value("Elif Şahin"))
				.andExpect(jsonPath("$.topic").value("Staj"))
				.andExpect(jsonPath("$.place").value("A-204"))
				.andExpect(jsonPath("$.allowedActions[0]").value("WITHDRAW"))
				.andReturn().getResponse().getContentAsString();
		String requestId = JsonPath.read(created, "$.id");
		postAs(student, "/api/office-hours/me/requests", ask).andExpect(status().isConflict());

		postAs(academician, "/api/office-hours/me/requests/" + requestId + "/decision",
				"{\"action\":\"PROPOSE_TIMES\",\"times\":[\"" + second + "\"],\"message\":\"Bu saat daha iyi\"}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("COUNTER_PROPOSED"));
		String mine = getAs(student, "/api/office-hours/me/appointments");
		assertThat(JsonPath.<String>read(mine, "$[0].status")).isEqualTo("COUNTER_PROPOSED");
		assertThat(JsonPath.<List<String>>read(mine, "$[0].allowedActions")).containsExactly("ACCEPT_PROPOSED",
				"WITHDRAW");
		assertThat(JsonPath.<String>read(mine, "$[0].academicianMessage")).isEqualTo("Bu saat daha iyi");

		postAs(student, "/api/office-hours/me/requests/" + requestId + "/alternative",
				"{\"accept\":true,\"time\":\"" + second + "\"}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("ACCEPTED"))
				.andExpect(jsonPath("$.allowedActions.length()").value(0));
		assertThat(meetings.findAll()).hasSize(1);
		mockMvc.perform(get("/api/office-hours/me/meetings").param("actorId", academician.getId().toString())
						.with(frontendServer()))
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].student.fullName").value("Ali Kaya"));
	}

	@Test
	void anAcademicianWhoDidNotOptInCannotBeFoundOrAsked() throws Exception {
		String hiddenId = officeHoursUsers.findByAppUserId(hidden.getId()).orElseThrow().getId().toString();

		mockMvc.perform(get("/api/office-hours/me/academicians/" + hiddenId).param("actorId",
						student.getId().toString()).with(frontendServer()))
				.andExpect(status().isNotFound());
		postAs(student, "/api/office-hours/me/requests", "{\"academicianId\":\"" + hiddenId
				+ "\",\"startsAt\":\"2030-01-07T09:00:00Z\",\"title\":\"Staj\"}")
				.andExpect(status().isNotFound());
		assertThat(officeHoursUsers.findByAppUserId(student.getId())).isEmpty();
	}

	@Test
	void aTimeThatIsNotOneOfTheOpenSlotsIsRefused() throws Exception {
		String listedId = officeHoursUsers.findByAppUserId(academician.getId()).orElseThrow().getId().toString();

		postAs(student, "/api/office-hours/me/requests", "{\"academicianId\":\"" + listedId
				+ "\",\"startsAt\":\"2030-01-07T03:17:00Z\",\"title\":\"Staj\"}")
				.andExpect(status().isConflict());
	}

	@Test
	void onlyAStudentSeesTheStudentSide() throws Exception {
		mockMvc.perform(get("/api/office-hours/me/academicians").param("actorId", academician.getId().toString())
						.with(frontendServer()))
				.andExpect(status().isNotFound());
		mockMvc.perform(get("/api/office-hours/me/appointments").param("actorId", student.getId().toString())
						.with(frontendServer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(0));
	}
}
