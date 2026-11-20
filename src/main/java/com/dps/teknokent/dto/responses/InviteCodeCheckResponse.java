package com.dps.teknokent.dto.responses;

import org.jspecify.annotations.Nullable;

/**
 * Whether a team code opens a team, and that team's name so the person can see they typed the right one. Nothing
 * else: no members, addresses or program, since anyone who holds a code may ask.
 */
public record InviteCodeCheckResponse(boolean valid, @Nullable String teamName) {

	public static InviteCodeCheckResponse invalid() {
		return new InviteCodeCheckResponse(false, null);
	}

	public static InviteCodeCheckResponse of(String teamName) {
		return new InviteCodeCheckResponse(true, teamName);
	}
}
