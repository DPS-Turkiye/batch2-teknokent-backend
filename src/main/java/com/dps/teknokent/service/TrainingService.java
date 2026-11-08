package com.dps.teknokent.service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.BadgeSource;
import com.dps.teknokent.domain.BadgeStatus;
import com.dps.teknokent.domain.NotificationCategory;
import com.dps.teknokent.domain.ProgramType;
import com.dps.teknokent.domain.TrainingState;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.SaveTrainingRequest;
import com.dps.teknokent.dto.responses.StudentTrainingResponse;
import com.dps.teknokent.dto.responses.TrainingAttemptResponse;
import com.dps.teknokent.dto.responses.TrainingModuleResponse;
import com.dps.teknokent.dto.responses.TrainingQuizResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.TrainingModule;
import com.dps.teknokent.entity.TrainingAttempt;
import com.dps.teknokent.entity.TrainingProgress;
import com.dps.teknokent.entity.TrainingQuestion;
import com.dps.teknokent.entity.UserBadge;
import com.dps.teknokent.exception.ConflictException;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.repository.TrainingAttemptRepository;
import com.dps.teknokent.repository.TrainingProgressRepository;
import com.dps.teknokent.repository.UserBadgeRepository;
import com.dps.teknokent.repository.TrainingModuleRepository;
import com.dps.teknokent.repository.TrainingQuestionRepository;
import com.dps.teknokent.util.Texts;

import lombok.RequiredArgsConstructor;

/**
 * The Akran Mentoru training of a peer program (4T): a YouTube video and a ten-question quiz. Its editors are the
 * program's coordinators and admins, the same people who award the badge by hand (D10). Saving publishes; a save
 * that changes anything makes a new version, which students start over on (D2e).
 */
@Service
@RequiredArgsConstructor
public class TrainingService {

	static final int QUESTION_COUNT = 10;
	static final int PASS_MARK = 8;
	static final int ATTEMPTS_PER_WINDOW = 3;
	static final Duration ATTEMPT_WINDOW = Duration.ofHours(24);
	private static final SecureRandom RANDOM = new SecureRandom();
	private static final Pattern YOUTUBE_ID = Pattern.compile(
			"^https?://(?:www\\.|m\\.)?(?:youtube\\.com/(?:watch\\?(?:.*&)?v=|embed/|shorts/)|youtu\\.be/"
					+ "|youtube-nocookie\\.com/embed/)([A-Za-z0-9_-]{11})(?:[?&#/].*)?$");

	private final TrainingModuleRepository modules;
	private final TrainingQuestionRepository questions;
	private final TrainingProgressRepository progress;
	private final TrainingAttemptRepository attempts;
	private final UserBadgeRepository awards;
	private final ProgramRepository programs;
	private final AppUserRepository users;
	private final BadgeService badgeService;
	private final DomainLookupService domainLookupService;
	private final NotificationService notificationService;

	/** The editor's view; before the first save, the marked example content. */
	@Transactional(readOnly = true)
	public TrainingModuleResponse forEditor(UUID actorId, UUID programId) {
		Program program = badgeService.requirePeerProgram(actorId, programId);
		return modules.findByProgramId(program.getId())
				.map(this::editorView)
				.orElseGet(() -> new TrainingModuleResponse(false, null, TrainingPlaceholder.VIDEO_ID, null, true,
						TrainingPlaceholder.QUESTIONS));
	}

