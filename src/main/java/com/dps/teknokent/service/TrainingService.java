package com.dps.teknokent.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.NotificationCategory;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.SaveTrainingRequest;
import com.dps.teknokent.dto.responses.TrainingModuleResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.TrainingModule;
import com.dps.teknokent.entity.TrainingQuestion;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.repository.AppUserRepository;
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
	private static final Pattern YOUTUBE_ID = Pattern.compile(
			"^https?://(?:www\\.|m\\.)?(?:youtube\\.com/(?:watch\\?(?:.*&)?v=|embed/|shorts/)|youtu\\.be/"
					+ "|youtube-nocookie\\.com/embed/)([A-Za-z0-9_-]{11})(?:[?&#/].*)?$");

	private final TrainingModuleRepository modules;
	private final TrainingQuestionRepository questions;
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
