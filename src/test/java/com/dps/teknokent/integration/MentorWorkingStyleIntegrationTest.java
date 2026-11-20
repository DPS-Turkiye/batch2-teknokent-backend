package com.dps.teknokent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.MentorProfile;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.MentorProfileRepository;
import com.dps.teknokent.security.AppUserPrincipal;

import jakarta.persistence.EntityManager;
import tools.jackson.databind.json.JsonMapper;

/** "Nasıl çalışıyorum" holds a paragraph: up to 2000 characters are stored whole, a longer text is refused by name. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MentorWorkingStyleIntegrationTest {

	private static final String PROFILE = "/api/mentors/{id}";
	private static final String SENTENCE = "Haftalık kısa görüşmelerle ilerlerim, gündemi birlikte belirleriz. ";

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private JsonMapper jsonMapper;
	@Autowired
	private AppUserRepository userRepository;
	@Autowired
	private MentorProfileRepository mentorProfileRepository;
	@Autowired
	private EntityManager entityManager;

	private AppUser mentor;

	@BeforeEach
	void setUp() {
		AppUser user = new AppUser();
		user.setFullName("Deniz Yılmaz");
		user.setEmail(UUID.randomUUID() + "@working-style.test");
		user.setRole(UserRole.MENTOR);
		mentor = userRepository.saveAndFlush(user);
		mentorProfileRepository.saveAndFlush(MentorProfile.blankFor(mentor, 1));
	}

	@Test
	void storesAWorkingStyleOfTwoThousandCharacters() throws Exception {
		String style = text(2000);

		save(style).andExpect(status().isOk())
				.andExpect(jsonPath("$.mentoringStyle").value(style));

		// Read it back from the table, not from the session the request filled.
		entityManager.flush();
		entityManager.clear();
		assertThat(mentorProfileRepository.findOneById(mentor.getId()))
				.hasValueSatisfying(profile -> assertThat(profile.getMentoringStyle()).isEqualTo(style));
	}

	@Test
	void refusesALongerWorkingStyleAndNamesTheField() throws Exception {
		save(text(2001)).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.fieldErrors.mentoringStyle").exists());

		assertThat(mentorProfileRepository.findOneById(mentor.getId()))
				.hasValueSatisfying(profile -> assertThat(profile.getMentoringStyle()).isEmpty());
	}

	private ResultActions save(String mentoringStyle) throws Exception {
		var body = jsonMapper.createObjectNode()
				.put("professionalTitle", "Ürün Danışmanı")
				.put("company", "")
				.put("availabilityLabel", "")
				.put("responseTimeLabel", "")
				.put("meetingMode", "")
				.put("communicationPreference", "")
				.put("mentoringStyle", mentoringStyle)
				.put("fitLabel", "")
				.put("experienceLevel", "10 yıl")
				.put("sectorExperience", "")
				.put("startupStages", "Seed");
		body.putArray("specialties");
		return mockMvc.perform(put(PROFILE, mentor.getId())
				.with(authentication(signedIn(mentor)))
				.contentType(MediaType.APPLICATION_JSON)
				.content(jsonMapper.writeValueAsString(body)));
	}

	private static String text(int length) {
		return SENTENCE.repeat(length / SENTENCE.length() + 1).substring(0, length);
	}

	private static UsernamePasswordAuthenticationToken signedIn(AppUser user) {
		return new UsernamePasswordAuthenticationToken(new AppUserPrincipal(
				user.getId(), user.getEmail(), user.getRole(), user.getStatus(), "working-style-test-uid"), null,
				List.of());
	}
}
