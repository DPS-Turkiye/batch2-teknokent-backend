package com.dps.teknokent.controller;

import java.time.Instant;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dps.teknokent.dto.responses.HealthResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@Tag(name = "Health Service", description = "API for checking the health status of the application")
public class HealthController {

	@Operation(summary = "Check application health", description = "Returns the current health status and timestamp of the application")
	@GetMapping("/health")
	public ResponseEntity<HealthResponse> health() {
		return ResponseEntity.ok(new HealthResponse("UP", Instant.now().toString()));
	}
}