	/** "Kaydet ve yayınla". Unchanged content keeps its version; the first publish tells the program's students. */
	@Transactional
	public TrainingModuleResponse save(UUID actorId, UUID programId, SaveTrainingRequest request) {
		Program program = badgeService.requirePeerProgram(actorId, programId);
		String videoId = youtubeId(request.videoUrl())
				.orElseThrow(() -> new InvalidRequestException(Texts.forRequest("error.trainingVideoLink")));
		List<TrainingModuleResponse.Question> wanted = request.questions().stream()
				.map(question -> new TrainingModuleResponse.Question(question.text().trim(),
						question.options().stream().map(String::trim).toList(), question.correctOption()))
				.toList();
		Instant now = Instant.now();
		Optional<TrainingModule> existing = modules.findByProgramId(program.getId());
		if (existing.isPresent() && sameContent(existing.get(), videoId, wanted)) {
			return editorView(existing.get());
		}
		TrainingModule module = existing.orElseGet(() -> {
			TrainingModule created = new TrainingModule();
			created.setId(UUID.randomUUID());
			created.setProgramId(program.getId());
			created.setCurrentVersion(0);
			return created;
		});
		boolean firstPublish = module.getCurrentVersion() == 0;
		module.setCurrentVersion(module.getCurrentVersion() + 1);
		module.setVideoId(videoId);
		module.setPublishedAt(now);
		module.setUpdatedAt(now);
		module.setUpdatedBy(actorId);
		modules.save(module);
		for (int position = 0; position < wanted.size(); position++) {
			questions.save(question(module, position, wanted.get(position)));
		}
		if (firstPublish) {
			announce(program);
		}
		return editorView(module);
	}

	/** The signed-in student's training in their peer program, for "Rozet Yok" and the training page. */
	@Transactional(readOnly = true)
	public StudentTrainingResponse forStudent(UUID actorId) {
		return studentView(context(domainLookupService.requireUser(actorId)));
	}

	/** The player reported the end, or 90 % (D2b). Recorded once per version; repeating it changes nothing. */
	@Transactional
	public StudentTrainingResponse videoWatched(UUID actorId) {
		Context context = context(domainLookupService.requireUser(actorId));
		if (context.state() != TrainingState.VIDEO && context.state() != TrainingState.QUIZ) {
			throw new InvalidRequestException(Texts.forRequest("error.trainingNotOpen"));
		}
		TrainingModule module = context.module();
		if (!progress.existsByModuleIdAndUserIdAndVersion(module.getId(), context.student().getId(),
				module.getCurrentVersion())) {
			TrainingProgress watched = new TrainingProgress();
			watched.setId(UUID.randomUUID());
			watched.setModuleId(module.getId());
			watched.setUserId(context.student().getId());
			watched.setVersion(module.getCurrentVersion());
			watched.setVideoCompletedAt(Instant.now());
			progress.save(watched);
		}
		return studentView(context(context.student()));
	}

	/** The questions in a fresh order, options shuffled, without the correct ones; only once the video counts. */
	@Transactional(readOnly = true)
	public TrainingQuizResponse quiz(UUID actorId) {
		Context context = requireQuizOpen(context(domainLookupService.requireUser(actorId)));
		List<TrainingQuizResponse.Question> shuffled = new ArrayList<>(currentQuestions(context.module()).stream()
				.map(question -> {
					List<TrainingQuizResponse.Option> options = new ArrayList<>();
					for (int index = 0; index < question.options().size(); index++) {
						options.add(new TrainingQuizResponse.Option(index, question.options().get(index)));
					}
					Collections.shuffle(options, RANDOM);
					return new TrainingQuizResponse.Question(question.getId(), question.getText(), List.copyOf(options));
				})
				.toList());
		Collections.shuffle(shuffled, RANDOM);
		return new TrainingQuizResponse(context.module().getCurrentVersion(), List.copyOf(shuffled));
	}

