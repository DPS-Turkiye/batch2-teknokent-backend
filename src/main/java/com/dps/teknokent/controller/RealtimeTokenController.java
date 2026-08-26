package com.dps.teknokent.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dps.teknokent.dto.requests.IssueRealtimeTokenRequest;
import com.dps.teknokent.dto.responses.RealtimeTokenResponse;
import com.dps.teknokent.service.RealtimeTokenService;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/realtime")
@RequiredArgsConstructor
@Tag(name = "Realtime")
public class RealtimeTokenController {

	private final RealtimeTokenService realtimeTokenService;

	@PostMapping("/token")
	public RealtimeTokenResponse issue(@Valid @RequestBody IssueRealtimeTokenRequest request) {
		return realtimeTokenService.issue(request.userId(), request.organizationId());
	}
}
