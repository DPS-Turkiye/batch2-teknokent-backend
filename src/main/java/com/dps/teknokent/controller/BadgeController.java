package com.dps.teknokent.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.dps.teknokent.domain.GateBadgeState;
import com.dps.teknokent.dto.requests.AwardBadgeRequest;
import com.dps.teknokent.dto.requests.RevokeBadgeRequest;
import com.dps.teknokent.dto.responses.AchievementProgressResponse;
import com.dps.teknokent.dto.responses.BadgeAwardResponse;
import com.dps.teknokent.dto.responses.BadgeCatalogueResponse;
import com.dps.teknokent.dto.responses.GateBadgeRowResponse;
import com.dps.teknokent.dto.responses.PublicBadgeResponse;
import com.dps.teknokent.security.CurrentActor;
import com.dps.teknokent.service.AchievementService;
import com.dps.teknokent.service.BadgeService;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** Reached through the Next.js proxy on the service credentials, with the caller taken from its session. */
@RestController
@RequiredArgsConstructor
@Tag(name = "Badges")
public class BadgeController {

	private final BadgeService badgeService;
	private final AchievementService achievementService;

	@GetMapping("/api/badges/catalogue")
	public List<BadgeCatalogueResponse> catalogue() {
		return badgeService.catalogue();
	}

	@GetMapping("/api/badges/mine")
	public List<BadgeAwardResponse> mine(@RequestParam(required = false) UUID actorId) {
		return badgeService.mine(CurrentActor.resolve(actorId));
	}

	@GetMapping("/api/badges/mine/achievements")
	public List<AchievementProgressResponse> myAchievements(@RequestParam(required = false) UUID actorId) {
		return achievementService.mine(CurrentActor.resolve(actorId));
	}

	@GetMapping("/api/users/{userId}/badges")
	public List<PublicBadgeResponse> publicBadges(@PathVariable UUID userId) {
		return badgeService.publicOf(userId);
	}

	@GetMapping("/api/coordinator/programs/{programId}/badges/roster")
	public List<GateBadgeRowResponse> roster(@PathVariable UUID programId,
			@RequestParam(required = false) UUID actorId, @RequestParam(required = false) GateBadgeState state) {
		return badgeService.roster(CurrentActor.resolve(actorId), programId, state);
	}

	@PostMapping("/api/coordinator/programs/{programId}/badges/awards")
	public GateBadgeRowResponse award(@PathVariable UUID programId, @Valid @RequestBody AwardBadgeRequest request) {
		return badgeService.awardGate(CurrentActor.resolve(request.actorId()), programId, request.userId());
	}

	@PostMapping("/api/coordinator/programs/{programId}/badges/awards/{awardId}/revoke")
	public GateBadgeRowResponse revoke(@PathVariable UUID programId, @PathVariable UUID awardId,
			@Valid @RequestBody RevokeBadgeRequest request) {
		return badgeService.revoke(CurrentActor.resolve(request.actorId()), programId, awardId, request.reason());
	}

	@GetMapping("/api/admin/students")
	public List<GateBadgeRowResponse> students(@RequestParam(required = false) UUID actorId,
			@RequestParam(required = false) GateBadgeState state) {
		return badgeService.students(CurrentActor.resolve(actorId), state);
	}
}
