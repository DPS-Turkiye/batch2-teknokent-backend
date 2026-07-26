package com.dps.teknokent.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dps.teknokent.dto.responses.HealthResponse;
import com.dps.teknokent.service.HealthService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@Tag(name = "Health Service", description = "API for checking the health status of the application")
public class HealthController {

	private final HealthService healthService;

	@Operation(summary = "Check application health", description = "Returns application status and database connectivity")
	@GetMapping("/api/health")
	public ResponseEntity<HealthResponse> health() {
		HealthResponse response = healthService.checkHealth();
		HttpStatus httpStatus = response.status().isUp() ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
		return ResponseEntity.status(httpStatus).body(response);
	}

}
