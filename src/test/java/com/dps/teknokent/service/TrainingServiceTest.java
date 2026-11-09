package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dps.teknokent.domain.NotificationCategory;
import com.dps.teknokent.domain.PermissionSource;
import com.dps.teknokent.domain.ProgramType;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.SaveTrainingRequest;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.TrainingModule;
import com.dps.teknokent.entity.TrainingQuestion;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.repository.TrainingAttemptRepository;
import com.dps.teknokent.repository.TrainingModuleRepository;
import com.dps.teknokent.repository.TrainingProgressRepository;
import com.dps.teknokent.repository.TrainingQuestionRepository;
import com.dps.teknokent.repository.UserBadgeRepository;
import com.dps.teknokent.domain.BadgeCode;
import com.dps.teknokent.domain.BadgeSource;
import com.dps.teknokent.domain.BadgeStatus;
import com.dps.teknokent.domain.TrainingState;
import com.dps.teknokent.entity.TrainingAttempt;
import com.dps.teknokent.entity.UserBadge;
import com.dps.teknokent.exception.ConflictException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@ExtendWith(MockitoExtension.class)
class TrainingServiceTest {

	@Mock
	private TrainingModuleRepository modules;
	@Mock
	private TrainingQuestionRepository questions;
	@Mock
	private TrainingProgressRepository progress;
	@Mock
	private TrainingAttemptRepository attempts;
	@Mock
	private UserBadgeRepository awards;
	@Mock
	private ProgramRepository programs;
	@Mock
	private AppUserRepository users;
	@Mock
	private BadgeService badgeService;
	@Mock
	private DomainLookupService domainLookupService;
	@Mock
	private NotificationService notificationService;

	@InjectMocks
	private TrainingService service;

	private final UUID coordinator = UUID.randomUUID();
	private final Program iskur = program();
	private final List<TrainingQuestion> stored = new ArrayList<>();

	@BeforeEach
	void setUp() {
		lenient().when(badgeService.requirePeerProgram(coordinator, iskur.getId())).thenReturn(iskur);
		lenient().when(questions.save(any(TrainingQuestion.class))).thenAnswer(call -> {
			stored.add(call.getArgument(0));
			return call.getArgument(0);
		});
		lenient().when(questions.findByModuleIdAndVersionOrderByPositionAsc(any(), org.mockito.ArgumentMatchers.anyInt()))
				.thenAnswer(call -> stored.stream()
						.filter(question -> question.getVersion() == (int) call.getArgument(1))
						.toList());
	}

	@Test
	void readsAYoutubeVideoIdFromEveryUsualLinkAndNothingElse() {
		for (String link : List.of("https://www.youtube.com/watch?v=M7lc1UVf-VE",
				"https://youtube.com/watch?feature=share&v=M7lc1UVf-VE", "https://youtu.be/M7lc1UVf-VE?t=3",
				"https://www.youtube.com/embed/M7lc1UVf-VE", "https://www.youtube-nocookie.com/embed/M7lc1UVf-VE",
				"https://m.youtube.com/shorts/M7lc1UVf-VE")) {
			assertThat(TrainingService.youtubeId(link)).as(link).contains("M7lc1UVf-VE");
		}
		for (String link : List.of("https://vimeo.com/123456", "https://evil.example/watch?v=M7lc1UVf-VE",
				"javascript:alert(1)", "https://www.youtube.com/watch?v=short", "")) {
			assertThat(TrainingService.youtubeId(link)).as(link).isEmpty();
		}
	}

	@Test
	void beforeTheFirstSaveTheEditorGetsTheMarkedExample() {
		when(modules.findByProgramId(iskur.getId())).thenReturn(Optional.empty());

		var view = service.forEditor(coordinator, iskur.getId());

		assertThat(view.placeholder()).isTrue();
		assertThat(view.published()).isFalse();
		assertThat(view.questions()).hasSize(10).allSatisfy(question -> {
			assertThat(question.text()).startsWith("[ÖRNEK]");
			assertThat(question.options()).hasSize(4);
		});
	}

