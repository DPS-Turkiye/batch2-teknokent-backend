package com.dps.teknokent.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.PeerTopicCategory;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.SaveStudentProfileCompletionRequest;
import com.dps.teknokent.dto.responses.StudentProfileCompletionResponse;
import com.dps.teknokent.entity.StudentInterest;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.repository.StudentInterestRepository;
import com.dps.teknokent.util.Texts;

import lombok.RequiredArgsConstructor;

/**
 * An ODTÜ student's interests and goal. Topics are limited to the categories active in the student's program, the
 * same list the peer-mentor directory filters on; a category a coordinator later turns off drops out on read.
 */
@Service
@RequiredArgsConstructor
public class StudentProfileCompletionService {

	private final PeerMentoringService peerMentoringService;
	private final StudentInterestRepository studentInterestRepository;

	@Transactional(readOnly = true)
	public StudentProfileCompletionResponse get(UUID actorId) {
		Set<PeerTopicCategory> active = activeFor(actorId);
		return studentInterestRepository.findById(actorId)
				.map(saved -> toResponse(saved, active))
				.orElseGet(() -> new StudentProfileCompletionResponse(List.of(), List.of(), "", false, false));
	}

	@Transactional
	public StudentProfileCompletionResponse save(UUID actorId, SaveStudentProfileCompletionRequest request) {
		Set<PeerTopicCategory> active = activeFor(actorId);
		if (!active.containsAll(request.topics())) {
			throw new InvalidRequestException(Texts.forRequest("error.peerCategoryInactive"));
		}
		StudentInterest interest = studentInterestRepository.findById(actorId)
				.orElseGet(() -> new StudentInterest(actorId));
		interest.setTopics(new ArrayList<>(request.topics().stream().distinct().toList()));
		interest.setSubtopics(new ArrayList<>(request.subtopics().stream().distinct().toList()));
		interest.setGoal(request.goal().trim());
		interest.setCompleted(request.completed());
		interest.setSkipped(!request.completed() && request.skipped());
		interest.setUpdatedAt(Instant.now());
		return toResponse(studentInterestRepository.save(interest), active);
	}

	/** Also the gate: only an ODTÜ student gets past {@link PeerMentoringService#categories}. */
	private Set<PeerTopicCategory> activeFor(UUID actorId) {
		List<PeerTopicCategory> categories = peerMentoringService.categories(actorId, UserRole.MENTEE);
		return categories.isEmpty() ? EnumSet.noneOf(PeerTopicCategory.class) : EnumSet.copyOf(categories);
	}

	private static StudentProfileCompletionResponse toResponse(StudentInterest interest, Set<PeerTopicCategory> active) {
		return new StudentProfileCompletionResponse(
				interest.getTopics().stream().filter(active::contains).toList(),
				List.copyOf(interest.getSubtopics()),
				interest.getGoal(),
				interest.isCompleted(),
				interest.isSkipped());
	}
}
