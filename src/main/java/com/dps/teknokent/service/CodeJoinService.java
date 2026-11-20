package com.dps.teknokent.service;

import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

import com.dps.teknokent.dto.requests.RegisterMemberRequest;
import com.dps.teknokent.dto.responses.UserResponse;
import com.dps.teknokent.security.FirebaseIdentity;

import lombok.RequiredArgsConstructor;

/**
 * The two ways a team code turns into a membership, each under {@link JoinCodeAttemptLimiter}. Kept outside the
 * transactional {@link RegistrationService} so a wrong code is still counted after its transaction rolls back.
 */
@Service
@RequiredArgsConstructor
public class CodeJoinService {

	private final RegistrationService registrationService;
	private final JoinCodeAttemptLimiter joinCodeAttemptLimiter;

	/** "Ekibe katıl" on /invite, by a Firebase login without an account. */
	public UserResponse registerMember(RegisterMemberRequest request, FirebaseIdentity login,
			@Nullable String clientAddress) {
		return joinCodeAttemptLimiter.guard("firebase:" + login.uid(), clientAddress,
				() -> registrationService.registerMember(request));
	}

	/** "Kodum var" on /startup, by an account without a startup. */
	public UserResponse joinStartup(UUID actorId, String inviteCode, @Nullable String clientAddress) {
		return joinCodeAttemptLimiter.guard("user:" + actorId, clientAddress,
				() -> registrationService.joinStartup(actorId, inviteCode));
	}
}
