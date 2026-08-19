package com.dps.teknokent.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.dps.teknokent.dto.requests.CreateOrganizationRequest;
import com.dps.teknokent.dto.responses.OrganizationResponse;
import com.dps.teknokent.service.OrganizationService;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/organizations")
@RequiredArgsConstructor
@Tag(name = "Organizations")
public class OrganizationController {

	private final OrganizationService organizationService;

	@GetMapping
	public List<OrganizationResponse> findAll() {
		return organizationService.findAll();
	}

	@GetMapping("/{id}")
	public OrganizationResponse findById(@PathVariable UUID id) {
		return organizationService.findById(id);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public OrganizationResponse create(@Valid @RequestBody CreateOrganizationRequest request) {
		return organizationService.create(request);
	}
}