	/**
	 * Grades a submission: every question answered, at most three tries in 24 hours (D2d). Eight right of ten
	 * (D2c) awards the Akran Mentoru badge through the same path as a coordinator's "Rozet ver".
	 */
	@Transactional
	public TrainingAttemptResponse submit(UUID actorId, int version, Map<UUID, Integer> answers) {
		Context context = requireQuizOpen(context(domainLookupService.requireUser(actorId)));
		TrainingModule module = context.module();
		if (version != module.getCurrentVersion()) {
			throw new ConflictException(Texts.forRequest("error.trainingChanged"));
		}
		List<TrainingQuestion> current = currentQuestions(module);
		if (answers.size() != current.size()
				|| !current.stream().allMatch(question -> answers.containsKey(question.getId()))) {
			throw new InvalidRequestException(Texts.forRequest("error.trainingAnswerAll"));
		}
		int score = (int) current.stream()
				.filter(question -> answers.get(question.getId()) != null
						&& answers.get(question.getId()) == question.getCorrectOption())
				.count();
		boolean passed = score >= PASS_MARK;
		Instant now = Instant.now();
		TrainingAttempt attempt = new TrainingAttempt();
		attempt.setId(UUID.randomUUID());
		attempt.setModuleId(module.getId());
		attempt.setUserId(context.student().getId());
		attempt.setVersion(module.getCurrentVersion());
		attempt.setScore(score);
		attempt.setPassed(passed);
		attempt.setAnswers(current.stream()
				.map(question -> question.getId() + "=" + answers.get(question.getId()))
				.collect(Collectors.joining(",")));
		attempt.setSubmittedAt(now);
		attempts.save(attempt);
		if (passed) {
			badgeService.award(context.student(), BadgeService.GATE, context.program(), BadgeSource.RULE, null);
		}
		StudentTrainingResponse after = studentView(context(context.student()));
		if (!passed) {
			AppUser student = context.student();
			notificationService.create(student, NotificationCategory.BADGE,
					Texts.get(student, "notification.trainingFailed.title"),
					Texts.get(student, after.attemptsLeft() > 0 ? "notification.trainingFailed.retryNow"
							: "notification.trainingFailed.retryLater", score, PASS_MARK),
					"/student/peer-mentoring/training");
		}
		return new TrainingAttemptResponse(score, passed, after);
	}

	private Context requireQuizOpen(Context context) {
		if (context.state() != TrainingState.QUIZ) {
			throw new InvalidRequestException(Texts.forRequest("error.trainingNotOpen"));
		}
		if (attemptsInWindow(context).size() >= ATTEMPTS_PER_WINDOW) {
			throw new ConflictException(Texts.forRequest("error.trainingNoAttemptsLeft"));
		}
		return context;
	}

	private record Context(AppUser student, Program program, TrainingModule module, TrainingState state) {
	}

	/** The student's peer program, its published training and where the student stands with it. */
	private Context context(AppUser student) {
		Program program = student.getProgramId() == null ? null
				: programs.findById(student.getProgramId())
						.filter(found -> found.getProgramType() == ProgramType.PEER_MENTORING)
						.orElse(null);
		TrainingModule module = program == null ? null : modules.findByProgramId(program.getId()).orElse(null);
		if (program == null || module == null || !student.has(UserRole.MENTEE)
				|| student.getStatus() != UserAccountStatus.APPROVED) {
			return new Context(student, program, module, TrainingState.NOT_AVAILABLE);
		}
		List<UserBadge> gate = awards.findByUserIdOrderByAwardedAtDesc(student.getId()).stream()
				.filter(award -> award.getBadgeCode() == BadgeService.GATE
						&& program.getId().equals(award.getProgramId()))
				.toList();
		if (gate.stream().anyMatch(award -> award.getStatus() == BadgeStatus.ACTIVE)) {
			return new Context(student, program, module, TrainingState.BADGE_ACTIVE);
		}
		if (gate.stream().anyMatch(award -> award.getStatus() == BadgeStatus.REVOKED)) {
			return new Context(student, program, module, TrainingState.BLOCKED);
		}
		boolean watched = progress.existsByModuleIdAndUserIdAndVersion(module.getId(), student.getId(),
				module.getCurrentVersion());
		return new Context(student, program, module, watched ? TrainingState.QUIZ : TrainingState.VIDEO);
	}

