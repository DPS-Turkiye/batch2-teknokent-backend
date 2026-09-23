package com.dps.teknokent.controller;

import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.dps.teknokent.dto.responses.AdminOverviewResponse;
import com.dps.teknokent.security.AppUserPrincipal;
import com.dps.teknokent.service.AdminOverviewService;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@Tag(name = "Admin")
public class AdminOverviewController {

	private final AdminOverviewService adminOverviewService;

	@GetMapping("/overview")
	public AdminOverviewResponse overview(@RequestParam(required = false) UUID actorId) {
		return adminOverviewService.overview(resolveActorId(actorId));
	}

	private static UUID resolveActorId(UUID requestedActorId) {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication != null && authentication.getPrincipal() instanceof AppUserPrincipal principal) {
			return principal.id();
		}
		return requestedActorId;
	}
}
