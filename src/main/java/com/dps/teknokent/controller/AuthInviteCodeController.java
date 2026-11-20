package com.dps.teknokent.controller;

import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dps.teknokent.dto.requests.InviteCodeCheckRequest;
import com.dps.teknokent.dto.responses.InviteCodeCheckResponse;
import com.dps.teknokent.service.InviteCodeCheckService;
import com.dps.teknokent.util.ClientAddressKey;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/auth/invite-code")
@RequiredArgsConstructor
@Tag(name = "Auth")
public class AuthInviteCodeController {

	private final InviteCodeCheckService inviteCodeCheckService;

	/**
	 * Asked before a Firebase login exists, so it is outside /register and reached only on the proxy's service
	 * login, like "Şifremi unuttum"; that login is why the address header can be trusted. A POST so the code stays
	 * out of URLs and access logs.
	 */
	@PostMapping("/check")
	public InviteCodeCheckResponse check(@Valid @RequestBody InviteCodeCheckRequest request,
			@RequestHeader(name = ClientAddressKey.HEADER, required = false) @Nullable String clientAddress) {
		return inviteCodeCheckService.check(request.code(), clientAddress);
	}
}