	private StudentTrainingResponse studentView(Context context) {
		TrainingModule module = context.module();
		if (context.state() == TrainingState.NOT_AVAILABLE) {
			return new StudentTrainingResponse(TrainingState.NOT_AVAILABLE,
					context.program() == null ? null : context.program().getId(),
					context.program() == null ? null : context.program().getName(), null, null, false, 0, null, null,
					PASS_MARK, QUESTION_COUNT);
		}
		List<TrainingAttempt> recent = attemptsInWindow(context);
		int left = Math.max(0, ATTEMPTS_PER_WINDOW - recent.size());
		Instant next = left > 0 || recent.isEmpty() ? null : recent.getFirst().getSubmittedAt().plus(ATTEMPT_WINDOW);
		Integer lastScore = attempts.findByModuleIdAndUserIdAndVersionOrderBySubmittedAtDesc(module.getId(),
				context.student().getId(), module.getCurrentVersion()).stream()
				.findFirst().map(TrainingAttempt::getScore).orElse(null);
		return new StudentTrainingResponse(context.state(), context.program().getId(), context.program().getName(),
				module.getCurrentVersion(), module.getVideoId(), context.state() == TrainingState.QUIZ, left, next,
				lastScore, PASS_MARK, QUESTION_COUNT);
	}

	/** Oldest first; the window counts every try at this program's training, whatever its version. */
	private List<TrainingAttempt> attemptsInWindow(Context context) {
		return attempts.findByModuleIdAndUserIdAndSubmittedAtAfterOrderBySubmittedAtAsc(context.module().getId(),
				context.student().getId(), Instant.now().minus(ATTEMPT_WINDOW));
	}

	private List<TrainingQuestion> currentQuestions(TrainingModule module) {
		return questions.findByModuleIdAndVersionOrderByPositionAsc(module.getId(), module.getCurrentVersion());
	}

	/** The id of a YouTube video link (watch, youtu.be, embed, shorts or nocookie), the only host the page embeds. */
	static Optional<String> youtubeId(String link) {
		Matcher matcher = YOUTUBE_ID.matcher(link == null ? "" : link.trim());
		return matcher.matches() ? Optional.of(matcher.group(1)) : Optional.empty();
	}

	private void announce(Program program) {
		for (AppUser student : users.findByProgramIdAndPermissionAndStatusOrderByCreatedAtAsc(program.getId(),
				UserRole.MENTEE, UserAccountStatus.APPROVED)) {
			if (!student.has(UserRole.MENTOR)) {
				notificationService.create(student, NotificationCategory.BADGE,
						Texts.get(student, "notification.trainingOpened.title"),
						Texts.get(student, "notification.trainingOpened.body", program.getName()),
						"/student/peer-mentoring/training");
			}
		}
	}

	private boolean sameContent(TrainingModule module, String videoId, List<TrainingModuleResponse.Question> wanted) {
		return module.getVideoId().equals(videoId) && editorView(module).questions().equals(wanted);
	}

	private TrainingModuleResponse editorView(TrainingModule module) {
		List<TrainingModuleResponse.Question> current = questions
				.findByModuleIdAndVersionOrderByPositionAsc(module.getId(), module.getCurrentVersion()).stream()
				.map(question -> new TrainingModuleResponse.Question(question.getText(), question.options(),
						question.getCorrectOption()))
				.toList();
		return new TrainingModuleResponse(true, module.getCurrentVersion(), module.getVideoId(),
				module.getUpdatedAt(), false, current);
	}

	private static TrainingQuestion question(TrainingModule module, int position,
			TrainingModuleResponse.Question source) {
		TrainingQuestion question = new TrainingQuestion();
		question.setId(UUID.randomUUID());
		question.setModuleId(module.getId());
		question.setVersion(module.getCurrentVersion());
		question.setPosition(position);
		question.setText(source.text());
		question.setOption1(source.options().get(0));
		question.setOption2(source.options().get(1));
		question.setOption3(source.options().get(2));
		question.setOption4(source.options().get(3));
		question.setCorrectOption((short) source.correctOption());
		return question;
	}
}
