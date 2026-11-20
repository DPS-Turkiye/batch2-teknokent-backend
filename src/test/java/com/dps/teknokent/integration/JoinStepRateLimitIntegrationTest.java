package com.dps.teknokent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.OrganizationRepository;
import com.dps.teknokent.security.AppUserPrincipal;
import com.dps.teknokent.security.FirebaseIdentity;

/** Guessing team codes where a code joins a team: sign-up's "Ekibe katıl" and "Kodum var". */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class JoinStepRateLimitIntegrationTest {

	private static final String TOO_MANY = "Çok fazla hatalı takım kodu denendi. 10 dakika bekleyip tekrar dene.";
	/** A code made before codes grew to six random characters; it must keep working. */
	private static final String OLD_CODE = "BIGG26-K4TX";

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private AppUserRepository userRepository;
	@Autowired
	private OrganizationRepository organizationRepository;

	private Organization team;

	@BeforeEach
	void setUp() {
		Organization organization = new Organization();
		organization.setName("Yıldız Team");
		organization.setInviteCode(OLD_CODE);
		team = organizationRepository.saveAndFlush(organization);
		AppUser founder = user("Merve Geçer", team);
		team.setFounder(founder);
		team = organizationRepository.saveAndFlush(team);
	}

	@Test
	void aSignupLoginIsStoppedAfterFiveWrongCodesAndTheRightCodeIsRefusedToo() throws Exception {
		String email = UUID.randomUUID() + "@join-limit.test";
		String uid = "uid-" + UUID.randomUUID();
		for (int i = 0; i < 5; i++) {
			register(uid, email, "WRONG-" + i)
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.message").value("Bu kodla bir startup bulunamadı."));
		}

		register(uid, email, OLD_CODE)
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.message").value(TOO_MANY));
		assertThat(userRepository.findByEmailIgnoreCase(email)).isEmpty();
	}

	@Test
	void anotherLoginStillJoinsWithAnOldFourCharacterCode() throws Exception {
		String email = UUID.randomUUID() + "@join-limit.test";

		register("uid-" + UUID.randomUUID(), email, " bigg26-k4tx ").andExpect(status().isCreated());

		AppUser joined = userRepository.findByEmailIgnoreCase(email).orElseThrow();
		assertThat(joined.getOrganization().getId()).isEqualTo(team.getId());
	}

	@Test
	void kodumVarIsStoppedAfterFiveWrongCodes() throws Exception {
		AppUser person = user("Ece Arı", null);
		for (int i = 0; i < 5; i++) {
			joinWithCode(person, "WRONG-" + i).andExpect(status().isBadRequest());
		}

		joinWithCode(person, OLD_CODE)
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.message").value(TOO_MANY));
		assertThat(userRepository.findById(person.getId()).orElseThrow().getOrganization()).isNull();
	}

	@Test
	void anotherProgramsCodeCountsLikeAWrongOneForKodumVar() throws Exception {
		AppUser person = user("Ece Arı", null);
		for (int i = 0; i < 5; i++) {
			joinWithCode(person, OLD_CODE).andExpect(status().isBadRequest());
		}

		joinWithCode(person, OLD_CODE).andExpect(status().isTooManyRequests());
	}

	private ResultActions register(String uid, String email, String code) throws Exception {
		return mockMvc.perform(post("/api/auth/register/member")
				.with(authentication(new UsernamePasswordAuthenticationToken(
						new FirebaseIdentity(uid, email, true), null,
						List.of(new SimpleGrantedAuthority("ROLE_REGISTERING")))))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"fullName": "Deniz Kaya", "email": "%s", "inviteCode": "%s"}
						""".formatted(email, code)));
	}

	private ResultActions joinWithCode(AppUser person, String code) throws Exception {
		return mockMvc.perform(post("/api/organizations/join")
				.with(authentication(new UsernamePasswordAuthenticationToken(new AppUserPrincipal(
						person.getId(), person.getEmail(), person.getRole(), person.getStatus(), "join-limit-uid"),
						null, List.of())))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"inviteCode\": \"" + code + "\"}"));
	}

	private AppUser user(String name, Organization organization) {
		AppUser user = new AppUser();
		user.setFullName(name);
		user.setEmail(UUID.randomUUID() + "@join-limit.test");
		user.setRole(UserRole.MENTEE);
		user.setStatus(UserAccountStatus.APPROVED);
		user.setOrganization(organization);
		return userRepository.saveAndFlush(user);
	}
}
