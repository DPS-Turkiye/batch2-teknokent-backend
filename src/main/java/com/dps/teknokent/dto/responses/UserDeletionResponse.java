package com.dps.teknokent.dto.responses;

import java.util.UUID;

/**
 * {@code cleanupPending} is true while the Firebase login or a stored file is still being removed in the
 * background; the account itself is already gone.
 */
public record UserDeletionResponse(UUID deletedUserId, boolean cleanupPending) {
}
