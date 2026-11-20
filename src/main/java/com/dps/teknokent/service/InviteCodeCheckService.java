package com.dps.teknokent.service;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.dps.teknokent.config.RateLimitingConfig;
import com.dps.teknokent.domain.ProgramStatus;
import com.dps.teknokent.dto.responses.InviteCodeCheckResponse;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.ProgramTeamInvite;
import com.dps.teknokent.repository.OrganizationRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.repository.ProgramTeamInviteRepository;
import com.dps.teknokent.util.TextUtils;
import com.dps.teknokent.util.Texts;

/**
 * /invite asks this before it creates a Firebase login, so a mistyped or rotated code is caught while nothing has
 * been made yet. It answers for the codes {@link RegistrationService#registerMember} takes: a live startup's code,
 * or the team invite of a launched program whose lead has not joined yet.
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
	static final String UNKNOWN_ADDRESS = "unknown";
	static final long WINDOW_MS = 60_000;
	static final long PER_ADDRESS = 10;
	static final long PER_ADDRESS_WINDOW_MS = 10 * 60_000;

	/** Only an IP literal is taken, so a header cannot make a host name lookup or mint endless bucket keys. */
	private static final Pattern IP_LITERAL = Pattern.compile("[0-9A-Fa-f:.]{2,45}");
	/** An IPv6 visitor usually holds a whole /64, so the budget is per /64, not per address in it. */
	private static final int IPV6_PREFIX_BYTES = 8;

	private final OrganizationRepository organizationRepository;
	private final ProgramTeamInviteRepository programTeamInviteRepository;
	private final ProgramRepository programRepository;
	private final EditionService editionService;
	private final RateLimitingConfig rateLimitingConfig;
	private final long checksPerWindow;

	public InviteCodeCheckService(OrganizationRepository organizationRepository,
			ProgramTeamInviteRepository programTeamInviteRepository, ProgramRepository programRepository,
			EditionService editionService, RateLimitingConfig rateLimitingConfig,
			@Value("${app.invite-code-check.per-minute:60}") long checksPerWindow) {
		this.organizationRepository = organizationRepository;
		this.programTeamInviteRepository = programTeamInviteRepository;
		this.programRepository = programRepository;
		this.editionService = editionService;
		this.rateLimitingConfig = rateLimitingConfig;
		this.checksPerWindow = checksPerWindow;
	}

	/**
	 * {@code clientAddress} is the visitor's address as the frontend's server saw it, or null when it sent none.
	 * Not transactional: the reads are single lookups, and a refused caller should not hold a connection.
	 */
	public InviteCodeCheckResponse check(String rawCode, @Nullable String clientAddress) {
		// In order, stopping at the first refusal: a visitor over its own budget spends nothing of the shared one.
		if (!rateLimitingConfig.resolveBucket(ADDRESS_KEY + addressKey(clientAddress), PER_ADDRESS,
				PER_ADDRESS_WINDOW_MS).tryConsume(1)
				|| !rateLimitingConfig.resolveBucket(GLOBAL_KEY, checksPerWindow, WINDOW_MS).tryConsume(1)) {
			throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
					Texts.forRequest("error.tooManyCodeChecks"));
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
						.filter(this::isOpen)
						.map(ProgramTeamInvite::getTeamName))
				.map(InviteCodeCheckResponse::of)
				.orElseGet(InviteCodeCheckResponse::invalid);
	}

	/** A draft program's codes, or the other edition's, open nothing yet: registration would refuse them. */
	private boolean isOpen(ProgramTeamInvite invite) {
		return programRepository.findById(invite.getProgramId())
				.filter(program -> program.getStatus() != ProgramStatus.DRAFT)
				.filter(editionService::serves)
				.isPresent();
	}

	static String addressKey(@Nullable String clientAddress) {
		String value = clientAddress == null ? "" : clientAddress.trim();
		if (!IP_LITERAL.matcher(value).matches()) {
			return UNKNOWN_ADDRESS;
		}
		try {
			InetAddress address = InetAddress.ofLiteral(value);
			if (address instanceof Inet6Address) {
				return "v6:" + HexFormat.of().formatHex(Arrays.copyOf(address.getAddress(), IPV6_PREFIX_BYTES));
			}
			return address.getHostAddress();
		} catch (IllegalArgumentException invalid) {
			return UNKNOWN_ADDRESS;
		}
	}
}
