package com.dps.teknokent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.AppLanguage;
import com.dps.teknokent.domain.PlatformFeedbackKind;
import com.dps.teknokent.domain.ProgramType;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.PlatformFeedback;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.PlatformFeedbackRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.security.AppUserPrincipal;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PlatformFeedbackIntegrationTest {

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private AppUserRepository userRepository;
	@Autowired
	private PlatformFeedbackRepository feedbackRepository;
	@Autowired
	private ProgramRepository programRepository;

	private AppUser author;
	private AppUser admin;

	@BeforeEach
	void setUp() {
		feedbackRepository.deleteAll();
		author = user(UserRole.MENTEE);
		admin = user(UserRole.ADMIN);
	}

	@Test
	void requiresAuthenticationForSubmissionInboxAndRead() throws Exception {
		mockMvc.perform(post("/api/platform-feedback").contentType(MediaType.APPLICATION_JSON).content(body()))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/admin/platform-feedback")).andExpect(status().isUnauthorized());
		mockMvc.perform(patch("/api/admin/platform-feedback/{id}/read", UUID.randomUUID()))
				.andExpect(status().isUnauthorized());
		assertThat(feedbackRepository.count()).isZero();
	}

	@ParameterizedTest
	@EnumSource(UserRole.class)
	void approvedAccountsSubmitFeedbackWithTheirVerifiedIdentity(UserRole role) throws Exception {
		author.setRole(role);
		mockMvc.perform(post("/api/platform-feedback")
				.with(authentication(signedIn(author)))
				.contentType(MediaType.APPLICATION_JSON)
				.content(body().replace("\"kind\"", "\"actorId\":\"" + admin.getId() + "\",\"kind\"")))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.subject").value("A better dashboard"))
				.andExpect(jsonPath("$.message").value("Please add a shortcut."))
				.andExpect(jsonPath("$.language").value("en"))
				.andExpect(jsonPath("$.authorEmail").value(author.getEmail()))
				.andExpect(jsonPath("$.authorRole").value(role.name()))
				.andExpect(jsonPath("$.createdAt").exists());
		PlatformFeedback saved = feedbackRepository.findAll().getFirst();
		assertThat(saved.getAuthor().getId()).isEqualTo(author.getId());
		assertThat(saved.getReadAt()).isNull();
	}

	@Test
	void theServerProxyCanSubmitOnBehalfOfItsSessionUser() throws Exception {
		mockMvc.perform(post("/api/platform-feedback")
				.with(httpBasic("test", "test"))
				.contentType(MediaType.APPLICATION_JSON)
				.content(body().replace("\"kind\"", "\"actorId\":\"" + author.getId() + "\",\"kind\"")))
				.andExpect(status().isCreated());
		assertThat(feedbackRepository.findAll().getFirst().getAuthor().getId()).isEqualTo(author.getId());
	}

	@ParameterizedTest
	@EnumSource(value = UserAccountStatus.class, names = { "PENDING", "REJECTED" })
	void unapprovedAccountsCannotSubmitOrReadTheInbox(UserAccountStatus accountStatus) throws Exception {
		author.setStatus(accountStatus);
		admin.setStatus(accountStatus);
		mockMvc.perform(post("/api/platform-feedback").with(authentication(signedIn(author)))
				.contentType(MediaType.APPLICATION_JSON).content(body()))
				.andExpect(status().isForbidden());
		mockMvc.perform(get("/api/admin/platform-feedback").with(authentication(signedIn(admin))))
				.andExpect(status().isForbidden());
		assertThat(feedbackRepository.count()).isZero();
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"{\"kind\":\"UNKNOWN\",\"subject\":\"Hello\",\"message\":\"A message\",\"language\":\"en\"}",
			"{\"kind\":\"FEEDBACK\",\"subject\":\"  \",\"message\":\"A message\",\"language\":\"tr\"}",
			"{\"kind\":\"SUGGESTION\",\"subject\":\"Hello\",\"message\":\"  \",\"language\":\"en\"}",
			"{\"kind\":\"FEEDBACK\",\"subject\":\"Hello\",\"message\":\"A message\",\"language\":\"de\"}",
			"{}", "not-json"
	})
	void invalidSubmissionsDoNotPersist(String content) throws Exception {
		mockMvc.perform(post("/api/platform-feedback").with(authentication(signedIn(author)))
				.contentType(MediaType.APPLICATION_JSON).content(content))
				.andExpect(status().isBadRequest());
		assertThat(feedbackRepository.count()).isZero();
	}

	@Test
	void enforcesSubjectAndMessageLimits() throws Exception {
		for (String content : List.of(body().replace("  A better dashboard  ", "x".repeat(121)),
				body().replace("  Please add a shortcut.  ", "x".repeat(2001)))) {
			mockMvc.perform(post("/api/platform-feedback").with(authentication(signedIn(author)))
					.contentType(MediaType.APPLICATION_JSON).content(content))
					.andExpect(status().isBadRequest());
		}
		assertThat(feedbackRepository.count()).isZero();
	}

	@ParameterizedTest
	@EnumSource(value = UserRole.class, names = { "MENTEE", "MENTOR", "COORDINATOR" })
	void onlyAdminsCanReadOrMarkFeedbackAndCannotSpoofAnAdmin(UserRole role) throws Exception {
		author.setRole(role);
		PlatformFeedback saved = feedback();
		mockMvc.perform(get("/api/admin/platform-feedback").param("actorId", admin.getId().toString())
				.with(authentication(signedIn(author))))
				.andExpect(status().isForbidden());
		mockMvc.perform(patch("/api/admin/platform-feedback/{id}/read", saved.getId())
				.param("actorId", admin.getId().toString()).with(authentication(signedIn(author))))
				.andExpect(status().isForbidden());
		assertThat(saved.getReadAt()).isNull();
	}

	@Test
	void adminsPageThroughMessagesWithAuthorDetailsAndUnreadCounts() throws Exception {
		for (int index = 0; index < 21; index++) feedback();
		mockMvc.perform(get("/api/admin/platform-feedback").with(authentication(signedIn(admin))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.page.content", hasSize(20)))
				.andExpect(jsonPath("$.page.content[0].authorName").value(author.getFullName()))
				.andExpect(jsonPath("$.page.totalElements").value(21))
				.andExpect(jsonPath("$.page.totalPages").value(2))
				.andExpect(jsonPath("$.unreadCount").value(21));
		mockMvc.perform(get("/api/admin/platform-feedback").param("page", "1")
				.with(authentication(signedIn(admin))))
				.andExpect(status().isOk()).andExpect(jsonPath("$.page.content", hasSize(1)));
	}

	@Test
	void markingReadPersistsAndIsIdempotentAndUpdatesTheUnreadFilter() throws Exception {
		PlatformFeedback saved = feedback();
		for (int index = 0; index < 2; index++) {
			mockMvc.perform(patch("/api/admin/platform-feedback/{id}/read", saved.getId())
					.with(authentication(signedIn(admin))))
					.andExpect(status().isOk()).andExpect(jsonPath("$.readAt").exists());
		}
		Instant readAt = saved.getReadAt();
		assertThat(readAt).isNotNull();
		mockMvc.perform(get("/api/admin/platform-feedback").param("unreadOnly", "true")
				.with(authentication(signedIn(admin))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.page.content", hasSize(0)))
				.andExpect(jsonPath("$.unreadCount").value(0));
		mockMvc.perform(patch("/api/admin/platform-feedback/{id}/read", saved.getId())
				.with(authentication(signedIn(admin)))).andExpect(status().isOk());
		assertThat(saved.getReadAt()).isEqualTo(readAt);
	}

	@Test
	void rejectsInvalidPagesAndMissingFeedback() throws Exception {
		for (String page : List.of("-1", "10001", "not-a-number")) {
			mockMvc.perform(get("/api/admin/platform-feedback").param("page", page)
					.with(authentication(signedIn(admin)))).andExpect(status().isBadRequest());
		}
		mockMvc.perform(patch("/api/admin/platform-feedback/{id}/read", UUID.randomUUID())
				.with(authentication(signedIn(admin)))).andExpect(status().isNotFound());
	}

	@Test
	void permissionErrorsFollowTheSelectedLanguage() throws Exception {
		mockMvc.perform(get("/api/admin/platform-feedback").header("Accept-Language", "en")
				.with(authentication(signedIn(author))))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.message").value("Only admins can access the feedback inbox."));
		mockMvc.perform(get("/api/admin/platform-feedback").header("Accept-Language", "tr")
				.with(authentication(signedIn(author))))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.message").value("Geri bildirim kutusunu yalnızca yöneticiler görebilir."));
	}

	@Test
	void feedbackKeepsTheAuthorsProgramAndTheInboxFiltersByItsType() throws Exception {
		Program peer = new Program();
		peer.setId(UUID.randomUUID());
		peer.setName("İŞKUR Akran 2026");
		peer.setCreatedBy(admin.getId());
		peer.setCreatedAt(Instant.now());
		peer.setProgramType(ProgramType.PEER_MENTORING);
		programRepository.saveAndFlush(peer);
		author.setProgramId(peer.getId());
		userRepository.saveAndFlush(author);
		AppUser outsider = user(UserRole.MENTEE);

		mockMvc.perform(post("/api/platform-feedback").with(authentication(signedIn(author)))
				.contentType(MediaType.APPLICATION_JSON).content(body()))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.programName").value("İŞKUR Akran 2026"))
				.andExpect(jsonPath("$.programType").value("PEER_MENTORING"));
		mockMvc.perform(post("/api/platform-feedback").with(authentication(signedIn(outsider)))
				.contentType(MediaType.APPLICATION_JSON).content(body()))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.programType").value(org.hamcrest.Matchers.nullValue()));

		mockMvc.perform(get("/api/admin/platform-feedback").param("programType", "PEER_MENTORING")
				.with(authentication(signedIn(admin))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.page.content.length()").value(1))
				.andExpect(jsonPath("$.page.content[0].programId").value(peer.getId().toString()));
		mockMvc.perform(get("/api/admin/platform-feedback").param("programType", "STARTUP_MENTORING")
				.with(authentication(signedIn(admin))))
				.andExpect(jsonPath("$.page.content.length()").value(0));
		mockMvc.perform(get("/api/admin/platform-feedback").with(authentication(signedIn(admin))))
				.andExpect(jsonPath("$.page.content.length()").value(2));
	}

	private AppUser user(UserRole role) {
		AppUser user = new AppUser();
		user.setFullName("Feedback Test " + role);
		user.setEmail(UUID.randomUUID() + "@feedback.test");
		user.setRole(role);
		return userRepository.saveAndFlush(user);
	}

	private PlatformFeedback feedback() {
		PlatformFeedback feedback = new PlatformFeedback();
		feedback.setAuthor(author);
		feedback.setKind(PlatformFeedbackKind.SUGGESTION);
		feedback.setSubject("Calendar shortcut");
		feedback.setMessage("Please add a shortcut.");
		feedback.setLanguage(AppLanguage.EN);
		return feedbackRepository.saveAndFlush(feedback);
	}

	private static String body() {
		return """
				{"kind":"FEEDBACK","subject":"  A better dashboard  ","message":"  Please add a shortcut.  ","language":"en"}
				""";
	}

	private static UsernamePasswordAuthenticationToken signedIn(AppUser user) {
		return new UsernamePasswordAuthenticationToken(new AppUserPrincipal(
				user.getId(), user.getEmail(), user.getRole(), user.getStatus(), "feedback-test-uid"), null, List.of());
	}
}
