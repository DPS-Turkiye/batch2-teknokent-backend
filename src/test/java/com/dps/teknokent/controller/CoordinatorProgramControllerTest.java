package com.dps.teknokent.controller;

import com.dps.teknokent.dto.responses.ProgramEligibleEmailResponse;

import com.dps.teknokent.service.ProgramRosterService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.dps.teknokent.config.SecurityConfig;
import com.dps.teknokent.domain.ProgramEventKind;
import com.dps.teknokent.domain.ProgramLifecycle;
import com.dps.teknokent.domain.ProgramStatus;
import com.dps.teknokent.domain.ProgramType;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.CreateProgramEventRequest;
import com.dps.teknokent.dto.requests.ExtractSyllabusPhasesRequest;
import com.dps.teknokent.dto.responses.CoordinatorProgramResponse;
import com.dps.teknokent.dto.responses.ProgramEventResponse;
import com.dps.teknokent.dto.responses.SyllabusPhasesResponse;
import com.dps.teknokent.security.AppUserPrincipal;
import com.dps.teknokent.service.CoordinatorProgramService;
import com.dps.teknokent.service.ProgramCoordinatorService;
import com.dps.teknokent.service.MentorPoolService;
import com.dps.teknokent.service.ProgramApprovalService;
import com.dps.teknokent.service.ProgramMatchingService;
import com.dps.teknokent.service.ProgramLaunchService;
import com.dps.teknokent.service.SyllabusPhaseService;

