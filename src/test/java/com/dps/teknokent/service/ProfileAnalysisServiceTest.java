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

import com.dps.teknokent.entity.ProfileAnalysis;
import com.dps.teknokent.dto.requests.CreateManualProfileRequest;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.repository.OrganizationRepository;
import com.dps.teknokent.repository.ProfileAnalysisRepository;
import com.dps.teknokent.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;

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
				.findFirstByOrganizationIdAndProfileTypeAndStatusOrderByCreatedAtDesc(
						organizationId, "mentee", "CONFIRMED"))
				.thenReturn(Optional.of(analysis));

		var response = profileAnalysisService.findLatestConfirmedStartup(organizationId);

		assertThat(response.profile()).contains("Acme");
		assertThat(response.status()).isEqualTo("CONFIRMED");
		verify(profileAnalysisRepository)
				.findFirstByOrganizationIdAndProfileTypeAndStatusOrderByCreatedAtDesc(
						organizationId, "mentee", "CONFIRMED");
	}

	@Test
	void parseRequiresExplicitConsent() {
		assertThatThrownBy(() -> profileAnalysisService.parse(
				UUID.randomUUID(), "mentee", false, "auto", null))
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
				new CreateManualProfileRequest(
						"mentee",
						objectMapper.readTree("{\"startup_name\":\"Manual AI\",\"startup_summary\":\"Test\"}")));

		assertThat(response.status()).isEqualTo("CONFIRMED");
		assertThat(response.sourceFilename()).isEqualTo("manual-entry");
		assertThat(response.profile()).contains("Manual AI");
	}
}
