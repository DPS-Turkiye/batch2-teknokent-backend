package com.dps.teknokent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.IdentityKind;
import com.dps.teknokent.domain.InstitutionDataSource;
import com.dps.teknokent.domain.PeerDocumentVisibility;
import com.dps.teknokent.domain.PeerEvidenceType;
import com.dps.teknokent.domain.PermissionSource;
import com.dps.teknokent.domain.ProgramMentorInvitedBy;
import com.dps.teknokent.domain.ProgramMentorStatus;
import com.dps.teknokent.domain.ProgramStatus;
import com.dps.teknokent.domain.ProgramType;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.InstitutionPerson;
import com.dps.teknokent.entity.PeerMentorDocument;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramCoordinator;
import com.dps.teknokent.entity.ProgramMentor;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.InstitutionPersonRepository;
import com.dps.teknokent.repository.PeerMentorDocumentRepository;
import com.dps.teknokent.repository.ProgramCoordinatorRepository;
import com.dps.teknokent.repository.ProgramMentorRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.repository.StoredDocumentRepository;
import com.dps.teknokent.security.AppUserPrincipal;
import com.dps.teknokent.service.ObjectStorage;
import com.jayway.jsonpath.JsonPath;

/**
 * A peer mentor's documents (wizard step "Belgeler ve kanıtlar"): only they upload, change and delete; their program's
 * coordinators and admins see all; students of their program see the PROFILE ones; anyone else gets not found.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = "app.edition=ODTU")
class PeerMentorDocumentIntegrationTest {

	private static final String MINE = "/api/peer-mentoring/profile/documents";
	private static final String LINKS = "/api/peer-mentoring/profile/links";
	private static final String ONE = "/api/peer-mentoring/profile/documents/{id}";
	private static final String OF_MENTOR = "/api/peer-mentoring/mentors/{mentorId}/documents";
	private static final String CONTENT = "/api/peer-mentoring/documents/{id}/content";
	private static final byte[] PDF = "%PDF-1.4\n1 0 obj\n<<>>\nendobj\n%%EOF".getBytes(StandardCharsets.ISO_8859_1);

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private AppUserRepository userRepository;
	@Autowired
	private InstitutionPersonRepository institutionPersonRepository;
	@Autowired
	private ProgramRepository programRepository;
	@Autowired
	private ProgramMentorRepository programMentorRepository;
	@Autowired
	private ProgramCoordinatorRepository programCoordinatorRepository;
	@Autowired
	private PeerMentorDocumentRepository peerMentorDocumentRepository;
	@Autowired
	private StoredDocumentRepository storedDocumentRepository;
	@MockitoBean
	private ObjectStorage objectStorage;

	private AppUser admin;
	private AppUser coordinator;
	private AppUser otherCoordinator;
	private AppUser mentor;
	private AppUser otherMentor;
	private AppUser mentee;
	private AppUser otherProgramMentee;

	@BeforeEach
	void setUp() {
		admin = user("Admin Test", UserRole.ADMIN, null);
		coordinator = user("Program Koordinatörü", UserRole.COORDINATOR, null);
		otherCoordinator = user("Diğer Koordinatör", UserRole.COORDINATOR, null);
		Program program = peerProgram("Akran Mentörlüğü 2026");
		Program otherProgram = peerProgram("Diğer Akran Programı");
		coordinatorOf(program, coordinator);
		coordinatorOf(otherProgram, otherCoordinator);

		mentor = peerMentor("Leyla Aksoy", program);
		otherMentor = peerMentor("Kemal Arslan", program);
		mentee = student("Deniz Kaya", program);
		otherProgramMentee = student("Ece Yılmaz", otherProgram);
		when(objectStorage.get(anyString())).thenReturn(PDF);
	}

	@Test
	void theMentorUploadsListsChangesAndDeletesTheirDocument() throws Exception {
		String body = upload(mentor, PeerEvidenceType.TRANSCRIPT, PeerDocumentVisibility.VERIFICATION_ONLY)
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.evidenceType").value("TRANSCRIPT"))
				.andExpect(jsonPath("$.visibility").value("VERIFICATION_ONLY"))
				.andExpect(jsonPath("$.originalFilename").value("transkript.pdf"))
				.andExpect(jsonPath("$.contentType").value("application/pdf"))
				.andReturn().getResponse().getContentAsString();
		UUID documentId = UUID.fromString(JsonPath.read(body, "$.id"));

		mockMvc.perform(get(MINE).with(authentication(signedIn(mentor))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].id").value(documentId.toString()));

		changeVisibility(mentor, documentId, PeerDocumentVisibility.PROFILE)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.visibility").value("PROFILE"));

		UUID fileId = peerMentorDocumentRepository.findById(documentId).orElseThrow().getDocumentId();
		String key = storedDocumentRepository.findById(fileId).orElseThrow().getS3Key();
		mockMvc.perform(delete(ONE, documentId).with(authentication(signedIn(mentor))))
				.andExpect(status().isNoContent());
		assertThat(peerMentorDocumentRepository.findById(documentId)).isEmpty();
		assertThat(storedDocumentRepository.findById(fileId)).isEmpty();
		verify(objectStorage).delete(key);
	}

	@Test
	void aPortfolioIsALinkThatListsAndDeletesLikeAFile() throws Exception {
		upload(mentor, PeerEvidenceType.PORTFOLIO, PeerDocumentVisibility.PROFILE)
				.andExpect(status().isBadRequest());
		for (String bad : List.of("javascript:alert(1)", "data:text/html,hi", "https://", "https://user:pw@evil.test",
				"https://evil.test/\u00a0x", " https://ok.test", "ftp://files.test")) {
			addLink(mentor, bad).andExpect(status().isBadRequest());
		}
		String body = addLink(mentor, "https://github.com/leyla")
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.evidenceType").value("PORTFOLIO"))
				.andExpect(jsonPath("$.url").value("https://github.com/leyla"))
				.andExpect(jsonPath("$.originalFilename").doesNotExist())
				.andReturn().getResponse().getContentAsString();
		UUID linkId = UUID.fromString(JsonPath.read(body, "$.id"));

		mockMvc.perform(get(OF_MENTOR, mentor.getId()).with(authentication(signedIn(mentee))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].url").value("https://github.com/leyla"));
		mockMvc.perform(get(CONTENT, linkId).with(authentication(signedIn(mentor))))
				.andExpect(status().isNotFound());
		mockMvc.perform(delete(ONE, linkId).with(authentication(signedIn(mentor))))
				.andExpect(status().isNoContent());
		assertThat(peerMentorDocumentRepository.findById(linkId)).isEmpty();
	}

	@Test
	void aProfileHoldsAtMostThirtyPieces() throws Exception {
		for (int i = 0; i < 30; i++) {
			addLink(mentor, "https://example.com/" + i).andExpect(status().isCreated());
		}
		addLink(mentor, "https://example.com/one-more").andExpect(status().isBadRequest());
		upload(mentor, PeerEvidenceType.CV, PeerDocumentVisibility.PROFILE).andExpect(status().isBadRequest());
	}

	@Test
	void aFileTheUploadsDoNotTakeIsRefused() throws Exception {
		MockMultipartFile script = new MockMultipartFile("file", "not-a.pdf", "application/pdf",
				"<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8));
		mockMvc.perform(multipart(MINE).file(script)
						.param("evidenceType", PeerEvidenceType.TRANSCRIPT.name())
						.param("visibility", PeerDocumentVisibility.PROFILE.name())
						.with(authentication(signedIn(mentor))))
				.andExpect(status().isBadRequest());
		assertThat(peerMentorDocumentRepository.findByUserIdOrderByCreatedAtAsc(mentor.getId())).isEmpty();
	}

	@Test
	void theOtherDocumentPathsNeitherTakeNorHandOutPeerEvidence() throws Exception {
		UUID documentId = uploaded(mentor, PeerDocumentVisibility.VERIFICATION_ONLY);
		UUID fileId = peerMentorDocumentRepository.findById(documentId).orElseThrow().getDocumentId();

		for (AppUser actor : List.of(otherCoordinator, admin, mentor)) {
			mockMvc.perform(get("/api/documents/{id}/download-url", fileId).with(authentication(signedIn(actor))))
					.andExpect(status().isNotFound());
		}
		MockMultipartFile file = new MockMultipartFile("file", "cv.pdf", "application/pdf", PDF);
		mockMvc.perform(multipart("/api/documents").file(file).param("kind", "peer-evidence")
						.with(authentication(signedIn(mentor))))
				.andExpect(status().isBadRequest());
		assertThat(storedDocumentRepository.findByUserIdAndKind(mentor.getId(), "peer-evidence")).hasSize(1);
	}

	@Test
	void aStudentWhoIsNotAPeerMentorCannotUpload() throws Exception {
		upload(mentee, PeerEvidenceType.CV, PeerDocumentVisibility.PROFILE)
				.andExpect(status().isForbidden());
	}

	@Test
	void anotherMentorCannotChangeOrDeleteSomeoneElsesDocument() throws Exception {
		UUID documentId = uploaded(mentor, PeerDocumentVisibility.PROFILE);

		changeVisibility(otherMentor, documentId, PeerDocumentVisibility.VERIFICATION_ONLY)
				.andExpect(status().isNotFound());
		mockMvc.perform(delete(ONE, documentId).with(authentication(signedIn(otherMentor))))
				.andExpect(status().isNotFound());
		assertThat(peerMentorDocumentRepository.findById(documentId)).get()
				.extracting(PeerMentorDocument::getVisibility)
				.isEqualTo(PeerDocumentVisibility.PROFILE);
	}

	@Test
	void theProgramsCoordinatorAndAdminsSeeEveryDocument() throws Exception {
		UUID hidden = uploaded(mentor, PeerDocumentVisibility.VERIFICATION_ONLY);
		uploaded(mentor, PeerDocumentVisibility.PROFILE);

		for (AppUser viewer : List.of(coordinator, admin)) {
			mockMvc.perform(get(OF_MENTOR, mentor.getId()).with(authentication(signedIn(viewer))))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.length()").value(2));
			mockMvc.perform(get(CONTENT, hidden).with(authentication(signedIn(viewer))))
					.andExpect(status().isOk());
		}
	}

	@Test
	void aStudentOfTheProgramSeesOnlyTheProfileDocuments() throws Exception {
		UUID hidden = uploaded(mentor, PeerDocumentVisibility.VERIFICATION_ONLY);
		UUID shown = uploaded(mentor, PeerDocumentVisibility.PROFILE);

		mockMvc.perform(get(OF_MENTOR, mentor.getId()).with(authentication(signedIn(mentee))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].id").value(shown.toString()));
		mockMvc.perform(get(CONTENT, shown).with(authentication(signedIn(mentee))))
				.andExpect(status().isOk())
				.andExpect(content().contentType(MediaType.APPLICATION_PDF))
				.andExpect(header().string("Content-Disposition", startsWith("attachment")))
				.andExpect(header().string("X-Content-Type-Options", "nosniff"))
				.andExpect(header().string("Cache-Control", containsString("no-store")))
				.andExpect(content().bytes(PDF));
		mockMvc.perform(get(CONTENT, hidden).with(authentication(signedIn(mentee))))
				.andExpect(status().isNotFound());
	}

	@Test
	void someoneOutsideTheProgramGetsNotFound() throws Exception {
		UUID shown = uploaded(mentor, PeerDocumentVisibility.PROFILE);

		for (AppUser outsider : List.of(otherProgramMentee, otherCoordinator)) {
			mockMvc.perform(get(OF_MENTOR, mentor.getId()).with(authentication(signedIn(outsider))))
					.andExpect(status().isNotFound());
			mockMvc.perform(get(CONTENT, shown).with(authentication(signedIn(outsider))))
					.andExpect(status().isNotFound());
		}
	}

	@Test
	void anotherStudentsIdIsNotFound() throws Exception {
		uploaded(mentor, PeerDocumentVisibility.PROFILE);

		mockMvc.perform(get(OF_MENTOR, mentee.getId()).with(authentication(signedIn(otherMentor))))
				.andExpect(status().isNotFound());
		mockMvc.perform(get(OF_MENTOR, otherProgramMentee.getId()).with(authentication(signedIn(mentee))))
				.andExpect(status().isNotFound());
		mockMvc.perform(get(CONTENT, UUID.randomUUID()).with(authentication(signedIn(mentor))))
				.andExpect(status().isNotFound());
	}

	private UUID uploaded(AppUser owner, PeerDocumentVisibility visibility) throws Exception {
		String body = upload(owner, PeerEvidenceType.TRANSCRIPT, visibility)
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return UUID.fromString(JsonPath.read(body, "$.id"));
	}

	private ResultActions upload(AppUser actor, PeerEvidenceType evidenceType, PeerDocumentVisibility visibility)
			throws Exception {
		MockMultipartFile file = new MockMultipartFile("file", "transkript.pdf", "application/pdf", PDF);
		return mockMvc.perform(multipart(MINE).file(file)
				.param("evidenceType", evidenceType.name())
				.param("visibility", visibility.name())
				.with(authentication(signedIn(actor))));
	}

	private ResultActions addLink(AppUser actor, String url) throws Exception {
		return mockMvc.perform(post(LINKS)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"url\":\"" + url + "\",\"visibility\":\"PROFILE\"}")
				.with(authentication(signedIn(actor))));
	}

	private ResultActions changeVisibility(AppUser actor, UUID documentId, PeerDocumentVisibility visibility)
			throws Exception {
		return mockMvc.perform(patch(ONE, documentId)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"visibility\":\"" + visibility.name() + "\"}")
				.with(authentication(signedIn(actor))));
	}

	private AppUser user(String name, UserRole role, UUID programId) {
		AppUser user = new AppUser();
		user.setFullName(name);
		user.setEmail(UUID.randomUUID() + "@peer-docs.test");
		user.setRole(role);
		user.setProgramId(programId);
		return userRepository.saveAndFlush(user);
	}

	/** A student who earned the Akran Mentoru badge, accepted into {@code in}. */
	private AppUser peerMentor(String name, Program in) {
		AppUser user = user(name, UserRole.MENTEE, in.getId());
		user.grant(UserRole.MENTOR, PermissionSource.BADGE, Instant.now());
		user = userRepository.saveAndFlush(user);
		onTheStudentRoster(user);
		ProgramMentor membership = new ProgramMentor();
		membership.setId(UUID.randomUUID());
		membership.setProgramId(in.getId());
		membership.setMentorId(user.getId());
		membership.setStatus(ProgramMentorStatus.ACCEPTED);
		membership.setInvitedBy(ProgramMentorInvitedBy.COORDINATOR);
		membership.setCreatedAt(Instant.now());
		membership.setDecidedAt(Instant.now());
		programMentorRepository.saveAndFlush(membership);
		return user;
	}

	private AppUser student(String name, Program in) {
		AppUser user = user(name, UserRole.MENTEE, in.getId());
		onTheStudentRoster(user);
		return user;
	}

	private void onTheStudentRoster(AppUser user) {
		InstitutionPerson person = new InstitutionPerson("odtu-test", "student-" + user.getId());
		person.setFullName(user.getFullName());
		person.setEmail(user.getEmail());
		person.setSource(InstitutionDataSource.IMPORT);
		person.setSyncedAt(Instant.now());
		person.setKinds(Set.of(IdentityKind.STUDENT));
		person.setUser(user);
		institutionPersonRepository.saveAndFlush(person);
	}

	private Program peerProgram(String name) {
		Program created = new Program();
		created.setId(UUID.randomUUID());
		created.setName(name);
		created.setCreatedBy(admin.getId());
		created.setCreatedAt(Instant.now());
		created.setStatus(ProgramStatus.ACTIVE);
		created.setProgramType(ProgramType.PEER_MENTORING);
		return programRepository.saveAndFlush(created);
	}

	private void coordinatorOf(Program coordinated, AppUser user) {
		ProgramCoordinator link = new ProgramCoordinator();
		link.setId(UUID.randomUUID());
		link.setProgramId(coordinated.getId());
		link.setUserId(user.getId());
		link.setAddedBy(admin.getId());
		link.setCreatedAt(Instant.now());
		programCoordinatorRepository.saveAndFlush(link);
	}

	private static UsernamePasswordAuthenticationToken signedIn(AppUser user) {
		return new UsernamePasswordAuthenticationToken(new AppUserPrincipal(
				user.getId(), user.getEmail(), user.getRole(), user.getStatus(), "peer-docs-test-uid"), null, List.of());
	}
}
