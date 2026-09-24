package com.dps.teknokent.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.dps.teknokent.dto.requests.PushTokenRequest;
import com.dps.teknokent.dto.requests.UpdateNotificationPreferencesRequest;
import com.dps.teknokent.dto.responses.NotificationPreferencesResponse;
import com.dps.teknokent.dto.responses.NotificationResponse;
import com.dps.teknokent.dto.responses.NotificationUnreadCountResponse;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.security.AppUserPrincipal;
import com.dps.teknokent.service.NotificationPreferenceService;
import com.dps.teknokent.service.NotificationService;
import com.dps.teknokent.service.PushTokenService;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
@Tag(name = "Notifications")
public class NotificationController {

	private final NotificationService notificationService;
	private final NotificationPreferenceService notificationPreferenceService;
	private final PushTokenService pushTokenService;

	@GetMapping
	public List<NotificationResponse> list() {
		return notificationService.list(currentUserId());
	}

	@GetMapping("/unread-count")
	public NotificationUnreadCountResponse unreadCount() {
		return notificationService.unreadCount(currentUserId());
	}

	@PatchMapping("/{id}/read")
	public NotificationResponse markRead(@PathVariable UUID id) {
		return notificationService.markRead(currentUserId(), id);
	}

	@PatchMapping("/read-all")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void markAllRead() {
		notificationService.markAllRead(currentUserId());
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable UUID id) {
		notificationService.delete(currentUserId(), id);
	}

	@GetMapping("/preferences")
	public NotificationPreferencesResponse preferences() {
		return notificationPreferenceService.get(currentUserId());
	}

	@PutMapping("/preferences")
	public NotificationPreferencesResponse updatePreferences(
			@Valid @RequestBody UpdateNotificationPreferencesRequest request) {
		return notificationPreferenceService.update(currentUserId(), request);
	}

	@PostMapping("/push-tokens")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void registerPushToken(
			@Valid @RequestBody PushTokenRequest request,
			@RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent) {
		pushTokenService.register(currentUserId(), request.token(), userAgent);
	}

	@DeleteMapping("/push-tokens")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void unregisterPushToken(@Valid @RequestBody PushTokenRequest request) {
		pushTokenService.unregister(currentUserId(), request.token());
	}

	private UUID currentUserId() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || !(authentication.getPrincipal() instanceof AppUserPrincipal principal)) {
			throw new InvalidRequestException("This endpoint requires a signed-in user account.");
		}
		return principal.id();
	}
}
