package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dps.teknokent.dto.requests.AddCoordinatorNoteRequest;
import com.dps.teknokent.dto.responses.CoordinatorNoteResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.CoordinatorNote;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.CoordinatorNoteRepository;

@ExtendWith(MockitoExtension.class)
class CoordinatorNoteServiceTest {

	@Mock
	private CoordinatorMentorshipService coordinatorMentorshipService;
	@Mock
	private CoordinatorNoteRepository noteRepository;
	@Mock
	private AppUserRepository appUserRepository;

	@InjectMocks
	private CoordinatorNoteService service;

	private final UUID elif = UUID.randomUUID();
	private final UUID burcu = UUID.randomUUID();
	private final UUID mentorshipId = UUID.randomUUID();

	@Test
	void addsATrimmedNoteAfterTheScopeCheck() {
		when(noteRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
		when(appUserRepository.findById(elif)).thenReturn(Optional.of(user(elif, "Elif Demir")));

		CoordinatorNoteResponse note = service.add(elif, mentorshipId, new AddCoordinatorNoteRequest(null, "  Nakit akışı riskli  "));

		verify(coordinatorMentorshipService).requireManagedMentorship(elif, mentorshipId);
		assertThat(note.body()).isEqualTo("Nakit akışı riskli");
		assertThat(note.authorName()).isEqualTo("Elif Demir");
		assertThat(note.mine()).isTrue();
	}

	@Test
	void listsNotesWithTheirAuthorsAndMarksTheViewersOwn() {
		when(noteRepository.findByMentorshipIdOrderByCreatedAtDesc(mentorshipId))
				.thenReturn(List.of(note(burcu), note(elif)));
		when(appUserRepository.findAllById(List.of(burcu, elif)))
				.thenReturn(List.of(user(elif, "Elif Demir"), user(burcu, "Burcu Şen")));

		List<CoordinatorNoteResponse> notes = service.list(elif, mentorshipId);

		assertThat(notes).extracting(CoordinatorNoteResponse::authorName).containsExactly("Burcu Şen", "Elif Demir");
		assertThat(notes).extracting(CoordinatorNoteResponse::mine).containsExactly(false, true);
	}

	@Test
	void onlyTheAuthorDeletesANote() {
		CoordinatorNote others = note(burcu);
		when(noteRepository.findById(others.getId())).thenReturn(Optional.of(others));

		assertThatThrownBy(() -> service.delete(elif, others.getId())).isInstanceOf(InvalidRequestException.class);
		verify(noteRepository, never()).delete(any());

		CoordinatorNote own = note(elif);
		when(noteRepository.findById(own.getId())).thenReturn(Optional.of(own));
		service.delete(elif, own.getId());
		verify(noteRepository).delete(own);
	}

	private CoordinatorNote note(UUID authorId) {
		CoordinatorNote note = new CoordinatorNote();
		note.setId(UUID.randomUUID());
		note.setMentorshipId(mentorshipId);
		note.setAuthorId(authorId);
		note.setBody("Not");
		note.setCreatedAt(Instant.now());
		return note;
	}

	private static AppUser user(UUID id, String name) {
		AppUser user = new AppUser();
		user.setId(id);
		user.setFullName(name);
		return user;
	}
}
