package com.dps.teknokent.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import com.dps.teknokent.dto.requests.UpdateMentorProfileRequest;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.security.AppUserPrincipal;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.dps.teknokent.config.SecurityConfig;
import com.dps.teknokent.dto.responses.MentorProfileResponse;
import com.dps.teknokent.dto.responses.MentorRatingResponse;
import com.dps.teknokent.service.MentorProfileService;
import com.dps.teknokent.service.MentorRatingService;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MentorProfileController.class)
@Import(SecurityConfig.class)
class MentorProfileControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private MentorProfileService mentorProfileService;
	@MockitoBean
	private MentorRatingService mentorRatingService;

	@Test
	void requiresAuthentication() throws Exception {
		mockMvc.perform(get("/api/mentors"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void returnsMentorProfiles() throws Exception {
		UUID mentorId = UUID.fromString("00000000-0000-0000-0000-000000000002");
		when(mentorProfileService.findAll()).thenReturn(List.of(new MentorProfileResponse(
				mentorId,
				"Dr. Kemal Arslan",
				"kemal.arslan@teknokent.demo",
				"DK",
				"Ürün & Araştırma Direktörü",
				"Trendyol",
				"Bu hafta müsait",
				"~24 saat",
				"Online",
				"Mesajlaşma açık",
				"Yapılandırılmış & Uygulamalı",
				"Çok yüksek",
				"Kullanıcı testi, UX",
				"E-ticaret, SaaS",
				"Seed & MVP",
				List.of("Kullanıcı araştırması"),
				List.of("Kullanıcı testi süreçlerinde deneyimli"),
				List.of())));

		mockMvc.perform(get("/api/mentors").with(user("test-user")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].id").value(mentorId.toString()))
				.andExpect(jsonPath("$[0].fullName").value("Dr. Kemal Arslan"))
				.andExpect(jsonPath("$[0].specialties[0]").value("Kullanıcı araştırması"));
	}

	private static final String PROFILE_UPDATE = """
			{
			  "professionalTitle": "Ürün Danışmanı",
			  "company": "Looply",
			  "availabilityLabel": "",
			  "responseTimeLabel": "",
			  "meetingMode": "",
			  "communicationPreference": "",
			  "mentoringStyle": "Yapılandırılmış",
			  "fitLabel": "",
			  "experienceLevel": "Kıdemli",
			  "sectorExperience": "SaaS",
			  "startupStages": "Seed",
			  "specialties": []
			}
			""";

	@Test
	void anyoneSignedInCanReadAMentorsRating() throws Exception {
		UUID mentor = UUID.fromString("00000000-0000-0000-0000-000000000701");
		when(mentorRatingService.rating(mentor)).thenReturn(new MentorRatingResponse(4.8, 12, java.util.List.of()));

		mockMvc.perform(get("/api/mentors/{id}/rating", mentor)
						.with(authentication(signedInAs(UUID.fromString("00000000-0000-0000-0000-000000000801")))))
				.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
				.andExpect(jsonPath("$.average").value(4.8))
				.andExpect(jsonPath("$.feedbackCount").value(12));
	}

	private static UsernamePasswordAuthenticationToken signedInAs(UUID userId) {
		return new UsernamePasswordAuthenticationToken(
				new AppUserPrincipal(userId, "mentor@looply.co", UserRole.MENTOR, UserAccountStatus.APPROVED, "uid"),
				null, List.of());
	}

	@Test
	void refusesToEditAnotherMentorsProfile() throws Exception {
		UUID signedIn = UUID.fromString("00000000-0000-0000-0000-000000000701");
		UUID otherMentor = UUID.fromString("00000000-0000-0000-0000-000000000702");

		mockMvc.perform(put("/api/mentors/{id}", otherMentor)
						.with(authentication(signedInAs(signedIn)))
						.contentType(MediaType.APPLICATION_JSON)
						.content(PROFILE_UPDATE))
				.andExpect(status().isBadRequest());

		verifyNoInteractions(mentorProfileService);
	}

	@Test
	void letsAMentorEditTheirOwnProfile() throws Exception {
		UUID signedIn = UUID.fromString("00000000-0000-0000-0000-000000000701");

		mockMvc.perform(put("/api/mentors/{id}", signedIn)
						.with(authentication(signedInAs(signedIn)))
						.contentType(MediaType.APPLICATION_JSON)
						.content(PROFILE_UPDATE))
				.andExpect(status().isOk());

		verify(mentorProfileService).updateProfile(eq(signedIn), any(UpdateMentorProfileRequest.class));
	}

	@Test
	void refusesToReadAnotherMentorsAvailabilitySettings() throws Exception {
		UUID signedIn = UUID.fromString("00000000-0000-0000-0000-000000000801");
		UUID otherMentor = UUID.fromString("00000000-0000-0000-0000-000000000802");

		mockMvc.perform(get("/api/mentors/{id}/availability-settings", otherMentor)
						.with(authentication(signedInAs(signedIn))))
				.andExpect(status().isBadRequest());

		verifyNoInteractions(mentorProfileService);
	}

	@Test
	void letsAMentorReadTheirOwnAvailabilitySettings() throws Exception {
		UUID signedIn = UUID.fromString("00000000-0000-0000-0000-000000000801");

		mockMvc.perform(get("/api/mentors/{id}/availability-settings", signedIn)
						.with(authentication(signedInAs(signedIn))))
				.andExpect(status().isOk());

		verify(mentorProfileService).findAvailabilitySettings(signedIn);
	}

	@Test
	void refusesToSaveAnotherMentorsAvailabilitySettings() throws Exception {
		UUID signedIn = UUID.fromString("00000000-0000-0000-0000-000000000801");
		UUID otherMentor = UUID.fromString("00000000-0000-0000-0000-000000000802");

		mockMvc.perform(put("/api/mentors/{id}/availability-settings", otherMentor)
						.with(authentication(signedInAs(signedIn)))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"rules": [], "exceptions": [], "menteeCapacity": null,
								 "defaultMeetingDurationMinutes": 45, "meetingBufferMinutes": 15}
								"""))
				.andExpect(status().isBadRequest());

		verifyNoInteractions(mentorProfileService);
	}
}
