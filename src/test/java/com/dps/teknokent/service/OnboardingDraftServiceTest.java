package com.dps.teknokent.service;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dps.teknokent.dto.requests.UpsertOnboardingDraftRequest;
import com.dps.teknokent.entity.OnboardingDraft;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.repository.OnboardingDraftRepository;
import com.dps.teknokent.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OnboardingDraftServiceTest {

	@Mock
	private OnboardingDraftRepository onboardingDraftRepository;

	private final JsonUtils jsonUtils = new JsonUtils(new ObjectMapper());

	private OnboardingDraftService service() {
		return new OnboardingDraftService(onboardingDraftRepository, jsonUtils);
	}

	@Test
	void returnsEmptyWhenNothingHasBeenSaved() {
		when(onboardingDraftRepository.findById("uid-1")).thenReturn(Optional.empty());

		assertThat(service().find("uid-1")).isEmpty();
	}

	@Test
	void upsertsANewDraftForTheFirebaseUser() {
		when(onboardingDraftRepository.findById("uid-1")).thenReturn(Optional.empty());
		when(onboardingDraftRepository.saveAndFlush(any(OnboardingDraft.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));

		var response = service().upsert("uid-1", new UpsertOnboardingDraftRequest(
				"signup-wizard", "2", Map.of("bio", "Kurucu")));

		assertThat(response.kind()).isEqualTo("signup-wizard");
		assertThat(response.step()).isEqualTo("2");
		assertThat(response.payload()).containsEntry("bio", "Kurucu");

		ArgumentCaptor<OnboardingDraft> saved = ArgumentCaptor.forClass(OnboardingDraft.class);
		verify(onboardingDraftRepository).saveAndFlush(saved.capture());
		assertThat(saved.getValue().getFirebaseUid()).isEqualTo("uid-1");
		assertThat(saved.getValue().getPayload()).contains("Kurucu");
	}

	@Test
	void overwritesAnExistingDraft() {
		OnboardingDraft existing = OnboardingDraft.forUser("uid-1");
		existing.setKind("signup-wizard");
		existing.setStep("1");
		existing.setPayload("{\"bio\":\"eski\"}");
		when(onboardingDraftRepository.findById("uid-1")).thenReturn(Optional.of(existing));
		when(onboardingDraftRepository.saveAndFlush(any(OnboardingDraft.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));

		service().upsert("uid-1", new UpsertOnboardingDraftRequest(
				"mentor-onboarding", "3", Map.of("bio", "yeni")));

		assertThat(existing.getKind()).isEqualTo("mentor-onboarding");
		assertThat(existing.getStep()).isEqualTo("3");
		assertThat(existing.getPayload()).contains("yeni");
	}

	@Test
	void rejectsAnUnknownKind() {
		assertThatThrownBy(() -> service().upsert("uid-1", new UpsertOnboardingDraftRequest(
				"other", "1", Map.of())))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("kind");
		verify(onboardingDraftRepository, never()).saveAndFlush(any());
	}

	@Test
	void rejectsAPayloadThatIsTooLarge() {
		assertThatThrownBy(() -> service().upsert("uid-1", new UpsertOnboardingDraftRequest(
				"signup-wizard", "1", Map.of("bio", "x".repeat(OnboardingDraftService.MAX_PAYLOAD_CHARS)))))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("too large");
	}

	@Test
	void deletesByFirebaseUid() {
		service().delete("uid-1");
		verify(onboardingDraftRepository).deleteById("uid-1");
	}
}
