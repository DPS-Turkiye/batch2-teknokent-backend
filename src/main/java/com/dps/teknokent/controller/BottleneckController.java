package com.dps.teknokent.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.dps.teknokent.dto.requests.CreateBottleneckRequest;
import com.dps.teknokent.dto.requests.UpdateBottleneckRequest;
import com.dps.teknokent.dto.responses.BottleneckResponse;
import com.dps.teknokent.service.BottleneckService;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@Tag(name = "Bottlenecks")
public class BottleneckController {

	private final BottleneckService bottleneckService;

	@GetMapping("/api/organizations/{organizationId}/bottlenecks")
	public List<BottleneckResponse> findByOrganization(@PathVariable UUID organizationId) {
		return bottleneckService.findByOrganization(organizationId);
	}

	@PostMapping("/api/organizations/{organizationId}/bottlenecks")
	@ResponseStatus(HttpStatus.CREATED)
	public BottleneckResponse create(
			@PathVariable UUID organizationId,
			@Valid @RequestBody CreateBottleneckRequest request) {
		return bottleneckService.create(organizationId, request);
	}

	@PatchMapping("/api/bottlenecks/{id}")
	public BottleneckResponse update(
			@PathVariable UUID id,
			@Valid @RequestBody UpdateBottleneckRequest request) {
		return bottleneckService.update(id, request);
	}
}
