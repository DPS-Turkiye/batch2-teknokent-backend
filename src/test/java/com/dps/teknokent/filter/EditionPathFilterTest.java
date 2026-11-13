package com.dps.teknokent.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.dps.teknokent.domain.Edition;

import tools.jackson.databind.json.JsonMapper;

class EditionPathFilterTest {

	private static final String ID = "00000000-0000-0000-0000-000000000001";

	/** Served only by the ODTÜ deployment. */
	static Stream<String> odtuPaths() {
		return Stream.of(
				"/api/peer-mentoring/mentors",
				"/api/peer-mentoring",
				"/api/student/profile-completion",
				"/api/training/peer/quiz",
				"/api/badges/mine",
				"/api/badges/",
				"/api/institution/me/advisees",
				"/api/institution/me/advisees/" + ID + "/profile",
				"/api/auth/institution/odtu/start",
				"/api/auth/institution/odtu/dev-personas",
				"/api/admin/academics",
				"/api/admin/students",
				"/api/lti",
				"/api/lti/jwks",
				"/api/lti/launches",
				"/api/office-hours/requests",
				"/api/coordinator/programs/" + ID + "/academics",
				"/api/coordinator/programs/" + ID + "/badges/awards",
				"/api/coordinator/programs/" + ID + "/peer-categories",
				"/api/coordinator/programs/" + ID + "/training",
				"/api/users/" + ID + "/badges");
	}

	/** Served only by the Teknokent deployment. */
	static Stream<String> teknokentPaths() {
		return Stream.of(
				"/api/admin/programs/" + ID + "/mentors/" + ID + "/remove",
				"/api/admin/programs/" + ID + "/startups/" + ID + "/move",
				"/api/admin/users/" + ID + "/delete",
				"/api/assistant/bots",
				"/api/assistant/transcripts/google_meet/abc-defg-hij",
				"/api/auth/institution/otg/start",
				"/api/auth/register/mentor",
				"/api/auth/register/member",
				"/api/bottlenecks/" + ID,
				"/api/meetings/" + ID + "/assistant-share",
				"/api/meetings/" + ID + "/assistant-share/report-upload",
				"/api/organizations/" + ID + "/bottlenecks",
				"/api/organizations/" + ID + "/progress");
	}

	/** The core both deployments run, including look-alikes of the closed prefixes. */
	static Stream<String> sharedPaths() {
		return Stream.of(
				"/api/health",
				"/api/auth/me",
				"/api/auth/institution/exchange",
				"/api/auth/register/founder",
				"/api/auth/register/program-eligibility",
				"/api/mentorship-requests",
				"/api/mentors",
				"/api/meetings",
				"/api/meetings/" + ID + "/note",
				"/api/programs/mine",
				"/api/organizations/" + ID + "/process",
				"/api/organizations/" + ID + "/documents",
				"/api/coordinator/programs/" + ID,
				"/api/coordinator/programs/" + ID + "/weeks",
				"/api/coordinator/programs/syllabus/phases",
				"/api/users/" + ID,
				"/api/admin/coordinators",
				"/api/badgesmine",
				"/api/lti-docs",
				"/ws/info");
	}

	static Stream<Arguments> closed() {
		return Stream.concat(
				odtuPaths().map(path -> Arguments.of(Edition.TEKNOKENT, path)),
				teknokentPaths().map(path -> Arguments.of(Edition.ODTU, path)));
	}

	static Stream<Arguments> open() {
		return Stream.of(
				odtuPaths().map(path -> Arguments.of(Edition.ODTU, path)),
				teknokentPaths().map(path -> Arguments.of(Edition.TEKNOKENT, path)),
				sharedPaths().map(path -> Arguments.of(Edition.TEKNOKENT, path)),
				sharedPaths().map(path -> Arguments.of(Edition.ODTU, path)))
				.flatMap(stream -> stream);
	}

	@ParameterizedTest(name = "{0} answers 404 for {1}")
	@MethodSource("closed")
	void answersNotFoundForTheOtherEditionsPaths(Edition edition, String path) throws Exception {
		MockFilterChain chain = new MockFilterChain();
		MockHttpServletResponse response = run(edition, path, chain);

		assertThat(response.getStatus()).isEqualTo(404);
		assertThat(response.getContentAsString()).contains("\"status\":404").contains("\"path\":\"" + path + "\"");
		assertThat(chain.getRequest()).isNull();
	}

	@ParameterizedTest(name = "{0} serves {1}")
	@MethodSource("open")
	void passesItsOwnAndTheSharedPathsOn(Edition edition, String path) throws Exception {
		MockFilterChain chain = new MockFilterChain();
		MockHttpServletResponse response = run(edition, path, chain);

		assertThat(chain.getRequest()).isNotNull();
		assertThat(response.getStatus()).isEqualTo(200);
	}

	private static MockHttpServletResponse run(Edition edition, String path, MockFilterChain chain)
			throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
		MockHttpServletResponse response = new MockHttpServletResponse();
		new EditionPathFilter(edition, JsonMapper.builder().build()).doFilter(request, response, chain);
		return response;
	}
}
