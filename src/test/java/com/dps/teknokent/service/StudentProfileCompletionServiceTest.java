package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.dps.teknokent.domain.PeerTopicCategory;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.SaveStudentProfileCompletionRequest;
import com.dps.teknokent.dto.responses.StudentProfileCompletionResponse;
import com.dps.teknokent.entity.StudentInterest;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.repository.StudentInterestRepository;

class StudentProfileCompletionServiceTest {

	private static final UUID STUDENT = UUID.randomUUID();

	private final PeerMentoringService peerMentoring = mock(PeerMentoringService.class);
	private final StudentInterestRepository interests = mock(StudentInterestRepository.class);
	private StudentProfileCompletionService service;

	@BeforeEach
	void setUp() {
		service = new StudentProfileCompletionService(peerMentoring, interests);
		when(peerMentoring.categories(STUDENT, UserRole.MENTEE)).thenReturn(
				List.of(PeerTopicCategory.CAREER_INTERNSHIP, PeerTopicCategory.TECHNICAL_DIGITAL));
		when(interests.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
	}

	@Test
	void aStudentWhoNeverSavedGetsAnEmptyUnfinishedCompletion() {
		when(interests.findById(STUDENT)).thenReturn(Optional.empty());

		assertThat(service.get(STUDENT))
				.isEqualTo(new StudentProfileCompletionResponse(List.of(), List.of(), "", false, false));
	}

	@Test
	void savingReplacesTheWholeSetDeduplicatedAndTrimmed() {
		StudentInterest earlier = new StudentInterest(STUDENT);
		earlier.setTopics(new ArrayList<>(List.of(PeerTopicCategory.TECHNICAL_DIGITAL)));
		earlier.setSubtopics(new ArrayList<>(List.of("fixture-code")));
		when(interests.findById(STUDENT)).thenReturn(Optional.of(earlier));

		StudentProfileCompletionResponse saved = service.save(STUDENT, new SaveStudentProfileCompletionRequest(null,
				List.of(PeerTopicCategory.CAREER_INTERNSHIP, PeerTopicCategory.CAREER_INTERNSHIP),
				List.of("fixture-cv", "fixture-cv"), "  Yaz stajı bulmak  ", true, false));

		assertThat(saved).isEqualTo(new StudentProfileCompletionResponse(
				List.of(PeerTopicCategory.CAREER_INTERNSHIP), List.of("fixture-cv"), "Yaz stajı bulmak", true, false));
		assertThat(earlier.getUpdatedAt()).isNotNull();
	}

	@Test
	void aFinishedCompletionIsNeverAlsoSkipped() {
		when(interests.findById(STUDENT)).thenReturn(Optional.empty());

		assertThat(service.save(STUDENT, request(List.of(), true, true)).skipped()).isFalse();
		assertThat(service.save(STUDENT, request(List.of(), false, true)).skipped()).isTrue();
	}

	@Test
	void aCategoryOffInTheStudentsProgramIsRefusedAndNothingIsSaved() {
		assertThatThrownBy(() -> service.save(STUDENT, request(List.of(PeerTopicCategory.INTERNATIONAL), true, false)))
				.isInstanceOf(InvalidRequestException.class);
		verify(interests, never()).save(any());
	}

	@Test
	void aCategoryTurnedOffLaterDropsOutOnRead() {
		StudentInterest saved = new StudentInterest(STUDENT);
		saved.setTopics(new ArrayList<>(List.of(PeerTopicCategory.INTERNATIONAL, PeerTopicCategory.CAREER_INTERNSHIP)));
		when(interests.findById(STUDENT)).thenReturn(Optional.of(saved));

		assertThat(service.get(STUDENT).topics()).containsExactly(PeerTopicCategory.CAREER_INTERNSHIP);
	}

	@Test
	void onlyAnOdtuStudentGetsIn() {
		UUID mentor = UUID.randomUUID();
		when(peerMentoring.categories(mentor, UserRole.MENTEE))
				.thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));

		assertThatThrownBy(() -> service.get(mentor)).isInstanceOf(ResponseStatusException.class);
		assertThatThrownBy(() -> service.save(mentor, request(List.of(), true, false)))
				.isInstanceOf(ResponseStatusException.class);
		verify(interests, never()).save(any());
	}

	private static SaveStudentProfileCompletionRequest request(List<PeerTopicCategory> topics, boolean completed,
			boolean skipped) {
		return new SaveStudentProfileCompletionRequest(null, topics, List.of(), "", completed, skipped);
	}
}
