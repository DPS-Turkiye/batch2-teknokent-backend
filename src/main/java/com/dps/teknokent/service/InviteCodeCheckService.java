package com.dps.teknokent.service;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.dps.teknokent.config.RateLimitingConfig;
import com.dps.teknokent.dto.responses.InviteCodeCheckResponse;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.ProgramTeamInvite;
import com.dps.teknokent.repository.OrganizationRepository;
import com.dps.teknokent.repository.ProgramTeamInviteRepository;
import com.dps.teknokent.util.TextUtils;

/**
 * /invite asks this before it creates a Firebase login, so a mistyped or rotated code is caught while nothing has
 * been made yet. It answers for the same codes {@link RegistrationService#registerMember} takes: a live startup's
 * code, or a program's team invite whose lead has not joined yet.
 * <p>
 * A program's team codes are its prefix plus four random letters, so guessing is bounded by two budgets: one per
 * visitor address, which the frontend's server forwards (all calls arrive on its one service login, so the
 * backend's own view of the caller is that server), and one shared by everyone, which caps the total whatever
 * addresses a caller uses. The frontend treats a refusal as "not checked" and lets registration decide, so
 * spending the budgets cannot stop anyone joining.
 */
@Service
public class InviteCodeCheckService {

	static final String GLOBAL_KEY = "invite_code_check";
	static final String ADDRESS_KEY = "invite_code_check_ip:";
	static final long WINDOW_MS = 60_000;
	static final long PER_ADDRESS = 10;
	static final long PER_ADDRESS_WINDOW_MS = 10 * 60_000;

	private final OrganizationRepository organizationRepository;
	private final ProgramTeamInviteRepository programTeamInviteRepository;
	private final RateLimitingConfig rateLimitingConfig;
	private final long checksPerWindow;

	public InviteCodeCheckService(OrganizationRepository organizationRepository,
			ProgramTeamInviteRepository programTeamInviteRepository, RateLimitingConfig rateLimitingConfig,
			@Value("${app.invite-code-check.per-minute:120}") long checksPerWindow) {
		this.organizationRepository = organizationRepository;
		this.programTeamInviteRepository = programTeamInviteRepository;
		this.rateLimitingConfig = rateLimitingConfig;
		this.checksPerWindow = checksPerWindow;
	}

	/** {@code clientAddress} is the visitor's address as the frontend's server saw it, or null when it sent none. */
	@Transactional(readOnly = true)
	public InviteCodeCheckResponse check(String rawCode, @Nullable String clientAddress) {
		String address = clientAddress == null || clientAddress.isBlank() ? "unknown" : clientAddress.trim();
		// In order, stopping at the first refusal: a visitor over its own budget spends nothing of the shared one.
		if (!rateLimitingConfig.resolveBucket(ADDRESS_KEY + address, PER_ADDRESS, PER_ADDRESS_WINDOW_MS).tryConsume(1)
				|| !rateLimitingConfig.resolveBucket(GLOBAL_KEY, checksPerWindow, WINDOW_MS).tryConsume(1)) {
			throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many code checks; try again later.");
		}
		String code = TextUtils.normalizeInviteCode(rawCode);
		if (code.isEmpty()) {
			return InviteCodeCheckResponse.invalid();
		}
		return organizationRepository.findByInviteCode(code)
				.filter(team -> !team.isArchived())
				.map(Organization::getName)
				.or(() -> programTeamInviteRepository.findByCode(code)
						.filter(invite -> invite.getOrganizationId() == null)
						.map(ProgramTeamInvite::getTeamName))
				.map(InviteCodeCheckResponse::of)
				.orElseGet(InviteCodeCheckResponse::invalid);
	}
}
