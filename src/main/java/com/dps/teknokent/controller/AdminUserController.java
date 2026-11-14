package com.dps.teknokent.controller;

import java.util.UUID;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dps.teknokent.dto.requests.DeleteUserRequest;
import com.dps.teknokent.dto.responses.UserDeletionResponse;
import com.dps.teknokent.security.CurrentActor;
import com.dps.teknokent.service.UserDeletionService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
@Tag(name = "Admin")
public class AdminUserController {

	private final UserDeletionService userDeletionService;

	/**
	 * A POST rather than a DELETE because the confirmation travels in the body, which proxies may drop from a DELETE.
	 * The admin is taken from their own verified sign-in only; see {@link CurrentActor#requireSignedIn}.
	 */
	@PostMapping("/{id}/delete")
	@Operation(summary = "Delete a user for good; shared history stays, anonymized")
	public UserDeletionResponse delete(@PathVariable UUID id, @Valid @RequestBody DeleteUserRequest request) {
		return userDeletionService.delete(CurrentActor.requireSignedIn(), id, request.confirmEmail());
	}
}
