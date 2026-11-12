package com.dps.teknokent.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dps.teknokent.dto.responses.AiFeaturesResponse;
import com.dps.teknokent.service.AiFeatures;

import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/ai-features")
@RequiredArgsConstructor
public class AiFeatureController {

	private final AiFeatures aiFeatures;

	@GetMapping
	@Operation(summary = "Which AI features this deployment offers; a switched-off one is refused with AI_FEATURE_DISABLED")
	public AiFeaturesResponse features() {
		return aiFeatures.snapshot();
	}
}
