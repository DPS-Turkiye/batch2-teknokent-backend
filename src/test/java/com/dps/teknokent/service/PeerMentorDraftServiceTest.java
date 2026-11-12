package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.dps.teknokent.domain.PeerExperienceKind;
import com.dps.teknokent.domain.PeerTopicCategory;
import com.dps.teknokent.domain.PeerTopicLevel;
import com.dps.teknokent.dto.requests.PeerMentorProfileDraft;
import com.dps.teknokent.dto.responses.PeerMentorProfileDraftResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.PeerMentorDraft;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.PeerMentorDraftRepository;

import tools.jackson.databind.json.JsonMapper;

class PeerMentorDraftServiceTest {

	private final PeerMentoringService peerMentoring = mock(PeerMentoringService.class);
	private final PeerMentorDraftRepository drafts = mock(PeerMentorDraftRepository.class);
	private final PeerMentorDraftService service = new PeerMentorDraftService(peerMentoring, drafts,
			JsonMapper.builder().build());

	private final AppUser mentor = new AppUser();
	private final UUID actorId = UUID.randomUUID();

	@BeforeEach
	void setUp() {
		mentor.setId(UUID.randomUUID());
		when(peerMentoring.requirePeerMentor(actorId)).thenReturn(mentor);
		when(drafts.saveAndFlush(any())).thenAnswer(invocation -> {
			PeerMentorDraft saved = invocation.getArgument(0);
			saved.setUpdatedAt(Instant.parse("2026-11-12T11:05:00Z"));
			return saved;
		});
	}

	@Test
	void aHalfFilledWizardIsSavedForTheMentorAndReadBackAsItWas() {
		PeerMentorProfileDraft draft = new PeerMentorProfileDraft("experience",
				List.of(PeerTopicCategory.CAREER_INTERNSHIP), List.of("cv"), List.of(), List.of(), null, "Staj",
				null, Map.of(PeerTopicCategory.CAREER_INTERNSHIP, PeerTopicLevel.INTERMEDIATE),
				List.of(new PeerMentorProfileDraft.Card(PeerExperienceKind.INTERNSHIP, "Staj", null,
						YearMonth.of(2025, 6), null, null)));

		PeerMentorProfileDraftResponse saved = service.save(actorId, draft);

		ArgumentCaptor<PeerMentorDraft> stored = ArgumentCaptor.forClass(PeerMentorDraft.class);
		verify(drafts).saveAndFlush(stored.capture());
		assertThat(stored.getValue().getUserId()).isEqualTo(mentor.getId());
		assertThat(stored.getValue().getPayload()).contains("\"startedOn\":\"2025-06\"").doesNotContain(actorId.toString());
		assertThat(saved.updatedAt()).isEqualTo(Instant.parse("2026-11-12T11:05:00Z"));

		when(drafts.findById(mentor.getId())).thenReturn(Optional.of(stored.getValue()));
		assertThat(service.find(actorId).draft()).isEqualTo(draft);
	}

	@Test
	void anEmptyDraftIsAllowed() {
		PeerMentorProfileDraft empty = new PeerMentorProfileDraft(null, null, null, null, null, null, null, null, null,
				null);

		assertThat(service.save(actorId, empty).draft()).isEqualTo(empty);
	}

	@Test
	void noDraftOrOneThisVersionCannotReadIsNotFound() {
		when(drafts.findById(mentor.getId())).thenReturn(Optional.empty());
		assertThatThrownBy(() -> service.find(actorId)).isInstanceOf(ResourceNotFoundException.class);

		PeerMentorDraft broken = new PeerMentorDraft(mentor.getId());
		broken.setPayload("{\"categories\": [\"ASTRONOMY\"]}");
		when(drafts.findById(mentor.getId())).thenReturn(Optional.of(broken));
		assertThatThrownBy(() -> service.find(actorId)).isInstanceOf(ResourceNotFoundException.class);
	}

	@Test
	void someoneWhoIsNotAPeerMentorCanNeitherSaveNorReadADraft() {
		UUID outsider = UUID.randomUUID();
		when(peerMentoring.requirePeerMentor(outsider))
				.thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "students only"));

		assertThatThrownBy(() -> service.save(outsider, new PeerMentorProfileDraft(null, null, null, null, null,
				null, null, null, null, null))).isInstanceOf(ResponseStatusException.class);
		assertThatThrownBy(() -> service.find(outsider)).isInstanceOf(ResponseStatusException.class);
		assertThatThrownBy(() -> service.delete(outsider)).isInstanceOf(ResponseStatusException.class);
		verify(drafts, never()).saveAndFlush(any());
		verify(drafts, never()).deleteById(any());
	}

	@Test
	void deletingRemovesOnlyTheMentorsOwnDraft() {
		service.delete(actorId);

		verify(drafts).deleteById(mentor.getId());
	}
}
