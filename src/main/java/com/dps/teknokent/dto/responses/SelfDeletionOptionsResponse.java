package com.dps.teknokent.dto.responses;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.dps.teknokent.domain.AccountDeletionBlock;

/**
 * What the person may delete themselves. {@code account.blockedReason} says why not, when they may not;
 * {@code startup} is set only for the founder of a startup they may delete.
 */
public record SelfDeletionOptionsResponse(Account account, @Nullable Startup startup) {

	public record Account(boolean allowed, @Nullable AccountDeletionBlock blockedReason) {
	}

	public record Startup(UUID id, String name) {
	}
}