	@Test
	void theFirstSavePublishesVersionOneAndTellsTheStudentsWithoutTheBadge() {
		when(modules.findByProgramId(iskur.getId())).thenReturn(Optional.empty());
		AppUser waiting = student();
		AppUser mentor = student();
		mentor.grant(UserRole.MENTOR, PermissionSource.BADGE, java.time.Instant.now());
		when(users.findByProgramIdAndPermissionAndStatusOrderByCreatedAtAsc(iskur.getId(), UserRole.MENTEE,
				UserAccountStatus.APPROVED)).thenReturn(List.of(waiting, mentor));

		var view = service.save(coordinator, iskur.getId(), request("https://youtu.be/M7lc1UVf-VE", "Soru"));

		assertThat(view.version()).isEqualTo(1);
		assertThat(view.videoId()).isEqualTo("M7lc1UVf-VE");
		assertThat(view.placeholder()).isFalse();
		assertThat(stored).hasSize(10).allSatisfy(question -> assertThat(question.getVersion()).isEqualTo(1));
		verify(notificationService).create(eq(waiting), eq(NotificationCategory.BADGE), anyString(), anyString(),
				eq("/student/peer-mentoring/training"));
		verify(notificationService, never()).create(eq(mentor), any(), anyString(), anyString(), anyString());
	}

	// D2e: an unchanged save keeps everyone's progress; any change starts a new version.
	@Test
	void onlyAChangeMakesANewVersionAndOnlyTheFirstPublishIsAnnounced() {
		TrainingModule module = published(1, "M7lc1UVf-VE");
		when(modules.findByProgramId(iskur.getId())).thenReturn(Optional.of(module));
		request("https://youtu.be/M7lc1UVf-VE", "Soru").questions().forEach(question -> stored.add(
				questionOf(module, stored.size(), question, 1)));

		service.save(coordinator, iskur.getId(), request("https://youtu.be/M7lc1UVf-VE", " Soru"));
		assertThat(module.getCurrentVersion()).isEqualTo(1);

		var changed = service.save(coordinator, iskur.getId(), request("https://youtu.be/M7lc1UVf-VE", "Yeni soru"));
		assertThat(changed.version()).isEqualTo(2);
		assertThat(changed.questions()).allSatisfy(question -> assertThat(question.text()).startsWith("Yeni soru"));
		verify(modules, times(1)).save(module);
		verify(notificationService, never()).create(any(), any(), anyString(), anyString(), anyString());
	}

	@Test
	void refusesALinkThatIsNotAYoutubeVideo() {
		assertThatThrownBy(() -> service.save(coordinator, iskur.getId(),
				request("https://evil.example/video.mp4", "Soru")))
				.isInstanceOf(InvalidRequestException.class);
		verify(modules, never()).save(any());
	}

	@Test
	void onlyThePeerProgramsEditorsGetIn() {
		UUID stranger = UUID.randomUUID();
		when(badgeService.requirePeerProgram(stranger, iskur.getId()))
				.thenThrow(new InvalidRequestException("no"));

		assertThatThrownBy(() -> service.forEditor(stranger, iskur.getId()))
				.isInstanceOf(InvalidRequestException.class);
	}

	// ---- 4T-2: the student's side ----

	private TrainingModule live() {
		TrainingModule module = published(1, "M7lc1UVf-VE");
		lenient().when(modules.findByProgramId(iskur.getId())).thenReturn(Optional.of(module));
		lenient().when(programs.findById(iskur.getId())).thenReturn(Optional.of(iskur));
		request("https://youtu.be/M7lc1UVf-VE", "Soru").questions()
				.forEach(question -> stored.add(questionOf(module, stored.size(), question, 1)));
		return module;
	}

	private AppUser signedIn(boolean watched) {
		AppUser student = student();
		student.setStatus(UserAccountStatus.APPROVED);
		lenient().when(domainLookupService.requireUser(student.getId())).thenReturn(student);
		lenient().when(progress.existsByModuleIdAndUserIdAndVersion(any(), eq(student.getId()), eq(1)))
				.thenReturn(watched);
		return student;
	}

	private Map<UUID, Integer> answers(int right) {
		Map<UUID, Integer> answers = new HashMap<>();
		for (int index = 0; index < stored.size(); index++) {
			TrainingQuestion question = stored.get(index);
			answers.put(question.getId(),
					index < right ? (int) question.getCorrectOption() : (question.getCorrectOption() + 1) % 4);
		}
		return answers;
	}

	private static UserBadge gate(AppUser holder, Program program, BadgeStatus status) {
		UserBadge award = new UserBadge();
		award.setUserId(holder.getId());
		award.setBadgeCode(BadgeCode.PEER_MENTOR);
		award.setProgramId(program.getId());
		award.setStatus(status);
		return award;
	}

