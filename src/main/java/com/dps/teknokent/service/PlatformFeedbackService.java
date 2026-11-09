package com.dps.teknokent.service;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.SubmitPlatformFeedbackRequest;
import com.dps.teknokent.dto.responses.PageResponse;
import com.dps.teknokent.dto.responses.PlatformFeedbackInboxResponse;
import com.dps.teknokent.dto.responses.PlatformFeedbackResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.PlatformFeedback;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.PlatformFeedbackRepository;
import com.dps.teknokent.util.Texts;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PlatformFeedbackService {

	private static final int PAGE_SIZE = 20;

	private final DomainLookupService domainLookupService;
	private final PlatformFeedbackRepository feedbackRepository;

	@Transactional
	public PlatformFeedbackResponse submit(UUID actorId, SubmitPlatformFeedbackRequest request) {
		AppUser author = requireApprovedUser(actorId);
		PlatformFeedback feedback = new PlatformFeedback();
		feedback.setAuthor(author);
		feedback.setKind(request.kind());
		feedback.setSubject(request.subject().strip());
		feedback.setMessage(request.message().strip());
		feedback.setLanguage(request.language());
		return PlatformFeedbackResponse.from(feedbackRepository.saveAndFlush(feedback));
	}

	@Transactional(readOnly = true)
	public PlatformFeedbackInboxResponse inbox(UUID actorId, int page, boolean unreadOnly) {
		requireAdmin(actorId);
		if (page < 0 || page > 10000) {
			throw new InvalidRequestException(Texts.forRequest("feedback.invalidPage"));
		}
		PageRequest pageable = PageRequest.of(page, PAGE_SIZE);
		Page<PlatformFeedback> feedback = unreadOnly
				? feedbackRepository.findByReadAtIsNullOrderByCreatedAtDescIdDesc(pageable)
				: feedbackRepository.findAllByOrderByCreatedAtDescIdDesc(pageable);
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
}