@WebMvcTest(CoordinatorProgramController.class)
@Import(SecurityConfig.class)
class CoordinatorProgramControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private CoordinatorProgramService coordinatorProgramService;
	@MockitoBean
	private ProgramCoordinatorService programCoordinatorService;
	@MockitoBean
	private ProgramLaunchService programLaunchService;
	@MockitoBean
	private MentorPoolService mentorPoolService;
	@MockitoBean
	private ProgramMatchingService programMatchingService;
	@MockitoBean
	private SyllabusPhaseService syllabusPhaseService;
	@MockitoBean
	private ProgramApprovalService programApprovalService;
	@MockitoBean
	private ProgramRosterService programRosterService;

	private static final UUID ACTOR_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
	private static final UUID SIGNED_IN_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c9");
	private static final UUID PROGRAM_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

	@Test
	void requiresAuthentication() throws Exception {
		mockMvc.perform(get("/api/coordinator/programs"))
				.andExpect(status().isUnauthorized());
		verifyNoInteractions(coordinatorProgramService);
	}

	@Test
	void listsForTheActorOnTheServiceCredential() throws Exception {
		when(coordinatorProgramService.list(ACTOR_ID)).thenReturn(List.of(summary()));

		mockMvc.perform(get("/api/coordinator/programs")
						.param("actorId", ACTOR_ID.toString())
						.with(user("test-user")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].name").value("METU Stars 2026"))
				.andExpect(jsonPath("$[0].lifecycle").value("ACTIVE"));
	}

	@Test
	void actsAsTheSignedInUserWhateverActorIdIsSent() throws Exception {
		when(coordinatorProgramService.list(SIGNED_IN_ID)).thenReturn(List.of());

		mockMvc.perform(get("/api/coordinator/programs")
						.param("actorId", ACTOR_ID.toString())
						.with(authentication(signedIn())))
				.andExpect(status().isOk());

		verify(coordinatorProgramService).list(SIGNED_IN_ID);
	}

	@Test
	void rejectsAProgramUpdateWithoutAName() throws Exception {
		mockMvc.perform(put("/api/coordinator/programs/{id}", PROGRAM_ID)
						.with(authentication(signedIn()))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\" \"}"))
				.andExpect(status().isBadRequest());
		verifyNoInteractions(coordinatorProgramService);
	}

	@Test
	void readsASyllabusAsTheSignedInUserAndNeedsADocument() throws Exception {
		UUID documentId = UUID.randomUUID();
		when(syllabusPhaseService.extract(eq(SIGNED_IN_ID), any(ExtractSyllabusPhasesRequest.class)))
				.thenReturn(new SyllabusPhasesResponse(List.of(new SyllabusPhasesResponse.Phase(
						"Keşif", "Rapor", LocalDate.of(2026, 11, 3), null)), List.of(), 18));

		mockMvc.perform(post("/api/coordinator/programs/syllabus/phases")
						.with(authentication(signedIn()))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"actorId\":\"" + ACTOR_ID + "\",\"documentId\":\"" + documentId
								+ "\",\"startDate\":\"2026-11-03\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.pageCount").value(18))
				.andExpect(jsonPath("$.phases[0].name").value("Keşif"))
				.andExpect(jsonPath("$.phases[0].startDate").value("2026-11-03"));

		mockMvc.perform(post("/api/coordinator/programs/syllabus/phases")
						.with(authentication(signedIn()))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isBadRequest());
		verify(syllabusPhaseService).extract(eq(SIGNED_IN_ID), any(ExtractSyllabusPhasesRequest.class));
	}

	@Test
	void rejectsMoreThanTwelvePhases() throws Exception {
		String phases = String.join(",", Collections.nCopies(13, "{\"name\":\"Faz\"}"));

		mockMvc.perform(put("/api/coordinator/programs/{id}/phases", PROGRAM_ID)
						.with(authentication(signedIn()))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"phases\":[" + phases + "]}"))
				.andExpect(status().isBadRequest());
		verifyNoInteractions(coordinatorProgramService);
	}

	@Test
	void createsAnEvent() throws Exception {
		Instant startsAt = Instant.parse("2026-10-17T20:59:00Z");
		when(coordinatorProgramService.createEvent(eq(SIGNED_IN_ID), eq(PROGRAM_ID), any(CreateProgramEventRequest.class)))
				.thenReturn(new ProgramEventResponse(UUID.randomUUID(), PROGRAM_ID, "Ara Rapor Teslimi",
						ProgramEventKind.DEADLINE, startsAt, null, null));

		mockMvc.perform(post("/api/coordinator/programs/{id}/events", PROGRAM_ID)
						.with(authentication(signedIn()))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{ "title": "Ara Rapor Teslimi", "kind": "DEADLINE", "startsAt": "2026-10-17T20:59:00Z" }
								"""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.kind").value("DEADLINE"));
	}

	@Test
	void deletesAnEvent() throws Exception {
		UUID eventId = UUID.randomUUID();

		mockMvc.perform(delete("/api/coordinator/programs/{id}/events/{eventId}", PROGRAM_ID, eventId)
						.with(authentication(signedIn())))
				.andExpect(status().isNoContent());

		verify(coordinatorProgramService).deleteEvent(SIGNED_IN_ID, PROGRAM_ID, eventId);
	}

	// Admins now find coordinators through /api/admin/coordinators/search; nobody lists every coordinator here.
	@Test
	void noLongerListsEveryCoordinatorAsACandidate() throws Exception {
		mockMvc.perform(get("/api/coordinator/programs/{id}/coordinators/candidates", PROGRAM_ID)
						.with(authentication(signedIn())))
				.andExpect(status().is4xxClientError());
		verifyNoInteractions(programCoordinatorService);
	}

	@Test
	void archivesAndManagesCoordinatorsAsTheSignedInUser() throws Exception {
		UUID other = UUID.fromString("00000000-0000-0000-0000-0000000000c2");
		when(programCoordinatorService.add(SIGNED_IN_ID, PROGRAM_ID, other)).thenReturn(List.of(
				new CoordinatorProgramResponse.CoordinatorRef(SIGNED_IN_ID, "Elif Demir", true),
				new CoordinatorProgramResponse.CoordinatorRef(other, "Burcu Şen", false)));

		mockMvc.perform(put("/api/coordinator/programs/{id}/archive", PROGRAM_ID)
						.with(authentication(signedIn()))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"archived\":true}"))
				.andExpect(status().isOk());
		verify(coordinatorProgramService).setArchived(SIGNED_IN_ID, PROGRAM_ID, true);

		mockMvc.perform(post("/api/coordinator/programs/{id}/coordinators", PROGRAM_ID)
						.with(authentication(signedIn()))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"userId\":\"" + other + "\",\"actorId\":\"" + ACTOR_ID + "\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[1].fullName").value("Burcu Şen"))
				.andExpect(jsonPath("$[0].owner").value(true));

		mockMvc.perform(delete("/api/coordinator/programs/{id}/coordinators/{userId}", PROGRAM_ID, other)
						.with(authentication(signedIn())))
				.andExpect(status().isOk());
		verify(programCoordinatorService).remove(SIGNED_IN_ID, PROGRAM_ID, other);
	}

	@Test
	void managesTheProgramListAsTheSignedInUser() throws Exception {
		UUID entryId = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
		when(programRosterService.add(eq(SIGNED_IN_ID), eq(PROGRAM_ID), any())).thenReturn(List.of(
				new ProgramEligibleEmailResponse(entryId, "e1@metu.edu.tr", Instant.parse("2026-11-07T10:00:00Z"), false)));

		mockMvc.perform(post("/api/coordinator/programs/{id}/eligible-emails", PROGRAM_ID)
						.with(authentication(signedIn()))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"emails\":[\"e1@metu.edu.tr\"]}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].email").value("e1@metu.edu.tr"))
				.andExpect(jsonPath("$[0].registered").value(false));

		mockMvc.perform(post("/api/coordinator/programs/{id}/eligible-emails", PROGRAM_ID)
						.with(authentication(signedIn()))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"emails\":[]}"))
				.andExpect(status().isBadRequest());

		mockMvc.perform(get("/api/coordinator/programs/{id}/eligible-emails", PROGRAM_ID)
						.with(authentication(signedIn())))
				.andExpect(status().isOk());
		verify(programRosterService).list(SIGNED_IN_ID, PROGRAM_ID);

		mockMvc.perform(delete("/api/coordinator/programs/{id}/eligible-emails/{entryId}", PROGRAM_ID, entryId)
						.with(authentication(signedIn())))
				.andExpect(status().isOk());
		verify(programRosterService).remove(SIGNED_IN_ID, PROGRAM_ID, entryId);
	}

	@Test
	void launchesAProgramAndRejectsABadLeadEmail() throws Exception {
		String body = """
				{"type":"STARTUP_MENTORING","name":"METU BİGG 2026",
				 "coordinatorIds":["00000000-0000-0000-0000-0000000000c2"],"defaultMentorCapacity":4,"meetingFrequencyDays":14,
				 "communicationGapDays":14,"bottleneckFlagDays":21,
				 "teams":[{"teamName":"Kavşak","leadEmail":"%s"}]}
				""";
		mockMvc.perform(post("/api/coordinator/programs")
						.with(authentication(signedIn()))
						.contentType(MediaType.APPLICATION_JSON)
						.content(body.formatted("deniz@kavsak.co")))
				.andExpect(status().isCreated());
		verify(programLaunchService).create(eq(SIGNED_IN_ID), any());

		mockMvc.perform(post("/api/coordinator/programs")
						.with(authentication(signedIn()))
						.contentType(MediaType.APPLICATION_JSON)
						.content(body.formatted("can@tohum")))
				.andExpect(status().isBadRequest());
	}

	@Test
	void anAdminLaunchesADraftAndAProgramNeedsATypeAndACoordinator() throws Exception {
		mockMvc.perform(post("/api/coordinator/programs/{id}/launch", PROGRAM_ID)
						.with(authentication(signedIn())))
				.andExpect(status().isOk());
		verify(coordinatorProgramService).launch(SIGNED_IN_ID, PROGRAM_ID);

		mockMvc.perform(post("/api/coordinator/programs")
						.with(authentication(signedIn()))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"ODTÜ Akran\",\"coordinatorIds\":[\"00000000-0000-0000-0000-0000000000c2\"]}"))
				.andExpect(status().isBadRequest());
		mockMvc.perform(post("/api/coordinator/programs")
						.with(authentication(signedIn()))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"type\":\"PEER_MENTORING\",\"name\":\"ODTÜ Akran\",\"coordinatorIds\":[]}"))
				.andExpect(status().isBadRequest());
		mockMvc.perform(post("/api/coordinator/programs")
						.with(authentication(signedIn()))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"type\":\"PEER_MENTORING\",\"name\":\"ODTÜ Akran\","
								+ "\"coordinatorIds\":[\"00000000-0000-0000-0000-0000000000c2\"]}"))
				.andExpect(status().isCreated());
	}

	@Test
	void emailsATeamCodeAndOpensATeamAsTheSignedInCoordinator() throws Exception {
		UUID inviteId = UUID.randomUUID();
		UUID organizationId = UUID.randomUUID();

		mockMvc.perform(post("/api/coordinator/programs/{id}/teams/{inviteId}/send-code", PROGRAM_ID, inviteId)
						.with(authentication(signedIn())))
				.andExpect(status().isOk());
		verify(programLaunchService).sendCode(SIGNED_IN_ID, PROGRAM_ID, inviteId);

		mockMvc.perform(get("/api/coordinator/programs/{id}/team-details/{organizationId}", PROGRAM_ID, organizationId)
						.with(authentication(signedIn())))
				.andExpect(status().isOk());
		verify(programLaunchService).teamDetail(SIGNED_IN_ID, PROGRAM_ID, organizationId);
	}

	@Test
	void invitesAMentorByEmailAndDecidesAsTheSignedInUser() throws Exception {
		mockMvc.perform(post("/api/coordinator/programs/{id}/mentors", PROGRAM_ID)
						.with(authentication(signedIn()))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"cagri@yilmazfinans.com\",\"actorId\":\"" + ACTOR_ID + "\"}"))
				.andExpect(status().isOk());
		verify(mentorPoolService).invite(SIGNED_IN_ID, PROGRAM_ID, "cagri@yilmazfinans.com");

		mockMvc.perform(post("/api/coordinator/programs/{id}/mentors", PROGRAM_ID)
						.with(authentication(signedIn()))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"cagri@yilmaz\"}"))
				.andExpect(status().isBadRequest());

		UUID mentorId = UUID.randomUUID();
		mockMvc.perform(post("/api/coordinator/programs/{id}/mentors/{mentorId}/decision", PROGRAM_ID, mentorId)
						.with(authentication(signedIn()))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"accepted\":true}"))
				.andExpect(status().isOk());
		verify(mentorPoolService).decide(SIGNED_IN_ID, PROGRAM_ID, mentorId, true);
	}

	@Test
	void decidesApplicantsAndStartupsOfTheProgramAsTheSignedInUser() throws Exception {
		UUID person = UUID.randomUUID();
		mockMvc.perform(post("/api/coordinator/programs/{id}/approvals/people/{userId}", PROGRAM_ID, person)
						.with(authentication(signedIn()))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"accepted\":true}"))
				.andExpect(status().isOk());
		verify(programApprovalService).decidePerson(SIGNED_IN_ID, PROGRAM_ID, person, true);

		UUID startup = UUID.randomUUID();
		mockMvc.perform(post("/api/coordinator/programs/{id}/approvals/startups/{organizationId}", PROGRAM_ID,
						startup)
						.with(authentication(signedIn()))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"accepted\":false}"))
				.andExpect(status().isOk());
		verify(programApprovalService).decideStartup(SIGNED_IN_ID, PROGRAM_ID, startup, false);

		mockMvc.perform(post("/api/coordinator/programs/{id}/approvals/startups/{organizationId}", PROGRAM_ID,
						startup)
						.with(authentication(signedIn()))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void matchesATeamAsTheSignedInUserAndNeedsBothIds() throws Exception {
		UUID team = UUID.randomUUID();
		UUID mentor = UUID.randomUUID();
		mockMvc.perform(post("/api/coordinator/programs/{id}/matching", PROGRAM_ID)
						.with(authentication(signedIn()))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"organizationId\":\"" + team + "\",\"mentorId\":\"" + mentor + "\"}"))
				.andExpect(status().isOk());
		verify(programMatchingService).match(SIGNED_IN_ID, PROGRAM_ID, team, mentor);

		mockMvc.perform(post("/api/coordinator/programs/{id}/matching", PROGRAM_ID)
						.with(authentication(signedIn()))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"organizationId\":\"" + team + "\"}"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void archivingNeedsAnAnswer() throws Exception {
		mockMvc.perform(put("/api/coordinator/programs/{id}/archive", PROGRAM_ID)
						.with(authentication(signedIn()))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isBadRequest());
		verifyNoInteractions(coordinatorProgramService);
	}

	private static UsernamePasswordAuthenticationToken signedIn() {
		return new UsernamePasswordAuthenticationToken(
				new AppUserPrincipal(SIGNED_IN_ID, "elif@tekno.test", UserRole.COORDINATOR, UserAccountStatus.APPROVED, "uid"),
				null, List.of());
	}

	private static CoordinatorProgramResponse summary() {
		return new CoordinatorProgramResponse(
				PROGRAM_ID, "METU Stars 2026", ProgramStatus.ACTIVE, ProgramLifecycle.ACTIVE, ProgramType.STARTUP_MENTORING,
				null, null, null,
				ACTOR_ID, "Elif Demir", 42, 28, 3, 64, 6, 5, 4, null, List.of(), null, null);
	}
}
