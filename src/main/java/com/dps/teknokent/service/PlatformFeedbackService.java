package com.dps.teknokent.service;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.dps.teknokent.domain.ProgramMentorStatus;
import com.dps.teknokent.domain.ProgramStatus;
import com.dps.teknokent.domain.ProgramType;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.SubmitPlatformFeedbackRequest;
import com.dps.teknokent.dto.responses.PageResponse;
import com.dps.teknokent.dto.responses.PlatformFeedbackInboxResponse;
import com.dps.teknokent.dto.responses.PlatformFeedbackResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.PlatformFeedback;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.PlatformFeedbackRepository;
import com.dps.teknokent.repository.ProgramCoordinatorRepository;
import com.dps.teknokent.repository.ProgramMentorRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.util.ProgramCoordination;
import com.dps.teknokent.util.Texts;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PlatformFeedbackService {

	private static final int PAGE_SIZE = 20;

	private final DomainLookupService domainLookupService;
	private final PlatformFeedbackRepository feedbackRepository;
	private final MenteeProgramService menteeProgramService;
	private final ProgramRepository programRepository;
	private final ProgramMentorRepository programMentorRepository;
	private final ProgramCoordinatorRepository programCoordinatorRepository;

	@Transactional
	public PlatformFeedbackResponse submit(UUID actorId, SubmitPlatformFeedbackRequest request) {
		AppUser author = requireApprovedUser(actorId);
		PlatformFeedback feedback = new PlatformFeedback();
		feedback.setAuthor(author);
		feedback.setKind(request.kind());
		feedback.setSubject(request.subject().strip());
		feedback.setMessage(request.message().strip());
		feedback.setLanguage(request.language());
		authorProgram(author).ifPresent(program -> {
			feedback.setProgram(program);
			feedback.setProgramType(program.getProgramType());
		});
		return PlatformFeedbackResponse.from(feedbackRepository.saveAndFlush(feedback));
	}

	@Transactional(readOnly = true)
	public PlatformFeedbackInboxResponse inbox(UUID actorId, int page, boolean unreadOnly, ProgramType programType) {
		requireAdmin(actorId);
		if (page < 0 || page > 10000) {
			throw new InvalidRequestException(Texts.forRequest("feedback.invalidPage"));
		}
		PageRequest pageable = PageRequest.of(page, PAGE_SIZE);
		Page<PlatformFeedback> feedback;
		if (programType != null) {
			feedback = unreadOnly
					? feedbackRepository.findByReadAtIsNullAndProgramTypeOrderByCreatedAtDescIdDesc(programType, pageable)
					: feedbackRepository.findByProgramTypeOrderByCreatedAtDescIdDesc(programType, pageable);
		} else {
			feedback = unreadOnly
					? feedbackRepository.findByReadAtIsNullOrderByCreatedAtDescIdDesc(pageable)
					: feedbackRepository.findAllByOrderByCreatedAtDescIdDesc(pageable);
		}
		return new PlatformFeedbackInboxResponse(
				PageResponse.from(feedback.map(PlatformFeedbackResponse::from)), feedbackRepository.countByReadAtIsNull());
	}

	@Transactional
	public PlatformFeedbackResponse markRead(UUID actorId, UUID id) {
		requireAdmin(actorId);
		PlatformFeedback feedback = feedbackRepository.findById(id)
				.orElseThrow(() -> new ResourceNotFoundException(Texts.forRequest("feedback.notFound")));
		if (feedback.getReadAt() == null) {
			feedback.setReadAt(Instant.now());
		}
		return PlatformFeedbackResponse.from(feedback);
	}

	private AppUser requireApprovedUser(UUID actorId) {
		AppUser user = domainLookupService.requireUser(actorId);
		if (user.getStatus() != UserAccountStatus.APPROVED) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, Texts.forRequest("feedback.approvedOnly"));
		}
		return user;
	}

	private void requireAdmin(UUID actorId) {
		if (!requireApprovedUser(actorId).has(UserRole.ADMIN)) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, Texts.forRequest("feedback.adminOnly"));
		}
	}

	/**
	 * The program the author is in when they write: a mentee's own program; for a mentor or a coordinator, the
	 * one running program they mentor in or run (none when there are several, so nothing is guessed).
	 */
	private Optional<Program> authorProgram(AppUser author) {
		if (author.has(UserRole.MENTEE)) {
			Optional<Program> own = menteeProgramService.programOf(author).flatMap(programRepository::findById);
			if (own.isPresent()) {
				return own;
			}
		}
		Set<UUID> ids = new LinkedHashSet<>();
		if (author.has(UserRole.MENTOR)) {
			programMentorRepository.findByMentorIdAndStatusOrderByCreatedAtDesc(author.getId(), ProgramMentorStatus.ACCEPTED)
					.forEach(membership -> ids.add(membership.getProgramId()));
		}
		if (author.has(UserRole.COORDINATOR)) {
			ProgramCoordination.programsOf(author.getId(), programRepository, programCoordinatorRepository)
					.forEach(program -> ids.add(program.getId()));
		}
		List<Program> running = programRepository.findAllById(ids).stream()
				.filter(program -> program.getStatus() == ProgramStatus.ACTIVE)
				.toList();
		return running.size() == 1 ? Optional.of(running.get(0)) : Optional.empty();
	}
}
