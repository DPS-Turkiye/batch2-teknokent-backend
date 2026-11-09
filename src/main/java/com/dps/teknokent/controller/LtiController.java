package com.dps.teknokent.controller;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dps.teknokent.dto.requests.LtiLaunchRequest;
import com.dps.teknokent.dto.requests.LtiLoginRequest;
import com.dps.teknokent.dto.responses.LtiLaunchResponse;
import com.dps.teknokent.dto.responses.LtiLoginResponse;
import com.dps.teknokent.security.LtiToolKeys;
import com.dps.teknokent.service.LtiLaunchService;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * LTI 1.3 for office hours. Moodle talks to the frontend; the frontend's server passes the login initiation and
 * the launch here with the service credential. Only the tool's public key set is open to anyone.
 */
@RestController
@RequestMapping("/api/lti")
@RequiredArgsConstructor
@Tag(name = "LTI")
public class LtiController {

	private final LtiLaunchService launchService;
	private final LtiToolKeys toolKeys;

	@PostMapping("/login-initiations")
	public LtiLoginResponse startLogin(@Valid @RequestBody LtiLoginRequest request) {
		return launchService.startLogin(request);
	}

	@PostMapping("/launches")
	public LtiLaunchResponse completeLaunch(@Valid @RequestBody LtiLaunchRequest request) {
		return launchService.completeLaunch(request);
	}

	@GetMapping("/jwks")
	public Map<String, Object> keySet() {
		return toolKeys.publicKeySet();
	}
}
