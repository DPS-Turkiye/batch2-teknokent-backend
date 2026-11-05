package com.dps.teknokent.controller;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.dps.teknokent.dto.requests.RecommendMentorsRequest;
import com.dps.teknokent.dto.responses.MentorRecommendationResult;
import com.dps.teknokent.security.CurrentActor;
import com.dps.teknokent.service.MentorRecommendationService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/mentor-recommendations")
@RequiredArgsConstructor
public class MentorRecommendationController {

	private final MentorRecommendationService mentorRecommendationService;

	@PostMapping
	public ResponseEntity<?> recommend(
			@Valid @RequestBody RecommendMentorsRequest request,
			@RequestParam(required = false) UUID actorId) {
		MentorRecommendationResult result = mentorRecommendationService.recommendFor(CurrentActor.resolveOptional(actorId),
				request);
		return ResponseEntity.ok()
				.header("X-Matching-Method", result.matchingMethod())
				.header("X-Matching-Model", result.matchingModel() == null ? "" : result.matchingModel())
				.header("X-Recommendation-Generated-At", result.generatedAt().toString())
				.body(result.mentors());
	}
}