	@Test
	void aStudentGoesFromTheVideoToTheQuizOnceThePlayerSaysItWasWatched() {
		live();
		AppUser student = signedIn(false);

		assertThat(service.forStudent(student.getId()).state()).isEqualTo(TrainingState.VIDEO);
		assertThatThrownBy(() -> service.quiz(student.getId())).isInstanceOf(InvalidRequestException.class);

		when(progress.existsByModuleIdAndUserIdAndVersion(any(), eq(student.getId()), eq(1)))
				.thenReturn(false, false, true);
		var after = service.videoWatched(student.getId());
		verify(progress).save(any());
		assertThat(after.state()).isEqualTo(TrainingState.QUIZ);
		assertThat(after.attemptsLeft()).isEqualTo(3);
		assertThat(after.videoId()).isEqualTo("M7lc1UVf-VE");
	}

	@Test
	void theQuizHasEveryQuestionWithItsOptionsButNeverSaysWhichIsRight() {
		live();
		AppUser student = signedIn(true);

		var quiz = service.quiz(student.getId());

		assertThat(quiz.version()).isEqualTo(1);
		assertThat(quiz.questions()).hasSize(10).allSatisfy(question -> assertThat(question.options())
				.extracting(option -> option.index()).containsExactlyInAnyOrder(0, 1, 2, 3));
		assertThat(TrainingQuizResponseHasNoAnswer.check()).isTrue();
	}

	@Test
	void eightRightOfTenAwardsTheBadgeThroughThePhaseFourPath() {
		live();
		AppUser student = signedIn(true);

		var result = service.submit(student.getId(), 1, answers(8));

		assertThat(result.passed()).isTrue();
		assertThat(result.score()).isEqualTo(8);
		verify(badgeService).award(student, BadgeCode.PEER_MENTOR, iskur, BadgeSource.RULE, null);
		ArgumentCaptor<TrainingAttempt> attempt = ArgumentCaptor.forClass(TrainingAttempt.class);
		verify(attempts).save(attempt.capture());
		assertThat(attempt.getValue().isPassed()).isTrue();
		verify(notificationService, never()).create(any(), any(), anyString(), anyString(), anyString());
	}

	@Test
	void sevenRightIsAFailWithANoteAndNoBadge() {
		live();
		AppUser student = signedIn(true);

		var result = service.submit(student.getId(), 1, answers(7));

		assertThat(result.passed()).isFalse();
		verify(badgeService, never()).award(any(), any(), any(), any(), any());
		verify(notificationService).create(eq(student), eq(NotificationCategory.BADGE), eq("Sınavı geçemedin"),
				anyString(), eq("/student/peer-mentoring/training"));
	}

	// D2d: three tries in 24 hours, then the next one opens when the oldest leaves the window.
	@Test
	void aFourthTryWithin24HoursIsRefused() {
		TrainingModule module = live();
		AppUser student = signedIn(true);
		List<TrainingAttempt> recent = IntStream.range(0, 3).mapToObj(index -> {
			TrainingAttempt attempt = new TrainingAttempt();
			attempt.setSubmittedAt(Instant.now().minusSeconds(3600L * (3 - index)));
			return attempt;
		}).toList();
		when(attempts.findByModuleIdAndUserIdAndSubmittedAtAfterOrderBySubmittedAtAsc(eq(module.getId()),
				eq(student.getId()), any())).thenReturn(recent);

		assertThat(service.forStudent(student.getId()).attemptsLeft()).isZero();
		assertThat(service.forStudent(student.getId()).nextAttemptAt())
				.isEqualTo(recent.getFirst().getSubmittedAt().plus(java.time.Duration.ofHours(24)));
		assertThatThrownBy(() -> service.submit(student.getId(), 1, answers(10)))
				.isInstanceOf(ConflictException.class);
		assertThatThrownBy(() -> service.quiz(student.getId())).isInstanceOf(ConflictException.class);
	}

	@Test
	void aSubmissionForAnOldVersionOrWithAnUnansweredQuestionIsRefused() {
		live();
		AppUser student = signedIn(true);

		assertThatThrownBy(() -> service.submit(student.getId(), 2, answers(10)))
				.isInstanceOf(ConflictException.class);
		Map<UUID, Integer> partial = answers(10);
		partial.remove(stored.getFirst().getId());
		assertThatThrownBy(() -> service.submit(student.getId(), 1, partial))
				.isInstanceOf(InvalidRequestException.class);
		verify(attempts, never()).save(any());
	}

