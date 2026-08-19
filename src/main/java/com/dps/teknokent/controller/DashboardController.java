package com.dps.teknokent.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import java.util.UUID;

import com.dps.teknokent.dto.responses.DashboardSummaryResponse;
import com.dps.teknokent.service.DashboardService;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
@Tag(name = "Dashboard")
public class DashboardController {

	private final DashboardService dashboardService;

	@GetMapping("/summary")
	public DashboardSummaryResponse getSummary(@RequestParam(required = false) UUID organizationId) {
		return dashboardService.getSummary(organizationId);
	}
}
