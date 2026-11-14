package com.dps.teknokent.integration;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.IdentityKind;
import com.dps.teknokent.domain.InstitutionDataSource;
import com.dps.teknokent.domain.PeerTopicCategory;
import com.dps.teknokent.domain.ProgramStatus;
import com.dps.teknokent.domain.ProgramType;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.InstitutionAdvising;
import com.dps.teknokent.entity.InstitutionPerson;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramPeerCategory;
import com.dps.teknokent.entity.StudentInterest;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.InstitutionAdvisingRepository;
import com.dps.teknokent.repository.InstitutionPersonRepository;
import com.dps.teknokent.repository.ProgramPeerCategoryRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.repository.StudentInterestRepository;

/** An academician reads their own advisee's interests and goal through the real beans; nobody else can. */
@SpringBootTest(properties = "app.edition=ODTU")
@AutoConfigureMockMvc
@Transactional
class AdviseeProfileIntegrationTest {

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private AppUserRepository appUsers;
	@Autowired
	private InstitutionPersonRepository institutionPeople;
	@Autowired
	private InstitutionAdvisingRepository advising;
	@Autowired
	private ProgramRepository programs;
	@Autowired
	private ProgramPeerCategoryRepository programCategories;
	@Autowired
	private StudentInterestRepository interests;

	private AppUser advisor;
	private AppUser otherAcademician;
	private AppUser advisee;
	private AppUser othersAdvisee;
	private AppUser unadvised;

	@BeforeEach
	void setUp() {
		advisor = account(UserRole.ACADEMICIAN, "Elif Şahin", IdentityKind.ACADEMIC, null);
		otherAcademician = account(UserRole.ACADEMICIAN, "Can Yılmaz", IdentityKind.ACADEMIC, null);
		Program program = peerProgram();
		programCategories.saveAndFlush(new ProgramPeerCategory(program.getId(), PeerTopicCategory.CAREER_INTERNSHIP,
				advisor.getId(), Instant.now()));
		advisee = account(UserRole.MENTEE, "Ali Kaya", IdentityKind.STUDENT, program.getId());
		othersAdvisee = account(UserRole.MENTEE, "Zeynep Ak", IdentityKind.STUDENT, program.getId());
		unadvised = account(UserRole.MENTEE, "Deniz Er", IdentityKind.STUDENT, program.getId());
		advise(advisor, advisee);
		advise(otherAcademician, othersAdvisee);

		StudentInterest saved = new StudentInterest(advisee.getId());
		saved.setTopics(new ArrayList<>(List.of(PeerTopicCategory.CAREER_INTERNSHIP, PeerTopicCategory.INTERNATIONAL)));
		saved.setSubtopics(new ArrayList<>(List.of("cv")));
		saved.setGoal("Yaz stajı bulmak");
		saved.setCompleted(true);
		saved.setUpdatedAt(Instant.now());
		interests.saveAndFlush(saved);
	}

	@Test
	void theAdvisorSeesTheirAdviseesInterestsAndGoal() throws Exception {
		profile(advisor, advisee.getId())
				.andExpect(status().isOk())
				// INTERNATIONAL is off in the student's program, so their own Profilim doesn't show it either.
				.andExpect(jsonPath("$.interests.topics.length()").value(1))
				.andExpect(jsonPath("$.interests.topics[0]").value("CAREER_INTERNSHIP"))
				.andExpect(jsonPath("$.interests.subtopics[0]").value("cv"))
				.andExpect(jsonPath("$.goal").value("Yaz stajı bulmak"))
				.andExpect(jsonPath("$.completed").doesNotExist());
	}

	@Test
	void anAdviseeWhoNeverSavedShowsNothingYet() throws Exception {
		profile(otherAcademician, othersAdvisee.getId())
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.interests.topics.length()").value(0))
				.andExpect(jsonPath("$.interests.subtopics.length()").value(0))
				.andExpect(jsonPath("$.goal").value(""));
	}

	@Test
	void everyoneElseGetsTheSameNotFound() throws Exception {
		expectNotFound(profile(otherAcademician, advisee.getId()));
		expectNotFound(profile(advisor, othersAdvisee.getId()));
		expectNotFound(profile(advisor, unadvised.getId()));
		expectNotFound(profile(advisor, UUID.randomUUID()));
		expectNotFound(profile(advisee, advisee.getId()));
		expectNotFound(profile(othersAdvisee, advisee.getId()));
	}

	private ResultActions profile(AppUser caller, UUID studentId) throws Exception {
		return mockMvc.perform(get("/api/institution/me/advisees/" + studentId + "/profile")
				.param("actorId", caller.getId().toString())
				.with(user("frontend").roles("SERVICE")));
	}

	private static void expectNotFound(ResultActions result) throws Exception {
		result.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value("Advisee not found."))
				.andExpect(jsonPath("$.goal").doesNotExist());
	}

	private AppUser account(UserRole role, String name, IdentityKind kind, UUID programId) {
		AppUser account = new AppUser();
		account.setFullName(name);
		account.setEmail(UUID.randomUUID() + "@advisee.test");
		account.setRole(role);
		account.setProgramId(programId);
		account = appUsers.saveAndFlush(account);
		InstitutionPerson record = new InstitutionPerson("odtu", UUID.randomUUID().toString());
		record.setUser(account);
		record.setKinds(Set.of(kind));
		record.setFullName(name);
		record.setDepartmentName("Bilgisayar Mühendisliği");
		record.setSource(InstitutionDataSource.DEV_STUB);
		record.setSyncedAt(Instant.now());
		institutionPeople.saveAndFlush(record);
		return account;
	}

	private void advise(AppUser academician, AppUser student) {
		String academicianId = institutionPeople.findFirstByUserIdOrderBySyncedAtDesc(academician.getId()).orElseThrow()
				.getPersistentId();
		String studentId = institutionPeople.findFirstByUserIdOrderBySyncedAtDesc(student.getId()).orElseThrow()
				.getPersistentId();
		advising.saveAndFlush(new InstitutionAdvising("odtu", studentId, academicianId, Instant.now()));
	}

	private Program peerProgram() {
		Program created = new Program();
		created.setId(UUID.randomUUID());
		created.setName("Akran Mentörlüğü 2026");
		created.setCreatedBy(advisor.getId());
		created.setCreatedAt(Instant.now());
		created.setStatus(ProgramStatus.ACTIVE);
		created.setProgramType(ProgramType.PEER_MENTORING);
		return programs.saveAndFlush(created);
	}
}
