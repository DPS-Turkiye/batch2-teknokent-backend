package com.dps.teknokent.service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.ProfileAnalysis;
import com.dps.teknokent.dto.requests.CreateManualProfileRequest;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.OrganizationRepository;
import com.dps.teknokent.repository.ProfileAnalysisRepository;
import com.dps.teknokent.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProfileAnalysisServiceTest {
	private final ObjectMapper objectMapper = new ObjectMapper();
	@Spy
	private JsonUtils jsonUtils = new JsonUtils(objectMapper);

	@Mock
	private ProfileAnalysisRepository profileAnalysisRepository;

	@Mock
	private OrganizationRepository organizationRepository;
	@Mock
	private AppUserRepository appUserRepository;
	@Mock
	private TechnologyInferenceService technologyInferenceService;
	@Mock
	private NativeProfileParserService nativeProfileParserService;
	@Mock
	private MentorRecommendationService mentorRecommendationService;

	@InjectMocks
	private ProfileAnalysisService profileAnalysisService;

	@Test
	void latestConfirmedStartupUsesOnlyConfirmedMenteeProfiles() {
		UUID organizationId = UUID.randomUUID();
		ProfileAnalysis analysis = new ProfileAnalysis();
		analysis.setId(UUID.randomUUID());
		analysis.setOrganizationId(organizationId);
		analysis.setSourceFilename("pitch-deck.pdf");
		analysis.setProfileType("mentee");
		analysis.setParsedProfile("{}");
		analysis.setConfirmedProfile("{\"startup_name\":\"Acme\"}");
		analysis.setStatus("CONFIRMED");
		analysis.setCreatedAt(Instant.now());
		when(profileAnalysisRepository
				.findFirstByOrganizationIdAndAppUserIdIsNullAndProfileTypeAndStatusOrderByCreatedAtDesc(
						organizationId, "mentee", "CONFIRMED"))
				.thenReturn(Optional.of(analysis));

		var response = profileAnalysisService.findLatestConfirmedStartup(organizationId);

		assertThat(response.profile()).contains("Acme");
		assertThat(response.status()).isEqualTo("CONFIRMED");
		verify(profileAnalysisRepository)
				.findFirstByOrganizationIdAndAppUserIdIsNullAndProfileTypeAndStatusOrderByCreatedAtDesc(
						organizationId, "mentee", "CONFIRMED");
	}

	@Test
	void parseRequiresExplicitConsent() {
		assertThatThrownBy(() -> profileAnalysisService.parse(
				UUID.randomUUID(), null, "mentee", false, "auto", null))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("consent");
	}

	@Test
	void manualFallbackCreatesConfirmedProfile() throws Exception {
		UUID organizationId = UUID.randomUUID();
		when(organizationRepository.existsById(organizationId)).thenReturn(true);
		when(profileAnalysisRepository.save(any(ProfileAnalysis.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));

		var response = profileAnalysisService.createManual(
				organizationId,
				null,
				new CreateManualProfileRequest(
						"mentee",
						objectMapper.readTree("{\"startup_name\":\"Manual AI\",\"startup_summary\":\"Test\"}")));

		assertThat(response.status()).isEqualTo("CONFIRMED");
		assertThat(response.sourceFilename()).isEqualTo("manual-entry");
		assertThat(response.profile()).contains("Manual AI");
	}

	@Test
	void manualCreateRejectsUserOutsideTheOrganization() throws Exception {
		UUID organizationId = UUID.randomUUID();
		UUID otherOrganizationId = UUID.randomUUID();
		UUID appUserId = UUID.randomUUID();
		when(organizationRepository.existsById(organizationId)).thenReturn(true);
		Organization otherOrganization = new Organization();
		otherOrganization.setId(otherOrganizationId);
		AppUser outsider = new AppUser();
		outsider.setId(appUserId);
		outsider.setOrganization(otherOrganization);
		when(appUserRepository.findById(appUserId)).thenReturn(Optional.of(outsider));

		assertThatThrownBy(() -> profileAnalysisService.createManual(
				organizationId,
				appUserId,
				new CreateManualProfileRequest(
						"mentee",
						objectMapper.readTree("{\"startup_name\":\"Manual AI\",\"startup_summary\":\"Test\"}"))))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("not a member");
	}

	@Test
	void manualCreateScopesToAppUserWhenProvided() throws Exception {
		UUID organizationId = UUID.randomUUID();
		UUID appUserId = UUID.randomUUID();
		when(organizationRepository.existsById(organizationId)).thenReturn(true);
		Organization organization = new Organization();
		organization.setId(organizationId);
		AppUser member = new AppUser();
		member.setId(appUserId);
		member.setOrganization(organization);
		when(appUserRepository.findById(appUserId)).thenReturn(Optional.of(member));
		when(profileAnalysisRepository.save(any(ProfileAnalysis.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));

		var response = profileAnalysisService.createManual(
				organizationId,
				appUserId,
				new CreateManualProfileRequest(
						"mentee",
						objectMapper.readTree("{\"startup_name\":\"Manual AI\",\"startup_summary\":\"Test\"}")));

		assertThat(response.appUserId()).isEqualTo(appUserId);
	}

	@Test
	void findLatestForSelfNeverReturnsATeamMembersCv() {
		UUID organizationId = UUID.randomUUID();
		ProfileAnalysis orgOwnAnalysis = new ProfileAnalysis();
		orgOwnAnalysis.setId(UUID.randomUUID());
		orgOwnAnalysis.setOrganizationId(organizationId);
		orgOwnAnalysis.setSourceFilename("founder-pitch.pdf");
		orgOwnAnalysis.setProfileType("mentee");
		orgOwnAnalysis.setParsedProfile("{}");
		orgOwnAnalysis.setCreatedAt(Instant.now());
		when(profileAnalysisRepository.findFirstByOrganizationIdAndAppUserIdIsNullOrderByCreatedAtDesc(organizationId))
				.thenReturn(Optional.of(orgOwnAnalysis));

		profileAnalysisService.findLatest(organizationId, null);

		verify(profileAnalysisRepository)
				.findFirstByOrganizationIdAndAppUserIdIsNullOrderByCreatedAtDesc(organizationId);
		verify(profileAnalysisRepository, org.mockito.Mockito.never())
				.findFirstByAppUserIdOrderByCreatedAtDesc(any());
	}

	@Test
	void findLatestScopesToAppUserWhenProvided() {
		UUID appUserId = UUID.randomUUID();
		ProfileAnalysis analysis = new ProfileAnalysis();
		analysis.setId(UUID.randomUUID());
		analysis.setOrganizationId(UUID.randomUUID());
		analysis.setAppUserId(appUserId);
		analysis.setSourceFilename("member.pdf");
		analysis.setProfileType("mentee");
		analysis.setParsedProfile("{}");
		analysis.setCreatedAt(java.time.Instant.now());
		when(profileAnalysisRepository.findFirstByAppUserIdOrderByCreatedAtDesc(appUserId))
				.thenReturn(Optional.of(analysis));

		var response = profileAnalysisService.findLatest(null, appUserId);

		assertThat(response.appUserId()).isEqualTo(appUserId);
		verify(profileAnalysisRepository, org.mockito.Mockito.never())
				.findFirstByOrganizationIdAndAppUserIdIsNullOrderByCreatedAtDesc(any());
	}
}