	// Rule R: after a revoke only a coordinator or admin can award the badge again.
	@Test
	void aHolderHasNothingToDoAndARevokedStudentCannotRetakeIt() {
		live();
		AppUser holder = signedIn(true);
		when(awards.findByUserIdOrderByAwardedAtDesc(holder.getId()))
				.thenReturn(List.of(gate(holder, iskur, BadgeStatus.ACTIVE)));
		assertThat(service.forStudent(holder.getId()).state()).isEqualTo(TrainingState.BADGE_ACTIVE);

		AppUser revoked = signedIn(true);
		when(awards.findByUserIdOrderByAwardedAtDesc(revoked.getId()))
				.thenReturn(List.of(gate(revoked, iskur, BadgeStatus.REVOKED)));
		assertThat(service.forStudent(revoked.getId()).state()).isEqualTo(TrainingState.BLOCKED);
		assertThatThrownBy(() -> service.submit(revoked.getId(), 1, answers(10)))
				.isInstanceOf(InvalidRequestException.class);
		verify(badgeService, never()).award(any(), any(), any(), any(), any());
	}

	@Test
	void outsideAPeerProgramOrBeforePublishingThereIsNoTraining() {
		AppUser student = signedIn(false);
		when(programs.findById(iskur.getId())).thenReturn(Optional.of(iskur));
		when(modules.findByProgramId(iskur.getId())).thenReturn(Optional.empty());
		assertThat(service.forStudent(student.getId()).state()).isEqualTo(TrainingState.NOT_AVAILABLE);

		student.setProgramId(null);
		assertThat(service.forStudent(student.getId()).state()).isEqualTo(TrainingState.NOT_AVAILABLE);
	}

	/** The quiz's types carry no correct option at all, so it cannot leak by mistake. */
	private static final class TrainingQuizResponseHasNoAnswer {
		static boolean check() {
			return java.util.Arrays.stream(com.dps.teknokent.dto.responses.TrainingQuizResponse.Option.class
					.getRecordComponents()).noneMatch(component -> component.getName().contains("correct"))
					&& java.util.Arrays.stream(com.dps.teknokent.dto.responses.TrainingQuizResponse.Question.class
							.getRecordComponents()).noneMatch(component -> component.getName().contains("correct"));
		}
	}

	private static SaveTrainingRequest request(String link, String prefix) {
		return new SaveTrainingRequest(null, link, IntStream.range(0, 10)
				.mapToObj(index -> new SaveTrainingRequest.Question(prefix + " " + index,
						List.of("A", "B", "C", "D"), index % 4))
				.toList());
	}

	private static TrainingQuestion questionOf(TrainingModule module, int position,
			SaveTrainingRequest.Question source, int version) {
		TrainingQuestion question = new TrainingQuestion();
		question.setId(UUID.randomUUID());
		question.setModuleId(module.getId());
		question.setVersion(version);
		question.setPosition(position);
		question.setText(source.text());
		question.setOption1("A");
		question.setOption2("B");
		question.setOption3("C");
		question.setOption4("D");
		question.setCorrectOption((short) source.correctOption());
		return question;
	}

	private TrainingModule published(int version, String videoId) {
		TrainingModule module = new TrainingModule();
		module.setId(UUID.randomUUID());
		module.setProgramId(iskur.getId());
		module.setCurrentVersion(version);
		module.setVideoId(videoId);
		module.setPublishedAt(java.time.Instant.now());
		module.setUpdatedAt(java.time.Instant.now());
		return module;
	}

	private AppUser student() {
		AppUser student = new AppUser();
		student.setId(UUID.randomUUID());
		student.setFullName("Öğrenci");
		student.setEmail(student.getId() + "@odtu-dev.invalid");
		student.setRole(UserRole.MENTEE);
		student.setProgramId(iskur.getId());
		return student;
	}

	private static Program program() {
		Program program = new Program();
		program.setId(UUID.randomUUID());
		program.setName("İŞKUR 2026 Bahar");
		program.setProgramType(ProgramType.PEER_MENTORING);
		return program;
	}
}
